package com.alihassn.nextgenSample

import android.app.Application
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAdManager
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
        // backgrounded). It requests on demand at that moment — never at startup and never a "next"
        // ad after showing one. Screens can opt out via HideAppOpenAd or skipOn(...), e.g.:
        //   AppOpenAdManager.install(this, APP_OPEN_UNIT)
        //       .skipOn(SplashActivity::class.java, PaywallActivity::class.java)
        AppOpenAdManager.install(this, APP_OPEN_UNIT)
    }

    companion object {
        // Google's official app-open test unit — replace with your own AdMob id for release.
        const val APP_OPEN_UNIT = "ca-app-pub-3940256099942544/9257395921"

        // Google's official native test unit (matches MainActivity.NATIVE_UNIT).
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"

        /** App-wide fill/show-rate tracker; report via [ShowRateTracker.report]. */
        @JvmStatic
        val showRate = ShowRateTracker()
    }
}
