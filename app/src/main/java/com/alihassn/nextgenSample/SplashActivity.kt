package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.consent.ConsentManager
import com.alihassan.nextgenads.interstitial.SplashAd

/**
 * Splash screen that gathers consent, initializes the SDK, then shows a **splash interstitial** via
 * [SplashAd] — held for a minimum delay and bounded by a timeout — before handing off to
 * [MainActivity]. A watchdog guarantees we always leave the splash even if consent/init stalls.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Safety net: never trap the user on the splash if consent/init hangs (e.g. no network).
        handler.postDelayed({ goToMain() }, WATCHDOG_MS)

        gatherConsentThenShowAd()
    }

    private fun gatherConsentThenShowAd() {
        val testDeviceHash = if (BuildConfig.DEBUG) "445FDBFFE2FFB7A0A4CA9ADF81FE4675" else null
        val consent = ConsentManager.getInstance(this, testDeviceHash)

        val initAndShow = {
            NextGenAds.initialize(this, APP_ID) {
                // Init done → load the splash interstitial, show it after a minimum splash time,
                // and move on (dismiss / timeout / no-ad all land in goToMain).
                SplashAd.show(
                    activity = this,
                    adUnitId = INTERSTITIAL_UNIT,
                    minDelayMs = 1_500L,
                    timeoutMs = 8_000L,
                ) { goToMain() }
            }
        }

        if (consent.canRequestAds) {
            initAndShow()
        } else {
            consent.gatherConsent(this, forceEea = testDeviceHash != null) {
                // Proceed regardless of the consent outcome; SplashAd honours canShowAds() itself.
                initAndShow()
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        // Google's official sample / test ids — replace with your own for release.
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"

        /** Absolute upper bound on the splash, in case consent/init never calls back. */
        const val WATCHDOG_MS = 15_000L
    }
}
