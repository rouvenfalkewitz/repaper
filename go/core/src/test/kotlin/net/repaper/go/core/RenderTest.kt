package net.repaper.go.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {
    private val W = 0xFFFFFFFF.toInt()
    private val B = 0xFF000000.toInt()
    private val R = 0xFFFF0000.toInt()

    @Test fun solidColorsMapExactly() {
        val model = SheetModel(8, 8, "BWR")
        val page = Render.forSheet(IntArray(64) { R }, 8, 8, model)
        // a solid red source fills the full canvas red
        assertTrue(page.indexes.count { it == 2 } > 40)
        assertTrue(page.indexes.all { it in 0..2 })
    }

    @Test fun whiteSourceStaysWhite() {
        val page = Render.forSheet(IntArray(64) { W }, 8, 8, SheetModel(8, 8, "BW"))
        assertTrue(page.indexes.all { it == 0 })
    }

    @Test fun autoRotatesLandscapeContentOntoPortraitSheet() {
        // 20×10 black bar (landscape) onto a 10×20 sheet: content rotates to fill it
        val px = IntArray(200) { B }
        val page = Render.forSheet(px, 20, 10, SheetModel(10, 20, "BW"))
        assertEquals(200, page.indexes.size)
        assertTrue(page.indexes.count { it == 1 } > 150)
    }

    @Test fun insetKeepsBezelWhite() {
        val model = SheetModel(16, 16, "BW", inset = intArrayOf(0, 0, 0, 6))
        val page = Render.forSheet(IntArray(256) { B }, 16, 16, model)
        // bottom 6 rows are hidden under the bezel: must stay white
        for (y in 10 until 16) for (x in 0 until 16) {
            assertEquals(0, page.indexes[y * 16 + x], "pixel $x,$y should be white bezel")
        }
        assertTrue((0 until 10).all { y -> (0 until 16).any { x -> page.indexes[y * 16 + x] == 1 } })
    }

    @Test fun ditherProducesOnlyPaletteIndexes() {
        // a gradient exercises error diffusion across the full range
        val px = IntArray(32 * 32) { i ->
            val v = (i % 32) * 8
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val page = Render.forSheet(px, 32, 32, SheetModel(32, 32, "BWRY"))
        assertTrue(page.indexes.all { it in 0..3 })
        assertTrue(page.indexes.distinct().size >= 2)   // gradient must dither, not clip to one color
    }

    @Test fun rotateToNativeRoundTrips() {
        val viewed = IntArray(6) { it }   // 3×2 viewed
        val native90 = OdEncoding.rotateToNative(viewed, 3, 2, 90)   // native 2×3
        // viewed (x,y) → clockwise 90°: native(x,y) = viewed(y, H-1-x) … verify a corner:
        // viewed row-major [0 1 2 / 3 4 5]; rotated CW: [3 0 / 4 1 / 5 2]
        assertEquals(listOf(3, 0, 4, 1, 5, 2), native90.toList())
        val native180 = OdEncoding.rotateToNative(viewed, 3, 2, 180)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), native180.toList())
    }
}
