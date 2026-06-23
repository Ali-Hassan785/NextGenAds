package com.alihassan.nextgenads.banner

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.util.ArrayDeque

/**
 * Loads anchored adaptive banners (Next-Gen SDK) into a container, showing a shimmer placeholder
 * while the ad is in flight and collapsing the container on failure so no empty gap is left behind.
 *
 * Banners can also be [preload]ed: a detached [AdView] is loaded ahead of time and attached
 * instantly when the placement becomes visible — the same show-rate trick used for native ads.
 */
object BannerAdHelper {

    private val pool = HashMap<String, ArrayDeque<AdView>>()
    private val inFlight = HashMap<String, Int>()

    /** Maximum number of preloaded banners cached per ad unit. */
    @JvmStatic
    var maxCachePerUnit = 2

    /**
     * Preloads up to [count] banners (capped by [maxCachePerUnit]) into a detached cache so that a
     * later [loadAdaptiveBanner] call can attach one instantly. Call after [NextGenAds.initialize].
     */
    @JvmStatic
    @JvmOverloads
    fun preload(activity: Activity, adUnitId: String, count: Int = 1) {
        if (!NextGenAds.canShowAds()) return
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(adUnitId) + inFlightCount(adUnitId) < target) {
            incFlight(adUnitId)
            val adView = AdView(activity)
            val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, screenWidthDp(activity))
            adView.loadAd(
                BannerAdRequest.Builder(adUnitId, adSize).build(),
                object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) {
                        NextGenAds.runOnMain {
                            decFlight(adUnitId)
                            offer(adUnitId, adView)
                            NextGenAds.log("Banner preloaded: $adUnitId")
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        NextGenAds.runOnMain {
                            decFlight(adUnitId)
                            NextGenAds.log("Banner preload failed ($adUnitId): $adError")
                        }
                    }
                },
            )
        }
    }

    /**
     * Shows a banner in [container]. If a [preload]ed banner is cached it is attached instantly;
     * otherwise a fresh one is loaded behind a shimmer placeholder.
     *
     * @param container the [ViewGroup] that hosts the banner. Its current children are replaced.
     * @param refill when `true`, re-warms the cache after consuming a preloaded banner.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAdaptiveBanner(
        activity: Activity,
        container: ViewGroup,
        adUnitId: String,
        refill: Boolean = true,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((LoadAdError) -> Unit)? = null,
    ) {
        if (!NextGenAds.canShowAds()) {
            container.visibility = View.GONE
            return
        }

        // Fast path: attach a preloaded banner with no shimmer gap.
        val cached = poll(adUnitId)
        if (cached != null) {
            detachFromParent(cached)
            container.removeAllViews()
            container.addView(cached)
            container.visibility = View.VISIBLE
            NextGenAds.log("Banner attached from cache: $adUnitId")
            onLoaded?.invoke()
            if (refill) preload(activity, adUnitId, 1)
            return
        }

        val shimmer = LayoutInflater.from(activity)
            .inflate(R.layout.ngad_shimmer_banner, container, false) as ShimmerFrameLayout
        container.removeAllViews()

        val adView = AdView(activity)
        adView.visibility = View.GONE
        container.addView(adView)
        container.addView(shimmer)
        container.visibility = View.VISIBLE
        shimmer.startShimmer()

        val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            activity, bannerWidthDp(activity, container)
        )
        adView.loadAd(
            BannerAdRequest.Builder(adUnitId, adSize).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    NextGenAds.runOnMain {
                        shimmer.stopShimmer()
                        container.removeView(shimmer)
                        adView.visibility = View.VISIBLE
                        NextGenAds.log("Banner loaded: $adUnitId")
                        onLoaded?.invoke()
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        shimmer.stopShimmer()
                        container.removeAllViews()
                        container.visibility = View.GONE
                        NextGenAds.log("Banner failed ($adUnitId): $adError")
                        onFailed?.invoke(adError)
                    }
                }
            },
        )
    }

    private fun bannerWidthDp(activity: Activity, container: ViewGroup): Int {
        val metrics = activity.resources.displayMetrics
        var widthPx = container.width.toFloat()
        if (widthPx <= 0f) widthPx = metrics.widthPixels.toFloat()
        return (widthPx / metrics.density).toInt()
    }

    private fun screenWidthDp(activity: Activity): Int {
        val metrics = activity.resources.displayMetrics
        return (metrics.widthPixels / metrics.density).toInt()
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    @Synchronized
    private fun poll(adUnitId: String): AdView? = pool[adUnitId]?.pollFirst()

    @Synchronized
    private fun offer(adUnitId: String, adView: AdView) {
        val queue = pool.getOrPut(adUnitId) { ArrayDeque() }
        if (queue.size >= maxCachePerUnit) adView.destroy() else queue.addLast(adView)
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
