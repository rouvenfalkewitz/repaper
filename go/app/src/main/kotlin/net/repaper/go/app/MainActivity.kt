package net.repaper.go.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import net.repaper.go.core.LandingUrl
import net.repaper.go.core.OdDevice
import net.repaper.go.core.Render
import net.repaper.go.core.SheetModel
import net.repaper.go.core.hexToBytes
import java.io.File

/** Sheets + waiting jobs, kept deliberately close to the Dock's local web UI:
 *  add a sheet by its QR link, print a waiting job by picking the sheet. */
class MainActivity : AppCompatActivity() {
    private lateinit var registry: Registry
    private lateinit var jobs: JobStore
    private lateinit var listView: LinearLayout
    private val cloud by lazy { CloudAgent(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = Registry(this)
        jobs = JobStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_ring)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
            })
            addView(Ui.displayText(this@MainActivity, "RePaper Go", 22f, weight = 700, width = 112))
        })
        root.addView(Ui.bodyText(this, "Print from any app — pick “RePaper Go” in the print dialog, then choose a sheet here.", 14f).apply {
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            addView(Ui.button(this@MainActivity, "Add a sheet", primary = true) { addSheetDialog() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            addView(Ui.button(this@MainActivity, "Sample page", primary = false) { printSample() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })

        requestBlePermissions()
        cloud.start()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        listView.removeAllViews()

        val waiting = jobs.list()
        if (waiting.isNotEmpty()) {
            listView.addView(Ui.sectionHeader(this, "Waiting to print"))
            for (job in waiting) {
                listView.addView(sheetCard(job.name.substringAfter('-').removeSuffix(".pdf"),
                    "tap to choose a sheet", accent = true) { pickSheetFor(job) })
            }
        }

        listView.addView(Ui.sectionHeader(this, "Sheets"))
        val ids = registry.ids()
        if (ids.isEmpty()) {
            listView.addView(Ui.bodyText(this, "No sheets yet. Scan the QR on a label with your camera and paste the link here.", 14f))
        }
        for (id in ids) {
            val m = registry.model(id)
            listView.addView(sheetCard(registry.name(id), "${m.width}×${m.height} ${m.palette}",
                onLong = { sheetActions(id); true }) { testPrint(id) })
        }

        val online = cloud.state == "online"
        listView.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) }
            addView(Ui.dot(this@MainActivity, if (online) Ui.ACCENT else Ui.AMBER))
            addView(Ui.monoText(this@MainActivity,
                if (cloud.claimed) "Cloud · ${cloud.org ?: "claimed"}"
                else "Cloud ${cloud.state} · ${cloud.claimCode}", 11f))
        })
        listView.addView(Ui.monoText(this, "RePaper Go $GO_VERSION", 11f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        })
    }

    private fun sheetCard(title: String, sub: String, accent: Boolean = false,
                          onLong: (() -> Boolean)? = null, onTap: () -> Unit): LinearLayout =
        Ui.card(this, ripple = true).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (accent) addView(Ui.dot(this@MainActivity, Ui.ACCENT))
                addView(Ui.displayText(this@MainActivity, title, 16f, Ui.TEXT, weight = 600))
            })
            addView(Ui.monoText(this@MainActivity, sub, 12f, Ui.TEXT_3).apply { setPadding(0, dp(3), 0, 0) })
            setOnClickListener { onTap() }
            if (onLong != null) setOnLongClickListener { onLong() }
        }

    // ── add a sheet (QR link → BLE describe → registry) ──────────────────────

    private fun addSheetDialog() {
        val input = EditText(this).apply {
            hint = "Paste the link from the QR on the label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Add a sheet").setView(input)
            .setPositiveButton("Add") { _, _ -> addSheet(input.text.toString()) }
            .setNeutralButton("Demo sheet (no hardware)") { _, _ ->
                // the Dock's mock transport, phone edition: prints render to an image instead of BLE
                val id = "demo-" + (registry.ids().count { it.startsWith("demo-") } + 1)
                registry.add(id, "Demo 2.9\u2033", "demo", null, SheetModel(296, 128, "BWR"))
                registry.entry(id).put("transport", "mock"); registry.rename(id, "Demo 2.9\u2033")   // rename persists it
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addSheet(link: String) {
        lifecycleScope.launch {
            val progress = toastLong("Reading the sheet — keep it awake and nearby…")
            try {
                val landing = LandingUrl.parse(link)
                val caps = withContext(Dispatchers.IO) {
                    val dev = GattLink.find(this@MainActivity, landing.name, null)
                        ?: throw Exception("couldn't find ${landing.name} nearby — wake the sheet and try again")
                    val gatt = GattLink.connect(this@MainActivity, dev)
                    try {
                        val od = OdDevice(gatt, landing.keyHex?.hexToBytes())
                        if (landing.keyHex != null) od.authenticate()
                        val c = od.interrogate()
                        registry.add(landing.name, landing.name, landing.name, landing.keyHex,
                            SheetModel(c.viewedWidth, c.viewedHeight, c.scheme.paletteKey))
                        registry.updateKey(landing.name, "ble_address", dev.address)
                        registry.updateKey(landing.name, "native", "${c.width}x${c.height}")
                        registry.updateKey(landing.name, "rotation", c.rotation.toString())
                        c
                    } finally { gatt.close() }
                }
                progress.cancel()
                toast("Added ${landing.name} · ${caps.viewedWidth}×${caps.viewedHeight}")
                refresh()
            } catch (e: Exception) {
                progress.cancel(); toast(e.message ?: "could not add the sheet")
            }
        }
    }

    private fun sheetActions(id: String) {
        AlertDialog.Builder(this).setTitle(registry.name(id))
            .setItems(arrayOf("Rename", "Remove")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(registry.name(id)) }
                        AlertDialog.Builder(this).setTitle("Rename").setView(input)
                            .setPositiveButton("Save") { _, _ -> registry.rename(id, input.text.toString().trim()); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> { registry.remove(id); refresh() }
                }
            }.show()
    }

    // ── printing ─────────────────────────────────────────────────────────────

    private fun pickSheetFor(job: File) {
        val ids = registry.ids()
        if (ids.isEmpty()) { toast("Add a sheet first."); return }
        AlertDialog.Builder(this).setTitle("Print on which sheet?")
            .setItems(ids.map { registry.name(it) }.toTypedArray()) { _, which -> printJob(job, ids[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun printJob(job: File, sheetId: String) {
        lifecycleScope.launch {
            val progress = toastLong("Printing on ${registry.name(sheetId)}…")
            try {
                withContext(Dispatchers.IO) {
                    val model = registry.model(sheetId)
                    val bitmap = renderPdfPage(job, model)
                    val px = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val page = Render.forSheet(px, bitmap.width, bitmap.height, model)
                    printPage(sheetId, page)
                }
                job.delete()
                progress.cancel(); toast("Printed."); refresh()
            } catch (e: Exception) {
                progress.cancel(); toast(e.message ?: "print failed")
            }
        }
    }

    private fun testPrint(id: String) {
        AlertDialog.Builder(this).setTitle(registry.name(id))
            .setMessage("Print a test page on this sheet?")
            .setPositiveButton("Print") { _, _ ->
                lifecycleScope.launch {
                    val progress = toastLong("Printing a test page…")
                    try {
                        withContext(Dispatchers.IO) {
                            val model = registry.model(id)
                            printPage(id, Render.forSheet(testPattern(model), model.width, model.height, model))
                        }
                        progress.cancel(); toast("Printed.")
                    } catch (e: Exception) { progress.cancel(); toast(e.message ?: "print failed") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private suspend fun printPage(sheetId: String, page: net.repaper.go.core.Page) {
        if (registry.entry(sheetId).optString("transport") == "mock") {
            withContext(Dispatchers.Main) { showMockResult(page) }
            return
        }
        val dev = GattLink.find(this, registry.address(sheetId), registry.bleAddress(sheetId))
            ?: throw Exception("couldn't find the sheet — wake it and try again")
        val gatt = GattLink.connect(this, dev)
        try {
            OdDevice(gatt, registry.keyHex(sheetId)?.hexToBytes()).print(page)
        } finally { gatt.close() }
    }

    /** What the e-paper would show: the dithered page, pixel-exact, scaled up for the screen. */
    private fun showMockResult(page: net.repaper.go.core.Page) {
        val m = page.model
        val bm = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val colors = m.colors.colors.map { (0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2] }
        val px = IntArray(m.width * m.height) { colors[page.indexes[it]] }
        bm.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        val scaled = Bitmap.createScaledBitmap(bm, m.width * 3, m.height * 3, false)
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(scaled); setBackgroundColor(Ui.SURFACE_2); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Printed on the demo sheet").setView(iv)
            .setPositiveButton("Nice", null).show()
    }

    private fun renderPdfPage(pdf: File, model: SheetModel): Bitmap {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val p = renderer.openPage(0)
                // render at ~2× the sheet's long side for clean downscaling
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

    /** Prints through Android's own print dialog, so the whole path is the real one:
     *  dialog → "RePaper Go" printer → spooled job → pick a sheet. */
    private fun printSample() {
        val pm = getSystemService(android.print.PrintManager::class.java)
        pm.print("RePaper sample", object : android.print.PrintDocumentAdapter() {
            override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes,
                                  sig: android.os.CancellationSignal?, cb: LayoutResultCallback,
                                  extras: Bundle?) {
                cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder("sample.pdf")
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>?, dest: ParcelFileDescriptor,
                                 sig: android.os.CancellationSignal?, cb: WriteResultCallback) {
                val doc = android.graphics.pdf.PdfDocument()
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(472, 232, 1).create())
                val c = page.canvas
                val paint = android.graphics.Paint().apply { color = Color.BLACK; textSize = 40f; isAntiAlias = true }
                c.drawText("RePaper Go", 24f, 90f, paint)
                paint.textSize = 20f
                c.drawText("printed from a phone — no dock, no cloud", 24f, 130f, paint)
                paint.color = Color.RED
                c.drawRect(24f, 160f, 448f, 200f, paint)
                doc.finishPage(page)
                doc.writeTo(java.io.FileOutputStream(dest.fileDescriptor))
                doc.close()
                cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            }
        }, null)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun requestBlePermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun toastLong(msg: String): Toast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also { it.show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
