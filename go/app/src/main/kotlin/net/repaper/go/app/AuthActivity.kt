package net.repaper.go.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.repaper.go.R
import net.repaper.go.app.Ui.dp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** The gate: a RePaper Go belongs to a RePaper account. Two stages, like iOS — credentials,
 *  then (if the account has it) a dedicated two-factor screen with a shield and a segmented
 *  code entry. The footer server name is the on-prem entry point. */
class AuthActivity : AppCompatActivity() {
    private enum class Stage { CREDENTIALS, TWO_FACTOR }
    private var stage = Stage.CREDENTIALS

    private lateinit var stageHost: FrameLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private var code = ""
    private var busy = false

    // per-stage pieces we update
    private lateinit var credNote: TextView
    private lateinit var twoNote: TextView
    private lateinit var signInBtn: LoadingButton
    private lateinit var verifyBtn: LoadingButton
    private var codeField: CodeField? = null

    private val cookies = HashMap<String, List<Cookie>>()
    private val http = OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
            cookies[url.host] = (cookies[url.host] ?: emptyList()).filter { c -> list.none { it.name == c.name } } + list
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host] ?: emptyList()
    }).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        outer.addView(ImageView(this).apply {
            setImageResource(R.drawable.lockup_go); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        })
        stageHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        outer.addView(stageHost)

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(outer) })
        stageHost.addView(credentialsView())
        if (intent.getBooleanExtra("twofa_preview", false) ||
            getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("twofa_preview", false)) {   // screenshot hook
            email.setText("you@example.com"); stageHost.removeAllViews(); stageHost.addView(twoFactorView()); stage = Stage.TWO_FACTOR
        }
        CloudAgent.get(this).start()   // device channel connects meanwhile, so claiming is instant
    }

    // ── stage 1: credentials ─────────────────────────────────────────────────────
    private fun credentialsView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.bodyText(this@AuthActivity, "Sign in to your RePaper account to print from this phone.", 14f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(20))
        })
        val (emailField, emailEdit) = authField(R.drawable.ic_mail, "Email",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val (pwField, pwEdit) = authField(R.drawable.ic_lock, "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { signIn() }
        email = emailEdit; password = pwEdit
        addView(emailField); addView(spacer(10)); addView(pwField)

        credNote = note()
        addView(credNote)

        signInBtn = LoadingButton(this@AuthActivity, "Sign in") { signIn() }
        addView(signInBtn.view.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
        addView(Ui.button(this@AuthActivity, "Create account", primary = false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Prefs.cloudBase(this@AuthActivity)}/register")))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        addView(Ui.monoText(this@AuthActivity, cloudLabel(), 11f).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
            setOnClickListener { cloudDialog() }
        })
    }

    // ── stage 2: two-factor ──────────────────────────────────────────────────────
    private fun twoFactorView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        // shield hero
        addView(FrameLayout(this@AuthActivity).apply {
            addView(View(this@AuthActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT_TINT) }
                layoutParams = FrameLayout.LayoutParams(dp(72), dp(72))
            })
            addView(ImageView(this@AuthActivity).apply {
                setImageResource(R.drawable.ic_shield)
                layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { topMargin = dp(8) }
        })
        addView(Ui.displayText(this@AuthActivity, "TWO-STEP VERIFICATION", 14f, Ui.TEXT, weight = 800, width = 125).apply {
            letterSpacing = 0.03f; gravity = Gravity.CENTER
            (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        })
        addView(Ui.bodyText(this@AuthActivity, "Enter the 6-digit code from your authenticator app for ${email.text}.", 14f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), 0)
        })
        val cf = CodeField(this@AuthActivity) { verifyCode() }
        codeField = cf
        addView(cf.view.apply { (layoutParams as? LinearLayout.LayoutParams); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) } })

        twoNote = note()
        addView(twoNote)

        verifyBtn = LoadingButton(this@AuthActivity, "Verify") { verifyCode() }
        addView(verifyBtn.view.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })
        addView(LinearLayout(this@AuthActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(TextView(this@AuthActivity).apply { text = "‹"; textSize = 18f; setTextColor(Ui.TEXT_2); setPadding(0, 0, dp(5), 0) })
            addView(Ui.bodyText(this@AuthActivity, "Back to sign in", 14f, Ui.TEXT_2).apply { setTypeface(typeface, Typeface.BOLD) })
            setPadding(0, dp(16), 0, 0)
            isClickable = true; setOnClickListener { code = ""; showStage(Stage.CREDENTIALS) }
        })
    }

    private fun showStage(s: Stage) {
        stage = s
        val next = if (s == Stage.CREDENTIALS) credentialsView() else twoFactorView()
        next.alpha = 0f
        stageHost.animate().alpha(0f).setDuration(140).withEndAction {
            stageHost.removeAllViews(); stageHost.addView(next); stageHost.alpha = 1f
            next.animate().alpha(1f).setDuration(200).start()
            if (s == Stage.TWO_FACTOR) codeField?.focus()
        }.start()
    }

    // ── an auth field: leading glyph + input, border & glyph light up on focus ────
    private fun authField(iconRes: Int, hint: String, type: Int, onSubmit: (() -> Unit)? = null): Pair<View, EditText> {
        val icon = ImageView(this).apply {
            setImageResource(iconRes); setColorFilter(Ui.TEXT_3)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(11) }
        }
        val edit = EditText(this).apply {
            this.hint = hint; inputType = type
            setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT_3); typeface = Ui.body(this@AuthActivity); textSize = 15f
            background = null; setPadding(0, 0, 0, 0)
            if (onSubmit != null) {
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                setOnEditorActionListener { _, _, _ -> onSubmit(); true }
            }
        }
        val bg = GradientDrawable().apply { setColor(Ui.BG); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Ui.BORDER_STRONG) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg; setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(icon); addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        edit.setOnFocusChangeListener { _, focused ->
            bg.setStroke(dp(if (focused) 2 else 1), if (focused) Ui.ACCENT else Ui.BORDER_STRONG)
            icon.setColorFilter(if (focused) Ui.ACCENT else Ui.TEXT_3)
        }
        return container to edit
    }

    private fun note() = Ui.bodyText(this, "", 13f, Ui.AMBER).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0); visibility = View.GONE }
    private fun setNote(t: TextView, msg: String, color: Int) {
        t.text = msg; t.setTextColor(color); t.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun cloudLabel(): String {
        val base = Prefs.cloudBase(this)
        return if (base == Prefs.DEFAULT_CLOUD) "RePaper Cloud" else base.removePrefix("https://").removePrefix("http://")
    }
    private fun cloudDialog() {
        val input = EditText(this).apply { setText(Prefs.cloudBase(this@AuthActivity)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("Cloud server")
            .setMessage("Only for self-hosted RePaper Cloud installations.").setView(input)
            .setPositiveButton("Save") { _, _ -> Prefs.setCloudBase(this, input.text.toString()); recreate() }
            .setNeutralButton("Use RePaper Cloud") { _, _ -> Prefs.setCloudBase(this, ""); recreate() }
            .setNegativeButton("Cancel", null).show()
    }

    // ── the sign-in flow ─────────────────────────────────────────────────────────
    private fun signIn() {
        val em = email.text.toString().trim(); val pw = password.text.toString()
        if (em.isEmpty() || pw.isEmpty()) { setNote(credNote, "Email and password, please.", Ui.AMBER); return }
        setNote(credNote, "", Ui.AMBER); busy = true; signInBtn.loading(true)
        lifecycleScope.launch {
            try {
                val base = Prefs.cloudBase(this@AuthActivity)
                val r = withContext(Dispatchers.IO) { post("$base/api/login", JSONObject().put("email", em).put("password", pw)) }
                if (r.optBoolean("twofa")) { busy = false; signInBtn.loading(false); code = ""; showStage(Stage.TWO_FACTOR); return@launch }
                if (!r.optBoolean("ok")) throw Exception(r.optString("error", "sign in failed"))
                activate(base)
            } catch (e: Exception) { busy = false; signInBtn.loading(false); setNote(credNote, e.message ?: "sign in failed", Ui.RED) }
        }
    }

    private fun verifyCode() {
        if (code.length < 6 || busy) return
        setNote(twoNote, "", Ui.AMBER); busy = true; verifyBtn.loading(true)
        lifecycleScope.launch {
            try {
                val base = Prefs.cloudBase(this@AuthActivity)
                val r = withContext(Dispatchers.IO) { post("$base/api/login/2fa", JSONObject().put("code", code.trim())) }
                if (!r.optBoolean("ok")) throw Exception(r.optString("error", "that code didn't match"))
                activate(base)
            } catch (e: Exception) { busy = false; verifyBtn.loading(false); setNote(twoNote, e.message ?: "verification failed", Ui.RED); code = ""; codeField?.clear() }
        }
    }

    private suspend fun activate(base: String) {
        try {
            val approved = claimSelf(base)
            Prefs.setClaimed(this, true); Prefs.setApproved(this, approved)
            startActivity(Intent(this, if (approved) ShellActivity::class.java else PendingActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        } catch (e: Exception) {
            busy = false; signInBtn.loading(false); if (::verifyBtn.isInitialized) verifyBtn.loading(false)
            setNote(if (stage == Stage.TWO_FACTOR) twoNote else credNote, e.message ?: "claiming failed", Ui.RED)
        }
    }

    private suspend fun claimSelf(base: String): Boolean = withContext(Dispatchers.IO) {
        val agent = CloudAgent.get(this@AuthActivity)
        for (i in 0 until 30) { if (agent.state == "online") break; delay(500) }
        if (agent.state != "online") throw Exception("can't reach the cloud from this phone — check the connection")
        if (agent.claimed) return@withContext agent.approved
        var lastErr = "claiming failed"
        for (attempt in 0 until 3) {
            val r = post("$base/api/claim", JSONObject().put("code", agent.claimCode))
            if (r.optBoolean("ok")) return@withContext r.optBoolean("approved", true)
            lastErr = r.optString("error", lastErr); delay(1500)
        }
        throw Exception(lastErr)
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val resp = http.newCall(Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        resp.use { return JSONObject(it.body?.string()?.ifEmpty { "{}" } ?: "{}") }
    }

    // ── a primary button that shows a spinner while working (iOS UiButton loading) ──
    private inner class LoadingButton(c: Context, label: String, onTap: () -> Unit) {
        private val text = TextView(c).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = Ui.body(c)
            setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.ON_ACCENT)
        }
        private val spinner = ProgressBar(c).apply {
            isIndeterminate = true; visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ON_ACCENT)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        val view: View = FrameLayout(c).apply {
            background = GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = dp(12).toFloat() }
            setPadding(dp(16), dp(13), dp(16), dp(13))
            addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(spinner)
            isClickable = true; setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            Ui.accentShadow(this, 8)   // a soft accent glow under the button
        }
        fun loading(b: Boolean) {
            spinner.visibility = if (b) View.VISIBLE else View.GONE
            text.visibility = if (b) View.INVISIBLE else View.VISIBLE
            view.isClickable = !b
        }
    }

    // ── a segmented 6-box code entry: a hidden field captures digits, boxes show them ──
    private inner class CodeField(c: Context, val onComplete: () -> Unit) {
        private val boxes = (0 until 6).map {
            TextView(c).apply {
                gravity = Gravity.CENTER; textSize = 24f; setTextColor(Ui.TEXT); typeface = Ui.display(c)
                fontVariationSettings = "'wght' 700"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(56)).apply { if (it > 0) leftMargin = dp(9) }
            }
        }
        private val hidden = EditText(c).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setTextColor(0x00000000); setBackgroundColor(0x00000000); isCursorVisible = false
        }
        val view: View = FrameLayout(c).apply {
            addView(LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; boxes.forEach { addView(it) } })
            addView(hidden, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            isClickable = true; setOnClickListener { focus() }
        }
        init {
            render()
            hidden.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    code = s?.toString()?.filter { it.isDigit() }?.take(6) ?: ""
                    render()
                    if (code.length == 6) { hide(); onComplete() }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        fun focus() { hidden.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(hidden, InputMethodManager.SHOW_IMPLICIT) }
        private fun hide() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(hidden.windowToken, 0) }
        fun clear() { hidden.setText(""); code = ""; render() }
        private fun render() {
            for (i in boxes.indices) {
                val b = boxes[i]
                b.text = if (i < code.length) code[i].toString() else ""
                val active = i == code.length
                b.background = GradientDrawable().apply {
                    setColor(Ui.BG); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (active) 2 else 1), if (active) Ui.ACCENT else Ui.BORDER_STRONG)
                }
                if (active) Ui.accentShadow(b, 6) else { b.elevation = 0f }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
