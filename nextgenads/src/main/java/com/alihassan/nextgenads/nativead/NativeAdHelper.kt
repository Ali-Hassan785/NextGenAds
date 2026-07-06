package com.alihassan.nextgenads.nativead

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import java.util.ArrayDeque

/**
 * Loads native ads (Next-Gen SDK) and keeps a small per-unit cache so a template can be filled
 * instantly, which is the single biggest lever on native show-rate.
 *
 * Show-rate is maximised by three cooperating mechanisms:
 * - **Preload cache** — [preload] warms up to [maxCachePerUnit] ads so [populate] binds in <1ms.
 * - **Retry with backoff** — a failed load is retried up to [maxRetries] times (1s, 2s, 4s …) so a
 *   flaky / slow connection self-heals instead of leaving the placement empty.
 * - **Await-in-flight** — when [populate] runs with an empty cache but a load is already in flight
 *   (e.g. a preload that hasn't landed yet), it *waits* for that load instead of firing a second,
 *   competing request. Multiple placements waiting on the same unit each get their own load, but no
 *   request is ever duplicated.
 *
 * Load methods require [NextGenAds.initialize] to have completed first (the SDK supplies the
 * application context, so none is needed here).
 */
object NativeAdHelper {

    private val handler = Handler(Looper.getMainLooper())
    private val pool = HashMap<String, ArrayDeque<CachedAd>>()
    private val inFlight = HashMap<String, Int>()
    // Placements waiting for the next ad of a unit. Delivered a NativeAd on success, or null (with
    // the LoadAdError) once retries are exhausted, so the placement can collapse instead of
    // shimmering forever.
    private val waiters = HashMap<String, ArrayDeque<(NativeAd?, LoadAdError?) -> Unit>>()

    /** A pooled ad plus its load timestamp, so stale inventory can be evicted instead of bound. */
    private class CachedAd(val ad: NativeAd, val loadedAtElapsed: Long)

    /** Maximum number of ads cached per ad unit. */
    @JvmStatic
    var maxCachePerUnit = 3

    /** Maximum automatic reload attempts after a failed load (exponential backoff: 1s, 2s, 4s …). */
    @JvmStatic
    var maxRetries = 3

    /**
     * How long (ms) a preloaded native ad stays valid in the cache. AdMob ads go stale roughly an
     * hour after loading; binding a stale ad renders dead content / dead click-through, so anything
     * older is destroyed on poll and a fresh ad is loaded instead.
     */
    @JvmStatic
    var adValidityMs = 55 * 60 * 1000L

