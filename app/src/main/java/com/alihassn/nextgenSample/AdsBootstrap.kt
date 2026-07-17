package com.alihassn.nextgenSample

import android.app.Activity
import android.app.Application
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.NextGenAdsBootstrap
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.appopen.AppOpenCoverStyle

/**
 * This app's thin ads-setup layer. The reusable, app-agnostic sequence — gather consent → initialize,
 * plus connectivity recovery and the app-open manager — lives in the library
 * ([NextGenAdsBootstrap]); this file supplies only what is specific to THIS app (its ad units, event
 * listeners, test-device ids, splash screens to skip, and cover copy), so it doubles as a
 * copy-paste template for wiring the library into any app.
 */
object AdsBootstrap {

    /** Process-level setup — called once from [SampleApp.onCreate]. Units are passed in by the caller. */
    fun configure(app: Application, appOpenUnitId: String) {
        // App-wide default options live in NextGenAdsConfig (read live). Set here ONLY what you want
        // to change from the library default — assigning a value that already equals the default just
        // re-asserts it on every process start. The sample uses the defaults as-is; to override, e.g.:
        //     NextGenAdsConfig.minIntervalMs = 60_000L   // ≥ 60s between interstitials, app-wide

        // This app's ad-event listeners: Firebase Analytics (no-op until google-services.json is
        // added) and the live show-rate tracker.
        NextGenAds.registerEventListener(FirebaseAdEventListener(app))
        NextGenAds.registerEventListener(SampleApp.showRate)

        // Debug-only diagnostics: shake the device (any screen) for the live report. Needs no
        // manifest permission — the accelerometer is permission-free. Unlike SampleApp.showRate
        // (which groups by format for MainActivity's table), this one is tallied PER UNIT, so units
        // sharing a format each get their own fill% / use% / last error.
        AdReport.install(
            app = app,
            enabled = BuildConfig.DEBUG,
            units = mapOf(
                "Banner" to AdUnits.BANNER,
                "Native" to AdUnits.NATIVE,
                "Interstitial" to AdUnits.INTERSTITIAL,
                "Rewarded" to AdUnits.REWARDED,
                "Rewarded-int" to AdUnits.REWARDED_INT,
                "App-open" to AdUnits.APP_OPEN,
                // The splash runs its own units (see AdUnits), so they get their own rows: splash
                // fill is measured against a cold cache under a timeout and reads nothing like the
                // in-app numbers. Splash-open shares a row with App-open until real ids replace
                // Google's single app-open test id.
                "Splash-int" to AdUnits.SPLASH_INTERSTITIAL,
                "Splash-open" to AdUnits.SPLASH_APP_OPEN,
            ),
        )

        // Library setup: connectivity recovery + the auto-show app-open manager, skipping the splashes
        // (they run their own splash ad). Returns the manager for further tuning.
        val appOpen = NextGenAdsBootstrap.configure(
            application = app,
            appOpenUnitId = appOpenUnitId,
            skipAppOpenOn = listOf(SplashActivity::class.java, ComposeSplashActivity::class.java),
        )
        appOpen?.apply {
            loadTimeoutMs = 5_000L                 // show only if the on-return load lands within this
            showOnColdStart = false                // the cold-start foreground isn't a return
            coverStyle = AppOpenCoverStyle.WELCOME // or AppOpenCoverStyle.LOADING for a plain spinner
        }
        // Localise / rebrand the "Welcome back" cover copy (same unit the manager shows).
        AppOpenAds.get(appOpenUnitId).apply {
            welcomeTitle = app.getString(R.string.welcome_back_title)
            loadingText = app.getString(R.string.welcome_back_loading)
            showingText = app.getString(R.string.welcome_back_showing)
        }
    }

    /** Consent → initialize (App ID from the manifest) → [onReady]. Delegates to the library. */
    fun gatherConsentThenInitialize(activity: Activity, onReady: () -> Unit) {
        // UMP debug facilities (test-device hash + forced EEA geography) are debug-only — null in
        // release, so real users never get the form forced on them.
        val testHash = if (BuildConfig.DEBUG) SampleApp.TEST_DEVICE_HASH else null
        NextGenAdsBootstrap.gatherConsentThenInitialize(
            activity = activity,
            testDeviceHashedId = testHash,
            testDeviceIds = SampleApp.TEST_DEVICE_IDS,
        ) { onReady() }
    }
}
