package net.repaper.go.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.repaper.go.app.Ui.dp

/** Our own fade-in / slide-up panels — the Android answer to iOS's addOverlay / configureOverlay /
 *  infoOverlay. They dock into the Activity's content frame (above the bottom bar), dim the
 *  screen behind, and — for bottom sheets — follow a downward drag and let go past a threshold,
 *  exactly like the SwiftUI panels. Never a system AlertDialog where iOS shows its own panel. */
object Overlays {

    private fun contentRoot(act: Activity): FrameLayout =
        act.window.decorView.findViewById(android.R.id.content)

    /** A bottom drawer: rounded carbon panel that springs up from the bottom edge, floating with
     *  a hairline margin, a grabber on top, and drag-to-dismiss. `build` fills the content column;
     *  call the supplied `dismiss` to close it (a row that acts, then closes). */
    fun bottomSheet(act: Activity, build: (LinearLayout, () -> Unit) -> Unit) {
        val root = contentRoot(act)
        val layer = FrameLayout(act)

        val scrim = View(act).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
        layer.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = DragPanel(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE); cornerRadius = act.dp(22).toFloat(); setStroke(act.dp(1), Ui.BORDER)
            }
            elevation = act.dp(24).toFloat()
            setPadding(act.dp(20), act.dp(14), act.dp(20), act.dp(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = act.dp(10); rightMargin = act.dp(10); bottomMargin = act.dp(10)
            }
        }
        panel.addView(grabber(act))
        layer.addView(panel)

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        var dismissing = false
        val dismiss = {
            if (!dismissing) {
                dismissing = true
                scrim.animate().alpha(0f).setDuration(180).start()
                panel.animate().translationY(panel.height.toFloat() + act.dp(20)).alpha(0f)
                    .setDuration(200).withEndAction { root.removeView(layer) }.start()
            }
        }
        build(panel, dismiss)
        scrim.setOnClickListener { dismiss() }

        // spring up once measured
        panel.post {
            panel.translationY = panel.height.toFloat()
            scrim.animate().alpha(1f).setDuration(200).start()
            panel.animate().translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f)).start()
        }
        // drag-to-dismiss: DragPanel intercepts a downward drag (taps still reach children)
        panel.onDismiss = dismiss
    }

    /** A panel that fades in from the top over a scrim — iOS's infoOverlay. `topInset` clears the
     *  header so it reads as dropping from behind it. */
    fun topPanel(act: Activity, topInsetDp: Int = 74, build: (LinearLayout, () -> Unit) -> Unit) {
        val root = contentRoot(act)
        val layer = FrameLayout(act)

        val scrim = View(act).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
        layer.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE); cornerRadius = act.dp(22).toFloat(); setStroke(act.dp(1), Ui.BORDER)
            }
            elevation = act.dp(24).toFloat()
            setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(20))
            alpha = 0f; translationY = act.dp(-16).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = act.dp(20); rightMargin = act.dp(20); topMargin = act.dp(topInsetDp)
            }
        }
        layer.addView(panel)
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        var dismissing = false
        val dismiss = {
            if (!dismissing) {
                dismissing = true
                scrim.animate().alpha(0f).setDuration(180).start()
                panel.animate().alpha(0f).translationY(act.dp(-16).toFloat())
                    .setDuration(180).withEndAction { root.removeView(layer) }.start()
            }
        }
        build(panel, dismiss)
        scrim.setOnClickListener { dismiss() }
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun grabber(act: Activity): View = View(act).apply {
        background = GradientDrawable().apply { setColor(Ui.BORDER_STRONG); cornerRadius = 999f }
        layoutParams = LinearLayout.LayoutParams(act.dp(40), act.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = act.dp(14)
        }
    }
}

/** A bottom-sheet panel that follows a downward drag and lets go past a threshold — the drag
 *  the grabber promises. It INTERCEPTS the gesture (onInterceptTouchEvent) only once the finger
 *  clearly moves down, so buttons and fields inside still receive their taps. */
private class DragPanel(c: android.content.Context) : LinearLayout(c) {
    var onDismiss: (() -> Unit)? = null
    private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; downX = e.rawX }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (dy > slop && dy > kotlin.math.abs(e.rawX - downX)) return true   // a downward drag → take over
            }
        }
        return false
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> { translationY = kotlin.math.max(0f, e.rawY - downY); return true }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (translationY > dpF(110)) onDismiss?.invoke()
                else animate().translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
                return true
            }
        }
        return false
    }

    private fun dpF(v: Int) = v * resources.displayMetrics.density
}

/** The palette as a single dot — a circle sliced into the sheet's inks, like iOS's paletteDot
 *  (an AngularGradient of hard colour stops, starting at the top). */
class PalDot(c: android.content.Context, palette: String, private val sizeDp: Int = 14) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.BORDER_STRONG
        strokeWidth = (1 * c.resources.displayMetrics.density)
    }
    private val cols: IntArray = colorsFor(palette)

    init {
        val px = (sizeDp * c.resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(px, px)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f
        val n = cols.size
        // duplicate each stop so colours meet at hard edges (no blending), like the iOS stops
        val shaderCols = IntArray(n * 2); val pos = FloatArray(n * 2)
        for (i in 0 until n) {
            shaderCols[i * 2] = cols[i]; shaderCols[i * 2 + 1] = cols[i]
            pos[i * 2] = i.toFloat() / n; pos[i * 2 + 1] = (i + 1).toFloat() / n
        }
        val sweep = SweepGradient(cx, cy, shaderCols, pos)
        sweep.setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })   // start at 12 o'clock
        fill.shader = sweep
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
        canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
    }

    companion object {
        fun colorsFor(palette: String): IntArray {
            val W = Ui.EPAPER_PANEL; val B = Ui.INK
            val mapped = palette.uppercase().mapNotNull { ch ->
                when (ch) {
                    'B' -> B; 'W' -> W; 'R' -> Ui.EPAPER_RED; 'Y' -> Ui.EPAPER_YELLOW
                    else -> null
                }
            }
            return if (mapped.isEmpty()) intArrayOf(B, W) else mapped.toIntArray()
        }
    }
}
