package net.repaper.go.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.core.LandingUrl
import net.repaper.go.core.OdDevice
import net.repaper.go.core.SheetModel
import net.repaper.go.core.hexToBytes

/** Sheets and account plumbing live here — the main screen stays the printer, like the Dock. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var registry: Registry
    private lateinit var listView: LinearLayout
    private val printFlow by lazy { PrintFlow(this, registry) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = Registry(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(Ui.displayText(this, "Settings", 22f, weight = 700, width = 112))
        root.addView(Ui.button(this, "Add a sheet", primary = true) { addSheetDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        listView.removeAllViews()

        listView.addView(Ui.sectionHeader(this, "Printer"))
        listView.addView(Ui.card(this, ripple = true).apply {
            addView(Ui.displayText(this@SettingsActivity, Prefs.printerName(this@SettingsActivity), 16f, Ui.TEXT, weight = 600))
            addView(Ui.monoText(this@SettingsActivity, "what people see in their print dialog · tap to rename", 12f).apply {
                setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener {
                val input = EditText(this@SettingsActivity).apply { setText(Prefs.printerName(this@SettingsActivity)) }
                AlertDialog.Builder(this@SettingsActivity).setTitle("Printer name").setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        Prefs.setPrinterName(this@SettingsActivity, input.text.toString())
                        toast("Saved — the new name shows the next time a print dialog opens.")
                        refresh()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })

        listView.addView(Ui.sectionHeader(this, "Sheets"))
        val ids = registry.ids()
        if (ids.isEmpty()) {
            listView.addView(Ui.bodyText(this, "No sheets yet. Scan the QR on a label with your camera and paste the link here.", 14f))
        }
        for (id in ids) {
            val m = registry.model(id)
            listView.addView(Ui.card(this, ripple = true).apply {
                // top row: address in mono caps + the palette dots — the Dock's sheet-card anatomy
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.monoText(this@SettingsActivity, registry.address(id).uppercase(), 11f, Ui.TEXT_2).apply {
                        letterSpacing = 0.08f
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Ui.palDots(this@SettingsActivity, m.palette))
                })
                // the sheet as a sheet: bezel + panel at its real aspect ratio
                val panel = LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(Ui.EPAPER_PANEL)
                    val pw = dp(190)
                    layoutParams = LinearLayout.LayoutParams(pw, (pw * m.height / m.width).coerceIn(dp(28), dp(190)))
                    addView(Ui.monoText(this@SettingsActivity, "${m.width}×${m.height}", 11f, Ui.INK))
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(10); bottomMargin = dp(8) }
                    addView(Ui.frame(this@SettingsActivity, panel))
                })
                addView(Ui.displayText(this@SettingsActivity, registry.name(id), 16f, Ui.TEXT, weight = 600))
                addView(Ui.monoText(this@SettingsActivity, "tap for a test page · hold to rename or remove", 11f).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener { testPrint(id) }
                setOnLongClickListener { sheetActions(id); true }
            })
        }

        val cloud = CloudAgent.get(this)
        listView.addView(Ui.sectionHeader(this, "RePaper Cloud"))
        listView.addView(Ui.card(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Ui.dot(this@SettingsActivity, if (cloud.state == "online") Ui.ACCENT else Ui.AMBER))
                addView(Ui.bodyText(this@SettingsActivity,
                    if (cloud.claimed) "Connected — managed in the ${cloud.org ?: "fleet"}"
                    else "Connected — waiting to be claimed", 14f, Ui.TEXT))
            })
            if (!cloud.claimed) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Ui.BG); cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Ui.BORDER_STRONG, dp(5).toFloat(), dp(4).toFloat())
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(10) }
                    addView(Ui.displayText(this@SettingsActivity, "CLAIM CODE", 10f, Ui.TEXT_3, weight = 700, width = 112).apply { letterSpacing = 0.12f })
                    addView(Ui.monoText(this@SettingsActivity, cloud.claimCode, 20f, Ui.TEXT).apply { letterSpacing = 0.1f; setPadding(0, dp(2), 0, dp(2)) })
                    addView(Ui.bodyText(this@SettingsActivity, "Signing in claims this phone automatically — the code is only for claiming by hand.", 12f))
                })
            }
        })
        listView.addView(Ui.monoText(this, "RePaper Go $GO_VERSION", 11f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(24) }
        })
    }

    private fun addSheetDialog() {
        val input = EditText(this).apply {
            hint = "Paste the link from the QR on the label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Add a sheet").setView(input)
            .setPositiveButton("Add") { _, _ -> addSheet(input.text.toString()) }
            .setNeutralButton("Demo sheet (no hardware)") { _, _ ->
                val id = "demo-" + (registry.ids().count { it.startsWith("demo-") } + 1)
                registry.add(id, "Demo 2.9″", "demo", null, SheetModel(296, 128, "BWR"))
                registry.entry(id).put("transport", "mock"); registry.rename(id, "Demo 2.9″")
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addSheet(link: String) {
        lifecycleScope.launch {
            toast("Reading the sheet — keep it awake and nearby…")
            try {
                val landing = LandingUrl.parse(link)
                val caps = withContext(Dispatchers.IO) {
                    val dev = GattLink.find(this@SettingsActivity, landing.name, null)
                        ?: throw Exception("couldn't find ${landing.name} nearby — wake the sheet and try again")
                    val gatt = GattLink.connect(this@SettingsActivity, dev)
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
                toast("Added ${landing.name} · ${caps.viewedWidth}×${caps.viewedHeight}")
                refresh()
            } catch (e: Exception) { toast(e.message ?: "could not add the sheet") }
        }
    }

    private fun testPrint(id: String) {
        AlertDialog.Builder(this).setTitle(registry.name(id))
            .setMessage("Print a test page on this sheet?")
            .setPositiveButton("Print") { _, _ ->
                lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { printFlow.printTest(id) }; toast("Printed.") }
                    catch (e: Exception) { toast(e.message ?: "print failed") }
                }
            }.setNegativeButton("Cancel", null).show()
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
