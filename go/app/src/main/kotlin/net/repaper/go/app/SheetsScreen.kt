package net.repaper.go.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import net.repaper.go.core.LandingUrl

/** The Sheets tab: compact device rows that surface the sheet's real facts, inherited
 *  Dock-Labels tagged DOCK, and adding by QR scan or a pasted link — matching iOS. */
class SheetsScreen(private val c: AppCompatActivity) : Screen {
    private lateinit var registry: Registry
    private val listCard = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    override val view: View by lazy { build() }

    private fun build(): View {
        registry = Registry(c)
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        // header: SHEETS wordmark + accent "+"
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ui.wordmark(c, "Sheets"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(plusButton())
        })
        root.addView(listCard.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        })
        return ScrollView(c).apply { addView(root); setBackgroundColor(Ui.BG) }
    }

    override fun onShow() { registry = Registry(c); InheritedSheets.onChange = { c.runOnUiThread { registry = Registry(c); refresh() } }; refresh() }
    override fun onHide() { InheritedSheets.onChange = null }

    private fun refresh() {
        listCard.removeAllViews()
        val card = Ui.card(c)
        val ids = registry.ids()
        if (ids.isEmpty()) {
            card.addView(Ui.bodyText(c, "No sheets yet — scan the QR on a sheet to add your first.", 13f, Ui.TEXT_3))
            card.addView(divider())
        }
        for (id in ids) { card.addView(sheetRow(id)); card.addView(divider()) }
        card.addView(Ui.button(c, "Scan QR code", primary = true) { addSheetDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        card.addView(divider())
        card.addView(techRow("Sheet link", "How pages reach the paper", listOf("OpenDisplay BLE", "NFC tags")))
        listCard.addView(card)
    }

    private fun sheetRow(id: String): View {
        val m = registry.model(id)
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(epaperChip(m.width, m.height), LinearLayout.LayoutParams(dp(62), dp(62)).apply { rightMargin = dp(14) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.displayText(c, registry.name(id), 16f, Ui.TEXT, weight = 600))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    (layoutParams as? LinearLayout.LayoutParams)
                    addView(Ui.palDots(c, m.palette))
                    addView(Ui.monoText(c, "  ${m.width}×${m.height}", 11f).apply { setPadding(dp(6), 0, 0, 0) })
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) } })
                if (registry.dockName(id) != null || registry.tagUid(id) != null) addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    registry.dockName(id)?.let { addView(badge("DOCK", Ui.TEXT_2, Ui.SURFACE_2, Ui.BORDER)) }
                    if (registry.tagUid(id) != null) addView(badge("NFC", Ui.ACCENT, Ui.ACCENT_TINT, null).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) })
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) } })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(c).apply {
                text = "⋯"; textSize = 22f; setTextColor(Ui.TEXT_2); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE_2); setStroke(dp(1), Ui.BORDER) }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                isClickable = true; setOnClickListener { sheetActions(id) }
            })
            isClickable = true; setOnClickListener { sheetActions(id) }
        }
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

    private fun plusButton(): View = TextView(c).apply {
        text = "+"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.ON_ACCENT); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        isClickable = true; setOnClickListener { addSheetDialog() }
    }

    private fun badge(text: String, fg: Int, bg: Int, border: Int?): TextView =
        Ui.displayText(c, text, 9f, fg, weight = 700, width = 112).apply {
            letterSpacing = 0.08f
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 999f; if (border != null) setStroke(dp(1), border) }
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }

    private fun sheetActions(id: String) {
        val dockLabel = registry.isDockLabel(id)
        val items = buildList {
            if (!dockLabel) add("Rename")
            if (registry.landing(id) != null) add("Re-program the NFC tag")
            if (!dockLabel) add("Remove")
        }
        if (items.isEmpty()) { toast("This label lives on ${registry.dockName(id)} — manage it on the Dock."); return }
        AlertDialog.Builder(c).setTitle(registry.name(id))
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "Rename" -> {
                        val input = EditText(c).apply { setText(registry.name(id)) }
                        AlertDialog.Builder(c).setTitle("Rename").setView(input)
                            .setPositiveButton("Save") { _, _ -> registry.rename(id, input.text.toString().trim()); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    "Re-program the NFC tag" -> bleProgramTag(id)
                    "Remove" -> { registry.remove(id); refresh() }
                }
            }.show()
    }

    private fun addSheetDialog() {
        val input = EditText(c).apply {
            hint = "Paste the link from the QR on the label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(c).setTitle("Add a sheet").setView(input)
            .setPositiveButton("Add") { _, _ -> addSheet(input.text.toString()) }
            .setNeutralButton("Scan the QR") { _, _ -> scanQr() }
            .setNegativeButton("Cancel", null).show()
    }
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

    private fun divider() = View(c).apply {
        setBackgroundColor(Ui.BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }
    private fun techRow(title: String, sub: String, chips: List<String>): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.bodyText(c, title, 14f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(Ui.bodyText(c, sub, 12f).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            for (ch in chips) addView(Ui.chip(c, ch).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3); gravity = Gravity.END }
            })
        })
    }
    private fun toast(m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
