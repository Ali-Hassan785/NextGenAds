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
        if (!NextGenAds.canShowAds()) {
            onResult?.invoke(false)
            return
        }
        if (interstitialAd != null) {
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
                        onResult?.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        interstitialAd = null
                        loading = false
                        NextGenAds.log("Interstitial failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.INTERSTITIAL, adUnitId, adError)
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
