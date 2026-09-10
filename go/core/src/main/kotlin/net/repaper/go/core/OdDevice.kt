package net.repaper.go.core

/** One BLE link to a sheet, transport-agnostic: Android's BluetoothGatt implements [OdLink];
 *  tests script it. Mirrors py-opendisplay's device flow — auth, interrogate, legacy
 *  uncompressed direct-write upload with per-chunk ACKs. */
interface OdLink {
    /** Write to the single OpenDisplay characteristic. [withResponse]=false requests Write Without
     *  Response (bulk 0x71 chunks); implementations may fall back to write-with-response. */
    suspend fun write(data: ByteArray, withResponse: Boolean = true)

    /** Next notification frame, or throw OdError on timeout. */
    suspend fun read(timeoutMs: Long): ByteArray
}

class OdDevice(private val link: OdLink, private val masterKey: ByteArray? = null) {
    private var sessionKey: ByteArray? = null
    private var sessionId: ByteArray? = null
    private var nonceCounter: Long = 0

    var capabilities: Capabilities? = null; private set
    var lastConfigHex: String? = null; private set   // raw TLV of the last interrogate, for diagnostics

    companion object {
        const val TIMEOUT_ACK = 5_000L
        const val TIMEOUT_FIRST_CHUNK = 10_000L
        const val TIMEOUT_CONFIG_CHUNK = 2_000L
        const val TIMEOUT_DATA_ACK = 90_000L
        const val TIMEOUT_END_ACK = 90_000L
        const val TIMEOUT_REFRESH = 90_000L
        const val TIMEOUT_NFC_COMMIT = 15_000L   // the tag EEPROM commit is slow I2C work
    }

    val isAuthenticated: Boolean get() = sessionKey != null

    /** Two-step challenge-response; afterwards every command is CCM-wrapped. */
    suspend fun authenticate() {
        val key = masterKey ?: throw OdAuthError("sheet requires a key but none is registered")
        var challenge: Pair<ByteArray, ByteArray>? = null
        for (attempt in 0 until 2) {
            link.write(Od.authStep1())
            try { challenge = Od.parseAuthChallenge(link.read(TIMEOUT_ACK)); break }
            catch (e: OdAuthError) {
                if (attempt == 1 || !e.message!!.contains("active session")) throw e
            }
        }
        val (serverNonce, deviceId) = challenge!!
        val clientNonce = OdCrypto.clientNonce()
        link.write(Od.authStep2(clientNonce, OdCrypto.challengeResponse(key, serverNonce, clientNonce, deviceId)))
        val proof = Od.parseAuthSuccess(link.read(TIMEOUT_ACK))
        val sk = OdCrypto.deriveSessionKey(key, clientNonce, serverNonce, deviceId)
        // mutual auth: a device that answered OK without the master key can't produce this CMAC
        if (!proof.contentEquals(OdCrypto.serverProof(sk, serverNonce, clientNonce, deviceId))) {
            throw OdAuthError("device failed mutual authentication")
        }
        sessionKey = sk
        sessionId = OdCrypto.deriveSessionId(sk, clientNonce, serverNonce)
        nonceCounter = 0
    }

    /** Read + parse the device config; remembers capabilities. */
    suspend fun interrogate(): Capabilities {
        write(Od.readConfig())
        var resp = read(TIMEOUT_FIRST_CHUNK)
        if (resp.size == 4 && resp[0] == 0xFF.toByte() && (resp[1].toInt() and 0xFF) == (Od.READ_CONFIG and 0xFF)) {
            throw OdError("device has no stored configuration")
        }
        var chunk = Od.stripEcho(resp, Od.READ_CONFIG)
        if (chunk.size < 4) throw OdError("config first chunk too short")
        val total = (chunk[2].toInt() and 0xFF) or ((chunk[3].toInt() and 0xFF) shl 8)
        val tlv = ArrayList<Byte>(total)
        chunk.drop(4).forEach { tlv.add(it) }
        while (tlv.size < total) {
            resp = read(TIMEOUT_CONFIG_CHUNK)
            chunk = Od.stripEcho(resp, Od.READ_CONFIG)
            if (chunk.size <= 2) throw OdError("config read stalled at ${tlv.size}/$total bytes")
            for (i in 2 until chunk.size) tlv.add(chunk[i])   // skip the 2-byte chunk number
        }
        val bytes = tlv.toByteArray()
        lastConfigHex = bytes.toHexLower()
        return OdConfig.parse(bytes).also { capabilities = it }
    }

