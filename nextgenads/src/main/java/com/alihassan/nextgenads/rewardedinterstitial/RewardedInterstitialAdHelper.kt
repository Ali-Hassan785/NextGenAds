package com.alihassan.nextgenads.rewardedinterstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and shows a single rewarded interstitial ad unit (Next-Gen SDK) with automatic preloading
 * and exponential-backoff retries. A rewarded interstitial is a full-screen ad shown at a natural
 * transition that can grant a reward; a fresh ad is requested immediately after each dismissal.
 *
 * Prefer obtaining instances through [RewardedInterstitials.get] so the same cached ad is reused
 * across screens. Requires [NextGenAds.initialize] to have completed first. SDK callbacks are
 * marshalled to the main thread.
 */
class RewardedInterstitialAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    private var loading = false
    private var retryCount = 0

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    val isReady: Boolean
        get() = rewardedInterstitialAd != null

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canShowAds()) {
            onResult?.invoke(false)
            return
        }
        if (rewardedInterstitialAd != null) {
            onResult?.invoke(true)
            return
        }
        if (loading) return
        loading = true
        // Defer the request until the SDK is ready so preloads issued during app start are queued
        // rather than fired at an uninitialized SDK (which would fail and burn the retry budget).
        NextGenAds.whenInitialized { requestAd(onResult) }
    }

    private fun requestAd(onResult: ((Boolean) -> Unit)?) {
        NextGenAds.countRequest(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
        RewardedInterstitialAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<RewardedInterstitialAd> {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    NextGenAds.runOnMain {
                        rewardedInterstitialAd = ad
                        loading = false
                        retryCount = 0
                        NextGenAds.log("RewardedInterstitial loaded: $adUnitId")
                        NextGenAds.dispatchLoaded(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
                        onResult?.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        rewardedInterstitialAd = null
                        loading = false
                        NextGenAds.log("RewardedInterstitial failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.REWARDED_INTERSTITIAL, adUnitId, adError)
                        if (retryCount < maxRetries) {
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({ load() }, delayMs)
                        }
                        onResult?.invoke(false)
                    }
                }
            },
        )
    }

    /**
     * Shows the ad if one is ready, then preloads the next one.
     *
     * @param onReward invoked with the earned [RewardItem] when the user completes the ad.
     * @param onDismiss invoked when the ad is closed (whether or not a reward was earned). If no ad
     *   is ready it is called immediately and the method returns `false`.
     * @return `true` if the ad is being shown.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        onDismiss: () -> Unit = {},
    ): Boolean {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return false
        }
        val ad = rewardedInterstitialAd
        if (ad == null) {
            onDismiss()
            load() // make sure the next attempt has an ad ready
            return false
        }

        ad.adEventCallback = object : RewardedInterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("RewardedInterstitial shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    rewardedInterstitialAd = null
                    load()
                    NextGenAds.dispatchDismissed(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    rewardedInterstitialAd = null
                    NextGenAds.log("RewardedInterstitial show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.REWARDED_INTERSTITIAL, adUnitId, fullScreenContentError)
                    load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.REWARDED_INTERSTITIAL, adUnitId, value, ad.getResponseInfo())
            }
        }

        NextGenAds.log("RewardedInterstitial show requested: $adUnitId")
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    NextGenAds.runOnMain {
                        NextGenAds.dispatchReward(AdFormat.REWARDED_INTERSTITIAL, adUnitId, rewardItem)
                        onReward(rewardItem)
                    }
                }
            },
        )
        return true
    }
}

/** Registry that keeps one [RewardedInterstitialAdHelper] per ad unit alive for reuse. */
object RewardedInterstitials {

    private val helpers = ConcurrentHashMap<String, RewardedInterstitialAdHelper>()

    @JvmStatic
    fun get(adUnitId: String): RewardedInterstitialAdHelper =
        helpers.getOrPut(adUnitId) { RewardedInterstitialAdHelper(adUnitId) }

    /** Convenience: preload an ad unit. */
    @JvmStatic
    fun preload(adUnitId: String) = get(adUnitId).load()
}
