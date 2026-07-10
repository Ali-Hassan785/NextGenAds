package com.alihassan.nextgenads.banner

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * The size of banner to request. Two adaptive sizes flex their height to the slot width (recommended
 * for most placements), and the rest are the fixed IAB sizes.
 *
 * - [ADAPTIVE] — full-width **large anchored adaptive** banner (the default; height chosen by the SDK
 *   from the slot width). Best fill and the tallest anchored size.
 * - [ADAPTIVE_INLINE] — **inline adaptive** banner for scrollable content; can be taller than an
 *   anchored banner and is sized for feeds/lists rather than a pinned top/bottom slot.
 * - [BANNER] — fixed 320×50.
 * - [LARGE_BANNER] — fixed 320×100.
 * - [FULL_BANNER] — fixed 468×60 (tablets).
 * - [LEADERBOARD] — fixed 728×90 (tablets).
 * - [MEDIUM_RECTANGLE] — fixed 300×250 (MREC).
 *
 * Fixed sizes ignore the requested width; adaptive sizes use the slot/container width. The preload
 * cache is keyed by ad unit **and** size, so preloading one size never serves a request for another.
 */
enum class BannerSize {
    ADAPTIVE {
        override fun resolve(context: Context, widthDp: Int): AdSize =
            AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
    },
    ADAPTIVE_INLINE {
        override fun resolve(context: Context, widthDp: Int): AdSize =
            AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp)
    },
    BANNER {
        override fun resolve(context: Context, widthDp: Int): AdSize = AdSize.BANNER
    },
    LARGE_BANNER {
        override fun resolve(context: Context, widthDp: Int): AdSize = AdSize.LARGE_BANNER
    },
    FULL_BANNER {
        override fun resolve(context: Context, widthDp: Int): AdSize = AdSize.FULL_BANNER
    },
    LEADERBOARD {
        override fun resolve(context: Context, widthDp: Int): AdSize = AdSize.LEADERBOARD
    },
    MEDIUM_RECTANGLE {
        override fun resolve(context: Context, widthDp: Int): AdSize = AdSize.MEDIUM_RECTANGLE
    };

    /** Resolves the SDK [AdSize] for this size; [widthDp] is used only by the adaptive sizes. */
    internal abstract fun resolve(context: Context, widthDp: Int): AdSize

    /** Whether this size flexes its dimensions to the slot width (vs. a fixed IAB size). */
    internal val isAdaptive: Boolean get() = this == ADAPTIVE || this == ADAPTIVE_INLINE

    companion object {
        /** Maps a case-insensitive size name (e.g. from XML) to a [BannerSize]; defaults to [ADAPTIVE]. */
        @JvmStatic
        fun fromName(name: String?): BannerSize =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: ADAPTIVE
    }
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
     *   Ignored for the fixed [BannerSize]s (they have a fixed width).
     * @param size the banner size to warm. Preloading a size only serves a later [loadAdaptiveBanner]
     *   call requesting that **same** size — the cache is keyed by ad unit and size.
     */
    @JvmStatic
    @JvmOverloads
    fun preload(
        activity: Activity,
        adUnitId: String,
        count: Int = 1,
        widthDp: Int = screenWidthDp(activity),
        size: BannerSize = BannerSize.ADAPTIVE,
        remoteEnabled: Boolean = true,
    ) {
        if (!remoteEnabled || !NextGenAds.canRequest(AdFormat.BANNER)) return
        // Preloaded AdViews are created with this Activity as their context and sit detached in a
        // process-wide pool — purge them when their Activity dies, or they'd leak it (and attach
        // dead-context views later).
        installPurgeHook(activity.application)
        val key = poolKey(adUnitId, size)
        val target = count.coerceIn(0, maxCachePerUnit)
        while (cachedCount(key) + inFlightCount(key) < target) {
            incFlight(key)
            // Queue until the SDK is ready so banner warm-ups issued during app start aren't
            // dropped. The AdView is built inside the block so we never touch the SDK pre-init.
            NextGenAds.whenInitialized {
                if (activity.isFinishing || activity.isDestroyed) {
                    // The preloading Activity died while queued — don't build a dead-context view.
                    decFlight(key)
                    return@whenInitialized
                }
                val adView = AdView(activity)
                val adSize = size.resolve(activity, widthDp)
                loadBannerWithRetry(
                    adView, adUnitId, adSize, attempt = 0,
                    onLoaded = { ad ->
                        decFlight(key)
                        attachEvents(ad, adUnitId)
                        offer(key, adView)
                        NextGenAds.log("Banner preloaded: $adUnitId (${size.name})")
                        NextGenAds.dispatchLoaded(AdFormat.BANNER, adUnitId)
                    },
                    onFailed = { decFlight(key) },
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
     * @param size the banner size to show. Only a banner [preload]ed at the **same** size is attached
     *   from cache; any other size is loaded fresh. Defaults to a full-width adaptive banner.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAdaptiveBanner(
        activity: Activity,
        container: ViewGroup,
        adUnitId: String,
        refill: Boolean = false,
        collapsible: BannerCollapsible? = null,
        size: BannerSize = BannerSize.ADAPTIVE,
        onLoaded: (() -> Unit)? = null,
        onFailed: ((LoadAdError) -> Unit)? = null,
        remoteEnabled: Boolean = true,
    ) {
        if (!remoteEnabled || !NextGenAds.canShowAds(AdFormat.BANNER)) {
            container.visibility = View.GONE
            return
        }

        val key = poolKey(adUnitId, size)
        // Width to request: adaptive sizes flex to the container's content width; fixed sizes ignore it.
        val widthDp = bannerWidthDp(activity, container)

        // Fast path: attach a preloaded banner with no shimmer gap. Skipped for collapsible requests,
        // which must be loaded fresh so the SDK applies the collapsible overlay to this impression.
        val cached = if (collapsible == null) poll(key) else null
        if (cached != null) {
            detachFromParent(cached)
            destroyBannerChildren(container) // release any banner this placement was showing before
            container.removeAllViews()
            addCentered(container, cached)
            container.visibility = View.VISIBLE
            shownContainers.add(container)
            NextGenAds.log("Banner attached from cache: $adUnitId (${size.name})")
            onLoaded?.invoke()
            if (refill) preload(activity, adUnitId, 1, widthDp, size)
            return
        }

        // No cached banner: only fire a fresh request when the breaker allows it.
        if (NextGenAds.isRequestPaused()) {
            NextGenAds.log("Banner request paused by breaker: $adUnitId")
            container.visibility = View.GONE
            return
        }

        // Resolve the banner size/height up front so the shimmer can both reserve the exact slot AND
        // pick a skeleton that fits that height: a compact icon/text/CTA row for short banners, and a
        // media-style skeleton (media block + text + CTA) for tall ones like a 300x250 MREC — so the
        // placeholder never looks like a small strip floating in a big empty box.
        val adSize = size.resolve(activity, widthDp)
        // Inline adaptive reports its MAXIMUM height (often near the screen height), so sizing the
        // shimmer to it reserves a huge block. Reserve the anchored-adaptive height instead — the real
        // inline ad is usually about that tall — and let the container settle when it loads.
        val shimmerHeightPx = if (size == BannerSize.ADAPTIVE_INLINE) {
            BannerSize.ADAPTIVE.resolve(activity, widthDp).getHeightInPixels(activity)
        } else {
            adSize.getHeightInPixels(activity)
        }
        // Anything ≥ ~150dp tall (i.e. the MREC) reads better as a media/native-style skeleton;
        // shorter anchored/adaptive/fixed banners use the compact horizontal row skeleton.
        val tallThresholdPx = (150 * activity.resources.displayMetrics.density).toInt()
        val shimmerLayout = if (shimmerHeightPx >= tallThresholdPx) {
            R.layout.ngad_shimmer_banner_media
        } else {
            R.layout.ngad_shimmer_banner
        }

        val shimmer = LayoutInflater.from(activity)
            .inflate(shimmerLayout, container, false) as ShimmerFrameLayout
        destroyBannerChildren(container) // release any banner this placement was showing before
        container.removeAllViews()

        val adView = AdView(activity)
        adView.visibility = View.GONE
        addCentered(container, adView)
        addCentered(container, shimmer)
        container.visibility = View.VISIBLE
        shownContainers.add(container)
        shimmer.startShimmer()

        // Size the shimmer to the banner's resolved height so the placeholder occupies exactly the
        // slot the ad will fill — otherwise the container jumps when the (taller) ad swaps in. For a
        // fixed size, also match its width so the (centered) placeholder has the ad's exact footprint
        // instead of a full-width block that collapses to a narrow ad; adaptive banners stay full-width.
        shimmer.layoutParams = shimmer.layoutParams.apply {
            height = shimmerHeightPx
            if (!size.isAdaptive) width = adSize.getWidthInPixels(activity)
        }
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
     * Adds [view] to [container], horizontally centered when the container is a [FrameLayout]. Fixed
     * IAB sizes (e.g. 320×50, 300×250 MREC) are narrower than a full-width slot, so without this they
     * render flush to the start; adaptive banners are full-width, so centering is a no-op for them.
     * Any existing width/height is preserved — only the gravity is applied.
     */
    private fun addCentered(container: ViewGroup, view: View) {
        if (container !is FrameLayout) {
            container.addView(view)
            return
        }
        val existing = view.layoutParams
        val lp = existing as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(
            existing?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            existing?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.CENTER_HORIZONTAL
        container.addView(view, lp)
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

    /** Cache/in-flight key: an ad unit is pooled separately per [BannerSize] so sizes never mix. */
    private fun poolKey(adUnitId: String, size: BannerSize): String = "$adUnitId|${size.name}"

    @Synchronized
    private fun poll(key: String): AdView? {
        val queue = pool[key] ?: return null
        // Evict-and-skip entries that expired or whose Activity died while they sat in the pool.
        while (true) {
            val cached = queue.pollFirst() ?: return null
            val expired = SystemClock.elapsedRealtime() - cached.loadedAtElapsed >= adValidityMs
            val deadContext = (cached.adView.context as? Activity)?.isDestroyed == true
            if (!expired && !deadContext) return cached.adView
            NextGenAds.log("Banner cache entry ${if (expired) "expired" else "from destroyed activity"}, destroying: $key")
            cached.adView.destroy()
        }
    }

    @Synchronized
    private fun offer(key: String, adView: AdView) {
        val queue = pool.getOrPut(key) { ArrayDeque() }
        if (queue.size >= maxCachePerUnit) {
            adView.destroy()
        } else {
            queue.addLast(CachedBanner(adView, SystemClock.elapsedRealtime()))
        }
    }

    @Synchronized
    private fun cachedCount(key: String): Int = pool[key]?.size ?: 0

    @Synchronized
    private fun inFlightCount(key: String): Int = inFlight[key] ?: 0

    @Synchronized
    private fun incFlight(key: String) {
        inFlight[key] = (inFlight[key] ?: 0) + 1
    }

    @Synchronized
    private fun decFlight(key: String) {
        inFlight[key] = ((inFlight[key] ?: 1) - 1).coerceAtLeast(0)
    }
}
