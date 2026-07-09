package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.consent.ConsentManager
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

    /** Consent → init → splash ad → Main. */
    private fun startAdFlow() {
        val testHash = if (BuildConfig.DEBUG) SampleApp.TEST_DEVICE_HASH else null
        ConsentManager.getInstance(this, testHash).gatherConsent(this, forceEea = testHash != null) {
            NextGenAds.initialize(this, APP_ID, SampleApp.TEST_DEVICE_IDS) {
                handler.removeCallbacksAndMessages(null) // init done: the ad flow now owns completion
                SplashAdGate.show(
                    activity = this,
                    coldStart = coldStart,
                    interstitialUnitId = INTERSTITIAL_UNIT,
                    appOpenUnitId = SampleApp.APP_OPEN_UNIT,
                    // Show an interstitial on the splash for BOTH cold and warm/hot starts. An
                    // interstitial uses the plain "Loading ad…" cover, so the branded "Welcome back"
                    // dialog is never shown here. Flip either to SplashAdType.APP_OPEN for app-open.
                    coldStartAdType = SplashAdType.INTERSTITIAL,
                    warmStartAdType = SplashAdType.APP_OPEN,
                    onComplete = ::goToMain,
                )
            }
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
        // Google's official sample / test ids — replace with your own for release.
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"

        const val KEY_COLD = "ngad_splash_cold_start"
        const val WATCHDOG_MS = 10_000L
    }
}
