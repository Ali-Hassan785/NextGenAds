package com.alihassan.nextgenads.splash

import android.app.Activity
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.NextGenAdsConfig
import com.alihassan.nextgenads.appopen.SplashAppOpenAd
import com.alihassan.nextgenads.interstitial.SplashAd

/** Which full-screen format the [SplashAdGate] shows on the splash. */
enum class SplashAdType {
    /** A full-screen interstitial, driven by [SplashAd]. */
    INTERSTITIAL,

    /**
     * An app-open ad, driven by [SplashAppOpenAd] — shown **without** the "Welcome back" cover
     * (the splash screen itself covers the fetch/render gap).
     */
    APP_OPEN,
}

/**
 * One splash-screen gate that shows **either an interstitial or an app-open ad** while your splash is
 * up, then calls [onComplete] once so you just navigate onward there.
 *
 * It implements the standard launch pattern in a single call: on a **cold start** (fresh process) it
 * shows [coldStartAdType] (an interstitial by default), and on a **warm / hot start** (relaunch while
 * the process is still alive) it shows [warmStartAdType] (an app-open ad by default) — i.e.
 * "interstitial on first launch, app-open on return". Flip [coldStartAdType] to [SplashAdType.APP_OPEN]
 * if you'd rather show an app-open on the cold splash too.
 *
 * Under the hood it delegates to [SplashAd] (interstitial) or [SplashAppOpenAd] (app-open), so the
 * same guarantees hold whichever format is chosen: the splash stays visible for at least [minDelayMs],
 * a slow or failed load never traps the user past [timeoutMs], and [onComplete] runs exactly once —
 * after the ad is dismissed, on timeout, on load failure, or synchronously when ads are disabled.
 *
 * Cold vs warm is not detected here (only the host [Activity] can survive a config-change recreation
 * correctly): resolve it once with [consumeColdStart], persist it across recreation, and pass it in as
 * [coldStart]. See the sample's `SplashActivity` for the full pattern.
 *
 * If you also run [com.alihassan.nextgenads.appopen.AppOpenAdManager] for foreground returns, add your
 * splash to its `skipOn(...)` list so the manager doesn't try to show a second app-open over the one
 * this gate already shows on a warm relaunch.
 *
 * Requires [NextGenAds.initialize] (and consent) to have completed — call this once that's done.
 */
object SplashAdGate {

    /**
     * Shows the splash ad for this launch and invokes [onComplete] when it's time to leave the splash.
     *
     * @param activity the splash activity the ad is shown over.
     * @param coldStart `true` for a fresh-process (cold) launch, `false` for a warm / hot relaunch.
     *   Source it from [consumeColdStart] (persisting it across config-change recreation).
     * @param interstitialUnitId interstitial unit, used when the resolved type is [SplashAdType.INTERSTITIAL].
     * @param appOpenUnitId app-open unit, used when the resolved type is [SplashAdType.APP_OPEN].
     * @param coldStartAdType format shown on a cold start. Defaults to [SplashAdType.INTERSTITIAL].
     * @param warmStartAdType format shown on a warm / hot start. Defaults to [SplashAdType.APP_OPEN].
     * @param minDelayMs minimum time (ms) to keep the splash visible before the ad can show.
     *   Defaults to [NextGenAdsConfig.splashMinDelayMs].
     * @param timeoutMs maximum time (ms) to wait for the ad; coerced to be ≥ [minDelayMs]. Defaults
     *   to [NextGenAdsConfig.splashTimeoutMs]; `0` disables the timeout (bounded only by the load's
     *   own retry budget).
     * @param retryOnFailure when `false` (the default, from [NextGenAdsConfig.splashRetryOnFailure])
     *   the splash load is a **single** attempt — a failed load proceeds at once and never fires
     *   retry requests. `true` keeps the helper's retry/backoff.
     * @param onComplete run once, on the main thread, when the splash should be dismissed.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        coldStart: Boolean,
        interstitialUnitId: String,
        appOpenUnitId: String,
        coldStartAdType: SplashAdType = SplashAdType.INTERSTITIAL,
        warmStartAdType: SplashAdType = SplashAdType.APP_OPEN,
        minDelayMs: Long = NextGenAdsConfig.splashMinDelayMs,
        timeoutMs: Long = NextGenAdsConfig.splashTimeoutMs,
        retryOnFailure: Boolean = NextGenAdsConfig.splashRetryOnFailure,
        onComplete: () -> Unit,
    ) {
        val type = if (coldStart) coldStartAdType else warmStartAdType
        NextGenAds.log(
            "SplashAdGate: ${if (coldStart) "COLD" else "WARM/HOT"} start → showing $type",
        )
        when (type) {
            SplashAdType.INTERSTITIAL -> SplashAd.show(
                activity = activity,
                adUnitId = interstitialUnitId,
                minDelayMs = minDelayMs,
                timeoutMs = timeoutMs,
                retryOnFailure = retryOnFailure,
                onComplete = onComplete,
            )

            SplashAdType.APP_OPEN -> SplashAppOpenAd.show(
                activity = activity,
                adUnitId = appOpenUnitId,
                minDelayMs = minDelayMs,
                timeoutMs = timeoutMs,
                retryOnFailure = retryOnFailure,
                onComplete = onComplete,
            )
        }
    }

    @Volatile
    private var coldStartUnconsumed = true

    /**
     * Returns `true` exactly once per process — on the genuine cold start (fresh process) — and
     * `false` on every later call (a warm / hot start while the process is still alive). Process death
     * resets it, so a relaunch after the app was killed is treated as cold again.
     *
     * Call this once, early, from your splash's `onCreate`, and persist the result across a
     * config-change recreation (e.g. in `onSaveInstanceState`) so the cold/warm decision doesn't flip
     * mid-splash — then pass it to [show] as [show]'s `coldStart`.
     */
    @JvmStatic
    @Synchronized
    fun consumeColdStart(): Boolean {
        val cold = coldStartUnconsumed
        coldStartUnconsumed = false
        return cold
    }
}
