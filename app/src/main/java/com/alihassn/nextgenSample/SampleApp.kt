package com.alihassn.nextgenSample

import android.app.Application
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAdManager
import com.alihassan.nextgenads.nativead.NativeAdHelper

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

        // Warm the native cache as soon as the SDK is ready, and again on network recovery — the
        // biggest lever on show-rate. registerWarmUp runs the task on init completion and whenever
        // connectivity returns after a drop.
        NextGenAds.registerWarmUp { NativeAdHelper.preload(NATIVE_UNIT, count = 2) }
        NextGenAds.enableConnectivityRecovery(this)

        AppOpenAdManager.install(this, APP_OPEN_UNIT)
    }

    companion object {
        // Google's official app-open test unit — replace with your own AdMob id for release.
        const val APP_OPEN_UNIT = "ca-app-pub-3940256099942544/9257395921"

        // Google's official native test unit (matches MainActivity.NATIVE_UNIT).
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
    }
}
