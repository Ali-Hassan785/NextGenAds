package com.alihassn.nextgenSample

import android.app.Application
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAdManager
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.events.ShowRateTracker

/**
 * Installs the app-open auto-show manager once for the whole process. The manager keeps an
 * app-open ad warm and shows it whenever the user brings the app back to the foreground.
 *
 * It's safe to install here even though [com.alihassan.nextgenads.NextGenAds] isn't initialized
 * yet (consent + init happen on the first screen): the manager's load request is queued and replayed
 * once the SDK is ready, and nothing is shown until [com.alihassan.nextgenads.NextGenAds.canShowAds]
 * is true. The first cold-start foreground is skipped by default.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Route every ad lifecycle event (load / show / click / impression / revenue / …) to
        // Firebase Analytics. No-ops until google-services.json is added.
        NextGenAds.registerEventListener(FirebaseAdEventListener(this))

        // Live fill/show-rate measurement — long-press the status text to dump the report.
        NextGenAds.registerEventListener(showRate)

        // Reset the request breaker when connectivity returns so a button-triggered load that failed
        // on a dead connection can be retried. No ad is requested here — nothing is preloaded until a
        // button asks for it (no warm-up is registered), so nothing loads on startup / after consent.
        NextGenAds.enableConnectivityRecovery(this)

        // Auto-show an app-open ad ONLY on a genuine return to the foreground (app was actually
        // backgrounded). It requests on demand at that moment; the ad is shown only if it loads
        // within the manager's show window (loadTimeoutMs, default 5s) — a later-arriving ad is
        // cached for the next return instead of popping over app content. Screens can opt out via
        // HideAppOpenAd or skipOn(...), e.g.:
        //   AppOpenAdManager.install(this, APP_OPEN_UNIT)
        //       .skipOn(SplashActivity::class.java, PaywallActivity::class.java)
        // Skip the splash so its own ad is the only one shown there (no app-open competes). On a
        // Recents/home return the manager shows the app-open with its default branded "Welcome back"
        // cover; the splash app-open (SplashAppOpenAd) uses the plain "Loading ad…" cover instead, so
        // "Welcome back" only ever appears on a normal return, never on the splash.
        AppOpenAdManager.install(this, APP_OPEN_UNIT)
            .skipOn(SplashActivity::class.java)

        // The full-screen "Welcome back" cover's copy is customisable from the host app — localise or
        // rebrand it here. This targets the same helper the manager auto-shows. Any field left unset
        // keeps the module default; you can alternatively override the ngad_welcome_* string resources.
        AppOpenAds.get(APP_OPEN_UNIT).apply {
            welcomeTitle = getString(R.string.welcome_back_title)
            loadingText = getString(R.string.welcome_back_loading)
            showingText = getString(R.string.welcome_back_showing)
        }
    }

    companion object {
        // Google's official app-open test unit — replace with your own AdMob id for release.
        const val APP_OPEN_UNIT = "ca-app-pub-3940256099942544/9257395921"

        // Google's official native test unit (matches MainActivity.NATIVE_UNIT).
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"

        // This device's test / hashed id. The Mobile Ads SDK ("Use RequestConfiguration.Builder()
        // .setTestDeviceIds(...)") and UMP ("addTestDeviceHashedId(...)") log the SAME value, so one
        // constant serves both: test ads AND forcing the UMP consent form (debug EEA geography only
        // applies to a registered test device). Find yours in logcat on the first ad/consent request.
        const val TEST_DEVICE_HASH = "B4033DAF1ECF925FC80FD0731246735E"
        const val TEST_DEVICE_HASH1 = "445FDBFFE2FFB7A0A4CA9ADF81FE4675"

        // Devices that always receive test ads. Passed to NextGenAds.initialize.
        @JvmStatic
        val TEST_DEVICE_IDS = listOf(TEST_DEVICE_HASH,TEST_DEVICE_HASH1)

        /** App-wide fill/show-rate tracker; report via [ShowRateTracker.report]. */
        @JvmStatic
        val showRate = ShowRateTracker()
    }
}
