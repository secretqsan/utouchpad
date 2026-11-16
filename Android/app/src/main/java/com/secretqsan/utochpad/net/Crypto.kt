package com.secretqsan.utochpad.net

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives mirrored by Utochpad.Shared on the PC:
 * NIST P-256 ECDH, HKDF-SHA256, AES-256-GCM and HMAC-SHA256.
 */
object Crypto {
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    fun publicSpki(keyPair: KeyPair): ByteArray = keyPair.public.encoded

    fun publicKey(bytes: ByteArray) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicSpki: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey(peerPublicSpki), true)
        return agreement.generateSecret()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(actualSalt, ikm)

        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1

        while (output.size() < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            output.write(previous)
            counter++
        }

        return output.toByteArray().copyOf(length)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmac(key: ByteArray, text: String): ByteArray = hmac(key, text.toByteArray(Charsets.UTF_8))

    fun proofText(role: String, nonceP: ByteArray, nonceS: ByteArray): String =
        "$role|${b64(nonceP)}|${b64(nonceS)}"

    fun derivePairKey(sharedSecret: ByteArray): ByteArray =
        hkdf(
            sharedSecret,
            "utochpad-pair-v1".toByteArray(Charsets.UTF_8),
            "utochpad-pair-key".toByteArray(Charsets.UTF_8),
            32,
        )

    fun deriveSessionKeys(pairKey: ByteArray, nonceP: ByteArray, nonceS: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = nonceP + nonceS
        val clientToServer = hkdf(
            pairKey, salt, "utochpad-c2s".toByteArray(Charsets.UTF_8), 32)
        val serverToClient = hkdf(
            pairKey, salt, "utochpad-s2c".toByteArray(Charsets.UTF_8), 32)
        return clientToServer to serverToClient
    }

    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    fun nonceFor(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in NONCE_SIZE - 1 downTo 0) {
            nonce[index] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return nonce
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    fun unb64Url(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** One direction of an AES-GCM session. Frame = nonce(12) + ciphertext + tag. */
class AeadSession(private val key: ByteArray) {
    private var counter = 0L
    private var lastReceived = -1L

    // Constructing a Cipher costs far more than encrypting a 33-byte touch
    // frame. AeadSession has a single producer per direction and every entry
    // point is synchronized, so one initialized-on-demand Cipher can be reused.
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val secretKey = SecretKeySpec(key, "AES")

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = Crypto.nonceFor(counter++)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val frame = ByteArray(nonce.size + ciphertext.size)
        nonce.copyInto(frame, 0)
        ciphertext.copyInto(frame, nonce.size)
        return frame
    }

    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        require(frame.size >= Crypto.NONCE_SIZE + Crypto.TAG_SIZE) { "Frame too short" }

        val expected = Crypto.nonceFor(lastReceived + 1)
        for (index in 0 until Crypto.NONCE_SIZE) {
            if (frame[index] != expected[index]) {
                throw SecurityException("Unexpected frame counter")
            }
        }

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(128, frame, 0, Crypto.NONCE_SIZE),
        )
        val plaintext = cipher.doFinal(
            frame,
            Crypto.NONCE_SIZE,
            frame.size - Crypto.NONCE_SIZE,
        )
        lastReceived++
        return plaintext
    }
}
