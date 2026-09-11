package net.repaper.go.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** The gate: a RePaper Go belongs to a RePaper account. Signing in claims this phone into
 *  your fleet automatically — no claim codes. The small gear reconfigures the cloud URL
 *  for on-prem installations. */
class AuthActivity : AppCompatActivity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var code: EditText
    private lateinit var codeRow: LinearLayout
    private lateinit var note: android.widget.TextView

    // session cookies live only as long as the sign-in — afterwards the device channel (id+secret) carries everything
    private val cookies = HashMap<String, List<Cookie>>()
    private val http = OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
            cookies[url.host] = (cookies[url.host] ?: emptyList()).filter { c -> list.none { it.name == c.name } } + list
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host] ?: emptyList()
    }).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(24), dp(20), dp(24), dp(28)); gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.lockup_go)   // the real product lockup
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        })

        root.addView(Ui.bodyText(this, "Sign in with your RePaper account — this phone joins your fleet automatically.", 14f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(18))
        })

        email = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(email); root.addView(spacer(10)); root.addView(password)

        code = field("Code from your authenticator app", InputType.TYPE_CLASS_NUMBER)
        codeRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
            addView(spacer(10)); addView(code) }
        root.addView(codeRow)

        note = Ui.bodyText(this, "", 13f, Ui.AMBER).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        root.addView(note)

        root.addView(Ui.button(this, "Sign in", primary = true) { signIn() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        })
        root.addView(Ui.button(this, "Create account", primary = false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Prefs.cloudBase(this)}/register")))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        })

        // the footer line doubles as the on-prem entry point: tapping the server name
        // opens the reconfigure dialog — invisible to everyone who doesn't need it
        root.addView(Ui.monoText(this, cloudLabel(), 11f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            setOnClickListener { cloudDialog() }
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        CloudAgent.get(this).start()     // the device channel connects meanwhile, so claiming is instant
    }

    private fun cloudLabel(): String {
        val base = Prefs.cloudBase(this)
        return if (base == Prefs.DEFAULT_CLOUD) "RePaper Cloud" else base.removePrefix("https://").removePrefix("http://")
    }

    /** On-prem installations point the app at their own server here. */
    private fun cloudDialog() {
        val input = EditText(this).apply {
            setText(Prefs.cloudBase(this@AuthActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Cloud server")
            .setMessage("Only for self-hosted RePaper Cloud installations.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> Prefs.setCloudBase(this, input.text.toString()); recreate() }
            .setNeutralButton("Use RePaper Cloud") { _, _ -> Prefs.setCloudBase(this, ""); recreate() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun signIn() {
        val em = email.text.toString().trim(); val pw = password.text.toString()
        if (em.isEmpty() || pw.isEmpty()) { note.text = "Email and password, please."; return }
        note.text = "Signing in…"
        lifecycleScope.launch {
            try {
                val base = Prefs.cloudBase(this@AuthActivity)
                if (codeRow.visibility == android.view.View.VISIBLE) {
                    val r = withContext(Dispatchers.IO) { post("$base/api/login/2fa", JSONObject().put("code", code.text.toString().trim())) }
                    if (!r.optBoolean("ok")) throw Exception(r.optString("error", "that code didn't match"))
                } else {
                    val r = withContext(Dispatchers.IO) { post("$base/api/login", JSONObject().put("email", em).put("password", pw)) }
                    if (r.optBoolean("twofa")) {
                        codeRow.visibility = android.view.View.VISIBLE
                        note.text = "Enter the code from your authenticator app."
                        return@launch
                    }
                    if (!r.optBoolean("ok")) throw Exception(r.optString("error", "sign in failed"))
                }
                // signing in IS joining the fleet (decision A) — no repeated prompt.
                // A member simply lands on the "waiting for approval" screen.
                activate(base)
            } catch (e: Exception) { note.text = e.message ?: "sign in failed" }
        }
    }

    private fun activate(base: String) {
        note.text = "Adding this phone to your fleet…"
        lifecycleScope.launch {
            try {
                val approved = claimSelf(base)
                Prefs.setClaimed(this@AuthActivity, true)
                Prefs.setApproved(this@AuthActivity, approved)
                startActivity(Intent(this@AuthActivity,
                    if (approved) ShellActivity::class.java else PendingActivity::class.java))
                finish()
            } catch (e: Exception) { note.text = e.message ?: "claiming failed" }
        }
    }

    /** The app knows its own claim code — claiming is one call once the device channel is up. */
    private suspend fun claimSelf(base: String): Boolean = withContext(Dispatchers.IO) {
        val agent = CloudAgent.get(this@AuthActivity)
        for (i in 0 until 30) { if (agent.state == "online") break; delay(500) }   // device row must exist
        if (agent.state != "online") throw Exception("can't reach the cloud from this phone — check the connection")
        if (agent.claimed) return@withContext agent.approved   // re-login on a device the fleet already knows
        var lastErr = "claiming failed"
        for (attempt in 0 until 3) {
            val r = post("$base/api/claim", JSONObject().put("code", agent.claimCode))
            if (r.optBoolean("ok")) return@withContext r.optBoolean("approved", true)
            lastErr = r.optString("error", lastErr)
            delay(1500)
        }
        throw Exception(lastErr)
    }

    private fun get(url: String): JSONObject {
        val resp = http.newCall(Request.Builder().url(url).get().build()).execute()
        resp.use { return JSONObject(it.body?.string()?.ifEmpty { "{}" } ?: "{}") }
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val resp = http.newCall(Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        resp.use { return JSONObject(it.body?.string()?.ifEmpty { "{}" } ?: "{}") }
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type
        setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT_3)
        typeface = Ui.body(this@AuthActivity)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.BG); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer(h: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
