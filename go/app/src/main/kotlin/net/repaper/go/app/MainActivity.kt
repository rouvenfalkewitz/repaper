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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(TextView(this).apply { text = "RePaper Go"; textSize = 24f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply {
            text = "Print from any app — pick “RePaper Go” in the print dialog, then choose a sheet here."
            setTextColor(Color.parseColor("#9AA5A0")); setPadding(0, dp(4), 0, dp(12))
        })
        val addBtn = Button(this).apply { text = "Add a sheet"; setOnClickListener { addSheetDialog() } }
        root.addView(addBtn)
        root.addView(Button(this).apply {
            text = "Print a sample page"
            setOnClickListener { printSample() }   // the full illusion: system dialog → RePaper Go → sheet
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        setContentView(ScrollView(this).apply { addView(root) })

        requestBlePermissions()
        cloud.start()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        listView.removeAllViews()

        val waiting = jobs.list()
        if (waiting.isNotEmpty()) {
            listView.addView(header("Waiting to print"))
            for (job in waiting) {
                listView.addView(row(job.name.substringAfter('-').removeSuffix(".pdf"), "tap to choose a sheet") {
                    pickSheetFor(job)
                })
            }
        }

        listView.addView(header("Sheets"))
        val ids = registry.ids()
        if (ids.isEmpty()) {
            listView.addView(TextView(this).apply {
                text = "No sheets yet. Scan the QR on a label with your camera and paste the link here."
                setTextColor(Color.parseColor("#9AA5A0")); setPadding(0, dp(6), 0, 0)
            })
        }
        for (id in ids) {
            val m = registry.model(id)
            listView.addView(row(registry.name(id), "${m.width}×${m.height} ${m.palette}",
                onLong = { sheetActions(id); true }) { testPrint(id) })
        }
        listView.addView(TextView(this).apply {
            text = "Cloud: ${cloud.state}${if (cloud.claimed) " · ${cloud.org ?: ""}" else " · claim code ${cloud.claimCode}"}\nRePaper Go $GO_VERSION"
            setTextColor(Color.parseColor("#5E6A64")); textSize = 12f; setPadding(0, dp(24), 0, 0)
        })
    }

    // ── add a sheet (QR link → BLE describe → registry) ──────────────────────

    private fun addSheetDialog() {
        val input = EditText(this).apply {
            hint = "Paste the link from the QR on the label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Add a sheet").setView(input)
            .setPositiveButton("Add") { _, _ -> addSheet(input.text.toString()) }
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
        val dev = GattLink.find(this, registry.address(sheetId), registry.bleAddress(sheetId))
            ?: throw Exception("couldn't find the sheet — wake it and try again")
        val gatt = GattLink.connect(this, dev)
        try {
            OdDevice(gatt, registry.keyHex(sheetId)?.hexToBytes()).print(page)
        } finally { gatt.close() }
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

    private fun header(text: String) = TextView(this).apply {
        this.text = text.uppercase(); textSize = 12f; setTextColor(Color.parseColor("#5E6A64"))
        setPadding(0, dp(20), 0, dp(6)); letterSpacing = 0.1f
    }

    private fun row(title: String, sub: String, onLong: (() -> Boolean)? = null, onTap: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141A18"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8); layoutParams = lp
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
            addView(TextView(context).apply { text = sub; setTextColor(Color.parseColor("#9AA5A0")); textSize = 13f })
            setOnClickListener { onTap() }
            if (onLong != null) setOnLongClickListener { onLong() }
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun toastLong(msg: String): Toast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also { it.show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
