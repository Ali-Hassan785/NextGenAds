package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.alihassan.nextgenads.splash.SplashAdGate
import com.alihassan.nextgenads.splash.SplashAdType

/**
 * Splash: gather consent → initialize the SDK → show one splash ad → go to [MainActivity].
 *
 * The splash-ad choice lives entirely in [SplashAdGate]: an **interstitial** on a cold start and an
 * **app-open** on a warm / hot relaunch. A watchdog guarantees we always leave the splash even if
 * consent/init stalls.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
        private var coldStart = true
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Resolve cold vs warm once; keep it stable across a config-change recreation.
        coldStart = savedInstanceState?.getBoolean(KEY_COLD) ?: SplashAdGate.consumeColdStart()

        // Safety net: never trap the user on the splash if consent/init hangs (e.g. no network).
        handler.postDelayed(::goToMain, WATCHDOG_MS)

        if (savedInstanceState == null) startAdFlow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COLD, coldStart)
    }

    /** Consent → init → splash ad → Main. Consent + init are centralised in [AdsBootstrap]. */
    private fun startAdFlow() {
        AdsBootstrap.gatherConsentThenInitialize(this) {
            handler.removeCallbacksAndMessages(null) // init done: the ad flow now owns completion
            SplashAdGate.show(
                activity = this,
                coldStart = coldStart,
                // Splash-only units, separate from the in-app ones — see AdUnits.
                interstitialUnitId = AdUnits.SPLASH_INTERSTITIAL,
                appOpenUnitId = AdUnits.SPLASH_APP_OPEN,
                // Show an interstitial on the splash for BOTH cold and warm/hot starts. An
                // interstitial uses the plain "Loading ad…" cover, so the branded "Welcome back"
                // dialog is never shown here. Flip either to SplashAdType.APP_OPEN for app-open.
                coldStartAdType = SplashAdType.INTERSTITIAL,
                warmStartAdType = SplashAdType.APP_OPEN,
                onComplete = ::goToMain,
            )
        }
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded before navigating: drop the watchdog so it can't start Main from the background.
        if (!navigated) handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val KEY_COLD = "ngad_splash_cold_start"
        const val WATCHDOG_MS = 10_000L
    }
}
