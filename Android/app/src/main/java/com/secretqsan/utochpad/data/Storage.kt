package com.secretqsan.utochpad.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.secretqsan.utochpad.net.Crypto
import com.secretqsan.utochpad.net.PairingData
import org.json.JSONArray
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

enum class AppTheme { Light, Dark }

class Storage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val value = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_DEVICE_ID, value) }
            return value
        }

    val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun isPaired(): Boolean = prefs.getBoolean(KEY_PAIRED, false)

    fun loadPairing(): PairingData? {
        if (!isPaired()) return null

        return try {
            val hosts = prefs.getString(KEY_HOSTS, null) ?: return null
            val hostArray = JSONArray(hosts)
            val hostList = buildList {
                for (index in 0 until hostArray.length()) add(hostArray.getString(index))
            }
            if (hostList.isEmpty()) return null

            PairingData(
                hosts = hostList,
                port = prefs.getInt(KEY_PORT, 0),
                deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null,
                pairKey = decrypt(prefs.getString(KEY_PAIR_KEY, null) ?: return null),
                serviceName = prefs.getString(KEY_SERVICE_NAME, "Windows PC") ?: "Windows PC",
            )
        } catch (_: Exception) {
            null
        }
    }

    fun savePairing(data: PairingData) {
        prefs.edit {
            putBoolean(KEY_PAIRED, true)
            putString(KEY_HOSTS, JSONArray(data.hosts).toString())
            putInt(KEY_PORT, data.port)
            putString(KEY_DEVICE_ID, data.deviceId)
            putString(KEY_PAIR_KEY, encrypt(data.pairKey))
            putString(KEY_SERVICE_NAME, data.serviceName)
            // The phone's private key is only needed while pairing. Remove a
            // copy left behind by older versions instead of keeping it.
            remove(KEY_PHONE_PRIVATE)
        }
    }

    fun loadTheme(): AppTheme =
        if (prefs.getString(KEY_THEME, null) == "light") AppTheme.Light else AppTheme.Dark

    fun saveTheme(theme: AppTheme) {
        prefs.edit { putString(KEY_THEME, theme.name.lowercase()) }
    }

    fun clearPairing() {
        prefs.edit {
            remove(KEY_PAIRED)
            remove(KEY_HOSTS)
            remove(KEY_PORT)
            remove(KEY_PAIR_KEY)
            remove(KEY_PHONE_PRIVATE)
            remove(KEY_SERVICE_NAME)
        }
    }

    fun hasPassword(): Boolean =
        prefs.contains(KEY_WRAPPED_PDK) && prefs.contains(KEY_PASSWORD_BLOB)

    fun loadWrappedPdk(): ByteArray? =
        prefs.getString(KEY_WRAPPED_PDK, null)?.let(Crypto::unb64)

    fun saveWrappedPdk(blob: ByteArray) {
        prefs.edit { putString(KEY_WRAPPED_PDK, Crypto.b64(blob)) }
    }

    fun loadPasswordBlob(): ByteArray? =
        prefs.getString(KEY_PASSWORD_BLOB, null)?.let(Crypto::unb64)

    fun savePasswordBlob(blob: ByteArray) {
        prefs.edit { putString(KEY_PASSWORD_BLOB, Crypto.b64(blob)) }
    }

    fun clearPassword() {
        prefs.edit {
            remove(KEY_WRAPPED_PDK)
            remove(KEY_PASSWORD_BLOB)
        }
        KeystoreKeys.deletePasswordKey()
    }

    private fun encrypt(plaintext: ByteArray): String {
        val key = KeystoreKeys.storageKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return Crypto.b64(cipher.iv + ciphertext)
    }

    private fun decrypt(value: String): ByteArray {
        val blob = Crypto.unb64(value)
        val iv = blob.copyOfRange(0, Crypto.NONCE_SIZE)
        val ciphertext = blob.copyOfRange(Crypto.NONCE_SIZE, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            KeystoreKeys.storageKey(),
            GCMParameterSpec(128, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val PREFS = "utochpad"
        private const val KEY_PAIRED = "paired"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_PORT = "port"
        private const val KEY_DEVICE_ID = "device_id"
        // Legacy key written by older versions; never read, removed on save.
        private const val KEY_PHONE_PRIVATE = "phone_private"
        private const val KEY_PAIR_KEY = "pair_key"
        private const val KEY_SERVICE_NAME = "service_name"
        private const val KEY_WRAPPED_PDK = "wrapped_pdk"
        private const val KEY_PASSWORD_BLOB = "password_blob"
        private const val KEY_THEME = "theme"
    }
}
