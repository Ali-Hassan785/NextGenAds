package com.alihassan.nextgenads.events

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem

/** The kind of ad an [AdEventListener] callback refers to. */
enum class AdFormat {
    BANNER,
    NATIVE,
    INTERSTITIAL,
    REWARDED,
    REWARDED_INTERSTITIAL,
    APP_OPEN,
}

/**
 * A single, app-wide hook for every ad lifecycle event the library raises, across all formats.
 *
 * Register one (or more) with [com.alihassan.nextgenads.NextGenAds.registerEventListener] — typically
 * from `Application.onCreate` — to drive analytics, ROAS/revenue tracking, or your own logging
 * without having to thread callbacks through every `load`/`show` call site. The per-call callbacks
 * (`onResult`, `onDismiss`, `onReward`, …) still fire as before; these events are additive.
 *
 * Every method has a no-op default, so implement only the ones you need. All callbacks are
 * delivered on the **main thread**, and an exception thrown by one listener never prevents the
 * others from being notified.
 *
 * Java callers: implement the methods you care about — unimplemented ones fall back to the
 * defaults below.
 */
interface AdEventListener {

    /** A request for a new ad was just fired to the SDK (each attempt, including retries). */
    fun onAdRequested(format: AdFormat, adUnitId: String) {}

    /** A new ad finished loading and is cached / ready to show. */
    fun onAdLoaded(format: AdFormat, adUnitId: String) {}

    /** An ad request failed. [error] is the SDK's [LoadAdError]. */
    fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) {}

    /**
     * A full-screen ad (interstitial, rewarded, rewarded-interstitial, app-open) started showing.
     * Not raised for banners / native ads, which are inline — use [onAdImpression] for those.
     */
    fun onAdShown(format: AdFormat, adUnitId: String) {}

    /** A full-screen ad failed to present. [error] is the SDK's [FullScreenContentError]. */
    fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) {}

    /** A full-screen ad was dismissed and the user returned to the app. */
    fun onAdDismissed(format: AdFormat, adUnitId: String) {}

    /** The ad recorded an impression (the canonical "ad was seen" signal for all formats). */
    fun onAdImpression(format: AdFormat, adUnitId: String) {}

    /** The user clicked the ad. */
    fun onAdClicked(format: AdFormat, adUnitId: String) {}

    /**
     * The ad generated estimated revenue. Forward [value] (micros + currency) to your analytics /
     * attribution pipeline (e.g. Firebase `ad_impression`) for ROAS measurement.
     *
     * [responseInfo] carries the winning ad's provenance — mediation ad source name, adapter class
     * and response id — for richer revenue attribution. It is `null` when the SDK didn't surface it.
     */
    fun onAdPaid(
        format: AdFormat,
        adUnitId: String,
        value: AdValue,
        responseInfo: ResponseInfo?,
    ) {}

    /** The user earned a reward from a rewarded or rewarded-interstitial ad. */
    fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) {}
}
