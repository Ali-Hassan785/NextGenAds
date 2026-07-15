package com.alihassan.nextgenads.appopen

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdFormat

/**
 * Drives an **app-open ad on a splash screen** — the app-open counterpart of
 * [com.alihassan.nextgenads.interstitial.SplashAd]. It loads an app-open ad while your splash is up,
 * holds it for a **minimum delay** (branding is always visible) and bounds it by a **timeout** (a
 * slow or failed load can never trap the user), then shows it and calls [onComplete] once — after
 * the ad is dismissed, or when it's skipped — so you just navigate onward there.
 *
 * Unlike [AppOpenAdManager], the ad is shown here **without the branded "Welcome back" cover** — a
 * plain "Loading ad…" cover (the same one the interstitial splash uses) bridges the brief show→render
 * gap instead, so the splash never flashes the app icon + "Welcome back" text. Use it in place of
 * [SplashAd][com.alihassan.nextgenads.interstitial.SplashAd] when you want an app-open (rather than an
 * interstitial) on the splash — e.g. on a warm / hot start.
 *
 * Requires [NextGenAds.initialize] (and consent) to have completed — call this once that's done.
 *
 * Timeline:
 * - ad loads fast  → wait out [minDelayMs], show the ad, then [onComplete] on dismiss.
 * - ad loads slow  → show it as soon as it lands (past [minDelayMs]), up to [timeoutMs].
 * - ad fails       → [onComplete] fires after [minDelayMs]. With [retryOnFailure] `false` (default)
 *                    the splash makes a **single** request and never fires retry requests.
 * - ad never loads → [onComplete] fires at [timeoutMs].
 * - ads disabled   → [onComplete] fires after [minDelayMs], no request.
 */
object SplashAppOpenAd {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param adUnitId app-open unit to load for the splash.
     * @param minDelayMs minimum time (ms) to keep the splash visible before the ad can show.
     * @param timeoutMs maximum time (ms) to wait for the ad; coerced to be ≥ [minDelayMs]. `0`
     *   disables the timeout (bounded only by the load's own retry budget).
     * @param retryOnFailure when `false` (default) the splash load is a **single** attempt — a failed
     *   load proceeds at once and never fires retry requests. Set `true` to keep the helper's
     *   retry/backoff so the load keeps trying (warming the cache for a later opportunity).
     * @param onComplete run once, on the main thread, when the splash should be dismissed.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        adUnitId: String,
        minDelayMs: Long = 1_000L,
        timeoutMs: Long = 8_000L,
        retryOnFailure: Boolean = false,
        onComplete: () -> Unit,
    ) = NextGenAds.runOnMain {
        val start = SystemClock.elapsedRealtime()
        var finished = false

        fun finishOnce() {
            if (finished) return
            finished = true
            onComplete()
        }

        // Hold the splash for the remainder of minDelayMs, then leave.
        fun proceedAfterMinDelay() {
            val wait = (minDelayMs - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
            handler.postDelayed({ finishOnce() }, wait)
        }

        if (!NextGenAds.canShowAds(AdFormat.APP_OPEN)) {
            // Premium / kill-switch: no ad, but still respect the minimum splash time.
            proceedAfterMinDelay()
            return@runOnMain
        }

        val helper = AppOpenAds.get(adUnitId)

        // Fail-fast splash: cap the load to a single attempt so a failed splash load doesn't fire
        // retry requests (during or after the splash). Restored the moment the load resolves — safe
        // because the helper runs only one in-flight load at a time, so nothing else races on it.
        val savedMaxRetries = helper.maxRetries
        if (!retryOnFailure) helper.maxRetries = 0

        // Hard ceiling on the whole splash: past this we leave no matter what. timeoutMs > minDelayMs
        // is enforced so the timeout can never cut the minimum splash time short.
        val cappedTimeout = if (timeoutMs <= 0) 0 else timeoutMs.coerceAtLeast(minDelayMs)
        val timeoutRunnable = Runnable {
            NextGenAds.log("SplashAppOpenAd: ad not ready within ${cappedTimeout}ms, proceeding: $adUnitId")
            finishOnce() // in-flight load is left running to warm the cache for later
        }
        if (cappedTimeout > 0) handler.postDelayed(timeoutRunnable, cappedTimeout)

        helper.load { loaded ->
            if (!retryOnFailure) helper.maxRetries = savedMaxRetries // restore before anything else
            if (finished) return@load // already timed out / proceeded
            handler.removeCallbacks(timeoutRunnable)
            if (!loaded) {
                NextGenAds.log("SplashAppOpenAd: load failed, proceeding after min delay: $adUnitId")
                proceedAfterMinDelay()
                return@load
            }
            // Loaded: show once the minimum splash time has elapsed.
            val wait = (minDelayMs - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
            handler.postDelayed({
                if (finished) return@postDelayed
                if (activity.isFinishing || activity.isDestroyed) {
                    // Splash already gone; keep the ad cached rather than showing into nothing.
                    finishOnce()
                    return@postDelayed
                }
                // Only open the ad while the app is actually resumed in the foreground; if it was
                // backgrounded during the splash, keep the ad cached and let the host proceed.
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    // Backgrounded during the splash: don't open the app-open on the user's return
                    // (the AppOpenAdManager owns foreground returns). Keep the ad cached — but still
                    // guarantee we leave the splash: defer completion to the next foreground so
                    // onComplete fires exactly once instead of never (which would trap the user on the
                    // splash once the host has handed completion to this flow).
                    NextGenAds.log("SplashAppOpenAd: app not resumed (backgrounded during splash) — deferring proceed: $adUnitId")
                    lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            lifecycle.removeObserver(this)
                            finishOnce() // proceed off the splash; the loaded ad stays cached for later
                        }
                    })
                    return@postDelayed
                }
                // Bridge the show→render gap with the plain "Loading ad…" cover, never the branded
                // "Welcome back" one (out of place on a splash). show() calls onComplete via its
                // dismiss callback; if it can't show, proceed now.
                val shown = helper.show(
                    activity,
                    onDismiss = { finishOnce() },
                    coverStyle = AppOpenCoverStyle.LOADING,
                )
                if (!shown) finishOnce()
            }, wait)
        }
    }
}
