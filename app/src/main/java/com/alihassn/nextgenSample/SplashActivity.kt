package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.consent.ConsentManager
import com.alihassan.nextgenads.interstitial.SplashAd

/**
 * Launch splash. Gathers consent, initializes the SDK, then shows one full-screen ad **on the
 * splash** — a **splash interstitial** on a cold start (fresh process) or an **app-open** ad on a
 * warm / hot start (relaunch while the process is alive) — before handing off to [MainActivity].
 * A watchdog guarantees we always leave the splash even if consent/init stalls.
 *
 * Cold vs warm/hot is decided by [SampleApp.consumeColdStart] (process-static), so it survives the
 * launcher re-running this activity on a warm relaunch and resets correctly on process death.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false
    private var coldStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch in the process = cold (interstitial); any later launch = warm/hot (app-open).
        // Restored across a config-change recreation so it doesn't flip mid-splash.
        coldStart = if (savedInstanceState != null) {
            savedInstanceState.getBoolean(KEY_COLD, true)
        } else {
            SampleApp.consumeColdStart()
        }
        Log.i(TAG, "onCreate — ${if (coldStart) "COLD start → interstitial" else "WARM/HOT start → app-open"}")

        setContentView(R.layout.activity_splash)
        // Safety net: never trap the user on the splash if consent/init hangs (e.g. no network).
        handler.postDelayed({
            Log.w(TAG, "Watchdog fired (${WATCHDOG_MS}ms) — leaving splash regardless of consent/init")
            goToMain()
        }, WATCHDOG_MS)

        // Re-inflating on recreation shouldn't re-run the ad flow (it's already in flight).
        if (savedInstanceState == null) gatherConsentThenShowAd()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COLD, coldStart)
    }

    private fun gatherConsentThenShowAd() {
        val testDeviceHash = if (BuildConfig.DEBUG) SampleApp.TEST_DEVICE_HASH else null
        val consent = ConsentManager.getInstance(this, testDeviceHash)

        // Ask for consent FIRST, then initialize the ads SDK. On the first launch this presents the
        // UMP form and waits for the user's choice; on later launches it just refreshes consent info
        // (no form unless the requirement changed). Only once consent resolves — granted, not required,
        // or errored — do we initialize and request ads.
        Log.i(TAG, "Gathering UMP consent (forceEea=${testDeviceHash != null})…")
        consent.gatherConsent(this, forceEea = testDeviceHash != null) {
            Log.i(TAG, "Consent resolved (canRequestAds=${consent.canRequestAds}) → initializing ads SDK")
            NextGenAds.initialize(this, APP_ID, SampleApp.TEST_DEVICE_IDS) {
                // Init done. The watchdog only guards a consent/init hang; from here the ad flow owns
                // completion (its own timeout / the ad's dismiss), so cancel the watchdog — otherwise
                // it could fire goToMain while the ad is on screen, launching MainActivity behind it.
                handler.removeCallbacksAndMessages(null)
                if (coldStart) showColdInterstitial() else showWarmAppOpen()
            }
        }
    }

    /** Cold start: show a splash interstitial, then move to Main. */
    private fun showColdInterstitial() {
        Log.i(TAG, "Cold start → showing splash interstitial")
        SplashAd.show(
            activity = this,
            adUnitId = INTERSTITIAL_UNIT,
            minDelayMs = 1_500L,
            timeoutMs = 8_000L,
        ) {
            Log.i(TAG, "Splash interstitial finished (shown / timed out / no-ad) → MainActivity")
            goToMain()
        }
    }

    /**
     * Warm / hot start: show an app-open ad **on the splash**, then move to Main. Uses the same
     * helper the [com.alihassan.nextgenads.appopen.AppOpenAdManager] warms; the manager itself skips
     * this (SplashActivity is on its skip list) so the ad is shown here exactly once.
     */
    private fun showWarmAppOpen() {
        Log.i(TAG, "Warm/hot start → showing app-open on splash")
        AppOpenAds.get(SampleApp.APP_OPEN_UNIT).loadAndShow(
            activity = this,
            timeoutMs = 8_000L,
            onDismiss = {
                Log.i(TAG, "App-open finished (shown / timed out / no-ad) → MainActivity")
                goToMain()
            },
        )
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded before navigating: cancel the watchdog so it can't run goToMain from the
        // background (a background Activity start would be blocked, stranding the task). The pending
        // ad flow resumes/completes on return, or the next foreground is handled normally.
        if (!navigated) handler.removeCallbacksAndMessages(null)
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        Log.i(TAG, "Navigating to MainActivity, finishing splash")
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "SplashFlow"
        private const val KEY_COLD = "ngad_splash_cold_start"

        // Google's official sample / test ids — replace with your own for release.
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"

        /** Absolute upper bound on the splash, in case consent/init never calls back. */
        const val WATCHDOG_MS = 10_000L
    }
}
