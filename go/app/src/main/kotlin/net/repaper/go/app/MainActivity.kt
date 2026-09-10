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
    private lateinit var pillHolder: LinearLayout
    private val printFlow by lazy { PrintFlow(this, registry) }
    private var busy = false                   // a BLE print is running
    private var flash: RingView.Led? = null    // DONE/ERR held briefly, then back to the state machine
    private val autoTried = HashSet<String>()  // one-sheet auto-print: one attempt per job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.isClaimed(this)) {      // the app belongs to an account — sign in first
            startActivity(Intent(this, AuthActivity::class.java)); finish(); return
        }
        if (!Prefs.isApproved(this)) {     // a member's phone waits for an admin
            startActivity(Intent(this, PendingActivity::class.java)); finish(); return
        }
        registry = Registry(this)
        jobs = JobStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        // header: the real product lockup left, gear right
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.lockup_go)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_gear)
                setColorFilter(Ui.TEXT_2)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
        })

        // the ring in its box — the device outcut, exactly like the Dock's page
        ring = RingView(this)
        val box = Ui.ringBox(this, 148)
        box.addView(ring, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(26) }
            addView(box)
        })
        pillHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) }
        }
        root.addView(pillHolder)
        statusTitle = Ui.displayText(this, "", 22f, Ui.TEXT, weight = 700).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        }
        statusSub = Ui.bodyText(this, "", 14f).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), 0) }
        root.addView(statusTitle)
        root.addView(statusSub)
        jobList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(jobList)

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        requestBlePermissions()
        CloudAgent.get(this).start()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isClaimed(this)) {      // removed from the fleet while we were running
            startActivity(Intent(this, AuthActivity::class.java)); finish(); return
        }
        registry = Registry(this)          // Settings may have added/removed sheets meanwhile
        refresh()
        // tap-to-print: the labels' built-in NFC tag carries the same landing link as the QR
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, { tag ->
            val ndef = android.nfc.tech.Ndef.get(tag) ?: return@enableReaderMode
            val uri = try {
                ndef.connect()
                (ndef.ndefMessage ?: ndef.cachedNdefMessage)?.records
                    ?.firstNotNullOfOrNull { r -> r.toUri()?.toString() }
            } catch (e: Exception) { null } finally { runCatching { ndef.close() } }
            if (uri != null) runOnUiThread { onSheetTap(uri) }
        }, android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
           android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V, null)
    }

    override fun onPause() {
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    /** A sheet touched the phone: that IS the sheet choice — the Dock's tap, phone edition.
     *  Adding sheets lives in Settings (decision of 10 Sep): an unknown tag just points there. */
    private fun onSheetTap(uri: String) {
        val landing = runCatching { net.repaper.go.core.LandingUrl.parse(uri) }.getOrNull()
            ?: run { toast("That tag doesn't look like a RePaper sheet."); return }
        DiagLog.log("nfc tap: ${landing.name}")
        val known = SheetOps.findRegistered(registry, landing)
        val job = jobs.list().firstOrNull()
        when {
            known != null && job != null -> printJob(job, known)
            known != null -> toast("That's ${registry.name(known)} — nothing waiting to print.")
            else -> toast("${landing.name} isn't registered on this phone yet — add it in Settings.")
        }
    }

    /** The LED language, app edition: Printing > flash (Printed/Failed) > Job waiting > Setup > Ready. */
    private fun refresh() {
        val waiting = jobs.list()
        val f = flash
        // automatic sheet choice: one sheet decides itself; with "cycle through sheets" on,
        // several take turns. One attempt each — a failure shows red and waits for a tap.
        val ids = registry.ids()
        val auto = ids.size == 1 || (Prefs.cycleSheets(this) && ids.size > 1)
        if (!busy && f == null && waiting.isNotEmpty() && auto && ids.isNotEmpty()) {
            val job = waiting.first()
            if (autoTried.add(job.name)) {
                val pick = if (ids.size == 1) ids[0] else ids[Prefs.cycleIx(this) % ids.size]
                printJob(job, pick, advanceCycle = ids.size > 1); return
            }
        }
        when {
            busy -> setState(RingView.Led.BUSY, "Printing…", "Keep the sheet nearby.")
            f == RingView.Led.DONE -> setState(f, "Printed", "Take a look at the sheet.")
            f == RingView.Led.ERR -> setState(f, "Not printed", "Hold on — then just try again.")
            waiting.isNotEmpty() -> setState(RingView.Led.WAIT, "Job waiting",
                if (registry.ids().isEmpty()) "Add a sheet in Settings first."
                else if (android.nfc.NfcAdapter.getDefaultAdapter(this) != null) "Tap the sheet with this phone — or choose it below."
                else "Choose the sheet to print it on.")
            registry.ids().isEmpty() -> setState(RingView.Led.SETUP, "Set me up",
                "Add your first sheet in Settings — the gear, top right.")
            else -> setState(RingView.Led.READY, "Ready to print",
                "Print from any app and pick “RePaper Go” in the print dialog.")
        }

        jobList.removeAllViews()
        if (waiting.isNotEmpty()) {
            jobList.addView(Ui.sectionHeader(this, "Waiting to print"))
            for (job in waiting) {
                val card = Ui.card(this, ripple = true)
                card.orientation = LinearLayout.HORIZONTAL
                val thumbHolder = android.widget.ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Ui.EPAPER_PANEL)
                }
                card.addView(Ui.frame(this, thumbHolder).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { rightMargin = dp(12) }
                })
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.displayText(this@MainActivity, job.name.substringAfter('-').removeSuffix(".pdf"), 16f, Ui.TEXT, weight = 600))
                    addView(Ui.monoText(this@MainActivity, "tap to choose a sheet · hold to discard", 11f).apply { setPadding(0, dp(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                card.setOnClickListener { pickSheetFor(job) }
                card.setOnLongClickListener { job.delete(); refresh(); toast("Discarded."); true }
                jobList.addView(card)
                lifecycleScope.launch(Dispatchers.IO) {
                    val bm = PrintFlow.pdfThumb(job, dp(112))
                    if (bm != null) runOnUiThread { thumbHolder.setImageBitmap(bm) }
                }
            }
        } else if (!busy && f == null && registry.ids().isNotEmpty()) {
            jobList.addView(howToCard())
        }
    }

    // ── how printing works, told in pictures: share → the app → e-paper ──────

    private fun howToCard(): LinearLayout = Ui.card(this).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(howTile("Share", ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_share); setColorFilter(Ui.TEXT)
            }))
            addView(howArrow())
            addView(howTile("RePaper Go", ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.brand_mark)   // the OFFICIAL app-icon mark
            }))
            addView(howArrow())
            addView(howTile("On paper", miniSheet()))
        })
        addView(Ui.bodyText(this@MainActivity, "Share a photo or document from any app — it lands on your sheet.", 12f).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), dp(12), dp(6), dp(2))
        })
    }

    private fun howArrow() = Ui.bodyText(this, "→", 15f, Ui.TEXT_3).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun howTile(caption: String, content: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(android.widget.FrameLayout(this@MainActivity).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.SURFACE_2, Ui.BG)).apply {
                cornerRadius = dp(16).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG)
            }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            addView(content, android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        })
        addView(Ui.monoText(this@MainActivity, caption, 10f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun miniSheet(): android.view.View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.EPAPER_BEZEL); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG)
        }
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.EPAPER_PANEL); cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(20))
            addView(android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.INK); cornerRadius = dp(1).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(3))
            })
            addView(android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.EPAPER_RED); cornerRadius = dp(1).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(3)).apply { topMargin = dp(3) }
            })
        })
    }

    private fun setState(led: RingView.Led, title: String, sub: String) {
        ring.led = led; statusTitle.text = title; statusSub.text = sub
        pillHolder.removeAllViews()
        pillHolder.addView(when (led) {
            RingView.Led.READY -> Ui.pill(this, "Ready", Ui.ACCENT, Ui.ACCENT_TINT)
            RingView.Led.WAIT -> Ui.pill(this, "Waiting for sheet", Ui.ACCENT, Ui.ACCENT_TINT, blinkMs = 500)
            RingView.Led.BUSY -> Ui.pill(this, "Printing", Ui.ACCENT, Ui.ACCENT_TINT, blinkMs = 160)
            RingView.Led.DONE -> Ui.pill(this, "Printed", Ui.ACCENT, Ui.ACCENT_TINT, check = true)
            RingView.Led.ERR -> Ui.pill(this, "Failed", Ui.RED, Ui.RED_TINT, blinkMs = 500)
            RingView.Led.SETUP -> Ui.pill(this, "Setup", Ui.BLUE, Ui.BLUE_TINT, blinkMs = 500)
        })
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

    private fun printJob(job: File, sheetId: String, advanceCycle: Boolean = false) {
        lifecycleScope.launch {
            busy = true; refresh()
            try {
                withContext(Dispatchers.IO) {
                    printFlow.printPdf(job, sheetId) { phase -> runOnUiThread { statusSub.text = phase } }
                }
                job.delete()
                if (advanceCycle) Prefs.bumpCycleIx(this@MainActivity)
                busy = false; flashState(RingView.Led.DONE, 3000)
            } catch (e: Exception) {
                busy = false; flashState(RingView.Led.ERR, 6000); toast(e.message ?: "print failed")
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun requestBlePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)   // job notifications
        }.toTypedArray()
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
