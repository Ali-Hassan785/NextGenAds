package com.alihassn.nextgenSample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alihassan.nextgenads.nativead.NativeAdPreloader
import com.alihassan.nextgenads.nativead.NativeTemplateView

/**
 * Screen **B** of the cross-screen native-preload demo.
 *
 * [MainActivity] (screen A) warms the ad before launching this screen with
 * `NativeAdPreloader.preload(AdUnits.NATIVE)`. Here we just call [NativeAdPreloader.showInto], which:
 *  - binds the preloaded ad **instantly** if it's ready,
 *  - waits on the still-in-flight preload (no duplicate request) if it hasn't landed yet,
 *  - falls back to a fresh load if nothing was preloaded (e.g. this screen was opened directly).
 *
 * The [NativeTemplateView]'s template is set in XML (`app:ngad_template`), so `showInto`'s shimmer
 * matches the final ad. No ad-unit plumbing crosses the screen boundary — the preloader keys its
 * state by ad unit, so screen A and screen B only need to agree on [AdUnits.NATIVE].
 */
class PreloadedNativeActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var nativeView: NativeTemplateView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_preloaded_native)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.preloadedNativeRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.tvPreloadedStatus)
        nativeView = findViewById(R.id.preloadedNative)

        findViewById<Button>(R.id.btnReloadPreloaded).setOnClickListener { show() }

        show()
    }

    /**
     * Binds the ad warmed on the previous screen. If it was ready, this is instant; otherwise
     * `showInto` shows the shimmer and binds when the in-flight (or fallback) load settles.
     */
    private fun show() {
        val ready = NativeAdPreloader.isReady(AdUnits.NATIVE)
        status.text =
            if (ready) "Preloaded ad was ready — binding instantly."
            else "No warm ad yet — waiting on the in-flight preload / fresh load…"
        NativeAdPreloader.showInto(
            nativeView,
            AdUnits.NATIVE,
            onFailed = { status.text = "Native failed to load." },
        )
    }

    override fun onDestroy() {
        // Release the bound ad and drop any parked waiters/held ad for this unit.
        nativeView.destroy()
        NativeAdPreloader.clear(AdUnits.NATIVE)
        super.onDestroy()
    }
}
