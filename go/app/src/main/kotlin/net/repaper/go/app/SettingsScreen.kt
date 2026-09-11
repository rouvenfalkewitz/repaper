package net.repaper.go.app

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import net.repaper.go.app.Ui.dp

/** The Settings tab — placeholder while the nav is verified; ported next. */
class SettingsScreen(private val c: AppCompatActivity) : Screen {
    override val view: View by lazy {
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        root.addView(Ui.wordmark(c, "Settings"))
        root.addView(Ui.bodyText(c, "settings tab", 14f).apply { setPadding(0, dp(16), 0, 0) })
        ScrollView(c).apply { addView(root); setBackgroundColor(Ui.BG) }
    }
    override fun onShow() {}
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
