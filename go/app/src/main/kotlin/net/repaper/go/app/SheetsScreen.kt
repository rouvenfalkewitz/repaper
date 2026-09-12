package net.repaper.go.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import net.repaper.go.core.LandingUrl

/** The Sheets tab: compact device rows that surface the sheet's real facts, inherited
 *  Dock-Labels tagged DOCK, adding by QR scan or a pasted link in our own bottom drawer,
 *  and a per-sheet configure drawer — all matching the iOS panels, not system dialogs. */
class SheetsScreen(private val c: AppCompatActivity) : Screen {
    private lateinit var registry: Registry
    private val listCard = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    override val view: View by lazy { build() }

    private fun build(): View {
        registry = Registry(c)
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        // header: SHEETS wordmark + "?" info + accent "+"
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ui.wordmark(c, "Sheets"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerButton("?", accent = false) { showInfo() })
            addView(headerButton("+", accent = true) { showAdd() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
        })
        root.addView(listCard.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        })
        return ScrollView(c).apply { addView(root); setBackgroundColor(Ui.BG) }
    }

    override fun onShow() { registry = Registry(c); InheritedSheets.onChange = { c.runOnUiThread { registry = Registry(c); refresh() } }; refresh() }
    override fun onHide() { InheritedSheets.onChange = null }

    private fun refresh() {
        listCard.removeAllViews()
        val ids = registry.ids()
        if (ids.isEmpty()) { listCard.addView(emptyState()); return }
        val card = Ui.card(c)
        for ((i, id) in ids.withIndex()) {
            card.addView(sheetRow(id))
            if (i != ids.lastIndex) card.addView(divider())
        }
        listCard.addView(card)
    }

    // ── header controls ─────────────────────────────────────────────────────────
    private fun headerButton(glyph: String, accent: Boolean, onTap: () -> Unit): View = TextView(c).apply {
        text = glyph; textSize = if (accent) 20f else 16f; gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD; setTextColor(if (accent) Ui.ON_ACCENT else Ui.TEXT_2)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(if (accent) Ui.ACCENT else Ui.SURFACE)
            if (!accent) setStroke(dp(1), Ui.BORDER)
        }
        if (accent) elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        isClickable = true; setOnClickListener { onTap() }
    }

    // ── a sheet row ───────────────────────────────────────────────────────────────
    private fun sheetRow(id: String): View {
        val m = registry.model(id)
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(epaperChip(m.width, m.height), LinearLayout.LayoutParams(dp(62), dp(62)).apply { rightMargin = dp(14) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.displayText(c, registry.name(id), 16f, Ui.TEXT, weight = 600).apply { maxLines = 1 })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(PalDot(c, m.palette))
                    addView(Ui.monoText(c, "${m.width}×${m.height}", 11f).apply { setPadding(dp(8), 0, 0, 0) })
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) } })
                if (registry.dockName(id) != null || registry.tagUid(id) != null) addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    registry.dockName(id)?.let { addView(dockBadge()) }
                    if (registry.tagUid(id) != null) addView(nfcBadge().apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) })
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) } })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(configureButton(id))
            isClickable = true; setOnClickListener { showConfigure(id) }
        }
    }

    /** A tuning glyph on a raised tile, like iOS's configureButton. */
    private fun configureButton(id: String): View = ImageView(c).apply {
        setImageResource(R.drawable.ic_gear); setColorFilter(Ui.TEXT)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.SURFACE_2, Ui.BG)).apply {
            shape = GradientDrawable.OVAL; setStroke(dp(1), Ui.BORDER_STRONG)
        }
        setPadding(dp(9), dp(9), dp(9), dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) }
        isClickable = true; setOnClickListener { showConfigure(id) }
    }

    private fun epaperChip(w: Int, h: Int): View {
        val pw = dp(52); val ph = (pw.toFloat() * h / w).toInt().coerceIn(dp(30), dp(52))
        val panel = View(c).apply {
            background = GradientDrawable().apply { setColor(Ui.EPAPER_PANEL); cornerRadius = dp(4).toFloat() }
            layoutParams = LinearLayout.LayoutParams(pw, ph)
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Ui.EPAPER_BEZEL); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(panel)
        }
    }

    private fun dockBadge(): TextView = badge("DOCK", Ui.TEXT_2, Ui.SURFACE_2, Ui.BORDER)
    private fun nfcBadge(): TextView = badge("NFC", Ui.ACCENT, Ui.ACCENT_TINT, null)
    private fun badge(text: String, fg: Int, bg: Int, border: Int?): TextView =
        Ui.displayText(c, text, 9f, fg, weight = 700, width = 112).apply {
            letterSpacing = 0.08f
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 999f; if (border != null) setStroke(dp(1), border) }
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }

    // ── empty state ───────────────────────────────────────────────────────────────
    private fun emptyState(): View = Ui.card(c).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(40), dp(24), dp(40))
        addView(bigLabelMark())
        addView(Ui.displayText(c, "No sheets yet", 17f, Ui.TEXT, weight = 700).apply {
            gravity = Gravity.CENTER; (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        })
        addView(Ui.bodyText(c, "Add your first RePaper sheet by scanning the QR code printed on it.", 13f, Ui.TEXT_3).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(0))
        })
        addView(Ui.button(c, "Add a sheet", primary = true) { showAdd() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })
    }

    private fun bigLabelMark(): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Ui.EPAPER_BEZEL); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG) }
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Ui.EPAPER_PANEL); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(44))
            addView(View(c).apply {
                background = GradientDrawable().apply { setColor(Ui.INK); cornerRadius = dp(2).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(5))
            })
            addView(View(c).apply {
                background = GradientDrawable().apply { setColor(Ui.EPAPER_RED); cornerRadius = dp(2).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(5)).apply { topMargin = dp(5) }
            })
        })
    }

    // ── add drawer (scan + paste fallback) ──────────────────────────────────────────
    private fun showAdd() = Overlays.bottomSheet(c) { panel, dismiss ->
        panel.addView(scanCard { dismiss(); scanQr() })
        panel.addView(orDivider())
        // paste field: link glyph + input + circular submit
        val input = EditText(c).apply {
            hint = "Paste the sheet's link"; setHintTextColor(Ui.TEXT_3); setTextColor(Ui.TEXT)
            typeface = Typeface.MONOSPACE; textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = null
        }
        val submit = ImageView(c).apply {
            setImageResource(R.drawable.ic_arrow_right); setColorFilter(Ui.ON_ACCENT)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            isClickable = true; setOnClickListener {
                val t = input.text.toString().trim(); if (t.isNotEmpty()) { dismiss(); addSheet(t) }
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { submit.alpha = if (s.isNullOrBlank()) 0.4f else 1f }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        panel.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Ui.BG); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG) }
            setPadding(dp(14), dp(6), dp(6), dp(6))
            addView(ImageView(c).apply { setImageResource(R.drawable.ic_link); setColorFilter(Ui.TEXT_3)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) } })
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(submit)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        })
    }

    private fun scanCard(onTap: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = dp(16).toFloat() }
        elevation = dp(3).toFloat()
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(FrameLayout(c).apply {
            background = GradientDrawable().apply { setColor(0x29000000.toInt()); cornerRadius = dp(11).toFloat() }
            addView(ImageView(c).apply { setImageResource(R.drawable.ic_qr); setColorFilter(Ui.ON_ACCENT)
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER) })
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) }
        })
        addView(Ui.bodyText(c, "Scan QR code", 16f, Ui.ON_ACCENT).apply { setTypeface(typeface, Typeface.BOLD) })
        isClickable = true; setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun orDivider(): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(View(c).apply { setBackgroundColor(Ui.BORDER); layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
        addView(Ui.monoText(c, "or", 11f).apply { setPadding(dp(10), 0, dp(10), 0) })
        addView(View(c).apply { setBackgroundColor(Ui.BORDER); layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
    }

    // ── configure drawer ────────────────────────────────────────────────────────────
    private fun showConfigure(id: String) = Overlays.bottomSheet(c) { panel, dismiss ->
        val m = registry.model(id)
        val dockLabel = registry.isDockLabel(id)
        // header: chip + name + size/palette + dots & badges
        panel.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(epaperChip(m.width, m.height), LinearLayout.LayoutParams(dp(62), dp(62)).apply { rightMargin = dp(12) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.bodyText(c, registry.name(id), 18f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
                addView(Ui.monoText(c, "${m.width}×${m.height} · ${paletteName(m.palette)}", 11f).apply { setPadding(0, dp(4), 0, 0) })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(PalDot(c, m.palette))
                    registry.dockName(id)?.let { addView(dockBadge().apply { (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) } }) }
                    if (registry.tagUid(id) != null) addView(nfcBadge().apply { (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) } })
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        panel.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
        // actions
        if (!dockLabel) panel.addView(configRow(R.drawable.ic_gear, "Rename") {
            dismiss()
            val input = EditText(c).apply { setText(registry.name(id)) }
            AlertDialog.Builder(c).setTitle("Rename").setView(input)
                .setPositiveButton("Save") { _, _ -> registry.rename(id, input.text.toString().trim()); refresh() }
                .setNegativeButton("Cancel", null).show()
        })
        if (registry.landing(id) != null) panel.addView(configRow(R.drawable.ic_reload, "Re-program the NFC tag") {
            dismiss(); bleProgramTag(id)
        })
        if (!dockLabel) panel.addView(configRow(R.drawable.ic_trash, "Remove from this device", tint = Ui.RED, iconBg = Ui.RED_TINT) {
            dismiss(); registry.remove(id); refresh()
        })
        panel.addView(Ui.bodyText(c, if (dockLabel)
            "This label lives on ${registry.dockName(id)}. It updates from there; it stays until removed on the Dock."
            else "Removing only forgets the sheet here — it keeps what it currently shows.", 12f, Ui.TEXT_3).apply {
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun configRow(iconRes: Int, label: String, tint: Int = Ui.TEXT, iconBg: Int = Ui.SURFACE_2, onTap: () -> Unit): View =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(ImageView(c).apply {
                setImageResource(iconRes); setColorFilter(tint)
                background = GradientDrawable().apply { setColor(iconBg); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Ui.BORDER) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) }
            })
            addView(Ui.bodyText(c, label, 15f, tint).apply { setTypeface(typeface, Typeface.BOLD) })
            isClickable = true; setOnClickListener { onTap() }
        }

    // ── info overlay ("how your sheets connect", behind "?") ──────────────────────────
    private fun showInfo() = Overlays.topPanel(c) { panel, _ ->
        panel.addView(Ui.displayText(c, "HOW YOUR SHEETS CONNECT", 11f, Ui.TEXT_3, weight = 700, width = 112).apply {
            letterSpacing = 0.14f; setPadding(0, 0, 0, dp(18))
        })
        panel.addView(infoItem(R.drawable.ic_ble, "OpenDisplay BLE",
            "Pages travel to the paper over Bluetooth — no Wi-Fi and no cloud in between."))
        panel.addView(infoItem(R.drawable.ic_nfc, "NFC tags",
            "Tap a sheet to the top of the phone to pick it for a waiting job — no menus.").apply {
            (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })
    }

    private fun infoItem(iconRes: Int, title: String, body: String): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ImageView(c).apply {
            setImageResource(iconRes); setColorFilter(Ui.ACCENT)
            background = GradientDrawable().apply { setColor(Ui.ACCENT_TINT); cornerRadius = dp(10).toFloat() }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.bodyText(c, title, 15f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(Ui.bodyText(c, body, 13f, Ui.TEXT_3).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // ── actions ───────────────────────────────────────────────────────────────────
    private fun scanQr() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(c, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { addSheet(it) } ?: toast("That QR carried no link.") }
            .addOnFailureListener { toast("Scanning isn't available — paste the link instead.") }
    }
    private fun addSheet(link: String) {
        c.lifecycleScope.launch {
            toast("Reading the sheet — keep it awake and nearby…")
            try {
                val landing = LandingUrl.parse(link)
                val caps = withContext(Dispatchers.IO) { SheetOps.describeAndRegister(c, registry, landing, link) }
                toast("Added ${landing.name} · ${caps.viewedWidth}×${caps.viewedHeight}" + if (SheetOps.lastTagProgrammed) " · tag programmed" else "")
                refresh()
            } catch (e: Exception) { toast(e.message ?: "could not add the sheet") }
        }
    }
    private fun bleProgramTag(id: String) {
        c.lifecycleScope.launch {
            toast("Waking ${registry.name(id)} to re-program its tag…")
            try { withContext(Dispatchers.IO) { SheetOps.programTag(c, registry, id) }; toast("Tag programmed.") }
            catch (e: Exception) { toast(e.message ?: "couldn't program the tag") }
        }
    }

    private fun paletteName(p: String): String = when (p.uppercase()) {
        "BWR" -> "black / white / red"
        "BWY" -> "black / white / yellow"
        "BWRY" -> "black / white / red / yellow"
        "BW" -> "black / white"
        else -> p
    }

    private fun divider() = View(c).apply {
        setBackgroundColor(Ui.BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }
    private fun toast(m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
