package net.repaper.go.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A member's phone between claiming and admin approval: the ring blinks Signal Blue
 *  (the LED language's setup state) and the app stays gated until the fleet says go. */
class PendingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(24), dp(48), dp(24), dp(28)); gravity = Gravity.CENTER_HORIZONTAL
        }

        val ring = RingView(this).apply { led = RingView.Led.SETUP }
        val box = Ui.ringBox(this, 148)
        box.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(box)

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) }
            addView(Ui.pill(this@PendingActivity, "Waiting for approval", Ui.AMBER, Ui.AMBER_TINT, blinkMs = 500))
        })
        root.addView(Ui.displayText(this, "Almost there", 22f, Ui.TEXT, weight = 700).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        val org = CloudAgent.get(this).org ?: "your organisation"
        root.addView(Ui.bodyText(this, "An administrator of $org needs to approve this phone in the fleet console. This page moves on by itself the moment that happens.", 14f).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        CloudAgent.get(this).start()

        // the approval arrives as a push on the device channel — just watch the flag
        lifecycleScope.launch {
            while (true) {
                delay(1500)
                if (!Prefs.isClaimed(this@PendingActivity)) {
                    startActivity(Intent(this@PendingActivity, AuthActivity::class.java)); finish(); return@launch
                }
                if (Prefs.isApproved(this@PendingActivity)) {
                    startActivity(Intent(this@PendingActivity, ShellActivity::class.java)); finish(); return@launch
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
