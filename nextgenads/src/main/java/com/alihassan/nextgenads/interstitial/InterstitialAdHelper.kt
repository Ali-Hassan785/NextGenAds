package com.alihassan.nextgenads.interstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and shows a single interstitial ad unit (Next-Gen SDK) with automatic preloading,
 * exponential-backoff retries and cache expiry — tuned for a high show-rate. Set [autoReload] to
 * `true` to request a fresh ad automatically after each dismissal, or warm the next one yourself
 * via [load] / [Interstitials.preload].
 *
 * Prefer obtaining instances through [Interstitials.get] so the same cached ad is reused across
 * screens. Requires [NextGenAds.initialize] to have completed first.
 */
class InterstitialAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var interstitialAd: InterstitialAd? = null
    private var loadedAtElapsed = 0L
    private var loading = false
    private var showing = false
    private var retryCount = 0
    private var lastShownElapsed = 0L
    private var loadStartElapsed = 0L
    private var triggerCount = 0
    // Callbacks waiting on the single in-flight load. Concurrent load() callers all get notified,
    // instead of every caller-after-the-first being silently dropped.
    private val pending = mutableListOf<(Boolean) -> Unit>()

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    /**
     * How long (ms) a loaded interstitial stays valid in the cache. AdMob interstitials expire
     * roughly an hour after loading; showing a stale ad fails with an "ad expired" error and the
     * show is silently lost. The helper drops (and, on the next [load], replaces) any cached ad
     * older than this, so a show request never burns on a stale ad.
     */
    var adValidityMs = 55 * 60 * 1000L

    /**
     * When `true`, the helper automatically requests the next ad after one is shown/dismissed (and
     * when [show] finds none ready). Default `false` so a preloaded ad results in a **single**
     * request — warm the next one explicitly via [load] / [Interstitials.preload], like the native
     * preloader. This prevents the "requested twice per show" behaviour.
     */
    var autoReload = false

    /** Minimum gap (ms) between two interstitials. `0` disables frequency capping. */
    var minIntervalMs = 0L

    /**
     * Duration (ms) of the full-screen "Loading ad…" interlude displayed before the interstitial
     * opens, so the ad doesn't pop in abruptly. Set to `0` to show the ad immediately.
     */
    var loadingOverlayMs = 1000L

    /** A non-expired ad is cached and ready to show. */
    val isReady: Boolean
        get() = interstitialAd != null && !isExpired

    /** `true` while this helper's interstitial is on screen (or committed to showing). */
    val isShowing: Boolean
        get() = showing

    private val isExpired: Boolean
        get() = SystemClock.elapsedRealtime() - loadedAtElapsed >= adValidityMs

    /** Drops a cached ad that has outlived [adValidityMs] so it is never offered to [show]. */
    private fun evictIfExpired() {
        if (interstitialAd != null && isExpired) {
            NextGenAds.log("Interstitial expired after ${adValidityMs / 60_000}min, dropping: $adUnitId")
            interstitialAd = null
        }
    }

    /** Wall-clock time (ms) the most recent successful load took, or `-1` if none has loaded yet. */
    var lastLoadMs: Long = -1L
        private set

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
        if (interstitialAd != null) {
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
        loadStartElapsed = SystemClock.elapsedRealtime()
        NextGenAds.countRequest(AdFormat.INTERSTITIAL, adUnitId)
        InterstitialAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    NextGenAds.runOnMain {
                        interstitialAd = ad
                        loadedAtElapsed = SystemClock.elapsedRealtime()
                        loading = false
                        retryCount = 0
                        val loadMs = SystemClock.elapsedRealtime() - loadStartElapsed
                        lastLoadMs = loadMs
                        NextGenAds.log("Interstitial loaded: $adUnitId (load ${loadMs}ms)")
                        NextGenAds.dispatchLoaded(AdFormat.INTERSTITIAL, adUnitId)
                        flushPending(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        interstitialAd = null
                        val loadMs = SystemClock.elapsedRealtime() - loadStartElapsed
                        NextGenAds.log("Interstitial failed ($adUnitId) after ${loadMs}ms: $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.INTERSTITIAL, adUnitId, adError)
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
     * On-demand "request and show": show the cached ad immediately if one is ready, otherwise
     * request one and show it the moment it loads — a higher-show-rate alternative to [show] for a
     * trigger point where nothing was preloaded.
     *
     * [timeoutMs] bounds the wait: if the ad hasn't loaded by then, [onDismiss] fires so the caller
     * proceeds, and the in-flight load is left to warm the cache for next time. `0` waits for the
     * load result (which is itself bounded by the retry budget).
     *
     * [onDismiss] is invoked exactly once — after the ad is dismissed, on failure/timeout, or
     * synchronously when ads are disabled.
     */
    @JvmOverloads
    fun loadAndShow(activity: Activity, timeoutMs: Long = 0L, onDismiss: () -> Unit = {}) {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return
        }
        if (isReady) {
            show(activity, onDismiss)
            return
        }

        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            NextGenAds.log("Interstitial load timed out ($adUnitId); proceeding")
            onDismiss()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load // timeout already let the caller proceed
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            // The activity may have died while the load was in flight — keep the ad cached for the
            // next trigger instead of burning it on a show that cannot render.
            if (loaded && interstitialAd != null && !activity.isFinishing && !activity.isDestroyed) {
                show(activity, onDismiss)
            } else {
                onDismiss()
            }
        }
    }

    /**
     * Shows the ad if one is ready, the frequency cap allows it and no other full-screen ad is on
     * screen, then preloads the next one. Must be called on the main thread.
     *
     * @return `true` if the ad is being shown. When `false`, [onDismiss] has already been invoked
     *   synchronously so the caller can proceed immediately (no ad was available).
     */
    fun show(activity: Activity, onDismiss: () -> Unit): Boolean {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return false
        }
        evictIfExpired()
        val ad = interstitialAd
        val now = SystemClock.elapsedRealtime()
        val capped = minIntervalMs > 0 && lastShownElapsed > 0 && now - lastShownElapsed < minIntervalMs
        if (ad == null || capped) {
            onDismiss()
            if (autoReload) load() // opt-in: make the next attempt have an ad ready
            return false
        }
        if (showing || !NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("Interstitial show skipped ($adUnitId): a full-screen ad is already showing")
            onDismiss()
            return false
        }
        // Committed: take ownership of the cached ad so a concurrent show()/load() can't reuse it
        // (its event callback is now bound to this caller's onDismiss).
        showing = true
        interstitialAd = null

        var overlay: View? = null
        fun dismissOverlay() {
            overlay?.let { removeLoadingOverlay(it) }
            overlay = null
        }

        // The show never happened (activity/app went away first): put the ad back for the next
        // trigger and free the full-screen slot.
        fun abortShow() {
            showing = false
            interstitialAd = ad
            NextGenAds.endFullScreenShow()
        }

        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.runOnMain { dismissOverlay() }
                NextGenAds.log("Interstitial shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.INTERSTITIAL, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    dismissOverlay()
                    showing = false
                    NextGenAds.endFullScreenShow()
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.INTERSTITIAL, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    dismissOverlay()
                    showing = false
                    NextGenAds.endFullScreenShow()
                    NextGenAds.log("Interstitial show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.INTERSTITIAL, adUnitId, fullScreenContentError)
                    if (autoReload) load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.INTERSTITIAL, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.INTERSTITIAL, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.INTERSTITIAL, adUnitId, value, ad.getResponseInfo())
            }
        }
        NextGenAds.log("Interstitial show requested: $adUnitId")
        if (loadingOverlayMs <= 0) {
            ad.show(activity)
            return true
        }

        // Full-screen "Loading ad…" interlude: cover the screen for loadingOverlayMs, then open the
        // ad. The overlay is a view attached to the activity's own decor (not a separate Dialog
        // window) so it fills the whole screen and fades in smoothly with no window-handoff flash.
        // It stays up until the ad actually renders (removed in the shown/failed callbacks above) so
        // the underlying screen never shows through.
        overlay = showLoadingOverlay(activity)
        handler.postDelayed({
            val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (activity.isFinishing || activity.isDestroyed || !appInForeground) {
                // The user left (home button / activity died) during the interlude — showing now
                // would pop an ad at an unexpected moment. Keep it cached for the next trigger.
                dismissOverlay()
                abortShow()
                onDismiss()
                return@postDelayed
            }
            ad.show(activity)
        }, loadingOverlayMs)
        return true
    }

    /**
     * Attaches a full-screen "Loading ad…" view to the activity's decor view and fades it in.
     * Returns the attached view (or `null` if it couldn't be attached), to be passed to
     * [removeLoadingOverlay] once the ad renders.
     */
    private fun showLoadingOverlay(activity: Activity): View? = runCatching {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val view = LayoutInflater.from(activity).inflate(R.layout.ngad_view_ad_loading, root, false)
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

    private fun removeLoadingOverlay(view: View) {
        if (view.parent !is ViewGroup) return
        view.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
        }.start()
        // Guard against the end-action never firing (e.g. detached window): hard-remove shortly after.
        view.postDelayed({ (view.parent as? ViewGroup)?.removeView(view) }, OVERLAY_FADE_MS + 50)
    }

    /**
     * On a gated-in trigger, either shows the cached ad or — when [forceLoad] is `true` and nothing
     * is cached — requests one on demand and shows it as soon as it loads (via [loadAndShow]),
     * bounded by [timeoutMs]. With [forceLoad] `false` this is a plain [show] that skips when no ad
     * is ready.
     *
     * @return `true` if an ad is being shown or (when forced) is being loaded to show; `false` only
     *   when nothing is ready and [forceLoad] is off — in which case [onDismiss] has already fired.
     */
    private fun showOrForceLoad(
        activity: Activity,
        forceLoad: Boolean,
        timeoutMs: Long,
        onDismiss: () -> Unit,
    ): Boolean {
        if (forceLoad && !isReady) {
            loadAndShow(activity, timeoutMs, onDismiss)
            return true
        }
        return show(activity, onDismiss)
    }

    /**
     * Counter-gated show: increments an internal call counter and only shows the interstitial on
     * every [nth]-th call — e.g. `showEvery(activity, nth = 3) { … }` shows an ad on every third
     * level/screen transition. The readiness check and frequency cap of [show] still apply, so a
     * call can be counted but skip showing if no ad is ready.
     *
     * When [forceLoad] is `true` and the gate opens with no ad cached, the ad is requested on demand
     * and shown as soon as it loads (via [loadAndShow], bounded by [timeoutMs]) instead of skipping
     * — so a counted trigger isn't wasted when nothing was preloaded. Leave it `false` (default) to
     * only show an already-ready ad.
     *
     * Because helpers are shared per ad unit (via [Interstitials]), the counter is app-wide for
     * that unit. [onDismiss] is always invoked (immediately when this call doesn't show an ad), so
     * callers can proceed uniformly.
     *
     * @param nth show on every Nth call; values `<= 1` show on every call.
     * @param forceLoad when the gate opens with no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait; `0` waits for the load result. Only
     *   used when [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showEvery(
        activity: Activity,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean {
        triggerCount++
        if (nth > 1 && triggerCount % nth != 0) {
            onDismiss()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onDismiss)
    }

    /**
     * "Show first, then every Nth" counter-gated show — shows on the **first** call and then on
     * every [nth]-th call afterwards. With `nth = 4` an ad shows on call 1, 5, 9, 13, … (i.e. the
     * first click, then after every 4 clicks). This differs from [showEvery], which shows on
     * multiples (N, 2N, 3N …) and never on the first call.
     *
     * When [forceLoad] is `true` and a gated-in call finds no cached ad, the ad is requested on
     * demand and shown as soon as it loads (via [loadAndShow], bounded by [timeoutMs]) instead of
     * skipping. Leave it `false` (default) to only show an already-ready ad.
     *
     * The readiness check and frequency cap of [show] still apply; [onDismiss] is always invoked so
     * callers can proceed uniformly. Because helpers are shared per ad unit (via [Interstitials]),
     * the counter is app-wide.
     *
     * @param nth clicks between shows after the first; values `<= 1` show on every call.
     * @param forceLoad when a gated-in call has no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait; `0` waits for the load result. Only
     *   used when [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showFirstThenEvery(
        activity: Activity,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean {
        val count = ++triggerCount
        // Show on 1, then 1 + nth, 1 + 2*nth … i.e. whenever (count - 1) is a multiple of nth.
        val shouldShow = nth <= 1 || (count - 1) % nth == 0
        if (!shouldShow) {
            onDismiss()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onDismiss)
    }

    /** Resets the [showEvery] / [showFirstThenEvery] counter back to zero. */
    fun resetTriggerCount() {
        triggerCount = 0
    }

    /**
     * Drops the cached ad and cancels any in-flight load / retry — used when ads are disabled at
     * runtime (e.g. the user goes premium). A currently-showing ad is left to finish.
     */
    fun clear() = NextGenAds.runOnMain {
        if (showing) return@runOnMain
        handler.removeCallbacksAndMessages(null)
        interstitialAd = null
        loading = false
        retryCount = 0
        flushPending(false)
    }

    private companion object {
        /** Fade duration (ms) for the loading overlay's enter/exit animation. */
        const val OVERLAY_FADE_MS = 180L
    }
}

