package com.secretqsan.utochpad

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import androidx.fragment.app.FragmentActivity
import com.secretqsan.utochpad.data.AppTheme
import com.secretqsan.utochpad.data.PasswordVault
import com.secretqsan.utochpad.data.Storage
import com.secretqsan.utochpad.data.VaultResult
import com.secretqsan.utochpad.net.ConnState
import com.secretqsan.utochpad.net.Contact
import com.secretqsan.utochpad.net.Crypto
import com.secretqsan.utochpad.net.PairClient
import com.secretqsan.utochpad.net.PairQrPayload
import com.secretqsan.utochpad.net.PairingData
import com.secretqsan.utochpad.net.Protocol
import com.secretqsan.utochpad.net.WsClient
import com.secretqsan.utochpad.net.WsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AppUiState(
    val paired: Boolean = false,
    val serviceName: String = "Windows PC",
    val connection: ConnState = ConnState.Disconnected("未连接"),
    val modeReady: Boolean = false,
    val modeBusy: Boolean = false,
    val modeError: String? = null,
    val passwordSet: Boolean = false,
    val authBusy: Boolean = false,
    val notice: String? = null,
    val theme: AppTheme = AppTheme.Dark,
)

class UtochpadController(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val storage = Storage(app)
    private val vault = PasswordVault(storage)

    // Set by UtochpadApplication as activities are resumed. Biometric prompts
    // are the only part of the controller that needs an Activity.
    private var currentActivity: FragmentActivity? = null

    // Every touch sample is its own small frame. Android's Wi-Fi power save
    // parks the radio between DTIM intervals, so buffered frames arrive in
    // bursts tens of milliseconds late — the occasional "the pad lags for a
    // moment" symptom. A low-latency Wi-Fi lock keeps the radio awake while the
    // app is in front.
    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? = run {
        val wifi = app.applicationContext.getSystemService(WifiManager::class.java)
            ?: return@run null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifi.createWifiLock(mode, "utochpad-input").apply { setReferenceCounted(false) }
    }
    private var wifiLockHeld = false

    private val _state = MutableStateFlow(
        AppUiState(
            paired = storage.isPaired(),
            serviceName = storage.loadPairing()?.serviceName ?: "Windows PC",
            passwordSet = storage.hasPassword(),
            theme = storage.loadTheme(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    fun setTheme(theme: AppTheme) {
        storage.saveTheme(theme)
        update { it.copy(theme = theme) }
    }

    private var client: WsClient? = null
    private var connectJob: Job? = null
    private var active = false
    private var fatal = false
    private var pendingUnlockId: String? = null

    private fun setWifiLowLatency(enabled: Boolean) {
        val lock = wifiLock ?: return
        try {
            if (enabled && !wifiLockHeld) {
                lock.acquire()
                wifiLockHeld = true
            } else if (!enabled && wifiLockHeld) {
                lock.release()
                wifiLockHeld = false
            }
        } catch (_: RuntimeException) {
            // Some vendors reject low-latency locks; the connection still works.
        }
    }

    fun attachActivity(activity: FragmentActivity) {
        currentActivity = activity
    }

    fun detachActivity(activity: FragmentActivity) {
        if (currentActivity === activity) currentActivity = null
    }

    fun onStart() {
        active = true
        setWifiLowLatency(true)
        val current = _state.value
        if (current.paired && !fatal) {
            startConnecting()
        }
    }

    fun onStop() {
        active = false
        setWifiLowLatency(false)
        connectJob?.cancel()
        connectJob = null
        vault.cancelActivePrompt()

        // Detach first so no further frames are queued, but keep the socket
        // alive long enough for the queued denial to reach the PC. Closing
        // immediately would shut the sender thread down first.
        val closing = client
        client = null
        val hadPendingUnlock = pendingUnlockId != null
        pendingUnlockId?.let { id ->
            closing?.sendUnlockResponse(id, false, null, "手机 App 已切到后台")
        }
        pendingUnlockId = null

        if (closing != null) {
            if (hadPendingUnlock) {
                scope.launch {
                    delay(150)
                    closing.close("app background")
                }
            } else {
                closing.close("app background")
            }
        }

        update {
            it.copy(
                connection = ConnState.Disconnected("已暂停"),
                modeReady = false,
                modeBusy = false,
                authBusy = false,
            )
        }
    }

    suspend fun pairWithCode(raw: String): Result<Unit> {
        return try {
            val payload = PairQrPayload.parse(raw)
            val keyPair = Crypto.generateKeyPair()
            val shared = Crypto.deriveSharedSecret(keyPair.private, payload.servicePublicKey)
            val pairKey = Crypto.derivePairKey(shared)
            val deviceName = storage.deviceName

            val result = PairClient.pair(
                payload = payload,
                phonePublicKey = Crypto.publicSpki(keyPair),
                pairKey = pairKey,
                deviceId = storage.deviceId,
                deviceName = deviceName,
            )

            result.onSuccess {
                completePairing(
                    PairingData(
                        hosts = payload.hosts,
                        port = payload.port,
                        deviceId = storage.deviceId,
                        pairKey = pairKey,
                        serviceName = payload.name,
                    ),
                )
            }

            result
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    private fun completePairing(data: PairingData) {
        storage.savePairing(data)
        // A password saved for a previous PC must never be sent to a new one.
        // Re-pairing is rare and the panel already guides the user to send the
        // password again, so start from a clean state.
        if (storage.hasPassword()) storage.clearPassword()
        fatal = false
        update {
            it.copy(
                paired = true,
                serviceName = data.serviceName,
                passwordSet = false,
                notice = null,
                connection = ConnState.Disconnected("未连接"),
            )
        }
        if (active) startConnecting()
    }

    /**
     * Forgets the PC. The app then shows the pairing screen again, which is the
     * only way to pair: there is no separate "pair again" path.
     */
    fun unpair() {
        connectJob?.cancel()
        connectJob = null
        client?.close("unpaired")
        client = null
        vault.cancelActivePrompt()
        storage.clearPairing()
        storage.clearPassword()
        fatal = false
        update {
            it.copy(
                paired = false,
                serviceName = "Windows PC",
                passwordSet = false,
                connection = ConnState.Disconnected("未配对"),
                modeReady = false,
                modeBusy = false,
                modeError = null,
                authBusy = false,
                notice = "已解除配对",
            )
        }
    }

    fun retry() {
        fatal = false
        if (active && _state.value.paired) startConnecting()
    }

    fun sendTouchFrame(contacts: List<Contact>) {
        if (_state.value.modeReady) {
            // No physical pad buttons: taps and gestures are interpreted by
            // Windows from the contacts themselves.
            client?.sendPtpFrame(Protocol.buildPtpFrame(contacts, 0))
        }
    }

    fun sendRadialFrame(frame: ByteArray) {
        if (_state.value.modeReady) {
            client?.sendRadialFrame(frame)
        }
    }

    fun retryMode() {
        val current = client
        if (current != null && current.isReady) {
            update { it.copy(modeBusy = true, modeReady = false, modeError = null) }
            current.sendEnterMode(Protocol.MODE_INPUT)
        }
    }

    fun clearNotice() {
        update { it.copy(notice = null) }
    }

    // One attempt only: if it fails or the link drops, the app stays
    // disconnected until the user taps retry.
    private fun startConnecting() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val pairing = storage.loadPairing()
            if (pairing == null) {
                update { it.copy(paired = false, connection = ConnState.Disconnected("未配对")) }
                return@launch
            }

            update { it.copy(connection = ConnState.Connecting, serviceName = pairing.serviceName) }

            val connectedClient = tryHosts(pairing)
            if (connectedClient == null) {
                if (!active) return@launch
                update {
                    it.copy(
                        connection = ConnState.Disconnected("无法连接电脑"),
                        modeReady = false,
                        modeBusy = false,
                    )
                }
                return@launch
            }

            client = connectedClient
            update { it.copy(connection = ConnState.Connected) }
            enterCurrentMode(connectedClient)

            while (active && connectedClient.isReady) {
                delay(500)
            }

            connectedClient.close("disconnected")
            if (client === connectedClient) client = null
            update {
                it.copy(
                    modeReady = false,
                    modeBusy = false,
                    connection = ConnState.Disconnected("连接已断开"),
                )
            }
        }
    }

    private suspend fun tryHosts(pairing: PairingData): WsClient? {
        for (host in pairing.hosts) {
            if (!active) return null

            val candidate = WsClient(
                pairing = pairing,
                host = host,
                passwordSet = { storage.hasPassword() },
                listener = listener,
            )
            client = candidate
            candidate.connect()

            val result = withTimeoutOrNull(8000) { candidate.awaitReady() }
            if (result?.isSuccess == true && candidate.isReady) {
                return candidate
            }

            candidate.close("connect failed")
            if (client === candidate) client = null
        }

        return null
    }

    private fun enterCurrentMode(ws: WsClient) {
        update { it.copy(modeBusy = true, modeReady = false, modeError = null) }
        ws.sendEnterMode(Protocol.MODE_INPUT)
    }

    private val listener = object : WsListener {
        override fun onState(state: ConnState) {
            scope.launch {
                update { it.copy(connection = state) }
            }
        }

        override fun onReady(passwordSet: Boolean) {
            scope.launch {
                update { it.copy(passwordSet = passwordSet) }
            }
        }

        override fun onFatalError(message: String) {
            scope.launch {
                fatal = true
                connectJob?.cancel()
                connectJob = null
                client?.close("fatal")
                client = null
                vault.cancelActivePrompt()
                storage.clearPairing()
                // The old pairing is gone, so a password saved for it must not
                // survive into the next pairing.
                if (storage.hasPassword()) storage.clearPassword()
                pendingUnlockId = null
                val reason = message.trim().trimEnd('。')
                update {
                    it.copy(
                        paired = false,
                        passwordSet = false,
                        connection = ConnState.Disconnected(reason),
                        modeReady = false,
                        modeBusy = false,
                        authBusy = false,
                        notice = "$reason。请重新配对。",
                    )
                }
            }
        }

        override fun onUnlockRequest(id: String, username: String, domain: String) {
            scope.launch {
                handleUnlockRequest(id, username, domain)
            }
        }

        override fun onCancelUnlock(id: String) {
            scope.launch {
                if (pendingUnlockId == id) {
                    vault.cancelActivePrompt()
                }
            }
        }

        override fun onSetPassword(password: String) {
            scope.launch {
                handleSetPassword(password)
            }
        }

        override fun onModeReady(mode: String) {
            scope.launch {
                if (mode == Protocol.MODE_INPUT) {
                    update {
                        it.copy(modeReady = true, modeBusy = false, modeError = null)
                    }
                    // Both devices are attached; start them from a neutral state.
                    sendTouchFrame(emptyList())
                    client?.sendRadialFrame(Protocol.radialNeutralFrame())
                }
            }
        }

        override fun onModeError(mode: String, message: String) {
            scope.launch {
                update {
                    it.copy(
                        modeReady = false,
                        modeBusy = false,
                        modeError = message.ifBlank { "无法启动输入设备，请重试。" },
                    )
                }
            }
        }
    }

    private suspend fun handleUnlockRequest(id: String, username: String, domain: String) {
        val account = if (domain.isBlank()) username else "$domain\\$username"
        if (!active) {
            client?.sendUnlockResponse(id, false, null, "手机 App 不在前台")
            return
        }

        if (pendingUnlockId != null || _state.value.authBusy) {
            client?.sendUnlockResponse(id, false, null, "手机正在处理另一个验证")
            return
        }

        if (!storage.hasPassword()) {
            client?.sendUnlockResponse(id, false, null, "手机尚未保存 Windows 密码")
            update { it.copy(notice = "电脑请求解锁，但手机尚未保存密码") }
            return
        }

        val activity = currentActivity
        if (activity == null) {
            client?.sendUnlockResponse(id, false, null, "手机 App 不在前台")
            return
        }

        pendingUnlockId = id
        update {
            it.copy(
                authBusy = true,
                notice = "电脑请求解锁：${account.ifBlank { "Windows" }}",
            )
        }

        try {
            when (val result = vault.decryptPassword(activity)) {
                is VaultResult.Success -> {
                    val passwordB64 = Crypto.b64(result.value.toByteArray(Charsets.UTF_8))
                    client?.sendUnlockResponse(id, true, passwordB64, null)
                }

                is VaultResult.Failure -> {
                    client?.sendUnlockResponse(id, false, null, result.message)
                    update { it.copy(notice = result.message) }
                }

                VaultResult.Cancelled -> {
                    client?.sendUnlockResponse(id, false, null, "指纹验证已取消")
                }
            }
        } finally {
            pendingUnlockId = null
            update { it.copy(authBusy = false) }
        }
    }

    private suspend fun handleSetPassword(password: String) {
        if (password.isEmpty()) {
            client?.sendSetPasswordResult(false, "密码为空")
            return
        }

        if (_state.value.authBusy) {
            client?.sendSetPasswordResult(false, "手机正在处理另一个验证")
            return
        }

        val activity = currentActivity
        if (activity == null) {
            client?.sendSetPasswordResult(false, "手机 App 不在前台")
            return
        }

        update { it.copy(authBusy = true, notice = "请在手机上验证指纹以保存密码") }

        try {
            when (val result = vault.storePassword(activity, password)) {
                is VaultResult.Success -> {
                    client?.sendSetPasswordResult(true, null)
                    update {
                        it.copy(
                            passwordSet = true,
                            notice = "Windows 密码已保存到手机",
                        )
                    }
                }

                is VaultResult.Failure -> {
                    client?.sendSetPasswordResult(false, result.message)
                    update { it.copy(notice = result.message) }
                }

                VaultResult.Cancelled -> {
                    client?.sendSetPasswordResult(false, "指纹验证已取消")
                }
            }
        } finally {
            update { it.copy(authBusy = false) }
        }
    }

    private fun update(block: (AppUiState) -> AppUiState) {
        _state.value = block(_state.value)
    }
}
