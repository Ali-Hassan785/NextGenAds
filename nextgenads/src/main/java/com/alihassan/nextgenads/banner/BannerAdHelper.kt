package com.alihassan.nextgenads.banner

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a collapsible banner's expanded overlay is anchored relative to the visible (collapsed) ad.
 * [BOTTOM] expands upward from a bottom-anchored banner; [TOP] expands downward from a top-anchored
 * one — pick the one matching where the banner sits on screen. Passed to
 * [BannerAdHelper.loadAdaptiveBanner] to request a collapsible banner.
 */
enum class BannerCollapsible(internal val value: String) {
    TOP("top"),
    BOTTOM("bottom"),
}

/**
 * Loads anchored adaptive banners (Next-Gen SDK) into a container, showing a shimmer placeholder
 * while the ad is in flight and collapsing the container on failure so no empty gap is left behind.
 *
 * Banners can also be [preload]ed: a detached [AdView] is loaded ahead of time and attached
 * instantly when the placement becomes visible — the same show-rate trick used for native ads.
 *
 * Pass a [BannerCollapsible] to [loadAdaptiveBanner] to request a **collapsible banner** — one that
 * shows as a larger overlay on first impression and collapses to the anchored banner.
 */
object BannerAdHelper {

    private val handler = Handler(Looper.getMainLooper())
    private val pool = HashMap<String, ArrayDeque<CachedBanner>>()
    private val inFlight = HashMap<String, Int>()
    private val purgeHookInstalled = AtomicBoolean(false)
    // Containers this helper has populated, held weakly so they can be cleared when ads are disabled
    // (e.g. the user goes premium) without keeping the host view alive.
    private val shownContainers: MutableSet<ViewGroup> =
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    /** A pooled detached banner plus its load timestamp, so stale inventory can be evicted. */
    private class CachedBanner(val adView: AdView, val loadedAtElapsed: Long)

    /** Maximum number of preloaded banners cached per ad unit. */
    @JvmStatic
    var maxCachePerUnit = 2

    /** Maximum automatic reload attempts after a failed banner load (backoff: 1s, 2s, 4s …). */
    @JvmStatic
    var maxRetries = 2

