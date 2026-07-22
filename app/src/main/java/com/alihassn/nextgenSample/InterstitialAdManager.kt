package com.alihassn.nextgenSample

import android.app.Activity
import android.util.Log
import com.alihassan.nextgenads.interstitial.Interstitials

/**
 * Interstitial-ad manager (Next-Gen Ads SDK) — the interstitial counterpart to [NativeAdManager]'s
 * cross-screen flow: **request on screen A, show on screen B**.
 *
 * Warm an ad up front with [preload] while the user is busy; a later trigger checks [isReady] and
 * either shows the cached ad instantly ([showIfReady]) or requests one on demand and shows it the
 * moment it loads ([showOrLoad]). Nothing is ever double-requested and a transient load failure is
 * retried with exponential backoff, so a single failed request doesn't cost the show.
 *
 * State is shared **per ad unit** across the whole app (via the library's [Interstitials] registry),
 * so two `InterstitialAdManager` instances for the same unit reuse the same cached ad — screen B
 * gets exactly what screen A preloaded. This is a thin, ergonomic facade over the tested
 * [com.alihassan.nextgenads.interstitial.InterstitialAdHelper]; you can also drive [Interstitials]
 * directly for the counter-gated shows (`showEvery` / `showFirstThenEvery`).
 *
 * ```
 * private val ads = InterstitialAdManager(BuildConfig.INTERSTITIAL_UNIT)
 *
 * // Screen A — warm it ahead of time:
 * ads.preload()
 *
 * // Screen B — reuse it if ready, otherwise fetch-and-show (bounded), then proceed:
 * ads.showOrLoad(activity) { goToNextScreen() }
 * ```
 *
 * Requires [com.alihassan.nextgenads.NextGenAds.initialize] to have completed first.
 */
class InterstitialAdManager(private val adUnitId: String) {

    /** A non-expired ad is cached and ready to show instantly (no request needed). */
    val isReady: Boolean
        get() = Interstitials.get(adUnitId).isReady

    /** True while this unit's interstitial is on screen (or committed to showing). */
    val isShowing: Boolean
        get() = Interstitials.get(adUnitId).isShowing

    /**
     * Warms the ad if not already cached / in flight. Safe to call repeatedly — it never fires a
     * second request while one is in flight or ready.
     *
     * @param onResult invoked on the main thread with `true` once cached, or `false` if the load was
     *   refused (premium / kill-switch / [remoteEnabled] off) or failed after the retry budget.
     */
    @JvmOverloads
    fun preload(remoteEnabled: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
        log("preload requested (ready=$isReady, remoteEnabled=$remoteEnabled): $adUnitId")
        Interstitials.preload(adUnitId, remoteEnabled) { loaded ->
            log("preload ${if (loaded) "ready" else "unavailable"}: $adUnitId")
            onResult?.invoke(loaded)
        }
    }

    /**
     * Shows the cached ad **only if one is ready** (and the frequency cap allows it), then returns.
     * Never requests on demand — use [showOrLoad] for that. [onComplete] is always invoked: after
     * the ad is dismissed, or immediately when nothing is ready.
     *
     * @return `true` if an ad is being shown.
     */
    @JvmOverloads
    fun showIfReady(activity: Activity, onComplete: () -> Unit = {}): Boolean {
        val ready = isReady
        log("showIfReady (ready=$ready): $adUnitId")
        return Interstitials.get(adUnitId).show(activity, onComplete = {
            log("show finished (dismissed or nothing to show): $adUnitId")
            onComplete()
        })
    }

    /**
     * Shows the cached ad immediately if ready; otherwise requests one and shows it the moment it
     * loads, covering the fetch with a loading overlay and bounding the wait by [timeoutMs] (a slow
     * fetch is left to warm the cache for next time). [onComplete] fires exactly once — after
     * dismissal, on timeout/failure, or immediately when ads are disabled.
     */
    @JvmOverloads
    fun showOrLoad(
        activity: Activity,
        timeoutMs: Long = com.alihassan.nextgenads.NextGenAdsConfig.forceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) {
        log(
            if (isReady) "showOrLoad — showing cached ad instantly: $adUnitId"
            else "showOrLoad — none ready, fetching then showing (timeout ${timeoutMs}ms): $adUnitId",
        )
        Interstitials.loadAndShow(activity, adUnitId, timeoutMs) {
            log("showOrLoad complete: $adUnitId")
            onComplete()
        }
    }

    /** Drops the cached ad and cancels any in-flight load / retry for this unit (e.g. on premium). */
    fun clear() {
        log("clear cached interstitial: $adUnitId")
        Interstitials.get(adUnitId).clear()
    }

    /** Debug-only Logcat line (`adb logcat -s $TAG`). Silent in release builds. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "InterstitialAdMgr"
    }
}
