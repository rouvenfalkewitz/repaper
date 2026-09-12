package net.repaper.go.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** The Settings tab: a device-identity hero, how this phone behaves as a printer,
 *  Print2Go, and the account — matching the iOS Settings pass. */
class SettingsScreen(private val c: AppCompatActivity) : Screen {
    private val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    // Print2Go inline picker state
    private var p2gExpanded = false
    private var p2gDocks: List<org.json.JSONObject>? = null   // null = not loaded yet
    private var p2gLoading = false

    override val view: View by lazy {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        root.addView(Ui.wordmark(c, "Settings"))
        root.addView(list.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        })
        ScrollView(c).apply { addView(root); setBackgroundColor(Ui.BG) }
    }

    override fun onShow() { p2gDocks = null; refresh() }   // refetch the Dock list on each visit

    private fun refresh() {
        val cloud = CloudAgent.get(c)
        list.removeAllViews()

        // identity hero
        list.addView(Ui.card(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
            addView(brandRing(), LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(14) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.displayText(c, "RePaper Go", 17f, Ui.TEXT, weight = 800, width = 100).apply { maxLines = 1; setSingleLine(true) })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.dot(c, if (cloud.state == "online") Ui.ACCENT else Ui.AMBER))
                    addView(Ui.bodyText(c, if (cloud.state == "online") "Connected${cloud.org?.let { " · $it" } ?: ""}" else "Connecting…", 13f, Ui.TEXT_2))
                }.apply { (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) } })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.monoText(c, "v$GO_VERSION", 11f).apply {
                background = GradientDrawable().apply { setColor(Ui.SURFACE_2); cornerRadius = 999f; setStroke(dp(1), Ui.BORDER) }
                setPadding(dp(9), dp(4), dp(9), dp(4))
            })
        })

        // Printer
        list.addView(Ui.sectionHeader(c, "Printer"))
        list.addView(Ui.card(c).apply {
            addView(row(R.drawable.ic_printer, "Printer name", Prefs.printerName(c)) {
                val input = EditText(c).apply { setText(Prefs.printerName(c)) }
                AlertDialog.Builder(c).setTitle("Printer name").setView(input)
                    .setPositiveButton("Save") { _, _ -> Prefs.setPrinterName(c, input.text.toString()); refresh() }
                    .setNegativeButton("Cancel", null).show()
            })
            addView(divider())
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(settingIcon(R.drawable.ic_reload))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.bodyText(c, "Cycle through sheets", 15f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(Ui.bodyText(c, "With several sheets, jobs print on each in turn.", 12f).apply { setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(c).apply { isChecked = Prefs.cycleSheets(c); setOnCheckedChangeListener { _, v -> Prefs.setCycleSheets(c, v) } })
            })
            addView(divider())
            addView(techRow(R.drawable.ic_share, "Intake", "How pages reach this printer", listOf("Android Print", "Share to print")))
        })

        // Print2Go — a toggle that reveals the Dock picker inline (iOS)
        list.addView(Ui.sectionHeader(c, "Print2Go"))
        val p2gOn = Prefs.print2goDock(c) != null || p2gExpanded
        list.addView(Ui.card(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(settingIcon(R.drawable.ic_doc_down))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.bodyText(c, "Print jobs from a Dock", 15f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(Ui.bodyText(c, "This phone prints the jobs sent to the Dock you pick, on its own sheets.", 12f).apply { setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(c).apply {
                    isChecked = p2gOn
                    setOnCheckedChangeListener { btn, on ->
                        if (!btn.isPressed) return@setOnCheckedChangeListener
                        if (on) { p2gExpanded = true; p2gDocks = null; refresh() }
                        else c.lifecycleScope.launch {
                            withContext(Dispatchers.IO) { cloud.setMirrorFrom(null) }
                            Prefs.setPrint2goDock(c, null); p2gExpanded = false; p2gDocks = null; refresh()
                        }
                    }
                })
            })
            if (p2gOn) {
                addView(divider())
                val docks = p2gDocks
                when {
                    docks == null -> { addView(Ui.bodyText(c, "Finding Docks…", 13f, Ui.TEXT_2)); loadDocks() }
                    docks.isEmpty() -> addView(Ui.bodyText(c, "No Docks in your fleet have Print2Go on yet. Turn it on in a Dock's settings first.", 13f, Ui.TEXT_3))
                    else -> for ((i, d) in docks.withIndex()) {
                        addView(dockRow(d))
                        if (i != docks.lastIndex) addView(View(c).apply {
                            setBackgroundColor(Ui.BORDER)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
                        })
                    }
                }
            }
        })

        // Account
        list.addView(Ui.sectionHeader(c, "Account"))
        list.addView(Ui.card(c, ripple = true).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(c).apply {
                setImageResource(R.drawable.ic_signout); setColorFilter(Ui.RED)
                background = GradientDrawable().apply { setColor(Ui.RED_TINT); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Ui.BORDER) }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.bodyText(c, "Sign out", 15f, Ui.RED).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(Ui.bodyText(c, "Removes this phone from your fleet — sheets stay here.", 12f).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { confirmSignOut(cloud.org) }
        })
    }

    /** A carbon puck with the green ring mark floating inside — echoes the printer hero (iOS). */
    private fun brandRing(): View = android.widget.FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.SURFACE_2, Ui.BG)).apply {
            shape = GradientDrawable.OVAL; setStroke(dp(1), Ui.BORDER_STRONG)
        }
        addView(View(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000); setStroke(dp(4), Ui.ACCENT) }
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
        })
    }

    /** A leading icon tile, like iOS's SettingIcon — carbon square, hairline, tinted glyph. */
    private fun settingIcon(iconRes: Int, tint: Int = Ui.TEXT_2): View = ImageView(c).apply {
        setImageResource(iconRes); setColorFilter(tint)
        background = GradientDrawable().apply { setColor(Ui.SURFACE_2); cornerRadius = dp(9).toFloat(); setStroke(dp(1), Ui.BORDER) }
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) }
    }

    private fun row(iconRes: Int, title: String, value: String, onTap: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(settingIcon(iconRes))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.bodyText(c, title, 15f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(Ui.bodyText(c, value, 14f, Ui.TEXT_2).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        isClickable = true; setOnClickListener { onTap() }
    }

    private fun loadDocks() {
        if (p2gLoading) return
        p2gLoading = true
        c.lifecycleScope.launch {
            val docks = withContext(Dispatchers.IO) { CloudAgent.get(c).print2goDocks() }
            p2gDocks = docks; p2gLoading = false; refresh()
        }
    }

    private fun chooseDock(id: String, name: String) {
        c.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { CloudAgent.get(c).setMirrorFrom(id) }
            if (ok) { Prefs.setPrint2goDock(c, name); p2gDocks = null; refresh() }
            else toast("Couldn't set that up — check the connection.")
        }
    }

    /** One Dock in the inline picker: a live dot, its name, online/offline, and a check if current. */
    private fun dockRow(d: org.json.JSONObject): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val online = d.optBoolean("online"); val current = d.optBoolean("current")
        addView(View(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (online) Ui.ACCENT else Ui.TEXT_3) }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) }
            if (online) Ui.accentShadow(this, 3)
        })
        addView(Ui.bodyText(c, d.optString("name"), 15f, if (current) Ui.ACCENT else Ui.TEXT).apply { if (current) setTypeface(typeface, Typeface.BOLD) })
        addView(Ui.monoText(c, if (online) "online" else "offline", 10f).apply { setPadding(dp(8), 0, 0, 0) })
        addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))   // spacer
        if (current) addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_check)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        isClickable = true; setOnClickListener { chooseDock(d.optString("id"), d.optString("name")) }
    }

    private fun confirmSignOut(org: String?) {
        AlertDialog.Builder(c).setTitle("Sign out of RePaper Go?")
            .setMessage("This phone is removed from ${org ?: "your fleet"}. Your sheets stay on this phone; signing in again brings it right back.")
            .setPositiveButton("Sign out") { _, _ -> signOut() }.setNegativeButton("Cancel", null).show()
    }
    private fun signOut() {
        c.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val id = Identity(c)
                    val body = JSONObject().put("id", id.deviceId).put("secret", id.secret)
                    OkHttpClient().newCall(Request.Builder().url("${Prefs.cloudBase(c)}/api/device/unclaim")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build())
                        .execute().use { JSONObject(it.body?.string() ?: "{}").optBoolean("ok") }
                } catch (e: Exception) { false }
            }
            if (!ok) { toast("couldn't reach the cloud — try again"); return@launch }
            Prefs.setClaimed(c, false)
            c.startActivity(android.content.Intent(c, AuthActivity::class.java)); c.finishAffinity()
        }
    }

    private fun divider() = View(c).apply {
        setBackgroundColor(Ui.BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }
    private fun techRow(iconRes: Int, title: String, sub: String, chips: List<String>): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(settingIcon(iconRes))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.bodyText(c, title, 15f, Ui.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
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