/** Registry that keeps one [InterstitialAdHelper] per ad unit alive for reuse across screens. */
object Interstitials {

    private val helpers = ConcurrentHashMap<String, InterstitialAdHelper>()

    @JvmStatic
    fun get(adUnitId: String): InterstitialAdHelper =
        helpers.getOrPut(adUnitId) { InterstitialAdHelper(adUnitId) }

    /** Convenience: preload an ad unit. */
    @JvmStatic
    fun preload(adUnitId: String) = get(adUnitId).load()

    /** Drops every cached interstitial across all units (e.g. on going premium / low memory). */
    @JvmStatic
    fun clearAll() = helpers.values.forEach { it.clear() }

    /** Convenience: request (if needed) and show [adUnitId] on demand, bounded by [timeoutMs]. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ) = get(adUnitId).loadAndShow(activity, timeoutMs, onDismiss)

    /**
     * Convenience: counter-gated show for an ad unit — shows the interstitial on every [nth]-th
     * call. When [forceLoad] is `true` and the gate opens with no cached ad, one is loaded on demand
     * (bounded by [timeoutMs]) and shown. See [InterstitialAdHelper.showEvery].
     */
    @JvmStatic
    @JvmOverloads
    fun showEvery(
        activity: Activity,
        adUnitId: String,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean = get(adUnitId).showEvery(activity, nth, forceLoad, timeoutMs, onDismiss)

    /**
     * Convenience: "show first, then every Nth" counter-gated show — shows on the first call and
     * then every [nth]-th call afterwards (call 1, 1 + nth, 1 + 2*nth …). When [forceLoad] is
     * `true` and a gated-in call has no cached ad, one is loaded on demand (bounded by [timeoutMs])
     * and shown. See [InterstitialAdHelper.showFirstThenEvery].
     */
    @JvmStatic
    @JvmOverloads
    fun showFirstThenEvery(
        activity: Activity,
        adUnitId: String,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean = get(adUnitId).showFirstThenEvery(activity, nth, forceLoad, timeoutMs, onDismiss)
}
