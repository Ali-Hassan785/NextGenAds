package com.alihassn.nextgenSample

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Forwards every NextGenAds lifecycle event to Firebase Analytics. Register once from
 * [SampleApp.onCreate]:
 *
 * ```
 * NextGenAds.registerEventListener(FirebaseAdEventListener(this))
 * ```
 *
 * - `onAdPaid` is logged as the **standard** [FirebaseAnalytics.Event.AD_IMPRESSION] event with the
 *   estimated revenue, currency and unit — this is the canonical AdMob → Firebase revenue / ROAS
 *   signal the Firebase & GA4 consoles understand.
 * - Every other stage (loaded, failed, shown, dismissed, clicked, impression, reward) is logged as a
 *   custom `ad_*` event with `ad_format` / `ad_unit_id` params for building funnels.
 *
 * If Firebase isn't configured yet (no `google-services.json`), it degrades to a no-op instead of
 * crashing, so the sample still runs.
 */
class FirebaseAdEventListener(context: Context) : AdEventListener {

    private val analytics: FirebaseAnalytics? = try {
        FirebaseAnalytics.getInstance(context.applicationContext)
    } catch (t: Throwable) {
        Log.w(TAG, "Firebase not configured; ad analytics disabled", t)
        null
    }

    override fun onAdLoaded(format: AdFormat, adUnitId: String) =
        log("ad_loaded", format, adUnitId)

    override fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) =
        log("ad_failed_to_load", format, adUnitId) {
            putString("error_code", error.code.name)
            putString("error_message", error.message)
        }

    override fun onAdShown(format: AdFormat, adUnitId: String) =
        log("ad_shown", format, adUnitId)

    override fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) =
        log("ad_failed_to_show", format, adUnitId) {
            putString("error_code", error.code.name)
            putString("error_message", error.message)
        }

    override fun onAdDismissed(format: AdFormat, adUnitId: String) =
        log("ad_dismissed", format, adUnitId)

    override fun onAdImpression(format: AdFormat, adUnitId: String) =
        log("ad_impression_recorded", format, adUnitId)

    override fun onAdClicked(format: AdFormat, adUnitId: String) =
        log("ad_clicked", format, adUnitId)

    override fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) =
        log("ad_user_earned_reward", format, adUnitId) {
            putString("reward_type", reward.type)
            putLong("reward_amount", reward.amount.toLong())
        }

    /** Standard revenue event for ROAS / AdMob revenue in the Firebase & GA4 consoles. */
    override fun onAdPaid(
        format: AdFormat,
        adUnitId: String,
        value: AdValue,
        responseInfo: ResponseInfo?,
    ) {
        val fa = analytics ?: return
        // The winning mediation ad source (e.g. "AdMob Network", "Meta"), or the adapter class when
        // no friendly name is available.
        val loaded = responseInfo?.loadedAdSourceResponseInfo
        val adSource = loaded?.name?.takeIf { it.isNotBlank() }
            ?: responseInfo?.adapterClassName
        fa.logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "GMA_NextGen")
            putString(FirebaseAnalytics.Param.AD_FORMAT, format.name)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, adUnitId)
            // AdValue is in micros of the currency's base unit; Firebase wants the base-unit value.
            putDouble(FirebaseAnalytics.Param.VALUE, value.valueMicros / 1_000_000.0)
            putString(FirebaseAnalytics.Param.CURRENCY, value.currencyCode)
            // Enriched attribution params.
            putString("precision_type", value.precisionType.name)
            if (adSource != null) putString(FirebaseAnalytics.Param.AD_SOURCE, adSource)
            responseInfo?.responseId?.let { putString("response_id", it) }
        })
    }

    private inline fun log(
        event: String,
        format: AdFormat,
        adUnitId: String,
        extras: Bundle.() -> Unit = {},
    ) {
        val fa = analytics ?: return
        fa.logEvent(event, Bundle().apply {
            putString("ad_format", format.name)
            putString("ad_unit_id", adUnitId)
            extras()
        })
    }

    private companion object {
        const val TAG = "FirebaseAdEvents"
    }
}
