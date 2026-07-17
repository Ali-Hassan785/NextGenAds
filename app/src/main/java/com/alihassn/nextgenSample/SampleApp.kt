package com.alihassn.nextgenSample

import android.app.Application
import com.alihassan.nextgenads.events.ShowRateTracker

/**
 * Applies the saved theme and hands **all** ads setup to [AdsBootstrap] — the app-wide default
 * options, ad-event listeners, connectivity recovery, and the auto-show app-open manager all live
 * there (with the consent → initialize handshake), so this class stays tiny and there's one place to
 * look for how ads come up.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply the saved day/night choice before any activity is shown, so the splash already
        // renders in the chosen mode. The in-app toggle (MainActivity header) updates it at runtime.
        ThemePrefs.apply(this)

        // Everything ads-related: defaults, event listeners, connectivity recovery, app-open manager.
        // Ad units are declared once in AdUnits and passed in here.
        AdsBootstrap.configure(this, AdUnits.APP_OPEN)
    }

    companion object {
        // This device's test / hashed id. The Mobile Ads SDK ("Use RequestConfiguration.Builder()
        // .setTestDeviceIds(...)") and UMP ("addTestDeviceHashedId(...)") log the SAME value, so one
        // constant serves both: test ads AND forcing the UMP consent form (debug EEA geography only
        // applies to a registered test device). Find yours in logcat on the first ad/consent request.
        const val TEST_DEVICE_HASH = "B4033DAF1ECF925FC80FD0731246735E"
        const val TEST_DEVICE_HASH1 = "445FDBFFE2FFB7A0A4CA9ADF81FE4675"

        // Devices that always receive test ads. Passed to NextGenAds.initialize.
        @JvmStatic
        val TEST_DEVICE_IDS = listOf(TEST_DEVICE_HASH, TEST_DEVICE_HASH1)

        /** App-wide fill/show-rate tracker; report via [ShowRateTracker.report]. */
        @JvmStatic
        val showRate = ShowRateTracker()
    }
}
