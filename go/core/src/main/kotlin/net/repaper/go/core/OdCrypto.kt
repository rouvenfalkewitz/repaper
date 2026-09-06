package net.repaper.go.core

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/** AES-128-CCM/CMAC session crypto, byte-for-byte the firmware's scheme (see py-opendisplay crypto.py). */
object OdCrypto {
    private const val TAG_LEN = 12
    val DEFAULT_DEVICE_ID = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun cmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = CMac(AESEngine.newInstance())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    fun ecbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val aes = AESEngine.newInstance()
        aes.init(true, KeyParameter(key))
        return ByteArray(16).also { aes.processBlock(block, 0, it, 0) }
    }

    /** CMAC(master, "OpenDisplay session" || 00 || device_id || cN || sN || 00 80), then
     *  AES-ECB(master, counter1_be8 || intermediate[0:8]). */
    fun deriveSessionKey(master: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray, deviceId: ByteArray = DEFAULT_DEVICE_ID): ByteArray {
        val label = "OpenDisplay session".toByteArray(Charsets.US_ASCII)
        val intermediate = cmac(master, label + byteArrayOf(0) + deviceId + clientNonce + serverNonce + byteArrayOf(0x00, 0x80.toByte()))
        val counter = ByteArray(8).also { it[7] = 1 }
        return ecbEncryptBlock(master, counter + intermediate.copyOfRange(0, 8))
    }

    fun deriveSessionId(sessionKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        cmac(sessionKey, clientNonce + serverNonce).copyOfRange(0, 8)

    fun challengeResponse(master: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray, deviceId: ByteArray = DEFAULT_DEVICE_ID): ByteArray =
        cmac(master, serverNonce + clientNonce + deviceId)

    fun serverProof(sessionKey: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray, deviceId: ByteArray = DEFAULT_DEVICE_ID): ByteArray =
        cmac(sessionKey, serverNonce + clientNonce + deviceId)

    /** [cmd:2][session_id:8 || counter:8 BE][ciphertext][tag:12]; CCM nonce = full[3:16], AAD = cmd,
     *  plaintext = [len(payload):1][payload]. */
    fun encryptCommand(sessionKey: ByteArray, sessionId: ByteArray, counter: Long, cmd: ByteArray, payload: ByteArray): ByteArray {
        val nonceFull = sessionId + counterBe(counter)
        val plaintext = byteArrayOf(payload.size.toByte()) + payload
        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(true, AEADParameters(KeyParameter(sessionKey), TAG_LEN * 8, nonceFull.copyOfRange(3, 16), cmd))
        val out = ByteArray(ccm.getOutputSize(plaintext.size))
        val n = ccm.processBytes(plaintext, 0, plaintext.size, out, 0)
        ccm.doFinal(out, n)
        return cmd + nonceFull + out
    }

    /** Parses [cmd:2][nonce:16][ciphertext][tag:12] → (cmdCode, payload). Throws on bad tag. */
    fun decryptResponse(sessionKey: ByteArray, raw: ByteArray): Pair<Int, ByteArray> {
        if (raw.size < 2 + 16 + 1 + TAG_LEN) throw OdError("encrypted response too short: ${raw.size} bytes")
        val cmd = raw.copyOfRange(0, 2)
        val nonceFull = raw.copyOfRange(2, 18)
        val ct = raw.copyOfRange(18, raw.size)
        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(false, AEADParameters(KeyParameter(sessionKey), TAG_LEN * 8, nonceFull.copyOfRange(3, 16), cmd))
        val out = ByteArray(ccm.getOutputSize(ct.size))
        val n = ccm.processBytes(ct, 0, ct.size, out, 0)
        try { ccm.doFinal(out, n) } catch (e: Exception) { throw OdError("integrity check failed", e) }
        val len = out[0].toInt() and 0xFF
        return Pair(((cmd[0].toInt() and 0xFF) shl 8) or (cmd[1].toInt() and 0xFF), out.copyOfRange(1, 1 + len))
    }

    fun clientNonce(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun counterBe(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr ((7 - i) * 8)) and 0xFF).toByte() }
}
