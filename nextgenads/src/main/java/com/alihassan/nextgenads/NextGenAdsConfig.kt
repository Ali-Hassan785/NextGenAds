package com.alihassan.nextgenads

import kotlin.reflect.KProperty

/**
 * Default, app-wide tuning for the NextGenAds library — one place to set the timings every format
 * would otherwise take as a per-call argument or a per-ad-unit property. Override any value once
 * (e.g. from `Application.onCreate`, before your first ad call):
 *
 * ```
 * NextGenAdsConfig.splashTimeoutMs = 6_000L      // leave the splash sooner
 * NextGenAdsConfig.forceShowTimeoutMs = 5_000L   // cap every on-demand interstitial fetch
 * NextGenAdsConfig.minIntervalMs = 60_000L       // ≥ 60s between interstitials, app-wide
 * ```
 *
 * Nothing here is a hard override — it is the **default** each call site and helper falls back to,
 * so an explicit argument (`SplashAd.show(..., timeoutMs = 3_000L)`) or an explicit per-unit
 * property (`Interstitials.get(UNIT).maxRetries = 5`) still wins for that one call / unit.
 *
 * Every value is read live: the call-time defaults are evaluated on each call, and a helper property
 * follows this config until the host assigns that property directly. So changing a value mid-session
 * (e.g. easing off after a slow-network signal) takes effect on the next call, with no re-init.
 *
 * **Not covered here:** banner and native already expose their tuning as app-wide properties on
 * [com.alihassan.nextgenads.banner.BannerAdHelper] / [com.alihassan.nextgenads.nativead.NativeAdHelper]
 * (they are singletons, not per-unit helpers), so set those there — mirroring them into this object
 * would only create a second source of truth.
 */
object NextGenAdsConfig {

    // ---------------------------------------------------------------------------------------------
    // Request circuit breaker
    //
    // On a slow / offline connection ad requests fail repeatedly; hammering the SDK wastes battery,
    // data and retry budget. Once `maxRequestFailures` requests fail in a row without a single
    // success, the library stops issuing **new** ad requests for `requestCooldownMs` — already-cached
    // ads still show — then automatically resumes. A single success resets the failure count.
    // See NextGenAds.canRequest.
    // ---------------------------------------------------------------------------------------------

    /**
     * Number of consecutive failed ad requests (across all formats, no successful load in between)
     * that trips the cooldown. Default `3`.
     */
    @JvmStatic
    @Volatile
    var maxRequestFailures: Int = 3

    /**
     * How long, in milliseconds, to pause issuing new ad requests once [maxRequestFailures] is hit.
     * Default 3 minutes. Cached ads keep showing during the pause.
     */
    @JvmStatic
    @Volatile
    var requestCooldownMs: Long = 3 * 60 * 1000L

    // ---------------------------------------------------------------------------------------------
    // Splash
    //
    // Defaults for SplashAdGate.show / SplashAd.show / SplashAppOpenAd.show — the splash "timer".
    // ---------------------------------------------------------------------------------------------

    /**
     * Minimum time (ms) the splash stays visible before an ad may show, so branding is never
     * flashed past. Default `1_500`. Also the floor the splash waits out when the ad fails or ads
     * are disabled entirely.
     */
    @JvmStatic
    @Volatile
    var splashMinDelayMs: Long = 1_500L

    /**
     * Maximum time (ms) the splash waits for its ad before proceeding regardless, so a slow or dead
     * load can never trap the user. Default `8_000`. Coerced to be ≥ [splashMinDelayMs] at the call
     * site; `0` disables the timeout (the wait is then bounded only by the load's own retry budget).
     */
    @JvmStatic
    @Volatile
    var splashTimeoutMs: Long = 8_000L

    /**
     * Whether a failed splash load keeps retrying in the background. Default `false` — the splash
     * load is a **single** attempt, so a failed one proceeds at once and never fires retry requests.
     * `true` keeps the helper's retry/backoff, warming the cache for a later screen.
     */
    @JvmStatic
    @Volatile
    var splashRetryOnFailure: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // On-demand ("forced") shows
    //
    // The upper bound on how long the user waits behind a "Loading ad…" cover when a trigger fires
    // with nothing cached and the ad is fetched on demand — `loadAndShow`, or `showEvery` /
    // `showFirstThenEvery` with `forceLoad = true`. Past the bound the caller proceeds and the
    // in-flight load is left to warm the cache for next time.
    // ---------------------------------------------------------------------------------------------

