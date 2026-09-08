package net.repaper.go.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives OdDevice against a scripted fake sheet — including a full encrypted session where the
 *  fake device runs the same firmware-side crypto, so auth, CCM framing and nonce advance are
 *  exercised end to end without hardware. */
class UploadFlowTest {

    private class FakeSheet(val masterKey: ByteArray? = null) : OdLink {
        val written = ArrayList<ByteArray>()
        val toRead = ArrayDeque<ByteArray>()
        var sessionKey: ByteArray? = null
        private var serverNonce = ByteArray(16) { 0x42 }
        var chunksReceived = 0
        var refreshMode = -1

        override suspend fun write(data: ByteArray, withResponse: Boolean) {
            written.add(data)
            val plain = decryptIfSession(data)
            when (Od.code(plain)) {
                Od.AUTHENTICATE -> handleAuth(plain)
                Od.DIRECT_WRITE_START -> toRead.add(ack(Od.DIRECT_WRITE_START))
                Od.DIRECT_WRITE_DATA -> { chunksReceived++; toRead.add(ack(Od.DIRECT_WRITE_DATA)) }
                Od.DIRECT_WRITE_END -> {
                    refreshMode = plain[2].toInt()
                    toRead.add(ack(Od.DIRECT_WRITE_END))
                    toRead.add(ack(Od.REFRESH_COMPLETE))
                }
            }
        }

        override suspend fun read(timeoutMs: Long): ByteArray =
            toRead.removeFirstOrNull() ?: throw OdError("no response within ${timeoutMs}ms")

        private fun ack(code: Int): ByteArray = byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())

        private fun decryptIfSession(data: ByteArray): ByteArray {
            val sk = sessionKey ?: return data
            if (data.size < 31) return data
            val (cmd, payload) = OdCrypto.decryptResponse(sk, data)   // same envelope both directions
            return byteArrayOf((cmd shr 8).toByte(), (cmd and 0xFF).toByte()) + payload
        }

        private fun handleAuth(plain: ByteArray) {
            val key = masterKey ?: return
            if (plain.size == 3 && plain[2] == 0x00.toByte()) {
                // step 1 → status OK + nonce + device id
                toRead.add(ack(Od.AUTHENTICATE) + byteArrayOf(0) + serverNonce + OdCrypto.DEFAULT_DEVICE_ID)
            } else {
                val clientNonce = plain.copyOfRange(2, 18)
                val challenge = plain.copyOfRange(18, 34)
                val expect = OdCrypto.challengeResponse(key, serverNonce, clientNonce)
                if (!challenge.contentEquals(expect)) { toRead.add(ack(Od.AUTHENTICATE) + byteArrayOf(1)); return }
                val sk = OdCrypto.deriveSessionKey(key, clientNonce, serverNonce)
                sessionKey = sk
                toRead.add(ack(Od.AUTHENTICATE) + byteArrayOf(0) + OdCrypto.serverProof(sk, serverNonce, clientNonce))
            }
        }
    }

    private fun page(w: Int, h: Int, palette: String) =
        Page(IntArray(w * h) { it % 2 }, SheetModel(w, h, palette))

    @Test fun plainUploadSendsStartChunksEndAndWaitsForRefresh() = runBlocking {
        val sheet = FakeSheet()
        val dev = OdDevice(sheet)
        // capabilities preloaded (as after interrogate): 16×8 mono, no rotation
        val caps = Capabilities(16, 8, ColorScheme.MONO, 0)
        dev.javaClass.getDeclaredField("capabilities").also { it.isAccessible = true }.set(dev, caps)
        val phases = ArrayList<String>()
        dev.print(page(16, 8, "BW"), narrate = { phases.add(it) })
        assertEquals(Od.directWriteStartUncompressed().toHexLower(), sheet.written.first().toHexLower())
        assertEquals(1, sheet.chunksReceived)          // 16 bytes fit one chunk
        assertEquals(0, sheet.refreshMode)
        assertTrue(phases.contains("printed"))
    }

    @Test fun encryptedUploadAuthenticatesAndFramesEveryCommand() = runBlocking {
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val sheet = FakeSheet(masterKey = key)
        val dev = OdDevice(sheet, masterKey = key)
        val caps = Capabilities(250, 122, ColorScheme.BWR, 0)
        dev.javaClass.getDeclaredField("capabilities").also { it.isAccessible = true }.set(dev, caps)
        dev.print(page(250, 122, "BWR"))
        assertTrue(dev.isAuthenticated)
        // 2 planes × ceil(250/8)×122 = 7808 bytes → 51 chunks of 154
        assertEquals(51, sheet.chunksReceived)
        // every post-auth write is a CCM frame: cmd + 16-byte nonce + ct + 12-byte tag ≥ 31
        val postAuth = sheet.written.drop(2)
        assertTrue(postAuth.all { it.size >= 31 })
    }

    @Test fun wrongKeyFailsAuthentication(): Unit = runBlocking {
        val sheet = FakeSheet(masterKey = "000102030405060708090a0b0c0d0e0f".hexToBytes())
        val dev = OdDevice(sheet, masterKey = "ffffffffffffffffffffffffffffffff".hexToBytes())
        assertFailsWith<OdAuthError> { dev.authenticate() }
    }

    @Test fun sizeMismatchRefusesToPrint(): Unit = runBlocking {
        val dev = OdDevice(FakeSheet())
        dev.javaClass.getDeclaredField("capabilities").also { it.isAccessible = true }
            .set(dev, Capabilities(250, 122, ColorScheme.BWR, 90))
        // 90° mount: viewed is 122×250, so a 250×122 page must be refused
        assertFailsWith<OdError> { dev.print(page(250, 122, "BWR")) }
    }

    @Test fun interrogateReassemblesChunkedConfig() = runBlocking {
        val g = org.json.JSONObject(javaClass.getResource("/golden/config.json")!!.readText())
        val tlv = g.getString("tlv").hexToBytes()
        val sheet = object : OdLink {
            val reads = ArrayDeque<ByteArray>()
            override suspend fun write(data: ByteArray, withResponse: Boolean) {
                if (Od.code(data) != Od.READ_CONFIG) return
                // first chunk: echo + chunk#(2) + total(2 LE) + first 40 bytes; then chunks of 60
                val total = tlv.size
                val first = tlv.copyOfRange(0, 40)
                reads.add(byteArrayOf(0x00, 0x40, 0, 0, (total and 0xFF).toByte(), (total shr 8).toByte()) + first)
                var pos = 40; var n = 1
                while (pos < total) {
                    val end = minOf(pos + 60, total)
                    reads.add(byteArrayOf(0x00, 0x40, (n and 0xFF).toByte(), 0) + tlv.copyOfRange(pos, end))
                    pos = end; n++
                }
            }
            override suspend fun read(timeoutMs: Long): ByteArray =
                reads.removeFirstOrNull() ?: throw OdError("timeout")
        }
        val caps = OdDevice(sheet).interrogate()
        assertEquals(250, caps.width)
        assertEquals(ColorScheme.BWRY, caps.scheme)
        assertEquals(0x001D, caps.panelIc)
    }
}
