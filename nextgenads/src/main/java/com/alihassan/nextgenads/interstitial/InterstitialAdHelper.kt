package com.alihassan.nextgenads.interstitial

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

    val isReady: Boolean
        get() = interstitialAd != null

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
        NextGenAds.countRequest(AdFormat.INTERSTITIAL, adUnitId)
        InterstitialAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    NextGenAds.runOnMain {
                        interstitialAd = ad
                        loading = false
                        retryCount = 0
                        NextGenAds.log("Interstitial loaded: $adUnitId")
                        NextGenAds.dispatchLoaded(AdFormat.INTERSTITIAL, adUnitId)
                        flushPending(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        interstitialAd = null
                        loading = false
                        NextGenAds.log("Interstitial failed ($adUnitId): $adError")
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

        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("Interstitial shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.INTERSTITIAL, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    interstitialAd = null
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.INTERSTITIAL, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
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
        ad.show(activity)
        return true
    }

    /**
     * Counter-gated show: increments an internal call counter and only shows the interstitial on
     * every [every]-th call — e.g. `showOnCount(activity, every = 3) { … }` shows an ad on every
     * third level/screen transition. The readiness check and frequency cap of [show] still apply,
     * so a call can be counted but skip showing if no ad is ready.
     *
     * Because helpers are shared per ad unit (via [Interstitials]), the counter is app-wide for
     * that unit. [onDismiss] is always invoked (immediately when this call doesn't show an ad), so
     * callers can proceed uniformly.
     *
     * @param every show on every Nth call; values `<= 1` show on every call.
     * @return `true` if an ad is being shown.
     */
    @JvmOverloads
    fun showOnCount(activity: Activity, every: Int = 1, onDismiss: () -> Unit = {}): Boolean {
        triggerCount++
        if (every > 1 && triggerCount % every != 0) {
            onDismiss()
            return false
        }
        return show(activity, onDismiss)
    }

    /** Resets the [showOnCount] counter back to zero. */
    fun resetCounter() {
        triggerCount = 0
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
     * call. See [InterstitialAdHelper.showOnCount].
     */
    @JvmStatic
    @JvmOverloads
    fun showOnCount(
        activity: Activity,
        adUnitId: String,
        every: Int = 1,
        onDismiss: () -> Unit = {},
    ): Boolean = get(adUnitId).showOnCount(activity, every, onDismiss)
}
