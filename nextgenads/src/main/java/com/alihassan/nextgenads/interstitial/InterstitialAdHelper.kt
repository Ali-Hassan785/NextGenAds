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
 * Loads and shows a single interstitial ad unit (Next-Gen SDK) with automatic preloading and
 * exponential-backoff retries — tuned for a high show-rate: a fresh ad is requested immediately
 * after each dismissal.
 *
 * Prefer obtaining instances through [Interstitials.get] so the same cached ad is reused across
 * screens. Requires [NextGenAds.initialize] to have completed first.
 */
class InterstitialAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var interstitialAd: InterstitialAd? = null
    private var loading = false
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

    val isReady: Boolean
        get() = interstitialAd != null

    /** Wall-clock time (ms) the most recent successful load took, or `-1` if none has loaded yet. */
    var lastLoadMs: Long = -1L
        private set

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canRequest()) {
            onResult?.invoke(false)
            return
        }
        if (interstitialAd != null) {
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
        loadStartElapsed = SystemClock.elapsedRealtime()
        NextGenAds.countRequest(AdFormat.INTERSTITIAL, adUnitId)
        InterstitialAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    NextGenAds.runOnMain {
                        interstitialAd = ad
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
                        loading = false
                        val loadMs = SystemClock.elapsedRealtime() - loadStartElapsed
                        NextGenAds.log("Interstitial failed ($adUnitId) after ${loadMs}ms: $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.INTERSTITIAL, adUnitId, adError)
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
        if (interstitialAd != null) {
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
            if (loaded && interstitialAd != null) show(activity, onDismiss) else onDismiss()
        }
    }

    /**
     * Shows the ad if one is ready and the frequency cap allows it, then preloads the next one.
     *
     * @return `true` if the ad is being shown. When `false`, [onDismiss] has already been invoked
     *   synchronously so the caller can proceed immediately (no ad was available).
     */
    fun show(activity: Activity, onDismiss: () -> Unit): Boolean {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return false
        }
        val ad = interstitialAd
        val now = SystemClock.elapsedRealtime()
        val capped = minIntervalMs > 0 && lastShownElapsed > 0 && now - lastShownElapsed < minIntervalMs
        if (ad == null || capped) {
            onDismiss()
            if (autoReload) load() // opt-in: make the next attempt have an ad ready
            return false
        }

        var overlay: View? = null
        fun dismissOverlay() {
            overlay?.let { removeLoadingOverlay(it) }
            overlay = null
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
                    interstitialAd = null
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.INTERSTITIAL, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    dismissOverlay()
                    interstitialAd = null
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
            if (activity.isFinishing || activity.isDestroyed) {
                dismissOverlay()
                onDismiss() // ad stays cached for the next trigger
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
        if (forceLoad && interstitialAd == null) {
            loadAndShow(activity, timeoutMs, onDismiss)
            return true
        }
        return show(activity, onDismiss)
    }

    /**
     * Counter-gated show: increments an internal call counter and only shows the interstitial on
     * every [every]-th call — e.g. `showOnCount(activity, every = 3) { … }` shows an ad on every
     * third level/screen transition. The readiness check and frequency cap of [show] still apply,
     * so a call can be counted but skip showing if no ad is ready.
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
     * @param every show on every Nth call; values `<= 1` show on every call.
     * @param forceLoad when the gate opens with no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait; `0` waits for the load result. Only
     *   used when [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showOnCount(
        activity: Activity,
        every: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean {
        triggerCount++
        if (every > 1 && triggerCount % every != 0) {
            onDismiss()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onDismiss)
    }

    /**
     * "Show first, then every Nth" counter-gated show — shows on the **first** call and then on
     * every [interval]-th call afterwards. With `interval = 4` an ad shows on call 1, 5, 9, 13, …
     * (i.e. the first click, then after every 4 clicks). This differs from [showOnCount], which
     * shows on multiples (N, 2N, 3N …) and never on the first call.
     *
     * When [forceLoad] is `true` and a gated-in call finds no cached ad, the ad is requested on
     * demand and shown as soon as it loads (via [loadAndShow], bounded by [timeoutMs]) instead of
     * skipping. Leave it `false` (default) to only show an already-ready ad.
     *
     * The readiness check and frequency cap of [show] still apply; [onDismiss] is always invoked so
     * callers can proceed uniformly. Because helpers are shared per ad unit (via [Interstitials]),
     * the counter is app-wide.
     *
     * @param interval clicks between shows after the first; values `<= 1` show on every call.
     * @param forceLoad when a gated-in call has no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait; `0` waits for the load result. Only
     *   used when [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showFirstThenEvery(
        activity: Activity,
        interval: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean {
        val count = ++triggerCount
        // Show on 1, then 1 + interval, 1 + 2*interval … i.e. whenever (count - 1) is a multiple of interval.
        val shouldShow = interval <= 1 || (count - 1) % interval == 0
        if (!shouldShow) {
            onDismiss()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onDismiss)
    }

    /** Resets the [showOnCount] / [showFirstThenEvery] counter back to zero. */
    fun resetCounter() {
        triggerCount = 0
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
     * Convenience: counter-gated show for an ad unit — shows the interstitial on every [every]-th
     * call. When [forceLoad] is `true` and the gate opens with no cached ad, one is loaded on demand
     * (bounded by [timeoutMs]) and shown. See [InterstitialAdHelper.showOnCount].
     */
    @JvmStatic
    @JvmOverloads
    fun showOnCount(
        activity: Activity,
        adUnitId: String,
        every: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean = get(adUnitId).showOnCount(activity, every, forceLoad, timeoutMs, onDismiss)

    /**
     * Convenience: "show first, then every Nth" counter-gated show — shows on the first call and
     * then every [interval]-th call afterwards (call 1, 1 + interval, 1 + 2*interval …). When
     * [forceLoad] is `true` and a gated-in call has no cached ad, one is loaded on demand (bounded
     * by [timeoutMs]) and shown. See [InterstitialAdHelper.showFirstThenEvery].
     */
    @JvmStatic
    @JvmOverloads
    fun showFirstThenEvery(
        activity: Activity,
        adUnitId: String,
        interval: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ): Boolean = get(adUnitId).showFirstThenEvery(activity, interval, forceLoad, timeoutMs, onDismiss)
}
