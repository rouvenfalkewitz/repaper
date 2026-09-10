package net.repaper.go.core

/** Command frames and response parsing for the OpenDisplay BLE protocol —
 *  the legacy direct-write subset every firmware supports (see py-opendisplay protocol/). */
object Od {
    const val SERVICE_UUID = "00002446-0000-1000-8000-00805F9B34FB"
    const val MANUFACTURER_ID = 0x2446

    const val READ_CONFIG = 0x0040
    const val READ_FW_VERSION = 0x0043
    const val AUTHENTICATE = 0x0050
    const val DIRECT_WRITE_START = 0x0070
    const val DIRECT_WRITE_DATA = 0x0071
    const val DIRECT_WRITE_END = 0x0072
    const val REFRESH_COMPLETE = 0x0073
    const val REFRESH_TIMEOUT = 0x0074
    const val ACK_FLAG = 0x8000

    const val CHUNK_SIZE = 230            // unencrypted 0x71 payload
    const val ENCRYPTED_CHUNK_SIZE = 154  // under a session: cmd(2)+nonce(16)+len(1)+data+tag(12) = 185

    // NFC endpoint 0x0083: the sheet writes its OWN tag over BLE (sub-opcode selects the op)
    const val NFC_ENDPOINT = 0x0083
    const val NFC_SUB_WRITE_INLINE = 0x01
    const val NFC_SUB_WRITE_START = 0x10
    const val NFC_SUB_WRITE_DATA = 0x11
    const val NFC_SUB_WRITE_END = 0x12
    const val NFC_REC_URI = 1
    const val NFC_INLINE_MAX = 120        // above this: chunked write
    const val NFC_CHUNK = 120
    const val NFC_MAX_TOTAL = 512
    const val NFC_STATUS_WRITE_OK = 0x81
    const val NFC_STATUS_CHUNK_ACK = 0x82

