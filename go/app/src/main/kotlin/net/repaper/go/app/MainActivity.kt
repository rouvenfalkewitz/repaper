package net.repaper.go.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import java.io.File

/** The main screen is the printer, like the Dock's page: the light ring front and centre,
 *  speaking the LED language, with the job that's waiting below. Sheets live in Settings. */
class MainActivity : AppCompatActivity() {
    private lateinit var registry: Registry
    private lateinit var jobs: JobStore
    private lateinit var ring: RingView
    private lateinit var statusTitle: android.widget.TextView
    private lateinit var statusSub: android.widget.TextView
    private lateinit var jobList: LinearLayout
    private val printFlow by lazy { PrintFlow(this, registry) }
    private var busy = false                   // a BLE print is running
    private var flash: RingView.Led? = null    // DONE/ERR held briefly, then back to the state machine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.isClaimed(this)) {      // the app belongs to an account — sign in first
            startActivity(Intent(this, AuthActivity::class.java)); finish(); return
        }
        registry = Registry(this)
        jobs = JobStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        // header: wordmark left, gear right
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_ring)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(9) }
            })
            addView(Ui.displayText(this@MainActivity, "RePaper Go", 20f, weight = 700, width = 112),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_gear)
                setColorFilter(Ui.TEXT_2)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
        })

        // the ring — the device itself, front and centre
        ring = RingView(this)
        root.addView(ring, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply { topMargin = dp(8) })
        statusTitle = Ui.displayText(this, "", 19f, Ui.TEXT, weight = 700).apply { gravity = Gravity.CENTER }
        statusSub = Ui.bodyText(this, "", 14f).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), 0) }
        root.addView(statusTitle)
        root.addView(statusSub)

        jobList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(jobList)

        root.addView(Ui.button(this, "Print a sample page", primary = false) { printSample() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(24) }
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        requestBlePermissions()
        CloudAgent.get(this).start()
    }

    override fun onResume() { super.onResume(); refresh() }

    /** The LED language, app edition: Printing > flash (Printed/Failed) > Job waiting > Setup > Ready. */
    private fun refresh() {
        val waiting = jobs.list()
        val f = flash
        when {
            busy -> setState(RingView.Led.BUSY, "Printing…", "Keep the sheet nearby.")
            f == RingView.Led.DONE -> setState(f, "Printed", "Take a look at the sheet.")
            f == RingView.Led.ERR -> setState(f, "Not printed", "Hold on — then just try again.")
            waiting.isNotEmpty() -> setState(RingView.Led.WAIT, "Job waiting",
                if (registry.ids().isEmpty()) "Add a sheet in Settings first." else "Choose the sheet to print it on.")
            registry.ids().isEmpty() -> setState(RingView.Led.SETUP, "Set me up",
                "Add your first sheet in Settings — the gear, top right.")
            else -> setState(RingView.Led.READY, "Ready to print",
                "Print from any app and pick “RePaper Go” in the print dialog.")
        }

        jobList.removeAllViews()
        if (waiting.isNotEmpty()) {
            jobList.addView(Ui.sectionHeader(this, "Waiting to print"))
            for (job in waiting) {
                jobList.addView(Ui.card(this, ripple = true).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(Ui.dot(this@MainActivity, Ui.ACCENT))
                        addView(Ui.displayText(this@MainActivity, job.name.substringAfter('-').removeSuffix(".pdf"), 16f, Ui.TEXT, weight = 600))
                    })
                    addView(Ui.monoText(this@MainActivity, "tap to choose a sheet · hold to discard", 12f).apply { setPadding(0, dp(3), 0, 0) })
                    setOnClickListener { pickSheetFor(job) }
                    setOnLongClickListener { job.delete(); refresh(); toast("Discarded."); true }
                })
            }
        }
    }

    private fun setState(led: RingView.Led, title: String, sub: String) {
        ring.led = led; statusTitle.text = title; statusSub.text = sub
    }

    private fun flashState(led: RingView.Led, ms: Long) {
        flash = led; refresh()
        lifecycleScope.launch { delay(ms); flash = null; refresh() }
    }

    // ── printing ─────────────────────────────────────────────────────────────

    private fun pickSheetFor(job: File) {
        val ids = registry.ids()
        if (ids.isEmpty()) { toast("Add a sheet in Settings first."); return }
        AlertDialog.Builder(this).setTitle("Print on which sheet?")
            .setItems(ids.map { registry.name(it) }.toTypedArray()) { _, which -> printJob(job, ids[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun printJob(job: File, sheetId: String) {
        lifecycleScope.launch {
            busy = true; refresh()
            try {
                withContext(Dispatchers.IO) {
                    printFlow.printPdf(job, sheetId) { phase -> runOnUiThread { statusSub.text = phase } }
                }
                job.delete()
                busy = false; flashState(RingView.Led.DONE, 3000)
            } catch (e: Exception) {
                busy = false; flashState(RingView.Led.ERR, 6000); toast(e.message ?: "print failed")
            }
        }
    }

    /** Prints through Android's own print dialog, so the whole path is the real one. */
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
