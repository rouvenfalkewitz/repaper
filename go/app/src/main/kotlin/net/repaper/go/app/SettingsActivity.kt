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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Sheets and account plumbing live here — the main screen stays the printer, like the Dock. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var registry: Registry
    private lateinit var listView: LinearLayout
    private lateinit var printFlow: PrintFlow   // rebuilt with registry so inherited sheets print

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = Registry(this)
        printFlow = PrintFlow(this, registry)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(net.repaper.go.R.drawable.ic_back)
                setColorFilter(Ui.TEXT_2)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8); leftMargin = -dp(8) }
                setOnClickListener { finish() }
            })
            addView(Ui.displayText(this@SettingsActivity, "Settings", 22f, weight = 700, width = 112))
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(net.repaper.go.R.drawable.ic_gear)
                setColorFilter(Ui.TEXT)   // right behind the word, in the word's white
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) }
            })
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume(); refresh()
        // inherited Dock-Labels arriving over the socket refresh the list live
        InheritedSheets.onChange = { runOnUiThread { registry = Registry(this); printFlow = PrintFlow(this, registry); refresh() } }
    }
    override fun onPause() { InheritedSheets.onChange = null; super.onPause() }

    private fun refresh() {
        listView.removeAllViews()

        // one card per section, rows separated by dividers — the pattern of 10 Sep
        listView.addView(Ui.sectionHeader(this, "Printer"))
        listView.addView(Ui.card(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
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
            addView(divider())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.displayText(this@SettingsActivity, "Cycle through sheets", 15f, Ui.TEXT, weight = 600))
                    addView(Ui.bodyText(this@SettingsActivity, "With several sheets, jobs print on each in turn.", 12f).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.Switch(this@SettingsActivity).apply {
                    isChecked = Prefs.cycleSheets(this@SettingsActivity)
                    setOnCheckedChangeListener { _, v -> Prefs.setCycleSheets(this@SettingsActivity, v) }
                })
            })
            addView(divider())
            addView(techRow("Intake", "How pages reach this printer", listOf("Android Print", "Share to print")))
        })

        listView.addView(Ui.sectionHeader(this, "Sheets"))
        listView.addView(Ui.card(this).apply {
            val ids = registry.ids()
            if (ids.isEmpty()) {
                addView(Ui.bodyText(this@SettingsActivity, "No sheets yet — add the first one below.", 14f))
                addView(divider())
            }
            for (id in ids) {
                addView(sheetRow(id))
                addView(divider())
            }
            addView(Ui.button(this@SettingsActivity, "Add a sheet", primary = true) { addSheetDialog() }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(divider())
            addView(techRow("Sheet link", "How pages reach the paper", listOf("OpenDisplay BLE", "NFC tags")))
        })

        val cloud = CloudAgent.get(this)

        // Print2Go: this phone can also print the jobs sent to a Dock
        listView.addView(Ui.sectionHeader(this, "Print2Go"))
        listView.addView(Ui.card(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.displayText(this@SettingsActivity, "Print jobs from a Dock", 15f, Ui.TEXT, weight = 600))
                val src = Prefs.print2goDock(this@SettingsActivity)
                addView(Ui.bodyText(this@SettingsActivity,
                    src?.let { "Printing jobs sent to $it, on this phone's sheets." }
                        ?: "This phone prints the jobs sent to the Dock you pick.", 12f).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Switch(this@SettingsActivity).apply {
                isChecked = Prefs.print2goDock(this@SettingsActivity) != null
                setOnCheckedChangeListener { btn, on ->
                    if (!btn.isPressed) return@setOnCheckedChangeListener
                    if (on) pickPrint2goDock() else lifecycleScope.launch {
                        withContext(Dispatchers.IO) { cloud.setMirrorFrom(null) }
                        Prefs.setPrint2goDock(this@SettingsActivity, null); refresh()
                    }
                }
            })
        })

        listView.addView(Ui.sectionHeader(this, "Cloud"))
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
            if (cloud.claimed) {
                addView(divider())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(android.widget.ImageView(this@SettingsActivity).apply {
                        setImageResource(net.repaper.go.R.drawable.ic_signout)
                        setColorFilter(Ui.RED)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Ui.RED_TINT); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Ui.BORDER)
                        }
                        setPadding(dp(7), dp(7), dp(7), dp(7))
                        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) }
                    })
                    addView(Ui.bodyText(this@SettingsActivity, "Sign out", 14f, Ui.RED).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    setOnClickListener { confirmSignOut(cloud.org) }
                })
            }
        })
        listView.addView(Ui.monoText(this, "RePaper Go $GO_VERSION", 11f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(24) }
        })
    }

    /** Pick which Dock this phone prints from (Print2Go). */
    private fun pickPrint2goDock() {
        lifecycleScope.launch {
            val cloud = CloudAgent.get(this@SettingsActivity)
            val docks = withContext(Dispatchers.IO) { cloud.print2goDocks() }
            if (docks.isEmpty()) {
                toast("No Docks in your fleet have Print2Go on yet — turn it on in a Dock's settings first.")
                refresh(); return@launch   // reverts the switch (nothing is set)
            }
            val names = docks.map { it.optString("name") + if (it.optBoolean("online")) "" else " (offline)" }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity).setTitle("Print jobs from which Dock?")
                .setItems(names) { _, which ->
                    val dock = docks[which]
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) { cloud.setMirrorFrom(dock.optString("id")) }
                        if (ok) { Prefs.setPrint2goDock(this@SettingsActivity, dock.optString("name")); toast("Jobs sent to ${dock.optString("name")} now print here too.") }
                        else toast("Couldn't set that up — check the connection.")
                        refresh()
                    }
                }
                .setOnCancelListener { refresh() }
                .show()
        }
    }

    private fun confirmSignOut(org: String?) {
        AlertDialog.Builder(this).setTitle("Sign out of RePaper Go?")
            .setMessage("This phone is removed from ${org ?: "your fleet"}. Your sheets stay on this phone; signing in again brings it right back.")
            .setPositiveButton("Sign out") { _, _ -> signOut() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Sign out = remove this device from the fleet server-side, then gate locally.
     *  The next sign-in claims it right back (the identity is kept). */
    private fun signOut() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val identity = Identity(this@SettingsActivity)
                    val body = JSONObject().put("id", identity.deviceId).put("secret", identity.secret)
                    val resp = OkHttpClient().newCall(Request.Builder()
                        .url("${Prefs.cloudBase(this@SettingsActivity)}/api/device/unclaim")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()).execute()
                    resp.use { JSONObject(it.body?.string() ?: "{}").optBoolean("ok") }
                } catch (e: Exception) { false }
            }
            if (!ok) {
                Toast.makeText(this@SettingsActivity, "couldn't reach the cloud — try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            DiagLog.log("signed out — removed from fleet")
            Prefs.setClaimed(this@SettingsActivity, false)
            startActivity(android.content.Intent(this@SettingsActivity, AuthActivity::class.java))
            finishAffinity()
        }
    }

    private fun addSheetDialog() {
        val input = EditText(this).apply {
            hint = "Paste the link from the QR on the label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Add a sheet").setView(input)
            .setPositiveButton("Add") { _, _ -> addSheet(input.text.toString()) }
            .setNeutralButton("Scan the QR") { _, _ -> scanQr() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Point the camera at the QR on the label — Play Services does the scanning UI. */
    private fun scanQr() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { addSheet(it) } ?: toast("That QR carried no link.") }
            .addOnCanceledListener { }
            .addOnFailureListener { toast("Scanning isn't available on this device — paste the link instead.") }
    }

    private fun addSheet(link: String) {
        lifecycleScope.launch {
            toast("Reading the sheet — keep it awake and nearby…")
            try {
                val landing = LandingUrl.parse(link)
                // one connection does it all: interrogate AND program the sheet's own
                // NFC tag over BLE (the sheet writes itself — no phone-radio fiddling)
                val caps = withContext(Dispatchers.IO) {
                    SheetOps.describeAndRegister(this@SettingsActivity, registry, landing, link)
                }
                toast("Added ${landing.name} · ${caps.viewedWidth}×${caps.viewedHeight}" +
                      if (SheetOps.lastTagProgrammed) " · tag programmed" else "")
                refresh()
            } catch (e: Exception) { toast(e.message ?: "could not add the sheet") }
        }
    }

    /** Re-program a sheet's tag over BLE (sheet options). */
    private fun bleProgramTag(id: String) {
        lifecycleScope.launch {
            toast("Waking ${registry.name(id)} to re-program its tag…")
            try {
                withContext(Dispatchers.IO) { SheetOps.programTag(this@SettingsActivity, registry, id) }
                toast("Tag programmed — tapping this sheet works now.")
            } catch (e: Exception) { toast(e.message ?: "couldn't program the tag") }
        }
    }

    /** Hairline between rows of a section card. */
    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(Ui.BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }

    /** The slim technology row at the bottom of a section — Printer vs Sheets halves.
     *  Chips stack vertically so long names never wrap. */
    private fun techRow(title: String, sub: String, chips: List<String>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.bodyText(this@SettingsActivity, title, 14f, Ui.TEXT).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(Ui.bodyText(this@SettingsActivity, sub, 12f).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                for (c in chips) addView(Ui.chip(this@SettingsActivity, c).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(3); gravity = Gravity.END }
                })
            })
        }

    /** A sheet as a sheet — one row of the Sheets card (the Dock's anatomy). */
    private fun sheetRow(id: String): LinearLayout {
        val m = registry.model(id)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Ui.monoText(this@SettingsActivity, registry.address(id).uppercase(), 11f, Ui.TEXT_2).apply {
                    letterSpacing = 0.08f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.palDots(this@SettingsActivity, m.palette))
            })
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
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Ui.displayText(this@SettingsActivity, registry.name(id), 16f, Ui.TEXT, weight = 600))
                registry.dockName(id)?.let {
                    addView(dockChip(), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
                }
                if (registry.tagUid(id) != null) addView(nfcChip(), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            })
            addView(Ui.monoText(this@SettingsActivity,
                registry.dockName(id)?.let { "lives on $it · tap for a test page" } ?: "tap for a test page · hold for options", 11f).apply {
                setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener { testPrint(id) }
            setOnLongClickListener { sheetActions(id); true }
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

    /** A compact badge: a Dock-Label marker (neutral) or the NFC-ready chip (accent). */
    private fun badge(text: String, fg: Int, bg: Int, border: Int?): android.widget.TextView =
        Ui.displayText(this, text, 9f, fg, weight = 700, width = 112).apply {
            letterSpacing = 0.08f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg); cornerRadius = 999f; if (border != null) setStroke(dp(1), border)
            }
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }
    private fun dockChip() = badge("DOCK", Ui.TEXT_2, Ui.SURFACE_2, Ui.BORDER)
    private fun nfcChip() = badge("NFC", Ui.ACCENT, Ui.ACCENT_TINT, null)

    private fun sheetActions(id: String) {
        val dockLabel = registry.isDockLabel(id)
        val items = buildList {
            if (!dockLabel) add("Rename")                       // a Dock-Label is read-only
            if (registry.landing(id) != null) add("Re-program the NFC tag")
            if (!dockLabel) add("Remove")
        }
        if (items.isEmpty()) { toast("This label lives on ${registry.dockName(id)} — manage it on the Dock."); return }
        AlertDialog.Builder(this).setTitle(registry.name(id))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Rename" -> {
                        val input = EditText(this).apply { setText(registry.name(id)) }
                        AlertDialog.Builder(this).setTitle("Rename").setView(input)
                            .setPositiveButton("Save") { _, _ -> registry.rename(id, input.text.toString().trim()); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    "Re-program the NFC tag" -> bleProgramTag(id)
                    "Remove" -> { registry.remove(id); refresh() }
                }
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
