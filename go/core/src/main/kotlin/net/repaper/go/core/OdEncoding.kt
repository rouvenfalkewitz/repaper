package net.repaper.go.core

/** Panel byte encodings, byte-identical to py-opendisplay's encoders (verified by golden tests).
 *  Indexes for BW/BWR/BWRY arrive in RePaper palette order (0=white, 1=black, accents after);
 *  the extended schemes (BWGBRY, grays, 7-color) use the SDK's own order (black first) so the
 *  wire encodings below are direct LUTs. Rows are zero-padded to byte boundaries, MSB first.
 *  Some panels change the wire codes (bb_epaper's per-panel tables) — hence [panelIc]. */
object OdEncoding {

    fun encode(indexes: IntArray, width: Int, height: Int, scheme: ColorScheme, panelIc: Int = 0): ByteArray = when (scheme) {
        ColorScheme.MONO -> packBits(indexes, width, height) { it == 0 }                       // bit 1 = white
        ColorScheme.BWR ->                                                                     // red sets BOTH planes
            packBits(indexes, width, height) { it == 0 || it == 2 } + packBits(indexes, width, height) { it == 2 }
        ColorScheme.BWY ->                                                                     // yellow only the accent plane
            packBits(indexes, width, height) { it == 0 } + packBits(indexes, width, height) { it >= 2 }
        ColorScheme.BWRY -> packNibbles(indexes, width, height, 2, bwryMap(panelIc))
        ColorScheme.BWGBRY -> packNibbles(indexes, width, height, 4, BWGBRY_LUT)
        ColorScheme.BWGBRY_SPLIT -> {                                                          // left half-plane, then right
            val mid = width / 2
            packHalf(indexes, width, height, 0, mid, BWGBRY_LUT) + packHalf(indexes, width, height, mid, width, BWGBRY_LUT)
        }
        ColorScheme.SEVEN_COLOR -> packNibbles(indexes, width, height, 4, IntArray(16) { it })
        ColorScheme.GRAY16 -> packNibbles(indexes, width, height, 4, IntArray(16) { it })
        ColorScheme.GRAY4 -> {                                                                 // two 1-bit planes of the 2-bit code
            val codes = gray4Codes(panelIc)
            packBits(indexes, width, height) { codes[it and 3] and 1 != 0 } +
            packBits(indexes, width, height) { codes[it and 3] and 2 != 0 }
        }
    }

    /** BWGBRY firmware nibble map: palette 0..5 → 0,1,2,3,5,6 (4 is skipped). */
    private val BWGBRY_LUT = intArrayOf(0, 1, 2, 3, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** RePaper BWRY order (white, black, red, yellow) → wire codes. Default panels store
     *  b0 w1 y2 r3; panels 0x001D/0x001E swap yellow/red on the wire (bb_ep u8Colors_4clr). */
    private fun bwryMap(panelIc: Int): IntArray =
        if (panelIc == 0x001D || panelIc == 0x001E) intArrayOf(1, 0, 2, 3)   // w→1 b→0 r→2 y→3
        else intArrayOf(1, 0, 3, 2)                                          // w→1 b→0 r→3 y→2

    /** Level→stored-code for 4-gray panels; EP426/EP368 use the v2 table. */
    private fun gray4Codes(panelIc: Int): IntArray =
        if (panelIc == 0x0028 || panelIc == 0x0048) intArrayOf(3, 2, 1, 0) else intArrayOf(3, 1, 2, 0)

    private inline fun packBits(indexes: IntArray, width: Int, height: Int, bit: (Int) -> Boolean): ByteArray {
        val bpl = (width + 7) / 8
        val out = ByteArray(bpl * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (bit(indexes[row + x])) {
                    out[y * bpl + x / 8] = (out[y * bpl + x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return out
    }

    /** [bits] per pixel (2 or 4), MSB first, rows zero-padded to whole bytes, values via [lut]. */
    private fun packNibbles(indexes: IntArray, width: Int, height: Int, bits: Int, lut: IntArray): ByteArray {
        val ppb = 8 / bits
        val bpl = (width + ppb - 1) / ppb
        val out = ByteArray(bpl * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val code = lut[indexes[row + x] and (lut.size - 1)]
                val shift = (ppb - 1 - x % ppb) * bits
                out[y * bpl + x / ppb] = (out[y * bpl + x / ppb].toInt() or (code shl shift)).toByte()
            }
        }
        return out
    }

    private fun packHalf(indexes: IntArray, width: Int, height: Int, x0: Int, x1: Int, lut: IntArray): ByteArray {
        val w = x1 - x0
        val half = IntArray(w * height)
        for (y in 0 until height) for (x in 0 until w) half[y * w + x] = indexes[y * width + x0 + x]
        return packNibbles(half, w, height, 4, lut)
    }

    /** Rotate viewed-orientation indexes clockwise by the panel's mounting rotation to native orientation. */
    fun rotateToNative(indexes: IntArray, viewedW: Int, viewedH: Int, rotation: Int): IntArray = when ((rotation % 360 + 360) % 360) {
        0 -> indexes
        90 -> IntArray(indexes.size) { i ->                     // native (W=viewedH, H=viewedW)
            val x = i % viewedH; val y = i / viewedH            // x,y in native
            indexes[(viewedH - 1 - x) * viewedW + y]
        }
        180 -> IntArray(indexes.size) { indexes[indexes.size - 1 - it] }
        270 -> IntArray(indexes.size) { i ->
            val x = i % viewedH; val y = i / viewedH
            indexes[x * viewedW + (viewedW - 1 - y)]
        }
        else -> throw OdError("unsupported rotation $rotation")
    }
}
