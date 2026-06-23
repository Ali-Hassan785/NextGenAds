package com.alihassan.nextgenads.nativead

import com.alihassan.nextgenads.NextGenAds
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import java.util.ArrayDeque

/**
 * Loads native ads (Next-Gen SDK) and keeps a small per-unit cache so a template can be filled
 * instantly, which is the single biggest lever on native show-rate.
 *
 * - [preload] warms the cache (call it from [NextGenAds.initialize]'s completion callback).
 * - [populate] binds an ad into a [NativeTemplateView], preferring a cached ad and transparently
 *   refilling the cache afterwards.
 * - [load] is the low-level single-shot loader if you need the raw [NativeAd].
 *
 * Load methods require [NextGenAds.initialize] to have completed first (the SDK supplies the
 * application context, so none is needed here).
 */
object NativeAdHelper {

    private val pool = HashMap<String, ArrayDeque<NativeAd>>()
    private val inFlight = HashMap<String, Int>()

    /** Maximum number of ads cached per ad unit. */
    @JvmStatic
    var maxCachePerUnit = 3

    /** Low-level single native ad load. */
    @JvmStatic
    @JvmOverloads
    fun load(
        adUnitId: String,
        onLoaded: (NativeAd) -> Unit,
        onFailed: ((LoadAdError) -> Unit)? = null,
    ) {
        if (!NextGenAds.canShowAds()) return

        val request = NativeAdRequest
            .Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .build()

        NativeAdLoader.load(
            request,
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    NextGenAds.runOnMain { onLoaded(nativeAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        NextGenAds.log("Native failed ($adUnitId): $adError")
                        onFailed?.invoke(adError)
                    }
                }

                override fun onAdLoadingCompleted() {}
            },
        )
    }

    /** Preloads up to [count] ads (capped by [maxCachePerUnit]) into the cache. */
    @JvmStatic
    @JvmOverloads
    fun preload(adUnitId: String, count: Int = maxCachePerUnit) {
        if (!NextGenAds.canShowAds()) return
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(adUnitId) + inFlightCount(adUnitId) < target) {
            incFlight(adUnitId)
            load(
                adUnitId,
                onLoaded = { ad ->
                    decFlight(adUnitId)
                    offer(adUnitId, ad)
                },
                onFailed = { decFlight(adUnitId) },
            )
        }
    }

    /**
     * Fills [templateView] with a native ad, showing its shimmer until ready.
     *
     * @param refill when `true`, re-warms the cache after consuming a cached ad.
     */
    @JvmStatic
    @JvmOverloads
    fun populate(
        templateView: NativeTemplateView,
        adUnitId: String,
        refill: Boolean = true,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((LoadAdError) -> Unit)? = null,
    ) {
        templateView.showShimmer()

        val cached = poll(adUnitId)
        if (cached != null) {
            templateView.setNativeAd(cached)
            onLoaded?.invoke()
            if (refill) preload(adUnitId, 1)
            return
        }

        load(
            adUnitId,
            onLoaded = { ad ->
                templateView.setNativeAd(ad)
                onLoaded?.invoke()
            },
            onFailed = { error -> onFailed?.invoke(error) },
        )
    }

    /** Destroys every cached ad for an ad unit (or all units when [adUnitId] is null). */
    @JvmStatic
    @JvmOverloads
    fun clear(adUnitId: String? = null) = synchronized(pool) {
        val keys = if (adUnitId != null) listOf(adUnitId) else pool.keys.toList()
        keys.forEach { key ->
            pool.remove(key)?.forEach { it.destroy() }
        }
    }

    @Synchronized
    private fun poll(adUnitId: String): NativeAd? = pool[adUnitId]?.pollFirst()

    @Synchronized
    private fun offer(adUnitId: String, ad: NativeAd) {
        val queue = pool.getOrPut(adUnitId) { ArrayDeque() }
        if (queue.size >= maxCachePerUnit) ad.destroy() else queue.addLast(ad)
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
}
