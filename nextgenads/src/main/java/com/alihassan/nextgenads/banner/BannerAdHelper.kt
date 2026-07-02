package com.alihassan.nextgenads.banner

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
import com.alihassan.nextgenads.events.AdFormat
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * Loads anchored adaptive banners (Next-Gen SDK) into a container, showing a shimmer placeholder
 * while the ad is in flight and collapsing the container on failure so no empty gap is left behind.
 *
 * Banners can also be [preload]ed: a detached [AdView] is loaded ahead of time and attached
 * instantly when the placement becomes visible — the same show-rate trick used for native ads.
 */
object BannerAdHelper {

    private val handler = Handler(Looper.getMainLooper())
    private val pool = HashMap<String, ArrayDeque<AdView>>()
    private val inFlight = HashMap<String, Int>()

    /** Maximum number of preloaded banners cached per ad unit. */
    @JvmStatic
    var maxCachePerUnit = 2

    /** Maximum automatic reload attempts after a failed banner load (backoff: 1s, 2s, 4s …). */
    @JvmStatic
    var maxRetries = 2

    /**
     * Preloads up to [count] banners (capped by [maxCachePerUnit]) into a detached cache so that a
     * later [loadAdaptiveBanner] call can attach one instantly. Call after [NextGenAds.initialize].
     *
     * @param widthDp the width (in dp) the banner will be shown at. Defaults to the full screen
     *   width; **pass the container's content width if your banner placement has horizontal padding
     *   or margins**, otherwise the preloaded ad is sized wider than the slot and the SDK logs
     *   "Not enough space to show the full ad". Use [containerWidthDp] to compute it from the view.
     */
    @JvmStatic
    @JvmOverloads
    fun preload(
        activity: Activity,
        adUnitId: String,
        count: Int = 1,
        widthDp: Int = screenWidthDp(activity),
    ) {
        if (!NextGenAds.canRequest()) return
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(adUnitId) + inFlightCount(adUnitId) < target) {
            incFlight(adUnitId)
            // Queue until the SDK is ready so banner warm-ups issued during app start aren't
            // dropped. The AdView is built inside the block so we never touch the SDK pre-init.
            NextGenAds.whenInitialized {
                val adView = AdView(activity)
                val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
                loadBannerWithRetry(
                    adView, adUnitId, adSize, attempt = 0,
                    onLoaded = { ad ->
                        decFlight(adUnitId)
                        attachEvents(ad, adUnitId)
                        offer(adUnitId, adView)
                        NextGenAds.log("Banner preloaded: $adUnitId")
                        NextGenAds.dispatchLoaded(AdFormat.BANNER, adUnitId)
                    },
                    onFailed = { decFlight(adUnitId) },
                )
            }
        }
    }

    /**
     * Shows a banner in [container]. If a [preload]ed banner is cached it is attached instantly;
     * otherwise a fresh one is loaded behind a shimmer placeholder.
     *
     * @param container the [ViewGroup] that hosts the banner. Its current children are replaced.
     * @param refill when `true`, re-warms the cache with a fresh request after consuming a preloaded
     *   banner. Defaults to `false` so that showing a preloaded banner issues **no** new request —
     *   call [preload] yourself when you want the next banner warmed.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAdaptiveBanner(
        activity: Activity,
        container: ViewGroup,
        adUnitId: String,
        refill: Boolean = false,
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

        // No cached banner: only fire a fresh request when the breaker allows it.
        if (NextGenAds.isRequestPaused()) {
            NextGenAds.log("Banner request paused by breaker: $adUnitId")
            container.visibility = View.GONE
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
        // Shimmer is already showing; queue the request so it fires as soon as the SDK is ready.
        NextGenAds.whenInitialized {
            loadBannerWithRetry(
                adView, adUnitId, adSize, attempt = 0,
                onLoaded = { ad ->
                    shimmer.stopShimmer()
                    container.removeView(shimmer)
                    adView.visibility = View.VISIBLE
                    attachEvents(ad, adUnitId)
                    NextGenAds.log("Banner loaded: $adUnitId")
                    NextGenAds.dispatchLoaded(AdFormat.BANNER, adUnitId)
                    onLoaded?.invoke()
                },
                onFailed = { error ->
                    shimmer.stopShimmer()
                    container.removeAllViews()
                    container.visibility = View.GONE
                    onFailed?.invoke(error)
                },
            )
        }
    }

    /**
     * Loads [adView] with exponential-backoff retries. [onLoaded] fires on success; [onFailed] only
     * once every retry is exhausted (or the request breaker paused new requests). Each failed
     * attempt dispatches to the global listeners; callbacks are delivered on the main thread.
     */
    private fun loadBannerWithRetry(
        adView: AdView,
        adUnitId: String,
        adSize: AdSize,
        attempt: Int,
        onLoaded: (BannerAd) -> Unit,
        onFailed: (LoadAdError) -> Unit,
    ) {
        NextGenAds.countRequest(AdFormat.BANNER, adUnitId)
        adView.loadAd(
            BannerAdRequest.Builder(adUnitId, adSize).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    NextGenAds.runOnMain { onLoaded(ad) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        NextGenAds.dispatchFailedToLoad(AdFormat.BANNER, adUnitId, adError)
                        if (attempt < maxRetries && !NextGenAds.isRequestPaused()) {
                            val delayMs = 1000L shl attempt // 1s, 2s, 4s …
                            NextGenAds.log(
                                "Banner failed ($adUnitId): $adError — retry " +
                                    "${attempt + 1}/$maxRetries in ${delayMs}ms",
                            )
                            handler.postDelayed(
                                { loadBannerWithRetry(adView, adUnitId, adSize, attempt + 1, onLoaded, onFailed) },
                                delayMs,
                            )
                        } else {
                            NextGenAds.log("Banner gave up after ${attempt + 1} attempts ($adUnitId): $adError")
                            onFailed(adError)
                        }
                    }
                }
            },
        )
    }

    /**
     * Attaches the ad-events bridge to a loaded banner so impression / click / paid-revenue events
     * reach the global [AdEventListener]s. Banners are inline, so they have no show/dismiss events.
     */
    private fun attachEvents(ad: BannerAd, adUnitId: String) {
        ad.adEventCallback = object : BannerAdEventCallback {
            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.BANNER, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.BANNER, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.BANNER, adUnitId, value, ad.getResponseInfo())
            }
        }
    }

    /**
     * The content width (in dp) available inside [container] — the value to pass as `widthDp` to
     * [preload] so a preloaded banner matches a padded/margined placement. Falls back to the full
     * screen width when the container hasn't been laid out yet.
     */
    @JvmStatic
    fun containerWidthDp(activity: Activity, container: ViewGroup): Int =
        bannerWidthDp(activity, container)

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
