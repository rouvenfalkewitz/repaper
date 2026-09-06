package net.repaper.go.core

/** Panel byte encodings, byte-identical to py-opendisplay's encoders (verified by golden tests).
 *  Input indexes are in RePaper palette order (0=white, 1=black, 2=red, 3=yellow); the firmware's
 *  own index order differs per scheme and is applied here. Rows are zero-padded to byte boundaries,
 *  MSB first. */
object OdEncoding {

    fun encode(indexes: IntArray, width: Int, height: Int, scheme: ColorScheme): ByteArray = when (scheme) {
        ColorScheme.MONO -> packBits(indexes, width, height) { it == 0 }                       // bit 1 = white
        ColorScheme.BWR ->                                                                     // red sets BOTH planes
            packBits(indexes, width, height) { it == 0 || it == 2 } + packBits(indexes, width, height) { it == 2 }
        ColorScheme.BWY ->                                                                     // yellow only the accent plane
            packBits(indexes, width, height) { it == 0 } + packBits(indexes, width, height) { it >= 2 }
        ColorScheme.BWRY -> pack2bpp(indexes, width, height)
    }

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

    /** BWRY: 2 bits per pixel in firmware index order (0=black, 1=white, 2=yellow, 3=red — note the
     *  yellow/red swap vs the dither palette; matches display_palettes' BWRY code table). */
    private fun pack2bpp(indexes: IntArray, width: Int, height: Int): ByteArray {
        val map = intArrayOf(1, 0, 3, 2)   // RePaper white,black,red,yellow → firmware codes
        val ppb = 4
        val bpl = (width + ppb - 1) / ppb
        val out = ByteArray(bpl * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val code = map[indexes[row + x] and 3]
                val shift = 6 - 2 * (x % ppb)
                out[y * bpl + x / ppb] = (out[y * bpl + x / ppb].toInt() or (code shl shift)).toByte()
            }
        }
        return out
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
