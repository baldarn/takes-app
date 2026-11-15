package app.takes.probe.layers

import android.app.Activity
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * The preview scenario's screen: one TextureView per player, one above the other, as the app's
 * preview lays its decks (a PlayerView with a texture view each). The players themselves are made
 * by [LayersProbe]. [interrupted] tells whether the screen left the front since it was last reset.
 */
class PreviewActivity : Activity() {
    val views = mutableListOf<TextureView>()

    @Volatile var interrupted = false
    @Volatile var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val count = intent.getIntExtra("players", 3)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(count) {
            val view = TextureView(this)
            views += view
            column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        interrupted = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) interrupted = true
    }
}
