package net.repaper.go.core

import java.util.Base64

/** The QR on a sheet opens a landing URL whose token carries the sheet's identity and AES key.
 *  Layout (23 bytes): tag_type u16 BE, device id 3 B, AES-128 key 16 B (all-zero = unencrypted),
 *  manufacturer u16 BE. Same decoding as the Dock's opendisplay_ble.parse_landing_url. */
data class Landing(
    val tagType: Int,
    val deviceId: String,      // 3 bytes hex, upper
    val name: String,          // "OD" + deviceId — the BLE advertised name
    val keyHex: String?,       // null when the sheet is unencrypted
    val manufacturerId: Int,
)

object LandingUrl {
    fun parse(url: String): Landing {
        val tok = url.trim().substringAfterLast("?").trimEnd('/')
        val padded = tok + "=".repeat((4 - tok.length % 4) % 4)
        val raw = try { Base64.getUrlDecoder().decode(padded) } catch (e: IllegalArgumentException) {
            throw OdError("not an OpenDisplay landing URL", e)
        }
        if (raw.size != 23) throw OdError("not an OpenDisplay landing URL (payload must be 23 bytes)")
        val key = raw.copyOfRange(5, 21)
        val id = raw.copyOfRange(2, 5).toHexUpper()
        return Landing(
            tagType = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF),
            deviceId = id,
            name = "OD$id",
            keyHex = if (key.all { it == 0.toByte() }) null else key.toHexLower(),
            manufacturerId = ((raw[21].toInt() and 0xFF) shl 8) or (raw[22].toInt() and 0xFF),
        )
    }
}

fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }
fun String.hexToBytes(): ByteArray {
    val s = replace(":", "").replace(" ", "")
    require(s.length % 2 == 0) { "odd-length hex" }
    return ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
