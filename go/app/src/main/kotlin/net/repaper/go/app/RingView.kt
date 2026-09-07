package net.repaper.go.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** The light ring, on screen — behaves exactly like the physical one (brand guidelines §06):
 *  Ready = breathe dim (30 %), Job waiting = blink 1 Hz full, Printing = blink 3 Hz,
 *  Printed = solid, Failed = blink 1 Hz red, Setup = blink 1 Hz Signal Blue. */
class RingView(context: Context) : View(context) {
    enum class Led { READY, WAIT, BUSY, DONE, ERR, SETUP }

    var led: Led = Led.READY
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 40) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 40) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = System.currentTimeMillis() / 1000.0
        val color = when (led) {
            Led.ERR -> 0xFFFF5C4D.toInt()          // Label Red
            Led.SETUP -> 0xFF4D8DFF.toInt()        // Signal Blue
            else -> Ui.ACCENT                       // Re Green
        }
        val alpha = when (led) {
            Led.READY -> 0.18 + 0.30 * (0.5 + 0.5 * Math.sin(t / 3.0 * 2 * Math.PI))   // breathe, dim
            Led.WAIT, Led.ERR, Led.SETUP -> if (t % 1.0 < 0.5) 1.0 else 0.08           // blink 1 Hz
            Led.BUSY -> if (t % 0.333 < 0.166) 1.0 else 0.08                           // blink 3 Hz
            Led.DONE -> 1.0                                                            // solid
        }
        val cx = width / 2f; val cy = height / 2f
        val r = Math.min(width, height) * 0.36f
        val stroke = r * 0.30f
        glow.color = color; glow.alpha = (alpha * 70).toInt(); glow.strokeWidth = stroke * 2.2f
        paint.color = color; paint.alpha = (alpha * 255).toInt(); paint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, glow)
        canvas.drawCircle(cx, cy, r, paint)
    }
}
