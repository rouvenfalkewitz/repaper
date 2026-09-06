package net.repaper.go.core

/** Mirrors the Dock's sheets/base.py: palettes in RePaper index order (0=white, 1=black, then accents).
 *  Transports translate to whatever their wire format expects — the renderer never emits anything else. */
data class Palette(val name: String, val colors: List<IntArray>) {
    companion object {
        private val W = intArrayOf(255, 255, 255)
        private val B = intArrayOf(0, 0, 0)
        private val R = intArrayOf(255, 0, 0)
        private val Y = intArrayOf(255, 255, 0)
        val ALL = mapOf(
            "BW" to Palette("BW", listOf(W, B)),
            "BWR" to Palette("BWR", listOf(W, B, R)),
            "BWRY" to Palette("BWRY", listOf(W, B, R, Y)),
        )
    }
}

data class SheetModel(
    val width: Int,
    val height: Int,
    val palette: String = "BW",
    val inset: IntArray = intArrayOf(0, 0, 0, 0),   // left, top, right, bottom: pixels hidden under the bezel
) {
    val colors: Palette get() = Palette.ALL.getValue(palette)

    /** (x0, y0, x1, y1) content may use, in viewed pixels. */
    val visible: IntArray get() = intArrayOf(inset[0], inset[1], width - inset[2], height - inset[3])
}

/** An already-rendered page: palette indexes in RePaper order, exactly width×height. */
class Page(val indexes: IntArray, val model: SheetModel) {
    init { require(indexes.size == model.width * model.height) { "Page must be at the sheet's exact size" } }
}

/** OpenDisplay wire color schemes (firmware byte values). */
enum class ColorScheme(val wire: Int, val paletteKey: String) {
    MONO(0, "BW"), BWR(1, "BWR"), BWY(2, "BWR"), BWRY(3, "BWRY");

    companion object {
        fun fromWire(v: Int): ColorScheme = entries.firstOrNull { it.wire == v }
            ?: throw OdError("unsupported color scheme $v")
    }
}

/** What upload needs to know about a device (from its config). */
data class Capabilities(
    val width: Int,          // native pixels
    val height: Int,
    val scheme: ColorScheme,
    val rotation: Int,       // mounting rotation in degrees; viewed size swaps at 90/270
    val sessionTimeoutSeconds: Int = 0,
) {
    val viewedWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val viewedHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
}

class OdError(message: String, cause: Throwable? = null) : Exception(message, cause)
class OdAuthError(message: String) : Exception(message)
