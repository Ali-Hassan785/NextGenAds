package com.alihassan.nextgenads.interstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.alihassan.nextgenads.NextGenAds

/**
 * Drives a **splash-screen interstitial**: while your splash is on screen, it loads an interstitial
 * and shows it once ready — but never before a [minimum delay][minDelayMs] (so the splash / branding
 * is always visible for at least that long) and never after a [timeout][timeoutMs] (so a slow or
 * failed load can't trap the user on the splash). [onComplete] is invoked exactly once, when it's
 * time to leave the splash — after the ad is dismissed, or when the ad is skipped — so the caller
 * just navigates to its main screen there.
 *
 * Timeline for a cold start:
 * - ad loads fast  → wait out [minDelayMs], show the ad, then [onComplete] on dismiss.
 * - ad loads slow  → show it as soon as it lands (past [minDelayMs]), up to [timeoutMs].
 * - ad never loads → [onComplete] fires at [timeoutMs] (the in-flight load keeps warming the cache).
 * - ads disabled   → [onComplete] fires after [minDelayMs], no request.
 *
 * Requires [NextGenAds.initialize] (and consent) to have completed — call this once that's done.
 */
object SplashAd {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param adUnitId interstitial unit to load for the splash.
     * @param minDelayMs minimum time (ms) to keep the splash visible before the ad can show.
     * @param timeoutMs maximum time (ms) to wait for the ad; coerced to be ≥ [minDelayMs]. `0`
     *   disables the timeout (bounded only by the load's own retry budget).
     * @param onComplete run once, on the main thread, when the splash should be dismissed.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        adUnitId: String,
        minDelayMs: Long = 1_000L,
        timeoutMs: Long = 8_000L,
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

        if (!NextGenAds.canShowAds()) {
            // Premium / kill-switch: no ad, but still respect the minimum splash time.
            proceedAfterMinDelay()
            return@runOnMain
        }

        val helper = Interstitials.get(adUnitId)

        // Hard ceiling on the whole splash: past this we leave no matter what. timeoutMs > minDelayMs
        // is enforced so the timeout can never cut the minimum splash time short.
        val cappedTimeout = if (timeoutMs <= 0) 0 else timeoutMs.coerceAtLeast(minDelayMs)
        val timeoutRunnable = Runnable {
            NextGenAds.log("SplashAd: ad not ready within ${cappedTimeout}ms, proceeding: $adUnitId")
            finishOnce() // in-flight load is left running to warm the cache for later
        }
        if (cappedTimeout > 0) handler.postDelayed(timeoutRunnable, cappedTimeout)

        helper.load { loaded ->
            if (finished) return@load // already timed out / proceeded
            handler.removeCallbacks(timeoutRunnable)
            if (!loaded) {
                NextGenAds.log("SplashAd: load failed, proceeding after min delay: $adUnitId")
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
                // show() calls onComplete via its dismiss callback; if it can't show, proceed now.
                if (!helper.show(activity, onDismiss = { finishOnce() })) finishOnce()
            }, wait)
        }
    }
}
