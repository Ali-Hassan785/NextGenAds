package com.alihassn.nextgenSample

import android.util.Log
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem

/**
 * Logs **every** ad lifecycle event for **every** format (banner, native, interstitial, rewarded,
 * rewarded-interstitial, app-open) to Logcat — a single, app-wide trace of the whole ad funnel
 * without threading logs through each `load` / `show` call site.
 *
 * Register once from [AdsBootstrap.configure]:
 * ```
 * NextGenAds.registerEventListener(LoggingAdEventListener())
 * ```
 * then watch it with `adb logcat -s NextGenAdsEvents`.
 *
 * This is separate from — and complementary to — the library's own internal logging
 * ([com.alihassan.nextgenads.NextGenAds.loggingEnabled], on by default), which traces load timing,
 * retries and cache/expiry decisions. This listener traces the *outcomes* (loaded / shown / clicked
 * / paid / …) in one consistent, app-owned format. It is gated on [BuildConfig.DEBUG] so release
 * builds stay silent; drop that guard if you want it in release too.
 *
 * Every callback has a no-op default in [AdEventListener], all fire on the main thread, and a throw
 * here never blocks the other listeners (e.g. [FirebaseAdEventListener]).
 */
class LoggingAdEventListener : AdEventListener {

    override fun onAdRequested(format: AdFormat, adUnitId: String) =
        log(format, "requested", adUnitId)

    override fun onAdLoaded(format: AdFormat, adUnitId: String) =
        log(format, "loaded", adUnitId)

    override fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) =
        warn(format, "FAILED_TO_LOAD", adUnitId, "[${error.code}] ${error.message}")

    override fun onAdShown(format: AdFormat, adUnitId: String) =
        log(format, "shown", adUnitId)

    override fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) =
        warn(format, "FAILED_TO_SHOW", adUnitId, "[${error.code}] ${error.message}")

    override fun onAdDismissed(format: AdFormat, adUnitId: String) =
        log(format, "dismissed", adUnitId)

    override fun onAdImpression(format: AdFormat, adUnitId: String) =
        log(format, "impression", adUnitId)

    override fun onAdClicked(format: AdFormat, adUnitId: String) =
        log(format, "clicked", adUnitId)

    override fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue, responseInfo: ResponseInfo?) {
        val revenue = value.valueMicros / 1_000_000.0
        val source = responseInfo?.loadedAdSourceResponseInfo?.name?.takeIf { it.isNotBlank() }
            ?: responseInfo?.adapterClassName
        log(format, "paid", adUnitId, "%.6f %s%s".format(revenue, value.currencyCode, source?.let { " via $it" } ?: ""))
    }

    override fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) =
        log(format, "reward", adUnitId, "${reward.amount} ${reward.type}")

    private fun log(format: AdFormat, event: String, adUnitId: String, extra: String? = null) {
        if (BuildConfig.DEBUG) Log.d(TAG, line(format, event, adUnitId, extra))
    }

    private fun warn(format: AdFormat, event: String, adUnitId: String, extra: String?) {
        if (BuildConfig.DEBUG) Log.w(TAG, line(format, event, adUnitId, extra))
    }

    /** e.g. `NATIVE · loaded · ca-app-pub-…/123` or `REWARDED · paid · …/9 — 0.004500 USD via AdMob`. */
    private fun line(format: AdFormat, event: String, adUnitId: String, extra: String?): String =
        "${format.name} · $event · $adUnitId" + (extra?.let { " — $it" } ?: "")

    private companion object {
        const val TAG = "NextGenAdsEvents"
    }
}
