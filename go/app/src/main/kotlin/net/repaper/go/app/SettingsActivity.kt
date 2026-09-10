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
        })
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

        listView.addView(Ui.card(this).apply {
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
        if (cloud.claimed) {
            listView.addView(Ui.card(this, ripple = true).apply {
                addView(Ui.bodyText(this@SettingsActivity, "Sign out", 14f, Ui.RED).apply {
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                setOnClickListener { confirmSignOut(cloud.org) }
            })
        }
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
