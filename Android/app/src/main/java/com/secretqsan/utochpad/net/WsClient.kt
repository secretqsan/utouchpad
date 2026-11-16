package com.secretqsan.utochpad.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.coroutines.resume

sealed interface ConnState {
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Disconnected(val reason: String) : ConnState
}

interface WsListener {
    fun onState(state: ConnState)
    fun onReady(passwordSet: Boolean)
    fun onFatalError(message: String)
    fun onUnlockRequest(id: String, username: String, domain: String)
    fun onCancelUnlock(id: String)
    fun onSetPassword(password: String)
    fun onModeReady(mode: String)
    fun onModeError(mode: String, message: String)
}

// OkHttp does not enable TCP_NODELAY, and Nagle holds small pointer frames back
// on Wi-Fi. A socket factory is the only hook before OkHttp connects.
private object NoDelaySocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket().apply { tcpNoDelay = true }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket().apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

private const val TAG = "UtochpadWs"

/** Authenticated, encrypted phone connection to the Utochpad PC service. */
class WsClient(
    private val pairing: PairingData,
    private val host: String,
    private val passwordSet: () -> Boolean,
    private val listener: WsListener,
) {
    private val ready = CompletableDeferred<Result<Unit>>()
    private val closed = AtomicBoolean(false)

    // Frames leave through one thread so AES work never competes with rendering.
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "utochpad-ws-send").apply { isDaemon = true }
    }

    // Position-only frames may be superseded by a newer snapshot; a frame that
    // changes the contact set may not, because Windows sees a tap in exactly
    // one Tip transition frame.
    private val ptpLock = Any()
    private val ptpQueue = ArrayDeque<PtpFrame>()
    private val ptpDrainScheduled = AtomicBoolean(false)

    @Volatile
    private var connected = false

    @Volatile
    private var socket: WebSocket? = null

    // Written on OkHttp's callback thread, read on the send thread.
    @Volatile
    private var sendCrypto: AeadSession? = null
    private var receiveCrypto: AeadSession? = null
    private var nonceP: ByteArray = ByteArray(0)
    private var awaitingChallenge = true

    val isReady: Boolean
        get() = connected && !closed.get()

    fun connect() {
        listener.onState(ConnState.Connecting)

        nonceP = Crypto.randomBytes(16)
        val request = Request.Builder()
            .url("ws://$host:${pairing.port}/ws")
            .build()

        socket = Http.client.newWebSocket(request, socketListener)
    }

    suspend fun awaitReady(): Result<Unit> = ready.await()

    fun close(reason: String = "closed") {
        if (closed.compareAndSet(false, true)) {
            try {
                socket?.close(1000, reason)
            } catch (_: Exception) {
                socket?.cancel()
            }
        }
        // Even when the socket already closed itself (onClosed sets `closed`),
        // the sender thread still has to be released.
        sendExecutor.shutdownNow()
        if (!ready.isCompleted) {
            ready.complete(Result.failure(Exception(reason)))
        }
    }

    fun sendUnlockResponse(id: String, ok: Boolean, passwordB64: String?, error: String?) {
        sendControl(Protocol.T_UNLOCK_RESPONSE) {
            put("id", id)
            put("ok", ok)
            if (passwordB64 != null) put("passwordB64", passwordB64)
            if (error != null) put("error", error)
        }
    }

    fun sendSetPasswordResult(ok: Boolean, error: String?) {
        sendControl(Protocol.T_SET_PASSWORD_RESULT) {
            put("ok", ok)
            if (error != null) put("error", error)
        }
    }

    fun sendEnterMode(mode: String) {
        sendControl(Protocol.T_ENTER_MODE) { put("mode", mode) }
    }

    fun sendPtpFrame(frame: ByteArray) {
        val layout = ptpLayout(frame)
        synchronized(ptpLock) {
            val last = ptpQueue.lastOrNull()
            if (last != null && last.layout == layout) {
                // Same contact set, only the positions moved: the newer
                // snapshot subsumes the older one.
                ptpQueue[ptpQueue.size - 1] = PtpFrame(frame, layout)
            } else {
                ptpQueue.addLast(PtpFrame(frame, layout))
                while (ptpQueue.size > MaxPtpQueue) ptpQueue.removeFirst()
            }
        }
        schedulePtpDrain()
    }

    fun sendRadialFrame(frame: ByteArray) = sendFrame(Protocol.TYPE_RADIAL, frame)

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("t", Protocol.T_HELLO)
                .put("v", Protocol.VERSION)
                .put("deviceId", pairing.deviceId)
                .put("nonceP", Crypto.b64(nonceP))
                .put("passwordSet", passwordSet())
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleHandshakeText(webSocket, text)
            } catch (_: Exception) {
                failAndClose("与电脑的连接出现异常，请重试。")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val session = receiveCrypto ?: error("encrypted frame before session ready")
                val plaintext = session.open(bytes.toByteArray())
                if (plaintext.isEmpty()) return

                when (plaintext[0].toInt()) {
                    Protocol.TYPE_CONTROL -> handleControl(
                        String(plaintext, 1, plaintext.size - 1, Charsets.UTF_8),
                    )
                }
            } catch (ex: Exception) {
                Log.w(TAG, "decrypt failed", ex)
                failAndClose("无法验证电脑的数据，请重新配对。")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            Log.w(TAG, "failure", t)
            if (!ready.isCompleted) {
                ready.complete(Result.failure(Exception("无法连接电脑")))
            }
            if (!closed.get()) {
                listener.onState(ConnState.Disconnected("无法连接电脑"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            Log.w(TAG, "closed code=$code reason=$reason")
            if (!ready.isCompleted) {
                ready.complete(Result.failure(Exception("连接已断开")))
            }
            if (!closed.getAndSet(true)) {
                listener.onState(ConnState.Disconnected("连接已断开"))
            }
        }
    }

    private fun handleHandshakeText(webSocket: WebSocket, text: String) {
        val message = JSONObject(text)
        when (message.optString("t")) {
            Protocol.T_CHALLENGE -> {
                if (!awaitingChallenge) {
                    failAndClose("与电脑的连接出现异常，请重试。")
                    return
                }
                awaitingChallenge = false

                val nonceS = Crypto.unb64(message.getString("nonceS"))
                val expected = Crypto.hmac(
                    pairing.pairKey,
                    Crypto.proofText("server", nonceP, nonceS),
                )
                val actual = Crypto.unb64(message.getString("proof"))
                if (!Crypto.constantTimeEquals(expected, actual)) {
                    failAndClose("无法验证电脑的身份，请重新配对。")
                    return
                }

                val (clientToServer, serverToClient) =
                    Crypto.deriveSessionKeys(pairing.pairKey, nonceP, nonceS)
                sendCrypto = AeadSession(clientToServer)
                receiveCrypto = AeadSession(serverToClient)

                val proof = Crypto.hmac(
                    pairing.pairKey,
                    Crypto.proofText("client", nonceP, nonceS),
                )
                val auth = JSONObject()
                    .put("t", Protocol.T_AUTH)
                    .put("v", Protocol.VERSION)
                    .put("proof", Crypto.b64(proof))
                webSocket.send(auth.toString())
            }

            Protocol.T_READY -> {
                if (sendCrypto == null || receiveCrypto == null) {
                    failAndClose("与电脑的安全连接未建立，请重试。")
                    return
                }

                if (!closed.get()) {
                    connected = true
                    if (!ready.isCompleted) ready.complete(Result.success(Unit))
                    listener.onState(ConnState.Connected)
                    listener.onReady(passwordSet())
                }
            }

            Protocol.T_ERROR -> {
                val messageText = message.optString("message", "电脑拒绝连接")
                val code = message.optString("code")
                Log.w(TAG, "server error code=$code message=$messageText")
                if (code == "not_paired" || code == "version" || code == "auth") {
                    if (!closed.get()) listener.onFatalError(messageText)
                }
                failAndClose(messageText)
            }
        }
    }

    private fun handleControl(text: String) {
        val message = JSONObject(text)
        when (message.optString("t")) {
            Protocol.T_UNLOCK_REQUEST -> listener.onUnlockRequest(
                message.optString("id"),
                message.optString("username"),
                message.optString("domain"),
            )

            Protocol.T_CANCEL_UNLOCK -> listener.onCancelUnlock(message.optString("id"))

            Protocol.T_SET_PASSWORD -> {
                val password = try {
                    String(
                        Crypto.unb64(message.getString("passwordB64")),
                        Charsets.UTF_8,
                    )
                } catch (_: Exception) {
                    ""
                }
                listener.onSetPassword(password)
            }

            Protocol.T_MODE_READY -> listener.onModeReady(message.optString("mode"))

            Protocol.T_MODE_ERROR -> listener.onModeError(
                message.optString("mode"),
                message.optString("message", "模式启动失败"),
            )
        }
    }

    private fun sendControl(type: String, block: JSONObject.() -> Unit = {}) {
        val message = Protocol.control(type, block)
        sendFrame(Protocol.TYPE_CONTROL, message.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Sends every queued snapshot in order. The queue already collapses
     * redundant motion, so there is no rate limiter here.
     */
    private fun schedulePtpDrain() {
        if (!ptpDrainScheduled.compareAndSet(false, true)) return

        enqueue {
            try {
                while (!closed.get()) {
                    val next = synchronized(ptpLock) { ptpQueue.removeFirstOrNull() } ?: break
                    sendEncrypted(Protocol.TYPE_PTP, next.bytes)
                }
            } finally {
                ptpDrainScheduled.set(false)
                // A frame may have arrived between the last pop and clearing
                // the flag; schedule another pass instead of losing it.
                if (hasPendingPtp()) schedulePtpDrain()
            }
        }
    }

    private fun hasPendingPtp(): Boolean = synchronized(ptpLock) { ptpQueue.isNotEmpty() }

    /** Identity of a snapshot's contact set; coordinates are excluded. */
    private fun ptpLayout(frame: ByteArray): Long {
        var hash = 1_125_899_906_842_597L
        hash = hash * 31 + frame[0]
        hash = hash * 31 + frame[1]
        for (slot in 0 until Protocol.MAX_CONTACTS) {
            val offset = 2 + slot * 6
            hash = hash * 31 + frame[offset]
            hash = hash * 31 + frame[offset + 1]
        }
        return hash
    }

    private class PtpFrame(val bytes: ByteArray, val layout: Long)

    private companion object {
        /** Bound on undelivered snapshots; transitions are a handful per tap. */
        const val MaxPtpQueue = 16
    }

    private fun sendFrame(type: Int, body: ByteArray) {
        enqueue { sendEncrypted(type, body) }
    }

    private fun enqueue(block: () -> Unit) {
        if (closed.get() || sendExecutor.isShutdown) return
        try {
            sendExecutor.execute {
                if (!closed.get()) block()
            }
        } catch (_: RejectedExecutionException) {
            // The connection closed between the check and the enqueue.
        }
    }

    private fun sendEncrypted(type: Int, body: ByteArray) {
        val session = sendCrypto ?: return
        val webSocket = socket ?: return
        if (closed.get()) return

        val plaintext = ByteArray(1 + body.size)
        plaintext[0] = type.toByte()
        body.copyInto(plaintext, 1)

        val frame = session.seal(plaintext)
        webSocket.send(ByteString.of(*frame))
    }

    private fun failAndClose(message: String) {
        connected = false
        Log.w(TAG, "failAndClose: $message")
        if (!ready.isCompleted) {
            ready.complete(Result.failure(Exception(message)))
        }
        if (!closed.get()) {
            listener.onState(ConnState.Disconnected(message))
        }
        close(message)
    }
}

object PairClient {
    suspend fun pair(
        payload: PairQrPayload,
        phonePublicKey: ByteArray,
        pairKey: ByteArray,
        deviceId: String,
        deviceName: String,
    ): Result<Unit> {
        var lastError: Exception? = null

        for (host in payload.hosts) {
            val result = withTimeoutOrNull(PairTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url("ws://$host:${payload.port}/ws")
                        .build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val proof = Crypto.hmac(pairKey, "pair|${payload.token}")
                            val message = JSONObject()
                                .put("t", Protocol.T_PAIR)
                                .put("v", Protocol.VERSION)
                                .put("token", payload.token)
                                .put("phonePub", Crypto.b64(phonePublicKey))
                                .put("deviceId", deviceId)
                                .put("deviceName", deviceName)
                                .put("proof", Crypto.b64(proof))
                            webSocket.send(message.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = try {
                                JSONObject(text)
                            } catch (ex: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(
                                        Result.failure(Exception("配对失败，请重试。")),
                                    )
                                }
                                return
                            }

                            when (message.optString("t")) {
                                Protocol.T_PAIR_OK -> {
                                    val servicePublic = message.optString("servicePub")
                                    // The PC must echo the key from the QR code;
                                    // accepting a blank value would let any host
                                    // on the QR address fake a successful pairing.
                                    if (servicePublic.isBlank() ||
                                        !Crypto.constantTimeEquals(
                                            Crypto.unb64(servicePublic),
                                            payload.servicePublicKey,
                                        )
                                    ) {
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                Result.failure(
                                                    Exception("无法验证电脑的身份，请重新扫描二维码。"),
                                                ),
                                            )
                                        }
                                    } else if (continuation.isActive) {
                                        continuation.resume(Result.success(Unit))
                                    }
                                    webSocket.close(1000, "paired")
                                }

                                Protocol.T_ERROR -> {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            Result.failure(
                                                Exception(
                                                    message.optString("message", "配对失败"),
                                                ),
                                            ),
                                        )
                                    }
                                    webSocket.close(1000, "error")
                                }
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(Exception("无法连接电脑")),
                                )
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(Exception("连接已断开，请重试。")),
                                )
                            }
                        }
                    }

                    val socket = Http.client.newWebSocket(request, listener)
                    continuation.invokeOnCancellation { socket.cancel() }
                }
            } ?: Result.failure(Exception("配对超时"))

            if (result.isSuccess) return result
            lastError = result.exceptionOrNull() as? Exception
                ?: Exception(result.exceptionOrNull()?.message)
        }

        return Result.failure(
            lastError ?: Exception("无法连接到电脑，请确认手机和电脑在同一 Wi-Fi"),
        )
    }

    /** A host that accepts the socket but never answers must not hang the UI. */
    private const val PairTimeoutMillis = 10_000L
}
