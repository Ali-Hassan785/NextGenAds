package com.alihassan.nextgenads.interstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.alihassan.nextgenads.NextGenAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
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

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

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

        InterstitialAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    NextGenAds.runOnMain {
                        interstitialAd = ad
                        loading = false
                        retryCount = 0
                        NextGenAds.log("Interstitial loaded: $adUnitId")
                        onResult?.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        interstitialAd = null
                        loading = false
                        NextGenAds.log("Interstitial failed ($adUnitId): $adError")
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
            load() // make sure the next attempt has an ad ready
            return false
        }

        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("Interstitial shown: $adUnitId")
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    interstitialAd = null
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    load()
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    interstitialAd = null
                    NextGenAds.log("Interstitial show failed ($adUnitId): $fullScreenContentError")
                    load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {}

            override fun onAdClicked() {}
        }
        ad.show(activity)
        return true
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
}
