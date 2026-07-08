package com.alihassan.nextgenads.rewardedinterstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * transition that can grant a reward. Set [autoReload] to `true` to request a fresh ad automatically
 * after each dismissal, or warm the next one yourself via [load] / [RewardedInterstitials.preload].
 *
 * Prefer obtaining instances through [RewardedInterstitials.get] so the same cached ad is reused
 * across screens. Requires [NextGenAds.initialize] to have completed first. SDK callbacks are
 * marshalled to the main thread.
 */
class RewardedInterstitialAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    private var loadedAtElapsed = 0L
    private var loading = false
    private var showing = false
    private var retryCount = 0
    // Concurrent load() callers all get notified instead of every caller-after-the-first being dropped.
    private val pending = mutableListOf<(Boolean) -> Unit>()

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    /**
     * How long (ms) a loaded rewarded interstitial stays valid in the cache. AdMob full-screen ads
     * expire roughly an hour after loading; a stale ad's show fails and the reward opportunity is
     * lost, so anything older is dropped and re-requested instead of shown.
     */
    var adValidityMs = 55 * 60 * 1000L

    /**
     * When `true`, the helper keeps an ad ready around [show]: it requests the next ad after each
     * dismissal/failed-show, and — crucially — when [show] finds none ready (the preload failed or
     * hasn't landed) it **force-loads one on demand and shows it** (via [loadAndShow], bounded by
     * [autoReloadTimeoutMs]) instead of giving up.
     *
     * Default `false` so a preloaded ad results in a **single** request — no extra load per show, and
     * a [show] with no cached ad simply invokes `onDismiss` (returning `false`) so the caller can
     * show its own "ad not ready" message. Preload explicitly via [load] /
     * [RewardedInterstitials.preload], or call [loadAndShow] yourself for on-demand load-and-show.
     */
    var autoReload = false

    /**
     * Upper bound (ms) on the force-load wait when [autoReload] is `true` and [show] must fetch an ad
     * on demand. `0` waits for the load result (itself bounded by the retry budget). Ignored when
     * [autoReload] is `false`.
     */
    var autoReloadTimeoutMs = 0L

    /** A non-expired ad is cached and ready to show. */
    val isReady: Boolean
        get() = rewardedInterstitialAd != null && !isExpired

    /** `true` while this helper's ad is on screen (or committed to showing). */
    val isShowing: Boolean
        get() = showing

    private val isExpired: Boolean
        get() = SystemClock.elapsedRealtime() - loadedAtElapsed >= adValidityMs

    private fun evictIfExpired() {
        if (rewardedInterstitialAd != null && isExpired) {
            NextGenAds.log("RewardedInterstitial expired after ${adValidityMs / 60_000}min, dropping: $adUnitId")
            rewardedInterstitialAd = null
        }
    }

    /**
     * Preloads the ad if not already available / in flight. Safe to call from any thread; state is
     * mutated (and [onResult] delivered) on the main thread.
     */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) = NextGenAds.runOnMain {
        if (!NextGenAds.canRequest()) {
            onResult?.invoke(false)
            return@runOnMain
        }
        evictIfExpired()
        if (rewardedInterstitialAd != null) {
            onResult?.invoke(true)
            return@runOnMain
        }
        onResult?.let { pending.add(it) }
        if (loading) return@runOnMain // a load is already in flight; this caller is parked in `pending`
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
                        loadedAtElapsed = SystemClock.elapsedRealtime()
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
                        NextGenAds.log("RewardedInterstitial failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.REWARDED_INTERSTITIAL, adUnitId, adError)
                        if (retryCount < maxRetries && !NextGenAds.isRequestPaused()) {
                            // Keep `loading` true and the waiters parked: the load isn't over until
                            // the retry budget is spent. Settling them now would make loadAndShow
                            // give up seconds before the retry succeeds — a lost show.
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({
                                if (NextGenAds.canRequest()) {
                                    requestAd()
                                } else { // breaker tripped / ads disabled during the backoff wait
                                    loading = false
                                    retryCount = 0
                                    flushPending(false)
                                }
                            }, delayMs)
                        } else {
                            loading = false
                            retryCount = 0 // reset budget so a later load() can retry afresh
                            flushPending(false)
                        }
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
        if (isReady) {
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
            // The activity may have died while the load was in flight — keep the ad cached.
            if (loaded && isReady && !activity.isFinishing && !activity.isDestroyed) {
                show(activity, onReward, onDismiss)
            } else {
                onDismiss()
            }
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
        evictIfExpired()
        val ad = rewardedInterstitialAd
        if (ad == null) {
            // No preloaded ad (the preload failed or hasn't landed yet). With autoReload on, force-load
            // one on demand and show it; otherwise fail fast so the caller can show its own message.
            if (autoReload) {
                loadAndShow(activity, onReward, autoReloadTimeoutMs, onDismiss)
                return true
            }
            onDismiss()
            return false
        }
        if (showing || !NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("RewardedInterstitial show skipped ($adUnitId): a full-screen ad is already showing")
            onDismiss()
            return false
        }
        // Committed: take ownership so a concurrent show()/load() can't grab the same ad.
        showing = true
        rewardedInterstitialAd = null

        ad.adEventCallback = object : RewardedInterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("RewardedInterstitial shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    showing = false
                    NextGenAds.endFullScreenShow()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.REWARDED_INTERSTITIAL, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    showing = false
                    NextGenAds.endFullScreenShow()
                    NextGenAds.log("RewardedInterstitial show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.REWARDED_INTERSTITIAL, adUnitId, fullScreenContentError)
                    if (autoReload) load()
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

    /**
     * Drops the cached ad and cancels any in-flight load / retry — used when ads are disabled at
     * runtime (e.g. the user goes premium). A currently-showing ad is left to finish.
     */
    fun clear() = NextGenAds.runOnMain {
        if (showing) return@runOnMain
        handler.removeCallbacksAndMessages(null)
        rewardedInterstitialAd = null
        loading = false
        retryCount = 0
        flushPending(false)
    }
}

/** Registry that keeps one [RewardedInterstitialAdHelper] per ad unit alive for reuse. */
object RewardedInterstitials {

    private val helpers = ConcurrentHashMap<String, RewardedInterstitialAdHelper>()

    /** Drops every cached rewarded-interstitial across all units (e.g. on going premium). */
    @JvmStatic
    fun clearAll() = helpers.values.forEach { it.clear() }

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
