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
    private val printFlow by lazy { PrintFlow(this, registry) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = Registry(this)

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

    override fun onResume() { super.onResume(); refresh() }

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
                val caps = withContext(Dispatchers.IO) {
                    SheetOps.describeAndRegister(this@SettingsActivity, registry, landing)
                }
                registry.updateKey(landing.name, "landing", link)   // for (re)programming the tag
                toast("Added ${landing.name} · ${caps.viewedWidth}×${caps.viewedHeight}")
                refresh()
                // some sheets ship with an empty tag: program it now so tap-to-print always works
                programTag(link)
            } catch (e: Exception) { toast(e.message ?: "could not add the sheet") }
        }
    }

    /** Writes the landing link onto the sheet's NFC tag — applies to every RePaper variant. */
    private fun programTag(link: String) {
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this) ?: return
        var done = false
        val dlg = AlertDialog.Builder(this).setTitle("Program the sheet's tag")
            .setMessage("Hold the sheet to the back of the phone — tapping it will then always work.")
            .setNegativeButton("Skip", null)
            .setOnDismissListener { if (!done) adapter.disableReaderMode(this) }
            .show()
        adapter.enableReaderMode(this, { tag ->
            val ok = try {
                val msg = android.nfc.NdefMessage(android.nfc.NdefRecord.createUri(link))
                val ndef = android.nfc.tech.Ndef.get(tag)
                if (ndef != null) { ndef.connect(); ndef.writeNdefMessage(msg); ndef.close(); true }
                else android.nfc.tech.NdefFormatable.get(tag)?.let { f -> f.connect(); f.format(msg); f.close(); true } ?: false
            } catch (e: Exception) { false }
            runOnUiThread {
                done = true
                adapter.disableReaderMode(this)
                dlg.dismiss()
                DiagLog.log("nfc tag program: $ok")
                toast(if (ok) "Tag programmed — tapping this sheet works now."
                      else "Couldn't write the tag — hold the sheet in the list to retry.")
            }
        }, android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
           android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V, null)
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
            addView(Ui.displayText(this@SettingsActivity, registry.name(id), 16f, Ui.TEXT, weight = 600))
            addView(Ui.monoText(this@SettingsActivity, "tap for a test page · hold for options", 11f).apply {
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

    private fun sheetActions(id: String) {
        val landing = registry.landing(id)
        val canProgram = landing != null && android.nfc.NfcAdapter.getDefaultAdapter(this) != null
        val items = buildList {
            add("Rename")
            if (canProgram) add("Program NFC tag")
            add("Remove")
        }
        AlertDialog.Builder(this).setTitle(registry.name(id))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Rename" -> {
                        val input = EditText(this).apply { setText(registry.name(id)) }
                        AlertDialog.Builder(this).setTitle("Rename").setView(input)
                            .setPositiveButton("Save") { _, _ -> registry.rename(id, input.text.toString().trim()); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    "Program NFC tag" -> programTag(landing!!)
                    "Remove" -> { registry.remove(id); refresh() }
                }
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
