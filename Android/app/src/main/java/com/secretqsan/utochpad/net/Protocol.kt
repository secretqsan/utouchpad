package com.secretqsan.utochpad.net

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val VERSION = 1

    const val TYPE_CONTROL = 1
    const val TYPE_PTP = 2
    const val TYPE_RADIAL = 3

    const val PTP_WIRE_SIZE = 32
    const val RADIAL_WIRE_SIZE = 3
    const val MAX_CONTACTS = 5

    /**
     * The page holds a touchpad and a radial knob; both virtual devices live on
     * one USB bus so both payload types can stream at once.
     */
    const val MODE_INPUT = "touchpad+radial"

    // Handshake
    const val T_HELLO = "hello"
    const val T_CHALLENGE = "challenge"
    const val T_AUTH = "auth"
    const val T_READY = "ready"
    const val T_PAIR = "pair"
    const val T_PAIR_OK = "pairOk"
    const val T_ERROR = "error"

    // Control
    const val T_UNLOCK_REQUEST = "unlockRequest"
    const val T_UNLOCK_RESPONSE = "unlockResponse"
    const val T_CANCEL_UNLOCK = "cancelUnlock"
    const val T_SET_PASSWORD = "setPassword"
    const val T_SET_PASSWORD_RESULT = "setPasswordResult"
    const val T_ENTER_MODE = "enterMode"
    const val T_MODE_READY = "modeReady"
    const val T_MODE_ERROR = "modeError"

    fun control(type: String, block: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("t", type).apply(block)

    fun buildPtpFrame(contacts: List<Contact>, buttons: Int): ByteArray {
        val buffer = ByteBuffer.allocate(PTP_WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val count = contacts.size.coerceAtMost(MAX_CONTACTS)

        buffer.put(count.toByte())
        buffer.put((buttons and 0x07).toByte())

        for (slot in 0 until MAX_CONTACTS) {
            val contact = if (slot < count) contacts[slot] else null
            if (contact == null) {
                repeat(6) { buffer.put(0) }
            } else {
                // Wire order matches the Go device: id(1) flags(1) x(2) y(2).
                buffer.put((contact.id and 0xFF).toByte())
                var flags = 0
                if (contact.confidence) flags = flags or FLAG_CONFIDENCE
                if (contact.tip) flags = flags or FLAG_TIP
                buffer.put(flags.toByte())
                buffer.putShort(contact.x.coerceIn(0, 4095).toShort())
                buffer.putShort(contact.y.coerceIn(0, 4095).toShort())
            }
        }

        return buffer.array()
    }

    fun buildRadialFrame(dialTenths: Int, buttons: Int): ByteArray {
        val buffer = ByteBuffer.allocate(RADIAL_WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(dialTenths.coerceIn(-3600, 3600).toShort())
        buffer.put((buttons and 0x01).toByte())
        return buffer.array()
    }

    fun radialNeutralFrame(): ByteArray = ByteArray(RADIAL_WIRE_SIZE)

    const val FLAG_CONFIDENCE = 0x01
    const val FLAG_TIP = 0x02
}

/**
 * One PTP contact. [tip] is false for the single frame that reports a finger
 * leaving: Windows needs that transition to turn the lift into a tap.
 */
data class Contact(
    val id: Int,
    val x: Int,
    val y: Int,
    val tip: Boolean = true,
    val confidence: Boolean = true,
)

data class PairQrPayload(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val servicePublicKey: ByteArray,
    val token: String,
) {
    companion object {
        fun parse(raw: String): PairQrPayload {
            val trimmed = raw.trim()
            val json = if (trimmed.startsWith("{")) {
                trimmed
            } else {
                String(Crypto.unb64Url(trimmed), Charsets.UTF_8)
            }

            val objectValue = JSONObject(json)
            val version = objectValue.optInt("v", 1)
            require(version == Protocol.VERSION) { "二维码无效，请重新在电脑上生成。" }

            val hostsArray = objectValue.optJSONArray("hosts") ?: JSONArray()
            val hosts = buildList {
                for (index in 0 until hostsArray.length()) {
                    val host = hostsArray.optString(index)
                    if (host.isNotBlank() && host != "127.0.0.1") add(host)
                }
            }
            require(hosts.isNotEmpty()) { "二维码无效，请重新在电脑上生成。" }

            return PairQrPayload(
                name = objectValue.optString("name", "Windows PC"),
                hosts = hosts,
                port = objectValue.getInt("port"),
                servicePublicKey = Crypto.unb64(objectValue.getString("pub")),
                token = objectValue.getString("token"),
            )
        }
    }
}

data class PairingData(
    val hosts: List<String>,
    val port: Int,
    val deviceId: String,
    val pairKey: ByteArray,
    val serviceName: String,
)
