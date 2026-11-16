package com.secretqsan.utochpad.data

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.secretqsan.utochpad.net.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

sealed interface VaultResult {
    data class Success(val value: String = "") : VaultResult
    data class Failure(val message: String) : VaultResult
    data object Cancelled : VaultResult
}

/**
 * Stores the Windows password under two layers: the password is encrypted with
 * a random PDK, and the PDK is wrapped by a biometric, auth-per-use Keystore key.
 */
class PasswordVault(private val storage: Storage) {
    @Volatile
    private var activePrompt: BiometricPrompt? = null

    suspend fun storePassword(activity: FragmentActivity, password: String): VaultResult =
        withContext(Dispatchers.Main) {
            val availability = BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                return@withContext VaultResult.Failure(availabilityMessage(availability))
            }

            try {
                val existing = storage.loadWrappedPdk()
                var pdk = ByteArray(0)
                var needsNewPdk = false

                if (existing != null) {
                    try {
                        val (cipher, ciphertext) = decryptCipher(existing)
                        val authenticated = authenticate(
                            activity,
                            cipher,
                            "保存 Windows 密码",
                            "验证指纹以更新手机中保存的密码",
                        ) ?: return@withContext VaultResult.Cancelled

                        pdk = authenticated.doFinal(ciphertext)
                    } catch (_: Exception) {
                        // The wrapping key is gone or was invalidated (fingerprint
                        // changes, backup restore). Replace it instead of trapping
                        // the user in a state where no password can be saved.
                        storage.clearPassword()
                        needsNewPdk = true
                    }
                } else {
                    needsNewPdk = true
                }

                if (needsNewPdk) {
                    pdk = Crypto.randomBytes(32)
                    val cipher = encryptCipher()
                    val authenticated = authenticate(
                        activity,
                        cipher,
                        "保存 Windows 密码",
                        "验证指纹以保护电脑密码",
                    ) ?: return@withContext VaultResult.Cancelled

                    val wrapped = authenticated.doFinal(pdk)
                    storage.saveWrappedPdk(authenticated.iv + wrapped)
                }

                val iv = Crypto.randomBytes(Crypto.NONCE_SIZE)
                val blob = iv + Crypto.aeadSeal(pdk, iv, password.toByteArray(Charsets.UTF_8))
                storage.savePasswordBlob(blob)
                pdk.fill(0)

                VaultResult.Success()
            } catch (_: KeyPermanentlyInvalidatedException) {
                storage.clearPassword()
                VaultResult.Failure("指纹已变更，请在电脑上重新发送 Windows 密码")
            } catch (ex: Exception) {
                VaultResult.Failure(ex.message ?: "保存密码失败")
            }
        }

    suspend fun decryptPassword(activity: FragmentActivity): VaultResult =
        withContext(Dispatchers.Main) {
            val wrapped = storage.loadWrappedPdk()
            val blob = storage.loadPasswordBlob()
            if (wrapped == null || blob == null) {
                return@withContext VaultResult.Failure("手机尚未保存 Windows 密码")
            }

            val availability = BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                return@withContext VaultResult.Failure(availabilityMessage(availability))
            }

            try {
                val (cipher, ciphertext) = decryptCipher(wrapped)
                val authenticated = authenticate(
                    activity,
                    cipher,
                    "解锁电脑",
                    "验证指纹以把 Windows 密码发送到电脑",
                ) ?: return@withContext VaultResult.Cancelled

                val pdk = authenticated.doFinal(ciphertext)
                val iv = blob.copyOfRange(0, Crypto.NONCE_SIZE)
                val password = Crypto.aeadOpen(
                    pdk,
                    iv,
                    blob.copyOfRange(Crypto.NONCE_SIZE, blob.size),
                )
                pdk.fill(0)

                VaultResult.Success(String(password, Charsets.UTF_8))
            } catch (_: KeyPermanentlyInvalidatedException) {
                storage.clearPassword()
                VaultResult.Failure("指纹已变更，请在电脑上重新发送 Windows 密码")
            } catch (_: Exception) {
                VaultResult.Failure("无法解密密码，请在电脑上重新发送")
            }
        }

    fun cancelActivePrompt() {
        activePrompt?.cancelAuthentication()
        activePrompt = null
    }

    private fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KeystoreKeys.passwordKey())
        return cipher
    }

    private fun decryptCipher(wrapped: ByteArray): Pair<Cipher, ByteArray> {
        require(wrapped.size > Crypto.NONCE_SIZE) { "保存的密钥数据无效" }
        val iv = wrapped.copyOfRange(0, Crypto.NONCE_SIZE)
        val ciphertext = wrapped.copyOfRange(Crypto.NONCE_SIZE, wrapped.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            KeystoreKeys.passwordKey(),
            GCMParameterSpec(128, iv),
        )
        return cipher to ciphertext
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Cipher? = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activePrompt = null
                if (continuation.isActive) {
                    continuation.resume(result.cryptoObject?.cipher)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activePrompt = null
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }

            override fun onAuthenticationFailed() {
                // Wrong finger: keep the prompt open.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        activePrompt = prompt

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("取消")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        continuation.invokeOnCancellation {
            prompt.cancelAuthentication()
            activePrompt = null
        }

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun availabilityMessage(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "这台手机没有可用的指纹硬件"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "指纹硬件暂时不可用"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "请先在系统设置中录入指纹"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "系统需要安全更新后才能使用指纹"
        else -> "无法使用指纹，请检查手机的指纹设置"
    }
}