    /**
     * Low-level single native ad load with automatic retry/backoff. [onLoaded] fires on success;
     * [onFailed] fires once every retry is exhausted — with a `null` error when the load was
     * aborted because ads were disabled (premium / kill-switch) rather than refused by the SDK.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        adUnitId: String,
        onLoaded: (NativeAd) -> Unit,
        onFailed: ((LoadAdError?) -> Unit)? = null,
    ) = loadWithRetry(adUnitId, attempt = 0, onLoaded = onLoaded, onFailed = onFailed)

    private fun loadWithRetry(
        adUnitId: String,
        attempt: Int,
        onLoaded: (NativeAd) -> Unit,
        onFailed: ((LoadAdError?) -> Unit)?,
    ) {
        if (!NextGenAds.canShowAds()) {
            // Must settle the callback: silently dropping it would leak the caller's in-flight
            // accounting (waiters would shimmer forever and block future loads for this unit).
            onFailed?.invoke(null)
            return
        }

        val request = NativeAdRequest
            .Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .build()

        // Queue until the SDK is ready so cache warm-ups issued during app start aren't dropped.
        NextGenAds.whenInitialized {
            if (!NextGenAds.canShowAds()) { // may have flipped while queued for initialization
                onFailed?.invoke(null)
                return@whenInitialized
            }
            NextGenAds.countRequest(AdFormat.NATIVE, adUnitId)
            val startElapsed = SystemClock.elapsedRealtime()
            NativeAdLoader.load(
                request,
                object : NativeAdLoaderCallback {
                    override fun onNativeAdLoaded(nativeAd: NativeAd) {
                        val ms = SystemClock.elapsedRealtime() - startElapsed
                        NextGenAds.runOnMain {
                            attachEvents(nativeAd, adUnitId)
                            NextGenAds.log("Native loaded in ${ms}ms (attempt ${attempt + 1}): $adUnitId")
                            NextGenAds.dispatchLoaded(AdFormat.NATIVE, adUnitId)
                            onLoaded(nativeAd)
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        val ms = SystemClock.elapsedRealtime() - startElapsed
                        NextGenAds.runOnMain {
                            NextGenAds.dispatchFailedToLoad(AdFormat.NATIVE, adUnitId, adError)
                            // Stop retrying if the breaker paused requests (e.g. this failure just
                            // tripped it), otherwise back off and try again.
                            if (attempt < maxRetries && !NextGenAds.isRequestPaused()) {
                                val delayMs = 1000L shl attempt // 1s, 2s, 4s …
                                NextGenAds.log(
                                    "Native failed after ${ms}ms ($adUnitId): $adError — retry " +
                                        "${attempt + 1}/$maxRetries in ${delayMs}ms",
                                )
                                handler.postDelayed(
                                    { loadWithRetry(adUnitId, attempt + 1, onLoaded, onFailed) },
                                    delayMs,
                                )
                            } else {
                                NextGenAds.log("Native gave up after ${attempt + 1} attempts ($adUnitId): $adError")
                                onFailed?.invoke(adError)
                            }
                        }
                    }

                    override fun onAdLoadingCompleted() {}
                },
            )
        }
    }

    /** Preloads up to [count] ads (capped by [maxCachePerUnit]) into the cache. */
    @JvmStatic
    @JvmOverloads
    fun preload(adUnitId: String, count: Int = maxCachePerUnit) {
        if (!NextGenAds.canRequest()) return
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(adUnitId) + inFlightCount(adUnitId) < target) {
            startLoad(adUnitId)
        }
    }

    /**
     * Fills [templateView] with a native ad, showing its shimmer until ready.
     *
     * - A [preload]ed ad is bound instantly with **no** new request.
     * - An empty cache with a load already in flight *waits* for it (no duplicate request).
     * - A genuinely cold cache starts one retrying load and binds when it lands.
     *
     * @param refill when `true`, re-warms the cache with a fresh request after consuming a preloaded
     *   ad. Defaults to `false` so a single show issues no extra request; set `true` for recurring
     *   placements to keep the next ad always warm (higher sustained show-rate).
     */
    @JvmStatic
    @JvmOverloads
    fun populate(
        templateView: NativeTemplateView,
        adUnitId: String,
        refill: Boolean = false,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((LoadAdError) -> Unit)? = null,
    ) {
        if (!NextGenAds.canShowAds()) {
            // Premium / kill-switch: even a cached ad must not be shown.
            templateView.showError()
            return
        }
        templateView.showShimmer()

        val cached = poll(adUnitId)
        if (cached != null) {
            NextGenAds.log("Native shown from preloaded cache: $adUnitId")
            templateView.setNativeAd(cached)
            onLoaded?.invoke()
            if (refill) preload(adUnitId, 1)
            return
        }

        if (!NextGenAds.canRequest()) {
            // Ads disabled, or the breaker paused new requests on a slow connection.
            templateView.showError()
            return
        }

        // No cached ad: park a waiter and make sure a load exists to satisfy it. If a preload is
        // already in flight it counts, so no duplicate request is issued.
        NextGenAds.log(
            if (inFlightCount(adUnitId) > 0) "Native awaiting in-flight load: $adUnitId"
            else "Native cache empty, starting load: $adUnitId",
        )
        addWaiter(adUnitId) { ad, error ->
            if (ad != null) {
                templateView.setNativeAd(ad)
                onLoaded?.invoke()
            } else {
                // Stop the shimmer and collapse the slot — otherwise it shimmers forever.
                templateView.showError()
                if (error != null) onFailed?.invoke(error)
            }
        }
        ensureLoadsForWaiters(adUnitId)
    }

    /** Destroys every cached ad for an ad unit (or all units when [adUnitId] is null). */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun clear(adUnitId: String? = null) {
        val keys = if (adUnitId != null) listOf(adUnitId) else pool.keys.toList()
        keys.forEach { key ->
            pool.remove(key)?.forEach { it.ad.destroy() }
        }
    }

    // --- internal load orchestration -------------------------------------------------------------

    /** Fires one retrying load, incrementing the in-flight count; result routed via [deliver]. */
    private fun startLoad(adUnitId: String) {
        incFlight(adUnitId)
        load(
            adUnitId,
            onLoaded = { ad ->
                decFlight(adUnitId)
                deliver(adUnitId, ad, null)
            },
            onFailed = { error ->
                decFlight(adUnitId)
                deliver(adUnitId, null, error)
            },
        )
    }

    /** Starts loads until every parked waiter has an in-flight load backing it (never over-requests). */
    private fun ensureLoadsForWaiters(adUnitId: String) {
        if (!NextGenAds.canRequest()) return
        while (inFlightCount(adUnitId) < waiterCount(adUnitId)) {
            startLoad(adUnitId)
        }
    }

    /** Hands a load result to the next waiting placement, or caches it when nobody is waiting. */
    private fun deliver(adUnitId: String, ad: NativeAd?, error: LoadAdError?) {
        val waiter = nextWaiter(adUnitId)
        when {
            waiter != null -> waiter(ad, error)
            ad != null -> offer(adUnitId, ad) // no waiter: warm the cache
            // ad == null and no waiter: a failed preload — nothing to do (retries already spent).
        }
    }

    /**
     * Attaches the ad-events bridge to a loaded native ad so impression / click / paid-revenue
     * events reach the global [AdEventListener]s. Attached at load time so cached ads keep emitting
     * once bound into a template.
     */
    private fun attachEvents(ad: NativeAd, adUnitId: String) {
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.NATIVE, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.NATIVE, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.NATIVE, adUnitId, value, ad.getResponseInfo())
            }
        }
    }

    @Synchronized
    private fun poll(adUnitId: String): NativeAd? {
        val queue = pool[adUnitId] ?: return null
        // Evict-and-skip stale entries: binding an expired ad shows dead content and wastes the slot.
        while (true) {
            val cached = queue.pollFirst() ?: return null
            if (SystemClock.elapsedRealtime() - cached.loadedAtElapsed < adValidityMs) return cached.ad
            NextGenAds.log("Native cache entry expired, destroying: $adUnitId")
            cached.ad.destroy()
        }
    }

    @Synchronized
    private fun offer(adUnitId: String, ad: NativeAd) {
        val queue = pool.getOrPut(adUnitId) { ArrayDeque() }
        if (queue.size >= maxCachePerUnit) {
            ad.destroy()
        } else {
            queue.addLast(CachedAd(ad, SystemClock.elapsedRealtime()))
        }
    }

    @Synchronized
    private fun cachedCount(adUnitId: String): Int = pool[adUnitId]?.size ?: 0

    @Synchronized
    private fun inFlightCount(adUnitId: String): Int = inFlight[adUnitId] ?: 0

    @Synchronized
    private fun incFlight(adUnitId: String) {
        inFlight[adUnitId] = (inFlight[adUnitId] ?: 0) + 1
    }

    @Synchronized
    private fun decFlight(adUnitId: String) {
        inFlight[adUnitId] = ((inFlight[adUnitId] ?: 1) - 1).coerceAtLeast(0)
    }

    @Synchronized
    private fun addWaiter(adUnitId: String, waiter: (NativeAd?, LoadAdError?) -> Unit) {
        waiters.getOrPut(adUnitId) { ArrayDeque() }.addLast(waiter)
    }

    @Synchronized
    private fun waiterCount(adUnitId: String): Int = waiters[adUnitId]?.size ?: 0

    @Synchronized
    private fun nextWaiter(adUnitId: String): ((NativeAd?, LoadAdError?) -> Unit)? =
        waiters[adUnitId]?.pollFirst()
}