    /** Upload an already-rendered page and refresh. [narrate] gets human-readable phases. */
    suspend fun print(page: Page, fullRefresh: Boolean = true, narrate: (String) -> Unit = {}) {
        if (masterKey != null && !isAuthenticated) { narrate("connecting"); authenticate() }
        val caps = capabilities ?: run { narrate("reading the sheet"); interrogate() }
        if (caps.viewedWidth != page.model.width || caps.viewedHeight != page.model.height) {
            throw OdError("sheet shows ${caps.viewedWidth}×${caps.viewedHeight}, page is ${page.model.width}×${page.model.height} — re-register the sheet")
        }
        val native = OdEncoding.rotateToNative(page.indexes, page.model.width, page.model.height, caps.rotation)
        val data = OdEncoding.encode(native, caps.width, caps.height, caps.scheme, caps.panelIc)

        narrate("connected — sending")
        write(Od.directWriteStartUncompressed())
        Od.validateAck(read(TIMEOUT_FIRST_CHUNK), Od.DIRECT_WRITE_START)

        val chunkSize = if (isAuthenticated) Od.ENCRYPTED_CHUNK_SIZE else Od.CHUNK_SIZE
        var sent = 0
        var autoCompleted = false
        while (sent < data.size) {
            val chunk = data.copyOfRange(sent, minOf(sent + chunkSize, data.size))
            write(Od.directWriteData(chunk), withResponse = false)
            sent += chunk.size
            val resp = read(TIMEOUT_DATA_ACK)
            when (Od.code(resp) and Od.ACK_FLAG.inv()) {
                Od.DIRECT_WRITE_END -> { autoCompleted = true; break }   // device buffer full: it refreshes by itself
                Od.DIRECT_WRITE_DATA -> {}
                else -> throw OdError("unexpected response during upload: 0x%04x".format(Od.code(resp)))
            }
        }

        if (!autoCompleted) {
            write(Od.directWriteEnd(if (fullRefresh) 0 else 1))
            Od.validateAck(read(TIMEOUT_END_ACK), Od.DIRECT_WRITE_END)
        }
        narrate("sheet is refreshing (about 20 s)")
        when (Od.code(read(TIMEOUT_REFRESH)) and Od.ACK_FLAG.inv()) {
            Od.REFRESH_COMPLETE -> narrate("printed")
            Od.REFRESH_TIMEOUT -> throw OdError("display refresh timed out (device sent 0x74)")
            else -> throw OdError("unexpected response waiting for refresh")
        }
    }

    /** Write the sheet's OWN NFC tag over BLE: a URI record carrying the landing link,
     *  so tapping the sheet always resolves — some sheets ship with an empty tag.
     *  Older firmware stays silent on the unknown opcode: that surfaces as
     *  "doesn't support" (non-fatal for callers). */
    suspend fun writeNfcUrl(url: String) {
        val payload = url.toByteArray(Charsets.UTF_8)
        if (payload.size > Od.NFC_MAX_TOTAL) throw OdError("landing link too long for the tag")
        var first = true
        suspend fun readNfc(timeoutMs: Long): ByteArray = try {
            read(timeoutMs).also { first = false }
        } catch (e: OdError) {
            if (first) throw OdError("this sheet's firmware doesn't support NFC writing") else throw e
        }
        if (payload.size <= Od.NFC_INLINE_MAX) {
            write(Od.nfcWriteInline(Od.NFC_REC_URI, payload))
            Od.validateNfc(readNfc(TIMEOUT_NFC_COMMIT), Od.NFC_STATUS_WRITE_OK)
            return
        }
        write(Od.nfcWriteStart(Od.NFC_REC_URI, payload.size))
        Od.validateNfc(readNfc(TIMEOUT_ACK), Od.NFC_STATUS_CHUNK_ACK)
        var off = 0
        while (off < payload.size) {
            val chunk = payload.copyOfRange(off, minOf(off + Od.NFC_CHUNK, payload.size))
            write(Od.nfcWriteData(chunk))
            Od.validateNfc(readNfc(TIMEOUT_ACK), Od.NFC_STATUS_CHUNK_ACK)
            off += chunk.size
        }
        write(Od.nfcWriteEnd())
        Od.validateNfc(readNfc(TIMEOUT_NFC_COMMIT), Od.NFC_STATUS_WRITE_OK)
    }

    // ── session-aware write/read ─────────────────────────────────────────────

    private suspend fun write(data: ByteArray, withResponse: Boolean = true) {
        val sk = sessionKey; val sid = sessionId
        if (sk != null && sid != null) {
            link.write(OdCrypto.encryptCommand(sk, sid, nonceCounter++, data.copyOfRange(0, 2), data.copyOfRange(2, data.size)), withResponse)
        } else link.write(data, withResponse)
    }

    private suspend fun read(timeoutMs: Long): ByteArray {
        val raw = link.read(timeoutMs)
        val sk = sessionKey
        // Encrypted responses are ≥31 bytes; short frames (direct-write ACKs, error frames) stay plaintext.
        if (sk != null && raw.size >= 31) {
            val (cmd, payload) = OdCrypto.decryptResponse(sk, raw)
            return byteArrayOf((cmd shr 8).toByte(), (cmd and 0xFF).toByte()) + payload
        }
        if (raw.size == 3 && raw[2] == 0xFE.toByte()) throw OdAuthError("device requires an encryption key")
        if (raw.size == 3 && raw[2] == 0xFF.toByte()) throw OdError("device rejected command: integrity check failed")
        return raw
    }
}