    /**
     * Default bound (ms) on an on-demand interstitial / app-open fetch. Default `8_000`.
     *
     * `0` means "wait for the load result" — itself bounded by the retry budget, which with the
     * default [maxRetries] is ~7s of backoff *plus* each attempt's own network time. That is a long
     * time to hold a user behind a cover, which is why this is capped by default.
     */
    @JvmStatic
    @Volatile
    var forceShowTimeoutMs: Long = 8_000L

    /**
     * Default bound (ms) on an on-demand rewarded / rewarded-interstitial fetch. Default `10_000` —
     * longer than [forceShowTimeoutMs] because the user explicitly opted in to watch this ad for a
     * reward, so giving up early costs them the reward. `0` waits for the load result.
     */
    @JvmStatic
    @Volatile
    var rewardedForceShowTimeoutMs: Long = 10_000L

    // ---------------------------------------------------------------------------------------------
    // Per-unit helper defaults
    //
    // Seeds for the matching property on every per-unit full-screen helper (interstitial, app-open,
    // rewarded, rewarded-interstitial). A helper follows the value here until the host assigns that
    // property on the helper itself, which pins it for that unit only.
    // ---------------------------------------------------------------------------------------------

    /** Maximum automatic reload attempts after a failed load, with 1s/2s/4s… backoff. Default `3`. */
    @JvmStatic
    @Volatile
    var maxRetries: Int = 3

    /**
     * How long (ms) a loaded full-screen ad stays valid in the cache. Default 55 minutes. AdMob
     * expires these roughly an hour after loading; showing a stale one fails and the show is
     * silently lost, so anything older is dropped and re-requested rather than shown.
     *
     * App-open ads are excluded — their validity is a fixed 4h SDK rule, not a tunable.
     */
    @JvmStatic
    @Volatile
    var adValidityMs: Long = 55 * 60 * 1000L

    /**
     * Whether helpers automatically request the next ad after one is shown/dismissed (and when a
     * show finds none ready). Default `false`, so a preloaded ad results in a **single** request —
     * warm the next one explicitly via `load()` / `preload()`. Turning this on app-wide trades extra
     * requests for a higher show rate.
     */
    @JvmStatic
    @Volatile
    var autoReload: Boolean = false

    /**
     * Minimum gap (ms) between two full-screen ads of the same format — the app-wide frequency cap
     * for interstitial and app-open. Default `0` (no cap). A show inside the gap is skipped and its
     * `onComplete` fires immediately, leaving the ad cached.
     */
    @JvmStatic
    @Volatile
    var minIntervalMs: Long = 0L

    /**
     * Artificial dwell (ms) on a "Showing ad…" cover before an **already-cached** ad opens, so it
     * doesn't pop in abruptly. Default `0` — a ready ad shows instantly (smoothest). This is not the
     * cover shown during a genuine fetch (see [minLoadingCoverMs]); it is pure padding.
     */
    @JvmStatic
    @Volatile
    var loadingOverlayMs: Long = 0L

    /**
     * Minimum time (ms) the "Loading ad…" cover stays up during a genuine on-demand fetch. Default
     * `500`. A warm fetch returns in a few frames, so without this floor the cover would fade in
     * halfway and be torn straight down — reading as a glitch rather than a loading state. It only
     * *pads* a fetch that finished sooner; a slower fetch is never delayed. `0` disables the floor.
     */
    @JvmStatic
    @Volatile
    var minLoadingCoverMs: Long = 500L

    // ---------------------------------------------------------------------------------------------
    // App-open manager
    // ---------------------------------------------------------------------------------------------

    /**
     * Default for [com.alihassan.nextgenads.appopen.AppOpenAdManager.loadTimeoutMs]: the window (ms)
     * after a foreground return during which a just-requested app-open ad may still be shown.
     * Default `5_000`. An ad landing after the window is **not** shown mid-session — that would pop a
     * full-screen ad at an unexpected moment — but stays cached so the next return shows it
     * instantly. `0` never shows a late-loading ad (the on-return request only warms the cache).
     */
    @JvmStatic
    @Volatile
    var appOpenLoadTimeoutMs: Long = 5_000L
}

/**
 * Backs a per-instance `var` whose default lives in [NextGenAdsConfig]: reads fall through to
 * [configValue] until the host assigns the property, after which the assigned value wins for that
 * instance forever (including across later config changes).
 *
 * This keeps the config live — a helper created before `Application.onCreate` finishes tuning still
 * picks up the final values — where a plain `var x = NextGenAdsConfig.x` initializer would snapshot
 * whatever happened to be set when that unit's helper was first constructed.
 */
internal class ConfigDefault<T : Any>(private val configValue: () -> T) {

    /** The host-assigned value, or `null` while this instance still follows [NextGenAdsConfig]. */
    @Volatile
    var override: T? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = override ?: configValue()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        override = value
    }
}
