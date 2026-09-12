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

    /** A tab-screen title in the CI letterforms (Archivo wght 800 / wdth 125, uppercase) —
     *  the same face as the RE|PAPER GO lockup and the GO button. */
    fun wordmark(c: Context, text: String) = displayText(c, text.uppercase(), 26f, TEXT, weight = 800, width = 125).apply {
        letterSpacing = -0.01f
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

    // e-paper + signal tokens (tokens.css)
    const val EPAPER_PANEL = 0xFFE9EBE6.toInt()
    const val EPAPER_BEZEL = 0xFF1C2320.toInt()
    const val EPAPER_RED = 0xFFC8102E.toInt()
    const val EPAPER_YELLOW = 0xFFF2C400.toInt()
    const val INK = 0xFF131614.toInt()
    const val BLUE = 0xFF4D8DFF.toInt()
    const val RED_TINT = 0xFF3A1512.toInt()
    const val AMBER_TINT = 0xFF3A2A08.toInt()
    const val BLUE_TINT = 0xFF102340.toInt()

    /** The Dock's ringwrap: rounded square, carbon gradient, hairline border — the device outcut. */
    fun ringBox(c: Context, sizeDp: Int): android.widget.FrameLayout = android.widget.FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(SURFACE_2, BG)).apply {
            cornerRadius = c.dp(sizeDp / 4).toFloat()
            setStroke(c.dp(1), BORDER_STRONG)
        }
        layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp))
    }

    /** Status pill, Dock anatomy: dot + Archivo caps, tinted per state; the dot blinks like the LED. */
    fun pill(c: Context, text: String, fg: Int, bg: Int, blinkMs: Long = 0, check: Boolean = false): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 999f }
            setPadding(c.dp(10), c.dp(4), c.dp(10), c.dp(4))
            if (check) {
                addView(TextView(c).apply { this.text = "✓"; textSize = 12f; setTextColor(fg); setPadding(0, 0, c.dp(6), 0) })
            } else {
                val d = android.view.View(c).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fg) }
                    layoutParams = LinearLayout.LayoutParams(c.dp(7), c.dp(7)).apply { rightMargin = c.dp(7) }
                }
                addView(d)
                if (blinkMs > 0) {
                    val r = object : Runnable {
                        override fun run() {
                            if (!d.isAttachedToWindow) return
                            d.alpha = if (d.alpha > 0.5f) 0.1f else 1f
                            d.postDelayed(this, blinkMs)
                        }
                    }
                    d.postDelayed(r, blinkMs)
                }
            }
            addView(displayText(c, text.uppercase(), 11f, fg, weight = 700, width = 112).apply { letterSpacing = 0.08f })
        }

    /** Protocol chips (the Dock's AirPrint · IPP row). */
    fun chip(c: Context, text: String) = monoText(c, text, 11f, TEXT_3).apply {
        background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT); cornerRadius = 999f; setStroke(c.dp(1), BORDER)
        }
        setPadding(c.dp(9), c.dp(3), c.dp(9), c.dp(3))
    }

    /** E-paper frame: carbon bezel around the panel — sheets are shown as sheets. */
    fun frame(c: Context, panel: android.view.View): LinearLayout = LinearLayout(c).apply {
        background = GradientDrawable().apply {
            setColor(EPAPER_BEZEL); cornerRadius = c.dp(10).toFloat(); setStroke(c.dp(1), BORDER_STRONG)
        }
        setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
        addView(panel)
    }

    /** The Dock's palette dots: which inks this sheet speaks. */
    fun palDots(c: Context, palette: String): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        val W = 0xFFFFFFFF.toInt(); val B = 0xFF000000.toInt()
        val colors = when (palette) {
            "BWR" -> intArrayOf(W, B, EPAPER_RED)
            "BWRY" -> intArrayOf(W, B, EPAPER_RED, EPAPER_YELLOW)
            "BWGBRY" -> intArrayOf(W, B, EPAPER_RED, EPAPER_YELLOW, 0xFF2255DD.toInt(), 0xFF1FA84D.toInt())
            "7COLOR" -> intArrayOf(W, B, EPAPER_RED, EPAPER_YELLOW, 0xFF2255DD.toInt(), 0xFF1FA84D.toInt(), 0xFFEE7712.toInt())
            "GRAY4", "GRAY16" -> intArrayOf(W, 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), B)
            else -> intArrayOf(W, B)
        }
        for (col in colors) addView(android.view.View(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(c.dp(1), BORDER_STRONG) }
            layoutParams = LinearLayout.LayoutParams(c.dp(10), c.dp(10)).apply { leftMargin = c.dp(3) }
        })
    }

    /** A soft radial glow — the Android answer to iOS's `shadow(color: accent, radius:)`.
     *  A view painted with a radial gradient from the colour out to transparent; place it
     *  BEHIND a round element (larger than it) to make the accent bloom around it. */
    fun glowHalo(c: Context, color: Int = ACCENT, intensity: Float = 0.6f): android.view.View =
        android.view.View(c).apply {
            background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: android.graphics.Canvas) {
                    val r = minOf(bounds.width(), bounds.height()) / 2f
                    if (r <= 0) return
                    val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
                    val core = (color and 0x00FFFFFF) or ((intensity.coerceIn(0f, 1f) * 255).toInt() shl 24)
                    paint.shader = android.graphics.RadialGradient(cx, cy, r,
                        intArrayOf(core, color and 0x00FFFFFF),
                        floatArrayOf(0.30f, 1f), android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx, cy, r, paint)
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }

    /** Tint a view's elevation shadow in the accent — a subtle glow under accent buttons/cards
     *  (iOS's `shadow(color: accent.opacity(0.3))`). Needs a set elevation; API 28+. */
    fun accentShadow(v: android.view.View, elevationDp: Int = 8, color: Int = ACCENT) {
        v.elevation = v.context.dp(elevationDp).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = color
            v.outlineAmbientShadowColor = color
        }
    }

    /** The calm status dot (green = good, amber = waiting/off). */
    fun dot(c: Context, color: Int) = android.view.View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(c.dp(8), c.dp(8)).apply {
            gravity = Gravity.CENTER_VERTICAL; rightMargin = c.dp(8)
        }
    }
}
