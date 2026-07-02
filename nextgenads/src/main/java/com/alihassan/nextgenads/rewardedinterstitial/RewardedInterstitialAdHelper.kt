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
    // Concurrent load() callers all get notified instead of every caller-after-the-first being dropped.
    private val pending = mutableListOf<(Boolean) -> Unit>()

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    val isReady: Boolean
        get() = rewardedInterstitialAd != null

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canRequest()) {
            onResult?.invoke(false)
            return
        }
        if (rewardedInterstitialAd != null) {
            onResult?.invoke(true)
            return
        }
        onResult?.let { pending.add(it) }
        if (loading) return // a load is already in flight; this caller is parked in `pending`
        loading = true
        // Defer the request until the SDK is ready so preloads issued during app start are queued
        // rather than fired at an uninitialized SDK (which would fail and burn the retry budget).
        NextGenAds.whenInitialized { requestAd() }
    }

    private fun flushPending(loaded: Boolean) {
        val waiters = pending.toList()
        pending.clear()
        waiters.forEach { it(loaded) }
    }

    private fun requestAd() {
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
                        flushPending(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        rewardedInterstitialAd = null
                        loading = false
                        NextGenAds.log("RewardedInterstitial failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.REWARDED_INTERSTITIAL, adUnitId, adError)
                        if (retryCount < maxRetries && !NextGenAds.isRequestPaused()) {
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({ load() }, delayMs)
                        } else {
                            retryCount = 0 // reset budget so a later load() can retry afresh
                        }
                        flushPending(false)
                    }
                }
            },
        )
    }

    /**
     * On-demand "request and show": show the cached ad immediately if ready, otherwise request one
     * and show it the moment it loads — higher show-rate than [show] when nothing was preloaded.
     *
     * [timeoutMs] bounds the wait; if it elapses first, [onDismiss] fires (no reward) and the load
     * is left to warm the cache. `0` waits for the load result. [onDismiss] is invoked exactly once.
     */
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ) {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return
        }
        if (rewardedInterstitialAd != null) {
            show(activity, onReward, onDismiss)
            return
        }

        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            NextGenAds.log("RewardedInterstitial load timed out ($adUnitId); proceeding")
            onDismiss()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            if (loaded && rewardedInterstitialAd != null) show(activity, onReward, onDismiss) else onDismiss()
        }
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

    /** Convenience: request (if needed) and show [adUnitId] on demand, bounded by [timeoutMs]. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ) = get(adUnitId).loadAndShow(activity, onReward, timeoutMs, onDismiss)
}