    /**
     * How long (ms) a preloaded banner stays valid in the cache. Attaching a stale banner renders
     * dead content, so anything older is destroyed on poll and a fresh one is loaded instead.
     */
    @JvmStatic
    var adValidityMs = 55 * 60 * 1000L

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
        // Preloaded AdViews are created with this Activity as their context and sit detached in a
        // process-wide pool — purge them when their Activity dies, or they'd leak it (and attach
        // dead-context views later).
        installPurgeHook(activity.application)
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(adUnitId) + inFlightCount(adUnitId) < target) {
            incFlight(adUnitId)
            // Queue until the SDK is ready so banner warm-ups issued during app start aren't
            // dropped. The AdView is built inside the block so we never touch the SDK pre-init.
            NextGenAds.whenInitialized {
                if (activity.isFinishing || activity.isDestroyed) {
                    // The preloading Activity died while queued — don't build a dead-context view.
                    decFlight(adUnitId)
                    return@whenInitialized
                }
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
     * @param collapsible when non-null, requests a **collapsible** banner anchored at that edge.
     *   Collapsible banners are always loaded fresh (the preload cache holds standard banners), so
     *   [refill] has no effect for them.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAdaptiveBanner(
        activity: Activity,
        container: ViewGroup,
        adUnitId: String,
        refill: Boolean = false,
        collapsible: BannerCollapsible? = null,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((LoadAdError) -> Unit)? = null,
    ) {
        if (!NextGenAds.canShowAds()) {
            container.visibility = View.GONE
            return
        }

        // Fast path: attach a preloaded banner with no shimmer gap. Skipped for collapsible requests,
        // which must be loaded fresh so the SDK applies the collapsible overlay to this impression.
        val cached = if (collapsible == null) poll(adUnitId) else null
        if (cached != null) {
            detachFromParent(cached)
            destroyBannerChildren(container) // release any banner this placement was showing before
            container.removeAllViews()
            container.addView(cached)
            container.visibility = View.VISIBLE
            shownContainers.add(container)
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
        destroyBannerChildren(container) // release any banner this placement was showing before
        container.removeAllViews()

        val adView = AdView(activity)
        adView.visibility = View.GONE
        container.addView(adView)
        container.addView(shimmer)
        container.visibility = View.VISIBLE
        shownContainers.add(container)
        shimmer.startShimmer()

        val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            activity, bannerWidthDp(activity, container)
        )
        // Shimmer is already showing; queue the request so it fires as soon as the SDK is ready.
        NextGenAds.whenInitialized {
            loadBannerWithRetry(
                adView, adUnitId, adSize, attempt = 0, collapsible = collapsible,
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
                    adView.destroy() // the failed view is never reused
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
        collapsible: BannerCollapsible? = null,
        onLoaded: (BannerAd) -> Unit,
        onFailed: (LoadAdError) -> Unit,
    ) {
        NextGenAds.countRequest(AdFormat.BANNER, adUnitId)
        adView.loadAd(
            buildRequest(adUnitId, adSize, collapsible),
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
                                {
                                    loadBannerWithRetry(
                                        adView, adUnitId, adSize, attempt + 1, collapsible,
                                        onLoaded, onFailed,
                                    )
                                },
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
     * Builds a [BannerAdRequest], attaching the `"collapsible"` extra (`"top"` / `"bottom"`) when a
     * [collapsible] position is requested so the SDK serves a collapsible banner for the placement.
     */
    private fun buildRequest(
        adUnitId: String,
        adSize: AdSize,
        collapsible: BannerCollapsible?,
    ): BannerAdRequest {
        val builder = BannerAdRequest.Builder(adUnitId, adSize)
        if (collapsible != null) {
            builder.setGoogleExtrasBundle(Bundle().apply { putString("collapsible", collapsible.value) })
        }
        return builder.build()
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
        // Content width: the banner renders inside the padding, so subtract it — otherwise the SDK
        // logs "Not enough space to show the full ad" and may clip.
        var widthPx = (container.width - container.paddingLeft - container.paddingRight).toFloat()
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

    /**
     * Destroys every pooled banner and hides/clears any banner currently shown in a container this
     * helper populated — used when ads are disabled at runtime (e.g. the user goes premium).
     */
    @JvmStatic
    fun clearAll() = NextGenAds.runOnMain {
        synchronized(pool) {
            pool.values.forEach { queue -> queue.forEach { it.adView.destroy() } }
            pool.clear()
        }
        inFlight.clear()
        shownContainers.toList().forEach { container ->
            destroyBannerChildren(container)
            container.removeAllViews()
            container.visibility = View.GONE
        }
        shownContainers.clear()
    }

    /** Destroys any [AdView] children of [container] so a replaced banner isn't leaked. */
    private fun destroyBannerChildren(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? AdView)?.destroy()
        }
    }

    /**
     * Purges pooled banners whose creating Activity is being destroyed. Installed once, on the
     * first [preload]; without it a detached cached AdView would keep its dead Activity reachable
     * indefinitely.
     */
    private fun installPurgeHook(application: Application) {
        if (!purgeHookInstalled.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) = purgeForActivity(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    @Synchronized
    private fun purgeForActivity(activity: Activity) {
        pool.values.forEach { queue ->
            val it = queue.iterator()
            while (it.hasNext()) {
                val cached = it.next()
                if (cached.adView.context === activity) {
                    it.remove()
                    cached.adView.destroy()
                }
            }
        }
    }

    @Synchronized
    private fun poll(adUnitId: String): AdView? {
        val queue = pool[adUnitId] ?: return null
        // Evict-and-skip entries that expired or whose Activity died while they sat in the pool.
        while (true) {
            val cached = queue.pollFirst() ?: return null
            val expired = SystemClock.elapsedRealtime() - cached.loadedAtElapsed >= adValidityMs
            val deadContext = (cached.adView.context as? Activity)?.isDestroyed == true
            if (!expired && !deadContext) return cached.adView
            NextGenAds.log("Banner cache entry ${if (expired) "expired" else "from destroyed activity"}, destroying: $adUnitId")
            cached.adView.destroy()
        }
    }

    @Synchronized
    private fun offer(adUnitId: String, adView: AdView) {
        val queue = pool.getOrPut(adUnitId) { ArrayDeque() }
        if (queue.size >= maxCachePerUnit) {
            adView.destroy()
        } else {
            queue.addLast(CachedBanner(adView, SystemClock.elapsedRealtime()))
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
}
