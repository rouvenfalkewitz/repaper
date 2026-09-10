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
    /** The 23-byte payload is 31 base64url chars. */
    private const val TOKEN_CHARS = 31

    fun parse(url: String): Landing {
        // Lenient by design: NFC tags come back mangled in the field — prefix bytes
        // swallowed by readers, EEPROM padding after the payload, stripped schemes.
        // The payload is a fixed-size base64url token, so recover exactly that:
        // take the alphabet-run after the last '?' (or the whole string), and if the
        // full run doesn't decode, try its first 31 chars (trailing garbage case).
        val tok = url.trim().substringAfterLast("?").trimEnd('/')
            .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        val raw = decode(tok)
            ?: (if (tok.length > TOKEN_CHARS) decode(tok.take(TOKEN_CHARS)) else null)
            ?: throw OdError("not an OpenDisplay landing URL")
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

    private fun decode(tok: String): ByteArray? {
        val padded = tok + "=".repeat((4 - tok.length % 4) % 4)
        val raw = try { Base64.getUrlDecoder().decode(padded) } catch (e: IllegalArgumentException) { return null }
        return if (raw.size == 23) raw else null
    }
}

fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }
fun String.hexToBytes(): ByteArray {
    val s = replace(":", "").replace(" ", "")
    require(s.length % 2 == 0) { "odd-length hex" }
    return ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
