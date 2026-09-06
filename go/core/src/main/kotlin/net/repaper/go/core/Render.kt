package net.repaper.go.core

/** Page image → Page for a specific sheet, the Dock's render/fit.py in Kotlin:
 *  trim white margins, auto-rotate to match orientation, scale to the visible area (contain),
 *  center on a white canvas honoring the bezel insets, Floyd–Steinberg dither to the palette.
 *  Pixels are ARGB ints (Android Bitmap.getPixels layout). */
object Render {

    fun forSheet(pixels: IntArray, width: Int, height: Int, model: SheetModel, dither: Boolean = true): Page {
        var img = Raster(pixels, width, height)
        trimBox(img)?.let { (x0, y0, x1, y1) -> img = img.crop(x0, y0, x1, y1) }

        val vis = model.visible
        val vw = vis[2] - vis[0]; val vh = vis[3] - vis[1]
        if ((img.w > img.h) != (vw > vh)) img = img.rotate90()

        val scale = minOf(vw.toDouble() / img.w, vh.toDouble() / img.h)
        val sw = maxOf(1, Math.round(img.w * scale).toInt())
        val sh = maxOf(1, Math.round(img.h * scale).toInt())
        img = img.resize(sw, sh)

        val canvas = Raster(IntArray(model.width * model.height) { WHITE }, model.width, model.height)
        canvas.paste(img, vis[0] + (vw - sw) / 2, vis[1] + (vh - sh) / 2)

        return Page(ditherToPalette(canvas, model.colors, dither), model)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Bounding box of non-white content (luma < 239, like the Dock's `invert > 16` trim). */
    private fun trimBox(img: Raster): IntArray? {
        var x0 = img.w; var y0 = img.h; var x1 = -1; var y1 = -1
        for (y in 0 until img.h) for (x in 0 until img.w) {
            if (luma(img.px[y * img.w + x]) < 239) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Serpentine Floyd–Steinberg on RGB, error-diffused per channel. */
    private fun ditherToPalette(img: Raster, palette: Palette, dither: Boolean): IntArray {
        val w = img.w; val h = img.h
        val r = DoubleArray(w * h); val g = DoubleArray(w * h); val b = DoubleArray(w * h)
        for (i in 0 until w * h) {
            val p = img.px[i]
            r[i] = ((p shr 16) and 0xFF).toDouble(); g[i] = ((p shr 8) and 0xFF).toDouble(); b[i] = (p and 0xFF).toDouble()
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            val xs = if (ltr) 0 until w else (w - 1) downTo 0
            for (x in xs) {
                val i = y * w + x
                val idx = nearest(palette, r[i], g[i], b[i])
                out[i] = idx
                if (!dither) continue
                val c = palette.colors[idx]
                val er = r[i] - c[0]; val eg = g[i] - c[1]; val eb = b[i] - c[2]
                val dx = if (ltr) 1 else -1
                diffuse(r, g, b, w, h, x + dx, y, er, eg, eb, 7.0 / 16)
                diffuse(r, g, b, w, h, x - dx, y + 1, er, eg, eb, 3.0 / 16)
                diffuse(r, g, b, w, h, x, y + 1, er, eg, eb, 5.0 / 16)
                diffuse(r, g, b, w, h, x + dx, y + 1, er, eg, eb, 1.0 / 16)
            }
        }
        return out
    }

    private fun diffuse(r: DoubleArray, g: DoubleArray, b: DoubleArray, w: Int, h: Int, x: Int, y: Int,
                        er: Double, eg: Double, eb: Double, f: Double) {
        if (x < 0 || x >= w || y >= h) return
        val i = y * w + x
        r[i] += er * f; g[i] += eg * f; b[i] += eb * f
    }

    private fun nearest(palette: Palette, r: Double, g: Double, b: Double): Int {
        var best = 0; var bestD = Double.MAX_VALUE
        for ((i, c) in palette.colors.withIndex()) {
            val d = (r - c[0]) * (r - c[0]) + (g - c[1]) * (g - c[1]) + (b - c[2]) * (b - c[2])
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Minimal ARGB raster ops; bilinear resize is plenty for label sizes. */
    class Raster(val px: IntArray, val w: Int, val h: Int) {
        fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Raster {
            val nw = x1 - x0; val nh = y1 - y0
            return Raster(IntArray(nw * nh) { i -> px[(y0 + i / nw) * w + x0 + i % nw] }, nw, nh)
        }
        fun rotate90(): Raster =        // counter-clockwise, like PIL rotate(90, expand)
            Raster(IntArray(w * h) { i ->
                val x = i % h; val y = i / h
                px[x * w + (w - 1 - y)]
            }, h, w)
        fun resize(nw: Int, nh: Int): Raster {
            val out = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val sx = (x + 0.5) * w / nw - 0.5; val sy = (y + 0.5) * h / nh - 0.5
                val x0 = sx.toInt().coerceIn(0, w - 1); val y0 = sy.toInt().coerceIn(0, h - 1)
                val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
                val fx = (sx - x0).coerceIn(0.0, 1.0); val fy = (sy - y0).coerceIn(0.0, 1.0)
                out[y * nw + x] = lerp2(px[y0 * w + x0], px[y0 * w + x1], px[y1 * w + x0], px[y1 * w + x1], fx, fy)
            }
            return Raster(out, nw, nh)
        }
        fun paste(src: Raster, atX: Int, atY: Int) {
            for (y in 0 until src.h) {
                val ty = atY + y
                if (ty < 0 || ty >= h) continue
                for (x in 0 until src.w) {
                    val tx = atX + x
                    if (tx in 0 until w) px[ty * w + tx] = src.px[y * src.w + x]
                }
            }
        }
        private fun lerp2(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Int {
            fun ch(shift: Int): Int {
                val a = ((c00 shr shift) and 0xFF) * (1 - fx) + ((c10 shr shift) and 0xFF) * fx
                val b = ((c01 shr shift) and 0xFF) * (1 - fx) + ((c11 shr shift) and 0xFF) * fx
                return Math.round(a * (1 - fy) + b * fy).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