    private fun cmd(code: Int): ByteArray = byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())

    fun readConfig(): ByteArray = cmd(READ_CONFIG)
    fun readFwVersion(): ByteArray = cmd(READ_FW_VERSION)
    fun authStep1(): ByteArray = cmd(AUTHENTICATE) + byteArrayOf(0x00)
    fun authStep2(clientNonce: ByteArray, challenge: ByteArray): ByteArray {
        require(clientNonce.size == 16 && challenge.size == 16)
        return cmd(AUTHENTICATE) + clientNonce + challenge
    }
    fun directWriteStartUncompressed(): ByteArray = cmd(DIRECT_WRITE_START)
    fun directWriteData(chunk: ByteArray): ByteArray {
        require(chunk.size <= CHUNK_SIZE) { "chunk ${chunk.size} > $CHUNK_SIZE" }
        return cmd(DIRECT_WRITE_DATA) + chunk
    }
    fun directWriteEnd(refreshMode: Int = 0): ByteArray = cmd(DIRECT_WRITE_END) + byteArrayOf(refreshMode.toByte())

    // ── the sheet's NFC tag, written over BLE ────────────────────────────────

    fun nfcWriteInline(recType: Int, payload: ByteArray): ByteArray {
        require(payload.size in 1..NFC_INLINE_MAX) { "inline payload ${payload.size} > $NFC_INLINE_MAX" }
        return cmd(NFC_ENDPOINT) + byteArrayOf(NFC_SUB_WRITE_INLINE.toByte(), recType.toByte(),
            (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte()) + payload
    }
    fun nfcWriteStart(recType: Int, totalLen: Int): ByteArray {
        require(totalLen in 1..NFC_MAX_TOTAL) { "nfc payload $totalLen > $NFC_MAX_TOTAL" }
        return cmd(NFC_ENDPOINT) + byteArrayOf(NFC_SUB_WRITE_START.toByte(), recType.toByte(),
            (totalLen shr 8).toByte(), (totalLen and 0xFF).toByte())
    }
    fun nfcWriteData(chunk: ByteArray): ByteArray {
        require(chunk.size in 1..NFC_CHUNK) { "nfc chunk ${chunk.size} > $NFC_CHUNK" }
        return cmd(NFC_ENDPOINT) + byteArrayOf(NFC_SUB_WRITE_DATA.toByte()) + chunk
    }
    fun nfcWriteEnd(): ByteArray = cmd(NFC_ENDPOINT) + byteArrayOf(NFC_SUB_WRITE_END.toByte())

    /** OK frames are [00 83 status]; errors are [FF 83 FF err]. */
    fun validateNfc(data: ByteArray, expectedStatus: Int) {
        if (data.size >= 4 && data[0] == 0xFF.toByte() && data[1] == 0x83.toByte() && data[2] == 0xFF.toByte()) {
            throw OdError("the sheet rejected the tag write (error 0x%02x)".format(data[3]))
        }
        if (data.size >= 3 && data[0].toInt() == 0x00 && data[1] == 0x83.toByte()) {
            val status = data[2].toInt() and 0xFF
            if (status == expectedStatus) return
            throw OdError("NFC status mismatch: expected 0x%02x, got 0x%02x".format(expectedStatus, status))
        }
        throw OdError("unexpected NFC response")
    }

    // ── responses ────────────────────────────────────────────────────────────

    fun code(data: ByteArray, offset: Int = 0): Int {
        if (data.size < offset + 2) throw OdError("response too short for a command code")
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    /** ACKs echo the command, sometimes with the high bit set. */
    fun validateAck(data: ByteArray, expected: Int) {
        val c = code(data)
        if (c != expected && c != (expected or ACK_FLAG)) {
            throw OdError("ACK mismatch: expected 0x%04x, got 0x%04x".format(expected, c))
        }
    }

    fun stripEcho(data: ByteArray, expected: Int): ByteArray {
        if (data.size >= 2) {
            val c = code(data)
            if (c == expected || c == (expected or ACK_FLAG)) return data.copyOfRange(2, data.size)
        }
        return data
    }

    /** Step-1 auth response: [echo:2][status:1][server_nonce:16][device_id:4?]. */
    fun parseAuthChallenge(data: ByteArray): Pair<ByteArray, ByteArray> {
        checkAuthEcho(data)
        when (data[2].toInt() and 0xFF) {
            0x00 -> {}
            0x02 -> throw OdAuthError("device has an active session; retry")
            0x03 -> throw OdAuthError("device does not have encryption configured")
            0x04 -> throw OdAuthError("authentication rate limit exceeded — wait before retrying")
            else -> throw OdAuthError("auth challenge failed with status 0x%02x".format(data[2]))
        }
        if (data.size < 19) throw OdError("auth challenge too short for nonce")
        val nonce = data.copyOfRange(3, 19)
        val deviceId = if (data.size >= 23) data.copyOfRange(19, 23) else OdCrypto.DEFAULT_DEVICE_ID
        return Pair(nonce, deviceId)
    }

    /** Step-2 auth response: [echo:2][status:1][server_proof:16]. */
    fun parseAuthSuccess(data: ByteArray): ByteArray {
        checkAuthEcho(data)
        when (data[2].toInt() and 0xFF) {
            0x00 -> {}
            0x01 -> throw OdAuthError("wrong encryption key")
            0x04 -> throw OdAuthError("authentication rate limit exceeded — wait before retrying")
            else -> throw OdAuthError("authentication failed with status 0x%02x".format(data[2]))
        }
        if (data.size < 19) throw OdError("auth success too short for server proof")
        return data.copyOfRange(3, 19)
    }

    private fun checkAuthEcho(data: ByteArray) {
        if (data.size < 3) throw OdError("auth response too short")
        val c = code(data)
        if (c != AUTHENTICATE && c != (AUTHENTICATE or ACK_FLAG)) throw OdError("auth echo mismatch: 0x%04x".format(c))
    }
}

/** Just enough of the TLV config to know what the panel is (display packet 0x20);
 *  other packets are skipped by their fixed size. */
object OdConfig {
    private val SIZES = mapOf(
        0x01 to 22, 0x02 to 22, 0x04 to 30, 0x20 to 46, 0x21 to 22, 0x22 to 30,
        0x24 to 30, 0x25 to 30, 0x26 to 160, 0x27 to 64, 0x28 to 32, 0x29 to 32,
        0x2A to 32, 0x2B to 32,
    )

    /** Input: full TLV bytes (wrapper [len:2LE][version:1][packets][crc:2] included). */
    fun parse(raw: ByteArray): Capabilities {
        if (raw.size < 5) throw OdError("config too short: ${raw.size} bytes")
        val packets = raw.copyOfRange(3, raw.size - 2)
        var off = 0
        var caps: Capabilities? = null
        while (off + 2 <= packets.size) {
            val type = packets[off + 1].toInt() and 0xFF
            off += 2
            val size = SIZES[type] ?: break        // unknown type: sizes unknowable, stop like the SDK
            if (off + size > packets.size) break
            val p = packets.copyOfRange(off, off + size)
            off += size
            when (type) {
                0x20 -> if (caps == null) {
                    // <BBHHHHHHBBBBBBBBBB> little-endian: instance(0) tech(1) panel_ic(2-3)
                    // width(4-5) height(6-7) mm_w(8-9) mm_h(10-11) tag_type(12-13)
                    // rotation(14) reset(15) busy(16) dc(17) cs(18) data(19)
                    // partial(20) color_scheme(21) trans_modes(22) clk(23)
                    val panelIc = le16(p, 2)
                    val w = le16(p, 4); val h = le16(p, 6)
                    val rotIdx = p[14].toInt() and 0xFF
                    val scheme = ColorScheme.fromWire(p[21].toInt() and 0xFF)
                    caps = Capabilities(w, h, scheme, (rotIdx % 4) * 90, panelIc)
                }
            }
        }
        return caps ?: throw OdError("device config has no display packet")
    }

    private fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}

/** Battery/temperature straight from the BLE advertisement (manufacturer data 0x2446, v1 layout):
 *  md[11] = (temp+40)*2, md[12] | ((md[13] & 1) << 8) = mV/10. No connection needed. */
data class AdvStatus(val batteryVolts: Double?, val temperatureC: Double?) {
    companion object {
        fun parse(md: ByteArray): AdvStatus {
            val mv = if (md.size >= 14) ((md[12].toInt() and 0xFF) or ((md[13].toInt() and 1) shl 8)) * 10 else null
            val temp = if (md.size >= 12) (md[11].toInt() and 0xFF) / 2.0 - 40 else null
            return AdvStatus(mv?.let { it / 1000.0 }, temp)
        }
    }
}
