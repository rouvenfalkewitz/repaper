package net.repaper.go.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.repaper.go.app.Ui.dp

/** The claimed+approved app: three destinations on a floating bottom bar — Sheets on the
 *  left, Settings on the right, and the Printer as a raised round home button wearing the
 *  glowing brand ring with "GO" in the CI letters. Content switches in place. */
class ShellActivity : AppCompatActivity() {
    enum class Tab { SHEETS, PRINTER, SETTINGS }
    private var tab = Tab.PRINTER
    private lateinit var content: FrameLayout
    private val printer by lazy { PrinterScreen(this) }
    private val sheets by lazy { SheetsScreen(this) }
    private val settings by lazy { SettingsScreen(this) }
    private val screens: Map<Tab, Screen> by lazy {
        mapOf(Tab.SHEETS to sheets, Tab.PRINTER to printer, Tab.SETTINGS to settings)
    }

    // bottom-nav pieces we restyle on selection
    private lateinit var sheetsIcon: TextView; private lateinit var sheetsLabel: TextView
    private lateinit var settingsIcon: TextView; private lateinit var settingsLabel: TextView
    private lateinit var goRing: View; private lateinit var goText: TextView; private lateinit var goCol: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.isClaimed(this)) { start(AuthActivity::class.java); return }
        if (!Prefs.isApproved(this)) { start(PendingActivity::class.java); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(0, 0, 0, dp(90))   // room for the floating bar
        }
        root.addView(content)
        root.addView(bottomBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        requestBlePermissions()
        CloudAgent.get(this).start()
        select(Tab.PRINTER)
    }

    private fun requestBlePermissions() {
        val wanted = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) { add(android.Manifest.permission.BLUETOOTH_SCAN); add(android.Manifest.permission.BLUETOOTH_CONNECT) }
            else add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isClaimed(this)) { start(AuthActivity::class.java); return }
        screens[tab]?.onShow()
        CloudAgent.get(this).onJobArrived = { runOnUiThread { screens[tab]?.onShow() } }
    }
    override fun onPause() { CloudAgent.get(this).onJobArrived = null; screens[tab]?.onHide(); super.onPause() }

    private fun start(a: Class<*>) { startActivity(android.content.Intent(this, a)); finish() }

    private fun select(t: Tab) {
        screens[tab]?.onHide()
        tab = t
        content.removeAllViews()
        content.addView(screens[t]!!.view)
        screens[t]!!.onShow()
        styleBar()
    }

    // ── the floating bottom bar ─────────────────────────────────────────────
    private fun bottomBar(): View {
        val bar = FrameLayout(this).apply {
            setPadding(dp(22), dp(14), dp(22), dp(30))   // clear the system gesture area
        }
        // the pill: a soft carbon capsule with a hairline
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE); cornerRadius = dp(30).toFloat(); setStroke(dp(1), Ui.BORDER)
            }
            elevation = dp(8).toFloat()
            setPadding(dp(22), dp(8), dp(22), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(60), Gravity.CENTER_VERTICAL)
        }
        sheetsIcon = navGlyph("▤"); sheetsLabel = navLabel("Sheets")
        settingsIcon = navGlyph("⚙"); settingsLabel = navLabel("Settings")
        pill.addView(navTab(sheetsIcon, sheetsLabel) { select(Tab.SHEETS) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pill.addView(View(this), LinearLayout.LayoutParams(dp(64), 1))   // gap for the GO button
        pill.addView(navTab(settingsIcon, settingsLabel) { select(Tab.SETTINGS) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill)
        bar.addView(goButton(), FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        return bar
    }

    private fun navGlyph(s: String) = TextView(this).apply {
        text = s; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT_3)
    }
    private fun navLabel(s: String) = TextView(this).apply {
        text = s; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT_3); typeface = Typeface.MONOSPACE
    }
    private fun navTab(icon: TextView, label: TextView, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(icon); addView(label)
        isClickable = true; setOnClickListener { onTap() }
    }

    private fun goButton(): View {
        val wrap = FrameLayout(this)
        goRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Ui.SURFACE_2, Ui.BG); orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(6), Ui.ACCENT)   // the CI ring, thick like the logo
            }
            layoutParams = FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER)
        }
        goText = Ui.displayText(this, "GO", 22f, Ui.ACCENT, weight = 800, width = 125).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { this.gravity = Gravity.CENTER }
        }
        wrap.addView(goRing); wrap.addView(goText)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            elevation = dp(14).toFloat()   // above the pill (elevation 8) so it isn't hidden
            addView(wrap, LinearLayout.LayoutParams(dp(66), dp(66)))
            isClickable = true; setOnClickListener { select(Tab.PRINTER) }
        }
        goCol = col
        return col
    }

    private fun styleBar() {
        val on = Ui.ACCENT; val off = Ui.TEXT_3
        sheetsIcon.setTextColor(if (tab == Tab.SHEETS) on else off); sheetsLabel.setTextColor(if (tab == Tab.SHEETS) on else off)
        settingsIcon.setTextColor(if (tab == Tab.SETTINGS) on else off); settingsLabel.setTextColor(if (tab == Tab.SETTINGS) on else off)
        goText.setTextColor(if (tab == Tab.PRINTER) Ui.ACCENT else Ui.TEXT)
        (goRing.background as? GradientDrawable)?.setStroke(dp(6), if (tab == Tab.PRINTER) Ui.ACCENT else Ui.BORDER_STRONG)
    }
}

/** Each tab is a Screen: its (lazily built) View plus show/hide hooks for the lifecycle. */
interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
}
