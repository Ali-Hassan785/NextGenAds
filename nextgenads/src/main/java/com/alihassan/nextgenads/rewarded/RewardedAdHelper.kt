package com.alihassan.nextgenads.rewarded

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import com.alihassan.nextgenads.nativead.NgadTheme
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.ConfigDefault
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.NextGenAdsConfig
import com.alihassan.nextgenads.R
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
 * exponential-backoff retries. Set [autoReload] to `true` to request a fresh ad automatically after
 * each dismissal, or warm the next one yourself via [load] / [RewardedAds.preload].
 *
 * Prefer obtaining instances through [RewardedAds.get] so the same cached ad is reused across
 * screens. Requires [NextGenAds.initialize] to have completed first. SDK callbacks are marshalled
 * to the main thread, so [load]/[show] callbacks are always delivered there.
 */
class RewardedAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardedAd: RewardedAd? = null
    private var loadedAtElapsed = 0L
    private var loading = false
    private var showing = false
    private var retryCount = 0
    // Concurrent load() callers all get notified instead of every caller-after-the-first being dropped.
    private val pending = mutableListOf<(Boolean) -> Unit>()

    /**
     * Maximum number of automatic reload attempts after a failed load. Defaults to
     * [NextGenAdsConfig.maxRetries]; assigning it pins the value for this unit.
     */
    var maxRetries: Int by ConfigDefault { NextGenAdsConfig.maxRetries }

    /**
     * How long (ms) a loaded rewarded ad stays valid in the cache. AdMob full-screen ads expire
     * roughly an hour after loading; a stale ad's show fails and the reward opportunity is lost, so
     * anything older is dropped and re-requested instead of shown. Defaults to
     * [NextGenAdsConfig.adValidityMs].
     */
    var adValidityMs: Long by ConfigDefault { NextGenAdsConfig.adValidityMs }

    /**
     * When `true`, the helper keeps an ad ready around [show]: it requests the next ad after each
     * dismissal/failed-show, and — crucially — when [show] finds none ready (the preload failed or
     * hasn't landed) it **force-loads one on demand and shows it** (via [loadAndShow], bounded by
     * [autoReloadTimeoutMs]) instead of giving up.
     *
     * Default `false` so a preloaded ad results in a **single** request — no extra load per show, and
     * a [show] with no cached ad simply invokes `onComplete` (returning `false`) so the caller can
     * show its own "ad not ready" message. Preload explicitly via [load] / [RewardedAds.preload], or
     * call [loadAndShow] yourself for on-demand load-and-show. Defaults to
     * [NextGenAdsConfig.autoReload].
     */
    var autoReload: Boolean by ConfigDefault { NextGenAdsConfig.autoReload }

    /**
     * Upper bound (ms) on the force-load wait when [autoReload] is `true` and [show] must fetch an ad
     * on demand. Defaults to [NextGenAdsConfig.rewardedForceShowTimeoutMs]; `0` waits for the load
     * result (itself bounded by the retry budget). Ignored when [autoReload] is `false`.
     */
    var autoReloadTimeoutMs: Long by ConfigDefault { NextGenAdsConfig.rewardedForceShowTimeoutMs }

    /**
     * Optional artificial dwell (ms) on a "Showing ad…" cover before an already-available ad opens,
     * so it doesn't pop in abruptly. Defaults to [NextGenAdsConfig.loadingOverlayMs] (`0`) — a ready
     * ad shows **instantly** (smoothest). This is separate from the "Loading ad…" cover [loadAndShow]
     * shows while genuinely fetching an ad, which always appears (it hides real network latency, not
     * an artificial delay).
     */
    var loadingOverlayMs: Long by ConfigDefault { NextGenAdsConfig.loadingOverlayMs }

    /**
     * Minimum time (ms) the "Loading ad…" cover stays on screen during a genuine on-demand fetch
     * (via [loadAndShow]) before the ad opens, so a fast/warm fetch reads as a real loading state
     * instead of a flash. Only *pads* a fetch that finished sooner than the floor; a slower fetch is
     * never delayed, and a cached ad ([isReady]) still shows instantly. Defaults to
     * [NextGenAdsConfig.minLoadingCoverMs]; `0` disables the floor.
     */
    var minLoadingCoverMs: Long by ConfigDefault { NextGenAdsConfig.minLoadingCoverMs }

    /**
     * Caption shown on the loading cover while an ad is being fetched on demand. `null` (default)
     * falls back to the `ngad_ad_loading` string resource — which the app can also override by
     * redeclaring that string.
     */
    var loadingText: CharSequence? = null

    /**
     * Caption shown on the cover for the brief interlude right before the ad opens. `null` (default)
     * falls back to the `ngad_ad_showing` string resource.
     */
    var showingText: CharSequence? = null

    /** A non-expired ad is cached and ready to show. */
    val isReady: Boolean
        get() = rewardedAd != null && !isExpired

    /** `true` while this helper's rewarded ad is on screen (or committed to showing). */
    val isShowing: Boolean
        get() = showing

    private val isExpired: Boolean
        get() = SystemClock.elapsedRealtime() - loadedAtElapsed >= adValidityMs

    private fun evictIfExpired() {
        if (rewardedAd != null && isExpired) {
            NextGenAds.log("Rewarded expired after ${adValidityMs / 60_000}min, dropping: $adUnitId")
            rewardedAd = null
        }
    }

    /**
     * Preloads the ad if not already available / in flight. Safe to call from any thread; state is
     * mutated (and [onResult] delivered) on the main thread.
     */
    @JvmOverloads
    fun load(
        remoteEnabled: Boolean = true,
        onResult: ((Boolean) -> Unit)? = null,
    ) = NextGenAds.runOnMain {
        if (!remoteEnabled || !NextGenAds.canRequest(AdFormat.REWARDED)) {
            onResult?.invoke(false)
            return@runOnMain
        }
        evictIfExpired()
        if (rewardedAd != null) {
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
        NextGenAds.countRequest(AdFormat.REWARDED, adUnitId)
        RewardedAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    NextGenAds.runOnMain {
                        rewardedAd = ad
                        loadedAtElapsed = SystemClock.elapsedRealtime()
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
                        NextGenAds.log("Rewarded failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.REWARDED, adUnitId, adError)
                        if (retryCount < maxRetries && !NextGenAds.isRequestPaused()) {
                            // Keep `loading` true and the waiters parked: the load isn't over until
                            // the retry budget is spent. Settling them now would make loadAndShow
                            // give up seconds before the retry succeeds — a lost show.
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({
                                if (NextGenAds.canRequest(AdFormat.REWARDED)) {
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
     * [timeoutMs] bounds the wait; if it elapses first, [onComplete] fires (no reward) and the load
     * is left to warm the cache. Defaults to [NextGenAdsConfig.rewardedForceShowTimeoutMs]; `0` waits
     * for the load result. [onComplete] is invoked exactly once.
     */
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = NextGenAdsConfig.rewardedForceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) {
        if (!NextGenAds.canShowAds(AdFormat.REWARDED)) {
            onComplete()
            return
        }
        if (isReady) {
            show(activity, onReward, onComplete)
            return
        }

        // Cover the genuine on-demand fetch so the user isn't left on a frozen screen. show() reveals
        // the ad the moment it loads (flipping the caption to "Showing ad…") and drops the cover when
        // it renders.
        val overlayRaisedAt = SystemClock.elapsedRealtime()
        val overlay = showLoadingOverlay(activity, activity.loadingCaption())

        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            overlay?.let { removeLoadingOverlay(it) }
            NextGenAds.log("Rewarded load timed out ($adUnitId); proceeding")
            onComplete()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            // The activity may have died while the load was in flight — keep the ad cached.
            if (loaded && isReady && !activity.isFinishing && !activity.isDestroyed) {
                // Hold the cover for at least minLoadingCoverMs so a fast fetch reads as a loading
                // state instead of a flash. A slower fetch already exceeded the floor and shows now.
                val remaining = minLoadingCoverMs - (SystemClock.elapsedRealtime() - overlayRaisedAt)
                val reveal = Runnable {
                    if (activity.isFinishing || activity.isDestroyed) {
                        overlay?.let { removeLoadingOverlay(it) }
                        onComplete()
                    } else {
                        show(activity, onReward, onComplete, overlay)
                    }
                }
                if (remaining > 0) handler.postDelayed(reveal, remaining) else reveal.run()
            } else {
                overlay?.let { removeLoadingOverlay(it) }
                onComplete()
            }
        }
    }

    /**
     * Shows the ad if one is ready, then preloads the next one.
     *
     * @param onReward invoked with the earned [RewardItem] when the user completes the ad.
     * @param onComplete invoked when the ad is closed (whether or not a reward was earned). If no ad
     *   is ready it is called immediately and the method returns `false`.
     * @return `true` if the ad is being shown.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        onComplete: () -> Unit = {},
        preloadedOverlay: View? = null,
    ): Boolean {
        if (!NextGenAds.canShowAds(AdFormat.REWARDED)) {
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onComplete()
            return false
        }
        evictIfExpired()
        val ad = rewardedAd
        if (ad == null) {
            // No preloaded ad (the preload failed or hasn't landed yet). With autoReload on, force-load
            // one on demand and show it; otherwise fail fast so the caller can show its own message.
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            if (autoReload) {
                loadAndShow(activity, onReward, autoReloadTimeoutMs, onComplete)
                return true
            }
            onComplete()
            return false
        }
        if (showing || !NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("Rewarded show skipped ($adUnitId): a full-screen ad is already showing")
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onComplete()
            return false
        }
        // Committed: take ownership so a concurrent show()/load() can't grab the same ad.
        showing = true
        rewardedAd = null

        var overlay: View? = preloadedOverlay
        fun dismissOverlay() {
            overlay?.let { removeLoadingOverlay(it) }
            overlay = null
        }
        // The show never happened (activity/app went away first): put the ad back for the next
        // trigger and free the full-screen slot.
        fun abortShow() {
            showing = false
            rewardedAd = ad
            NextGenAds.endFullScreenShow()
        }

        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.runOnMain { dismissOverlay() }
                NextGenAds.log("Rewarded shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.REWARDED, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    dismissOverlay()
                    showing = false
                    NextGenAds.endFullScreenShow()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.REWARDED, adUnitId)
                    onComplete()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    dismissOverlay()
                    showing = false
                    NextGenAds.endFullScreenShow()
                    NextGenAds.log("Rewarded show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.REWARDED, adUnitId, fullScreenContentError)
                    if (autoReload) load()
                    onComplete()
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

        val rewardListener = object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(rewardItem: RewardItem) {
                NextGenAds.runOnMain {
                    NextGenAds.dispatchReward(AdFormat.REWARDED, adUnitId, rewardItem)
                    onReward(rewardItem)
                }
            }
        }

        NextGenAds.log("Rewarded show requested: $adUnitId")
        if (loadingOverlayMs <= 0) {
            // No artificial dwell, but still bridge the show→render gap with the cover so the screen
            // isn't left frozen while the SDK brings the ad up: reuse a carried-over loader (from a
            // fetch) or raise one now, flip it to "Showing ad…", show immediately, and drop it the
            // moment the ad renders (callbacks above).
            overlay = overlay?.also { setOverlayText(it, activity.showingCaption()) }
                ?: showLoadingOverlay(activity, activity.showingCaption())
            ad.show(activity, rewardListener)
            return true
        }

        // Full-screen "Showing ad…" interlude: cover the screen for loadingOverlayMs, then open the
        // ad. Reuse loadAndShow's "Loading ad…" cover when it handed one in (flip its text) so
        // there's no flicker between the two phases.
        overlay = overlay?.also { setOverlayText(it, activity.showingCaption()) }
            ?: showLoadingOverlay(activity, activity.showingCaption())
        handler.postDelayed({
            val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (activity.isFinishing || activity.isDestroyed || !appInForeground) {
                // The user left during the interlude — keep the ad cached for the next trigger.
                dismissOverlay()
                abortShow()
                onComplete()
                return@postDelayed
            }
            ad.show(activity, rewardListener)
        }, loadingOverlayMs)
        return true
    }

    /** The fetch caption: the host-set [loadingText], or the `ngad_ad_loading` resource. */
    private fun Activity.loadingCaption(): CharSequence = loadingText ?: getString(R.string.ngad_ad_loading)

    /** The pre-show caption: the host-set [showingText], or the `ngad_ad_showing` resource. */
    private fun Activity.showingCaption(): CharSequence = showingText ?: getString(R.string.ngad_ad_showing)

    /**
     * Attaches a full-screen loader view (captioned with [caption], e.g. "Loading ad…" /
     * "Showing ad…") to the activity's decor view and fades it in. Returns the attached view (or
     * `null` if it couldn't be attached), to be passed to [removeLoadingOverlay] once the ad renders.
     */
    private fun showLoadingOverlay(activity: Activity, caption: CharSequence): View? = runCatching {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val view = LayoutInflater.from(NgadTheme.wrap(activity)).inflate(R.layout.ngad_view_ad_loading, root, false)
        setOverlayText(view, caption)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.FILL,
        )
        // Sit above everything (incl. system-bar backgrounds) and swallow touches to the screen below.
        view.isClickable = true
        view.elevation = 1_000_000f
        view.alpha = 0f
        root.addView(view)
        view.bringToFront()
        view.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start()
        view
    }.getOrNull()

    /** Updates the caption on a loader raised by [showLoadingOverlay] (e.g. loading → showing). */
    private fun setOverlayText(overlay: View, caption: CharSequence) {
        overlay.findViewById<TextView?>(R.id.ngad_ad_loading_text)?.text = caption
    }

    private fun removeLoadingOverlay(view: View) {
        if (view.parent !is ViewGroup) return
        view.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
        }.start()
        // Guard against the end-action never firing (e.g. detached window): hard-remove shortly after.
        view.postDelayed({ (view.parent as? ViewGroup)?.removeView(view) }, OVERLAY_FADE_MS + 50)
    }

    /**
     * Drops the cached ad and cancels any in-flight load / retry — used when ads are disabled at
     * runtime (e.g. the user goes premium). A currently-showing ad is left to finish.
     */
    fun clear() = NextGenAds.runOnMain {
        if (showing) return@runOnMain
        handler.removeCallbacksAndMessages(null)
        rewardedAd = null
        loading = false
        retryCount = 0
        flushPending(false)
    }

    private companion object {
        /** Fade duration (ms) for the loading overlay's enter/exit animation. */
        const val OVERLAY_FADE_MS = 180L
    }
}

/** Registry that keeps one [RewardedAdHelper] per ad unit alive for reuse across screens. */
object RewardedAds {

    private val helpers = ConcurrentHashMap<String, RewardedAdHelper>()

    /** Drops every cached rewarded ad across all units (e.g. on going premium / low memory). */
    @JvmStatic
    fun clearAll() = helpers.values.forEach { it.clear() }

    @JvmStatic
    fun get(adUnitId: String): RewardedAdHelper =
        helpers.getOrPut(adUnitId) { RewardedAdHelper(adUnitId) }

    /** Convenience: preload an ad unit. */
    @JvmStatic
    @JvmOverloads
    fun preload(adUnitId: String, remoteEnabled: Boolean = true) =
        get(adUnitId).load(remoteEnabled = remoteEnabled)

    /** Convenience: request (if needed) and show [adUnitId] on demand, bounded by [timeoutMs]. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = NextGenAdsConfig.rewardedForceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) = get(adUnitId).loadAndShow(activity, onReward, timeoutMs, onComplete)
}
