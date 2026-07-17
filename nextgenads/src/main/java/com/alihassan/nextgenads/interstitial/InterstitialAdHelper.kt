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

    private val maxRetriesDefault = ConfigDefault { NextGenAdsConfig.maxRetries }

    /**
     * Maximum number of automatic reload attempts after a failed load. Defaults to
     * [NextGenAdsConfig.maxRetries]; assigning it pins the value for this unit.
     */
    var maxRetries: Int by maxRetriesDefault

    /**
     * The raw [maxRetries] override — `null` while this helper still follows [NextGenAdsConfig].
     * [SplashAd] suppresses retries for the duration of a splash load and restores through this, so
     * a unit that was following the config keeps following it afterwards instead of being pinned to
     * whatever the config read at splash time.
     */
    internal var maxRetriesOverride: Int?
        get() = maxRetriesDefault.override
        set(value) {
            maxRetriesDefault.override = value
        }

    /**
     * How long (ms) a loaded interstitial stays valid in the cache. AdMob interstitials expire
     * roughly an hour after loading; showing a stale ad fails with an "ad expired" error and the
     * show is silently lost. The helper drops (and, on the next [load], replaces) any cached ad
     * older than this, so a show request never burns on a stale ad. Defaults to
     * [NextGenAdsConfig.adValidityMs].
     */
    var adValidityMs: Long by ConfigDefault { NextGenAdsConfig.adValidityMs }

    /**
     * When `true`, the helper automatically requests the next ad after one is shown/dismissed (and
     * when [show] finds none ready). Defaults to [NextGenAdsConfig.autoReload] (`false`), so a
     * preloaded ad results in a **single** request — warm the next one explicitly via [load] /
     * [Interstitials.preload], like the native preloader. This prevents the "requested twice per
     * show" behaviour.
     */
    var autoReload: Boolean by ConfigDefault { NextGenAdsConfig.autoReload }

    /**
     * Minimum gap (ms) between two interstitials. `0` disables frequency capping. Defaults to
     * [NextGenAdsConfig.minIntervalMs].
     */
    var minIntervalMs: Long by ConfigDefault { NextGenAdsConfig.minIntervalMs }

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
     * (via [loadAndShow]) before the ad opens. A fast/warm fetch returns in a few frames, so without
     * this floor the cover would fade in halfway and be torn straight down — reading as "a small
     * delay then an ad" rather than a real loading state. This only *pads* a fetch that finished
     * sooner than the floor; a slower fetch is never delayed, and a cached ad ([isReady]) still
     * shows instantly. Defaults to [NextGenAdsConfig.minLoadingCoverMs]; `0` disables the floor.
     */
    var minLoadingCoverMs: Long by ConfigDefault { NextGenAdsConfig.minLoadingCoverMs }

    /**
     * Caption shown on the loading cover while an ad is being fetched on demand. Set from the host
     * app to localise / rebrand it (e.g. `Interstitials.get(unit).loadingText = "Preparing…"`).
     * `null` (default) falls back to the `ngad_ad_loading` string resource — which the app can also
     * override by redeclaring that string.
     */
    var loadingText: CharSequence? = null

    /**
     * Caption shown on the cover for the brief interlude right before the ad opens. `null` (default)
     * falls back to the `ngad_ad_showing` string resource.
     */
    var showingText: CharSequence? = null

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
    fun load(
        remoteEnabled: Boolean = true,
        onResult: ((Boolean) -> Unit)? = null,
    ) = NextGenAds.runOnMain {
        if (!remoteEnabled || !NextGenAds.canRequest(AdFormat.INTERSTITIAL)) {
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
                                if (NextGenAds.canRequest(AdFormat.INTERSTITIAL)) {
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
     * [timeoutMs] bounds the wait: if the ad hasn't loaded by then, [onComplete] fires so the caller
     * proceeds, and the in-flight load is left to warm the cache for next time. Defaults to
     * [NextGenAdsConfig.forceShowTimeoutMs]; `0` waits for the load result (which is itself bounded
     * by the retry budget).
     *
     * [onComplete] is invoked exactly once — after the ad is dismissed, on failure/timeout, or
     * synchronously when ads are disabled.
     */
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) {
        if (!NextGenAds.canShowAds(AdFormat.INTERSTITIAL)) {
            onComplete()
            return
        }
        if (isReady) {
            show(activity, onComplete)
            return
        }

        // Always cover the genuine on-demand fetch — this isn't an artificial delay, it hides real
        // network latency so the user isn't left on a frozen screen. show() reveals the ad the moment
        // it loads (flipping the caption to "Showing ad…") and drops the cover when it renders.
        val overlayRaisedAt = SystemClock.elapsedRealtime()
        val overlay = showLoadingOverlay(activity, activity.loadingCaption())

        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            overlay?.let { removeLoadingOverlay(it) }
            NextGenAds.log("Interstitial load timed out ($adUnitId); proceeding")
            onComplete()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load // timeout already let the caller proceed
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            // The activity may have died while the load was in flight — keep the ad cached for the
            // next trigger instead of burning it on a show that cannot render.
            if (loaded && interstitialAd != null && !activity.isFinishing && !activity.isDestroyed) {
                // Hold the "Loading ad…" cover for at least minLoadingCoverMs so a fast/warm fetch
                // reads as a genuine loading state instead of a flash. A slow fetch already exceeded
                // the floor and shows with no extra wait.
                val remaining = minLoadingCoverMs - (SystemClock.elapsedRealtime() - overlayRaisedAt)
                val reveal = Runnable {
                    if (activity.isFinishing || activity.isDestroyed) {
                        overlay?.let { removeLoadingOverlay(it) }
                        onComplete()
                    } else {
                        show(activity, onComplete, overlay)
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
     * Shows the ad if one is ready, the frequency cap allows it and no other full-screen ad is on
     * screen, then preloads the next one. Must be called on the main thread.
     *
     * @param preloadedOverlay a full-screen loader already on screen (e.g. the "Loading ad…" cover
     *   raised by [loadAndShow] during the fetch). When non-null it is reused — its text is flipped
     *   to "Showing ad…" for the interlude — so there's no remove/re-add flicker between phases.
     * @return `true` if the ad is being shown. When `false`, [onComplete] has already been invoked
     *   synchronously so the caller can proceed immediately (no ad was available).
     */
    fun show(activity: Activity, onComplete: () -> Unit, preloadedOverlay: View? = null): Boolean {
        if (!NextGenAds.canShowAds(AdFormat.INTERSTITIAL)) {
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onComplete()
            return false
        }
        evictIfExpired()
        val ad = interstitialAd
        val now = SystemClock.elapsedRealtime()
        val capped = minIntervalMs > 0 && lastShownElapsed > 0 && now - lastShownElapsed < minIntervalMs
        if (ad == null || capped) {
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onComplete()
            if (autoReload) load() // opt-in: make the next attempt have an ad ready
            return false
        }
        if (showing || !NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("Interstitial show skipped ($adUnitId): a full-screen ad is already showing")
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onComplete()
            return false
        }
        // Committed: take ownership of the cached ad so a concurrent show()/load() can't reuse it
        // (its event callback is now bound to this caller's onComplete).
        showing = true
        interstitialAd = null

        var overlay: View? = preloadedOverlay
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
                    onComplete()
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
                    onComplete()
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
            // No artificial dwell, but still bridge the show→render gap with the cover so the screen
            // isn't left frozen while the SDK brings the ad up: reuse a carried-over loader (from a
            // fetch) or raise one now, flip it to "Showing ad…", show immediately, and drop it the
            // moment the ad renders (callbacks above).
            overlay = overlay?.also { setOverlayText(it, activity.showingCaption()) }
                ?: showLoadingOverlay(activity, activity.showingCaption())
            ad.show(activity)
            return true
        }

        // Full-screen "Showing ad…" interlude: cover the screen for loadingOverlayMs, then open the
        // ad. The overlay is a view attached to the activity's own decor (not a separate Dialog
        // window) so it fills the whole screen and fades in smoothly with no window-handoff flash.
        // It stays up until the ad actually renders (removed in the shown/failed callbacks above) so
        // the underlying screen never shows through. Reuse loadAndShow's "Loading ad…" cover when it
        // handed one in (flip its text) so there's no flicker between the two phases.
        overlay = overlay?.also { setOverlayText(it, activity.showingCaption()) }
            ?: showLoadingOverlay(activity, activity.showingCaption())
        handler.postDelayed({
            val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (activity.isFinishing || activity.isDestroyed || !appInForeground) {
                // The user left (home button / activity died) during the interlude — showing now
                // would pop an ad at an unexpected moment. Keep it cached for the next trigger.
                dismissOverlay()
                abortShow()
                onComplete()
                return@postDelayed
            }
            ad.show(activity)
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
        val view = LayoutInflater.from(activity).inflate(R.layout.ngad_view_ad_loading, root, false)
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
     * On a gated-in trigger, either shows the cached ad or — when [forceLoad] is `true` and nothing
     * is cached — requests one on demand and shows it as soon as it loads (via [loadAndShow]),
     * bounded by [timeoutMs]. With [forceLoad] `false` this is a plain [show] that skips when no ad
     * is ready.
     *
     * @return `true` if an ad is being shown or (when forced) is being loaded to show; `false` only
     *   when nothing is ready and [forceLoad] is off — in which case [onComplete] has already fired.
     */
    private fun showOrForceLoad(
        activity: Activity,
        forceLoad: Boolean,
        timeoutMs: Long,
        onComplete: () -> Unit,
    ): Boolean {
        if (forceLoad && !isReady) {
            loadAndShow(activity, timeoutMs, onComplete)
            return true
        }
        return show(activity, onComplete)
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
     * that unit. [onComplete] is always invoked (immediately when this call doesn't show an ad), so
     * callers can proceed uniformly.
     *
     * @param nth show on every Nth call; values `<= 1` show on every call.
     * @param forceLoad when the gate opens with no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait, defaulting to
     *   [NextGenAdsConfig.forceShowTimeoutMs]; `0` waits for the load result. Only used when
     *   [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showEvery(
        activity: Activity,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ): Boolean {
        triggerCount++
        if (nth > 1 && triggerCount % nth != 0) {
            onComplete()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onComplete)
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
     * The readiness check and frequency cap of [show] still apply; [onComplete] is always invoked so
     * callers can proceed uniformly. Because helpers are shared per ad unit (via [Interstitials]),
     * the counter is app-wide.
     *
     * @param nth clicks between shows after the first; values `<= 1` show on every call.
     * @param forceLoad when a gated-in call has no cached ad, load one on demand and show it.
     * @param timeoutMs upper bound (ms) on the forced-load wait, defaulting to
     *   [NextGenAdsConfig.forceShowTimeoutMs]; `0` waits for the load result. Only used when
     *   [forceLoad] is `true`.
     * @return `true` if an ad is being shown (or, when forced, is being loaded to show).
     */
    @JvmOverloads
    fun showFirstThenEvery(
        activity: Activity,
        nth: Int = 1,
        forceLoad: Boolean = false,
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ): Boolean {
        val count = ++triggerCount
        // Show on 1, then 1 + nth, 1 + 2*nth … i.e. whenever (count - 1) is a multiple of nth.
        val shouldShow = nth <= 1 || (count - 1) % nth == 0
        if (!shouldShow) {
            onComplete()
            return false
        }
        return showOrForceLoad(activity, forceLoad, timeoutMs, onComplete)
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

    /**
     * Convenience: preload an ad unit.
     *
     * @param onResult invoked on the main thread with `true` once the ad is cached, or `false` if the
     *   load was refused ([remoteEnabled] off, premium, kill-switch) or failed after the retry budget
     *   is spent. Fires immediately with `true` when an ad is already cached.
     */
    @JvmStatic
    @JvmOverloads
    fun preload(
        adUnitId: String,
        remoteEnabled: Boolean = true,
        onResult: ((Boolean) -> Unit)? = null,
    ) = get(adUnitId).load(remoteEnabled = remoteEnabled, onResult = onResult)

    /** Drops every cached interstitial across all units (e.g. on going premium / low memory). */
    @JvmStatic
    fun clearAll() = helpers.values.forEach { it.clear() }

    /** Convenience: request (if needed) and show [adUnitId] on demand, bounded by [timeoutMs]. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) = get(adUnitId).loadAndShow(activity, timeoutMs, onComplete)

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
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ): Boolean = get(adUnitId).showEvery(activity, nth, forceLoad, timeoutMs, onComplete)

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
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ): Boolean = get(adUnitId).showFirstThenEvery(activity, nth, forceLoad, timeoutMs, onComplete)
}
