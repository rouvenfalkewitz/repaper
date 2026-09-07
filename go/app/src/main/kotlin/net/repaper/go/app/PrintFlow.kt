package net.repaper.go.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AlertDialog
import net.repaper.go.core.OdDevice
import net.repaper.go.core.Page
import net.repaper.go.core.Render
import net.repaper.go.core.SheetModel
import net.repaper.go.core.hexToBytes
import java.io.File

/** The actual printing path, shared by both screens: render → (BLE | mock) → sheet. */
class PrintFlow(private val activity: Activity, private val registry: Registry) {

    companion object {
        /** Small first-page preview for job cards. */
        fun pdfThumb(pdf: File, maxWidthPx: Int): Bitmap? = try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val p = renderer.openPage(0)
                    val scale = maxWidthPx.toDouble() / p.width
                    val bm = Bitmap.createBitmap(maxWidthPx, (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bm.eraseColor(Color.WHITE)
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    bm
                }
            }
        } catch (e: Exception) { null }
    }

    /** Render the first PDF page for a sheet and print it. */
    suspend fun printPdf(pdf: File, sheetId: String, narrate: (String) -> Unit = {}) {
        val model = registry.model(sheetId)
        val bitmap = renderPdfPage(pdf, model)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        printPage(sheetId, Render.forSheet(px, bitmap.width, bitmap.height, model), narrate)
    }

    suspend fun printTest(sheetId: String, narrate: (String) -> Unit = {}) {
        val model = registry.model(sheetId)
        printPage(sheetId, Render.forSheet(testPattern(model), model.width, model.height, model), narrate)
    }

    suspend fun printPage(sheetId: String, page: Page, narrate: (String) -> Unit = {}) {
        if (registry.entry(sheetId).optString("transport") == "mock") {
            activity.runOnUiThread { showMockResult(page) }
            return
        }
        narrate("looking for the sheet")
        val dev = GattLink.find(activity, registry.address(sheetId), registry.bleAddress(sheetId))
            ?: throw Exception("couldn't find the sheet — wake it and try again")
        narrate("connecting")
        val gatt = GattLink.connect(activity, dev)
        try {
            OdDevice(gatt, registry.keyHex(sheetId)?.hexToBytes()).print(page, narrate = narrate)
        } finally { gatt.close() }
    }

    private fun renderPdfPage(pdf: File, model: SheetModel): Bitmap {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val p = renderer.openPage(0)
                val scale = 2.0 * maxOf(model.width, model.height) / maxOf(p.width, p.height)
                val bm = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1),
                    (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bm.eraseColor(Color.WHITE)
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                p.close()
                return bm
            }
        }
    }

    private fun testPattern(model: SheetModel): IntArray {
        val n = model.colors.colors.size
        return IntArray(model.width * model.height) { i ->
            val band = (i % model.width) * n / model.width
            val c = model.colors.colors[band]
            (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
        }
    }

    /** What the e-paper would show: the dithered page, pixel-exact, scaled for the screen. */
    private fun showMockResult(page: Page) {
        val m = page.model
        val bm = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val colors = m.colors.colors.map { (0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2] }
        val px = IntArray(m.width * m.height) { colors[page.indexes[it]] }
        bm.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        val scaled = Bitmap.createScaledBitmap(bm, m.width * 3, m.height * 3, false)
        val iv = android.widget.ImageView(activity).apply {
            setImageBitmap(scaled); setBackgroundColor(Ui.SURFACE_2)
            val d = (12 * activity.resources.displayMetrics.density).toInt(); setPadding(d, d, d, d)
        }
        AlertDialog.Builder(activity).setTitle("Printed on the demo sheet").setView(iv)
            .setPositiveButton("Nice", null).show()
    }
}
