package net.repaper.go.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import net.repaper.go.R

/** The Paper design system for Android — the same tokens as brand/tokens.css, applied
 *  through factories so every screen styles alike (never raw hex in screens). */
object Ui {
    // carbon scale + accent, verbatim from tokens.css
    const val BG = 0xFF0C100F.toInt()          // --carbon
    const val SURFACE = 0xFF151A18.toInt()     // --carbon-2
    const val SURFACE_2 = 0xFF1C2320.toInt()   // --carbon-3 (bezels)
    const val BORDER = 0xFF273029.toInt()      // --carbon-4
    const val BORDER_STRONG = 0xFF364139.toInt()
    const val TEXT = 0xFFEFF1EE.toInt()
    const val TEXT_2 = 0xFF9AA5A0.toInt()
    const val TEXT_3 = 0xFF5E6A64.toInt()
    const val ACCENT = 0xFF1EE3A5.toInt()      // --re-400
    const val ON_ACCENT = BG
    const val ACCENT_TINT = 0xFF0A2E24.toInt() // --re-900
    const val RED = 0xFFFF5C4D.toInt()
    const val AMBER = 0xFFFFB020.toInt()

    private var archivo: Typeface? = null
    private var figtree: Typeface? = null

    fun display(c: Context): Typeface = archivo ?: ResourcesCompat.getFont(c, R.font.archivo)!!.also { archivo = it }
    fun body(c: Context): Typeface = figtree ?: ResourcesCompat.getFont(c, R.font.figtree)!!.also { figtree = it }

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Display-face text: Archivo, weight + width via variable axes (like the web's font-stretch). */
    fun displayText(c: Context, text: String, sizeSp: Float, color: Int = TEXT, weight: Int = 700, width: Int = 100) =
        TextView(c).apply {
            this.text = text; textSize = sizeSp; setTextColor(color)
            typeface = display(c)
            fontVariationSettings = "'wght' $weight, 'wdth' $width"
            letterSpacing = if (sizeSp >= 18) -0.02f else 0f
        }

    fun bodyText(c: Context, text: String, sizeSp: Float = 15f, color: Int = TEXT_2) =
        TextView(c).apply { this.text = text; textSize = sizeSp; setTextColor(color); typeface = body(c) }

    fun monoText(c: Context, text: String, sizeSp: Float = 12f, color: Int = TEXT_3) =
        TextView(c).apply { this.text = text; textSize = sizeSp; setTextColor(color); typeface = Typeface.MONOSPACE }

    /** Uppercase micro-header, the web UI's section label. */
    fun sectionHeader(c: Context, text: String) = displayText(c, text.uppercase(), 11f, TEXT_3, weight = 700, width = 112).apply {
        letterSpacing = 0.12f
        setPadding(0, c.dp(22), 0, c.dp(8))
    }

    private fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT, radiusDp: Int = 14, c: Context? = null, strokeW: Int = 1) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = (radiusDp * (c?.resources?.displayMetrics?.density ?: 3f))
            if (stroke != Color.TRANSPARENT) setStroke((strokeW * (c?.resources?.displayMetrics?.density ?: 3f)).toInt(), stroke)
        }

    /** A card: surface, hairline border, 14dp radius. */
    fun card(c: Context, ripple: Boolean = false): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val base = rounded(SURFACE, BORDER, 14, c)
        background = if (ripple) RippleDrawable(ColorStateList.valueOf(SURFACE_2), base, null) else base
        setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        isClickable = ripple; isFocusable = ripple
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = c.dp(8); layoutParams = lp
    }

    /** Pill buttons: primary = accent fill, outline = hairline. */
    fun button(c: Context, label: String, primary: Boolean, onTap: () -> Unit) = TextView(c).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        typeface = body(c); setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) ON_ACCENT else TEXT_2)
        val base = if (primary) rounded(ACCENT, radiusDp = 12, c = c)
                   else rounded(Color.TRANSPARENT, BORDER_STRONG, 12, c)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else SURFACE_2), base, rounded(Color.WHITE, radiusDp = 12, c = c))
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    /** The calm status dot (green = good, amber = waiting/off). */
    fun dot(c: Context, color: Int) = android.view.View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(c.dp(8), c.dp(8)).apply {
            gravity = Gravity.CENTER_VERTICAL; rightMargin = c.dp(8)
        }
    }
}
