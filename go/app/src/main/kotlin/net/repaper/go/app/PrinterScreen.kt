package net.repaper.go.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import net.repaper.go.app.Ui.dp
import java.io.File

/** The printer tab: the light ring centred, speaking the LED language, with any waiting
 *  job below and the how-to behind a help button — like the iOS main screen. */
class PrinterScreen(private val act: AppCompatActivity) : Screen {
    private val c = act
    private lateinit var registry: Registry
    private lateinit var printFlow: PrintFlow
    private val jobs = JobStore(c)
    private var busy = false
    private var flash: RingView.Led? = null
    private val autoTried = HashSet<String>()

    private val ring = RingView(c)
    private val pillHolder = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val title = Ui.displayText(c, "", 24f, Ui.TEXT, weight = 800, width = 94).apply { gravity = Gravity.CENTER; letterSpacing = 0.02f }
    private val sub = Ui.bodyText(c, "", 14f).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), 0) }
    private val jobList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val helpBtn = TextView(c)
    private val heroCol = LinearLayout(c)

    override val view: View by lazy { build() }

    private fun build(): View {
        registry = Registry(c); printFlow = PrintFlow(c, registry)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        // top bar: lockup left, help "?" right
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(c).apply {
                setImageResource(R.drawable.lockup_go); adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
            })
            helpBtn.apply {
                text = "?"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT_2)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE); setStroke(dp(1), Ui.BORDER) }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                isClickable = true; setOnClickListener { showHelpPanel() }
            }
            addView(helpBtn)
        })
        // hero, vertically centred in the remaining space
        heroCol.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val box = Ui.ringBox(c, 148)
        box.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        heroCol.addView(box)
        heroCol.addView(pillHolder.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) } })
        heroCol.addView(title.apply { (layoutParams as? LinearLayout.LayoutParams) })
        heroCol.addView(sub)
        heroCol.addView(jobList.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        root.addView(heroCol)
        return ScrollView(c).apply { addView(root); isFillViewport = true; setBackgroundColor(Ui.BG) }
    }

    override fun onShow() {
        registry = Registry(c); printFlow = PrintFlow(c, registry)
        refresh()
        enableTap()
    }
    override fun onHide() { android.nfc.NfcAdapter.getDefaultAdapter(c)?.disableReaderMode(c) }

    private fun enableTap() {
        android.nfc.NfcAdapter.getDefaultAdapter(c)?.enableReaderMode(c, { tag ->
            val ndef = android.nfc.tech.Ndef.get(tag) ?: return@enableReaderMode
            val uri = try {
                ndef.connect()
                (ndef.ndefMessage ?: ndef.cachedNdefMessage)?.records?.firstNotNullOfOrNull { r ->
                    r.toUri()?.toString() ?: r.payload?.takeIf { it.isNotEmpty() }?.let { p ->
                        val cc = p[0].toInt() and 0xFF
                        String(if (cc < 0x20 || cc > 0x7E) p.copyOfRange(1, p.size) else p, Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) { null } finally { runCatching { ndef.close() } }
            if (uri != null) c.runOnUiThread { onSheetTap(uri) }
        }, android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
           android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V, null)
    }

    private fun onSheetTap(uri: String) {
        val landing = runCatching { net.repaper.go.core.LandingUrl.parse(uri) }.getOrNull() ?: run { toast("That tag doesn't look like a RePaper sheet."); return }
        val known = SheetOps.findRegistered(registry, landing)
        val job = jobs.list().firstOrNull()
        when {
            known != null && job != null -> printJob(job, known)
            known != null -> toast("That's ${registry.name(known)} — nothing waiting to print.")
            else -> toast("${landing.name} isn't on this phone yet — add it in the Sheets tab.")
        }
    }

    private fun refresh() {
        val waiting = jobs.list()
        val pending = CloudAgent.get(c).pending
        val ids = registry.ids()
        val auto = ids.size == 1 || (Prefs.cycleSheets(c) && ids.size > 1)
        if (!busy && flash == null && auto && ids.isNotEmpty()) {
            if (waiting.isNotEmpty()) { val job = waiting.first(); if (autoTried.add(job.name)) { printJob(job, if (ids.size == 1) ids[0] else ids[Prefs.cycleIx(c) % ids.size], ids.size > 1); return } }
            else if (pending.isNotEmpty()) { val p = pending.first(); if (autoTried.add(p.id)) { printMirror(p, if (ids.size == 1) ids[0] else ids[Prefs.cycleIx(c) % ids.size], ids.size > 1); return } }
        }
        val anyWaiting = waiting.isNotEmpty() || pending.isNotEmpty()
        when {
            busy -> setState(RingView.Led.BUSY, "Printing…", "Keep the sheet nearby.")
            flash == RingView.Led.DONE -> setState(flash!!, "Printed", "Take a look at the sheet.")
            flash == RingView.Led.ERR -> setState(flash!!, "Not printed", "Hold on — then just try again.")
            anyWaiting -> setState(RingView.Led.WAIT, "Job waiting", if (ids.isEmpty()) "Add a sheet in the Sheets tab first." else "Tap the sheet with this phone — or choose it below.")
            ids.isEmpty() -> setState(RingView.Led.SETUP, "Set me up", "Add your first sheet in the Sheets tab.")
            else -> setState(RingView.Led.READY, "Ready to print", "Print from any app and pick “RePaper Go”.")
        }
        helpBtn.visibility = if (!anyWaiting && (flash == null) && !busy) View.VISIBLE else View.INVISIBLE

        jobList.removeAllViews()
        if (anyWaiting) {
            jobList.addView(Ui.sectionHeader(c, "Waiting to print"))
            for (p in pending) jobList.addView(mirrorCard(p))
            for (job in waiting) jobList.addView(jobCard(job))
        }
    }

    /** The how-to, faded in over the screen behind the "?" — matching the iOS help overlay. */
    private fun showHelpPanel() = Overlays.topPanel(c) { panel, _ ->
        panel.addView(Ui.displayText(c, "HOW TO USE", 11f, Ui.TEXT_3, weight = 700, width = 112).apply {
            letterSpacing = 0.14f; setPadding(0, 0, 0, dp(10))
        })
        panel.addView(Ui.bodyText(c, "Share a photo or document from any app and pick “RePaper Go” — it lands on your sheet. Or tap a sheet to the phone when a job is waiting.", 13f, Ui.TEXT_2))
    }

    private fun mirrorCard(p: CloudAgent.Pending): View = Ui.card(c, ripple = true).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.displayText(c, p.name, 16f, Ui.TEXT, weight = 600))
            addView(Ui.monoText(c, "from ${p.from} · tap to choose a sheet", 11f).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { pickSheetForMirror(p) }
    }
    private fun jobCard(job: File): View = Ui.card(c, ripple = true).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.displayText(c, job.name.substringAfter('-').removeSuffix(".pdf"), 16f, Ui.TEXT, weight = 600))
            addView(Ui.monoText(c, "tap to choose a sheet · hold to discard", 11f).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { pickSheetFor(job) }
        setOnLongClickListener { job.delete(); refresh(); true }
    }

    private fun setState(led: RingView.Led, t: String, s: String) {
        ring.led = led; title.text = t.uppercase(); sub.text = s
        title.setTextColor(when (led) { RingView.Led.ERR -> Ui.RED; RingView.Led.SETUP -> Ui.BLUE; else -> Ui.TEXT })
        pillHolder.removeAllViews()
        pillHolder.addView(when (led) {
            RingView.Led.READY -> Ui.pill(c, "Ready", Ui.ACCENT, Ui.ACCENT_TINT)
            RingView.Led.WAIT -> Ui.pill(c, "Waiting for sheet", Ui.ACCENT, Ui.ACCENT_TINT, blinkMs = 500)
            RingView.Led.BUSY -> Ui.pill(c, "Printing", Ui.ACCENT, Ui.ACCENT_TINT, blinkMs = 160)
            RingView.Led.DONE -> Ui.pill(c, "Printed", Ui.ACCENT, Ui.ACCENT_TINT, check = true)
            RingView.Led.ERR -> Ui.pill(c, "Failed", Ui.RED, Ui.RED_TINT, blinkMs = 500)
            RingView.Led.SETUP -> Ui.pill(c, "Setup", Ui.BLUE, Ui.BLUE_TINT, blinkMs = 500)
        })
    }

    private fun flashState(led: RingView.Led, ms: Long) { flash = led; refresh(); act.lifecycleScope.launch { delay(ms); flash = null; refresh() } }

    private fun pickSheetFor(job: File) {
        val ids = registry.ids(); if (ids.isEmpty()) { toast("Add a sheet in the Sheets tab first."); return }
        AlertDialog.Builder(c).setTitle("Print on which sheet?")
            .setItems(ids.map { registry.name(it) }.toTypedArray()) { _, w -> printJob(job, ids[w]) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun pickSheetForMirror(p: CloudAgent.Pending) {
        val ids = registry.ids(); if (ids.isEmpty()) { toast("Add a sheet in the Sheets tab first."); return }
        AlertDialog.Builder(c).setTitle("Print on which sheet?")
            .setItems(ids.map { registry.name(it) }.toTypedArray()) { _, w -> printMirror(p, ids[w]) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun printJob(job: File, sheetId: String, advanceCycle: Boolean = false) {
        act.lifecycleScope.launch {
            busy = true; refresh()
            try {
                withContext(Dispatchers.IO) { printFlow.printPdf(job, sheetId) { p -> c.runOnUiThread { sub.text = p } } }
                job.delete(); if (advanceCycle) Prefs.bumpCycleIx(c); busy = false; flashState(RingView.Led.DONE, 3000)
            } catch (e: Exception) { busy = false; flashState(RingView.Led.ERR, 6000); toast(e.message ?: "print failed") }
        }
    }
    private fun printMirror(p: CloudAgent.Pending, sheetId: String, advanceCycle: Boolean = false) {
        act.lifecycleScope.launch {
            busy = true; refresh()
            val cloud = CloudAgent.get(c)
            val file = withContext(Dispatchers.IO) { cloud.takeJob(p.id) } ?: run { busy = false; refresh(); return@launch }
            try {
                withContext(Dispatchers.IO) { printFlow.printPdf(file, sheetId) { ph -> c.runOnUiThread { sub.text = ph } }; cloud.jobDone(p.id) }
                file.delete(); if (advanceCycle) Prefs.bumpCycleIx(c); busy = false; flashState(RingView.Led.DONE, 3000)
            } catch (e: Exception) { withContext(Dispatchers.IO) { cloud.jobReleased(p.id) }; file.delete(); busy = false; flashState(RingView.Led.ERR, 6000); toast(e.message ?: "print failed") }
        }
    }
    private fun toast(m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
