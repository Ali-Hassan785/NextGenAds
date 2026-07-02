package com.alihassan.nextgenads.rewarded

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
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and shows a single rewarded ad unit (Next-Gen SDK) with automatic preloading and
 * exponential-backoff retries. A fresh ad is requested immediately after each dismissal so the
 * next reward is ready to show.
 *
 * Prefer obtaining instances through [RewardedAds.get] so the same cached ad is reused across
 * screens. Requires [NextGenAds.initialize] to have completed first. SDK callbacks are marshalled
 * to the main thread, so [load]/[show] callbacks are always delivered there.
 */
class RewardedAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardedAd: RewardedAd? = null
    private var loading = false
    private var retryCount = 0
    // Concurrent load() callers all get notified instead of every caller-after-the-first being dropped.
    private val pending = mutableListOf<(Boolean) -> Unit>()

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    val isReady: Boolean
        get() = rewardedAd != null

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canRequest()) {
            onResult?.invoke(false)
            return
        }
        if (rewardedAd != null) {
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
        NextGenAds.countRequest(AdFormat.REWARDED, adUnitId)
        RewardedAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    NextGenAds.runOnMain {
                        rewardedAd = ad
                        loading = false
                        retryCount = 0
                        NextGenAds.log("Rewarded loaded: $adUnitId")
                        NextGenAds.dispatchLoaded(AdFormat.REWARDED, adUnitId)
                        flushPending(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        rewardedAd = null
                        loading = false
                        NextGenAds.log("Rewarded failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.REWARDED, adUnitId, adError)
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
        if (rewardedAd != null) {
            show(activity, onReward, onDismiss)
            return
        }

        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            NextGenAds.log("Rewarded load timed out ($adUnitId); proceeding")
            onDismiss()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            if (loaded && rewardedAd != null) show(activity, onReward, onDismiss) else onDismiss()
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
        val ad = rewardedAd
        if (ad == null) {
            onDismiss()
            load() // make sure the next attempt has an ad ready
            return false
        }

        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("Rewarded shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.REWARDED, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    rewardedAd = null
                    load()
                    NextGenAds.dispatchDismissed(AdFormat.REWARDED, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    rewardedAd = null
                    NextGenAds.log("Rewarded show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.REWARDED, adUnitId, fullScreenContentError)
                    load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.REWARDED, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.REWARDED, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.REWARDED, adUnitId, value, ad.getResponseInfo())
            }
        }

        NextGenAds.log("Rewarded show requested: $adUnitId")
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    NextGenAds.runOnMain {
                        NextGenAds.dispatchReward(AdFormat.REWARDED, adUnitId, rewardItem)
                        onReward(rewardItem)
                    }
                }
            },
        )
        return true
    }
}

/** Registry that keeps one [RewardedAdHelper] per ad unit alive for reuse across screens. */
object RewardedAds {

    private val helpers = ConcurrentHashMap<String, RewardedAdHelper>()

    @JvmStatic
    fun get(adUnitId: String): RewardedAdHelper =
        helpers.getOrPut(adUnitId) { RewardedAdHelper(adUnitId) }

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
