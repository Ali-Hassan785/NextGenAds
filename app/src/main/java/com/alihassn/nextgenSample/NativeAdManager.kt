package com.alihassn.nextgenSample

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.alihassan.nextgenads.nativead.NativeAdPreloader
import com.alihassan.nextgenads.nativead.NativeTemplateView
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest

/**
 * Native-ad manager (Next-Gen Ads SDK) with two independent modes:
 *
 * ### 1. Cross-screen preload / reuse — the [companion object] (static) API
 * The **"request on screen A, show on screen B"** flow. Warm an ad up front with [preload]; the
 * next screen checks [isAvailable] / [stateOf] and calls [showOrLoad], which:
 *  - shows an already-ready ad **instantly** (no new request),
 *  - if a load started on the previous screen is still in flight, **waits** for it instead of firing
 *    a second, competing request (no wasted requests),
 *  - only issues a fresh request when nothing was preloaded.
 *
 * This delegates to the library's [NativeAdPreloader] / [com.alihassan.nextgenads.nativead.NativeAdHelper],
 * so it inherits their per-unit in-flight de-duplication, exponential-backoff retry and cache
 * expiry — a single transient failure self-heals rather than leaving the slot empty.
 *
 * ```
 * // Screen A (e.g. Splash) — warm it while the user is busy:
 * NativeAdManager.preload(adUnitId)
 *
 * // Screen B — reuse the preloaded ad, or load one if none is ready:
 * NativeAdManager.showOrLoad(templateView, adUnitId, onFailed = { /* collapse the slot */ })
 * ```
 *
 * ### 2. One-shot owner-scoped load — the instance API
 * Use an instance when a single screen owns exactly one ad. [loadNativeAd] loads it (with automatic
 * retry so a flaky network doesn't fail the placement outright), [hasMedia] reports whether the
 * creative carries media, and [destroy] releases it. See the class docs on [loadNativeAd] for how
 * `withMedia` maps to a media-forward vs a compact template.
 *
 * Both modes require [com.alihassan.nextgenads.NextGenAds.initialize] to have completed first (the
 * SDK supplies the context, so none is needed here).
 */
class NativeAdManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The most-recently loaded ad, held so it can be destroyed on reload / teardown. */
    var nativeAd: NativeAd? = null
        private set

    /** Guards against overlapping loads for this instance (one ad, one in-flight request). */
    private var loading = false

    /** Set by [destroy]; late callbacks are dropped instead of resurrecting a dead manager. */
    private var destroyed = false

    /** Automatic reload attempts after a failed load (exponential backoff: 1s, 2s, 4s …). */
    var maxRetries = 2

    /**
     * Requests a native ad, retrying transient failures before giving up. [onAdLoaded] fires with
     * the ad on success; [onAdFailed] fires with the last SDK error only once every retry is spent.
     * Both are delivered on the main thread. A call made while a load is already in flight (or after
     * [destroy]) is ignored.
     *
     * @param withMedia `true` biases toward a landscape media creative for a `MediaView` template
     *   (`MEDIUM` / `LARGE` / `HERO`); `false` requests any media shape for a compact, no-`MediaView`
     *   template (`SMALL` / `BANNER` / `TITLE_ONLY`). Either way the icon, headline, body and CTA are
     *   downloaded and shown — "without media" means the template renders no `MediaView`, **not** that
     *   assets are skipped. (Don't reach for `disableImageDownloading()` to save the media bytes: it
     *   also drops the icon/image download, leaving the card iconless — there's no per-asset toggle.)
     */
    fun loadNativeAd(
        adUnitId: String,
        withMedia: Boolean,
        onAdLoaded: (NativeAd) -> Unit,
        onAdFailed: (LoadAdError) -> Unit,
    ) {
        if (destroyed) {
            log("loadNativeAd ignored — manager destroyed: $adUnitId")
            return
        }
        if (loading) {
            log("loadNativeAd ignored — a load is already in flight: $adUnitId")
            return
        }
        loading = true
        log("loadNativeAd start (withMedia=$withMedia, maxRetries=$maxRetries): $adUnitId")
        loadWithRetry(adUnitId, withMedia, attempt = 0, onAdLoaded = onAdLoaded, onAdFailed = onAdFailed)
    }

    private fun loadWithRetry(
        adUnitId: String,
        withMedia: Boolean,
        attempt: Int,
        onAdLoaded: (NativeAd) -> Unit,
        onAdFailed: (LoadAdError) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val request = NativeAdRequest
            .Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .apply {
                if (withMedia) {
                    // Media-forward slot: bias toward a wide creative for the MediaView.
                    setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.LANDSCAPE)
                } else {
                    // Compact slot with no MediaView. Media shape is irrelevant here, so request ANY
                    // to maximise fill. Do NOT call disableImageDownloading(): it drops the download
                    // of EVERY image asset (icon + image come back as URIs with a null drawable), so
                    // NativeTemplateView — which binds `ad.icon?.drawable` — would render no icon.
                    // The SDK has no per-asset toggle to skip only the large media, so images stay on.
                    setMediaAspectRatio(NativeAd.NativeMediaAspectRatio.ANY)
                }
            }
            .build()

        NativeAdLoader.load(
            request,
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) = onMain {
                    val ms = SystemClock.elapsedRealtime() - startedAt
                    loading = false
                    if (destroyed) {
                        log("loaded after destroy in ${ms}ms — destroying ad: $adUnitId")
                        nativeAd.destroy() // arrived after teardown — don't leak it
                        return@onMain
                    }
                    this@NativeAdManager.nativeAd?.destroy() // release the previous ad before replacing it
                    this@NativeAdManager.nativeAd = nativeAd
                    log("loaded in ${ms}ms (attempt ${attempt + 1}, hasMedia=${hasMedia()}): $adUnitId")
                    onAdLoaded(nativeAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMain {
                    val ms = SystemClock.elapsedRealtime() - startedAt
                    if (destroyed) {
                        log("load failed after destroy in ${ms}ms — dropping: $adUnitId")
                        loading = false
                        return@onMain
                    }
                    if (attempt < maxRetries) {
                        // Back off and retry so one flaky response doesn't fail the placement.
                        val delayMs = 1000L shl attempt // 1s, 2s, 4s …
                        log("load failed in ${ms}ms ($adUnitId): $adError — retry ${attempt + 1}/$maxRetries in ${delayMs}ms")
                        mainHandler.postDelayed(
                            { if (!destroyed) loadWithRetry(adUnitId, withMedia, attempt + 1, onAdLoaded, onAdFailed) },
                            delayMs,
                        )
                    } else {
                        loading = false
                        log("load gave up after ${attempt + 1} attempts ($adUnitId): $adError")
                        onAdFailed(adError)
                    }
                }

                override fun onAdLoadingCompleted() {}
            },
        )
    }

    /**
     * True when the loaded ad actually carries a media asset (an image or a video). Use it to
     * decide between a media template and a compact one after a `withMedia = true` load.
     */
    fun hasMedia(): Boolean {
        val media = nativeAd?.mediaContent ?: return false
        return media.hasVideoContent || media.mainImage != null || media.aspectRatio > 0f
    }

    /**
     * Destroys the held ad and blocks any further loads or callbacks. Idempotent — safe to call
     * from `onDestroy` even if nothing was loaded. Only affects this instance's one-shot ad; the
     * cross-screen preload cache is cleared via the static [clear].
     */
    fun destroy() {
        log("destroy — releasing ad (loaded=${nativeAd != null}, loading=$loading)")
        destroyed = true
        loading = false
        mainHandler.removeCallbacksAndMessages(null)
        nativeAd?.destroy()
        nativeAd = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    /**
     * Cross-screen preload / reuse. Backed by the library's [NativeAdPreloader], so state is shared
     * per ad unit across the whole app: what one screen preloads, the next screen reuses.
     */
    companion object {

        /** Logcat tag for both the instance and cross-screen native logs. */
        private const val TAG = "NativeAdManager"

        /**
         * Warms an ad for [adUnitId] from an earlier screen (e.g. Splash). Safe to call repeatedly —
         * it never fires a second request while one is already in flight or ready.
         */
        @JvmStatic
        @JvmOverloads
        fun preload(adUnitId: String, remoteEnabled: Boolean = true) {
            log("preload requested (state=${stateOf(adUnitId)}, remoteEnabled=$remoteEnabled): $adUnitId")
            NativeAdPreloader.preload(adUnitId, remoteEnabled)
        }

        /** True only when a preloaded ad is loaded and still held for [adUnitId] (show it instantly). */
        @JvmStatic
        fun isAvailable(adUnitId: String): Boolean = NativeAdPreloader.isReady(adUnitId)

        /** Current preload state for [adUnitId]: IDLE / LOADING / READY / FAILED. */
        @JvmStatic
        fun stateOf(adUnitId: String): NativeAdPreloader.State = NativeAdPreloader.stateOf(adUnitId)

        /**
         * The "check, then show or request" entry point. Binds [templateView] with:
         *  - a preloaded ad **instantly** when one is READY,
         *  - the result of an in-flight preload when one is still LOADING (waits — no 2nd request),
         *  - a fresh load when nothing was preloaded (IDLE / FAILED).
         *
         * Shows the template's shimmer until the ad is ready and collapses it via `showError()` if
         * the load ultimately fails, invoking [onFailed].
         */
        @JvmStatic
        @JvmOverloads
        fun showOrLoad(
            templateView: NativeTemplateView,
            adUnitId: String,
            onFailed: (() -> Unit)? = null,
        ) {
            val state = stateOf(adUnitId)
            log(
                when (state) {
                    NativeAdPreloader.State.READY -> "showOrLoad — showing preloaded ad instantly: $adUnitId"
                    NativeAdPreloader.State.LOADING -> "showOrLoad — awaiting in-flight load (no new request): $adUnitId"
                    else -> "showOrLoad — nothing preloaded ($state), starting a fresh load: $adUnitId"
                },
            )
            NativeAdPreloader.showInto(templateView, adUnitId, fallbackLoad = true, onFailed = {
                log("showOrLoad failed — slot collapsed: $adUnitId")
                onFailed?.invoke()
            })
        }

        /**
         * Destructive read: hands off the preloaded [NativeAd] for [adUnitId] (resetting the slot) so
         * the caller can bind it into a custom view and owns its lifecycle. Returns `null` when
         * nothing is held — pair it with [isAvailable].
         */
        @JvmStatic
        fun consume(adUnitId: String): NativeAd? = NativeAdPreloader.consume(adUnitId)

        /** Drops waiters and destroys any held preloaded ad for [adUnitId] (or every unit when null). */
        @JvmStatic
        @JvmOverloads
        fun clear(adUnitId: String? = null) {
            log("clear preloaded native ad(s): ${adUnitId ?: "ALL"}")
            NativeAdPreloader.clear(adUnitId)
        }

        /** Debug-only Logcat line (`adb logcat -s $TAG`). Silent in release builds. */
        private fun log(message: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, message)
        }
    }
}
