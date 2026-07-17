package com.alihassan.nextgenads.interstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.NextGenAdsConfig
import com.alihassan.nextgenads.events.AdFormat

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
 * - ad fails       → [onComplete] fires after [minDelayMs]. With [retryOnFailure] `false` (default)
 *                    the splash makes a **single** request and never fires retry requests; with it
 *                    `true` the load retries in the background to warm the cache for a later screen.
 * - ad never loads → [onComplete] fires at [timeoutMs].
 * - ads disabled   → [onComplete] fires after [minDelayMs], no request.
 *
 * Requires [NextGenAds.initialize] (and consent) to have completed — call this once that's done.
 */
object SplashAd {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param adUnitId interstitial unit to load for the splash.
     * @param minDelayMs minimum time (ms) to keep the splash visible before the ad can show.
     *   Defaults to [NextGenAdsConfig.splashMinDelayMs].
     * @param timeoutMs maximum time (ms) to wait for the ad; coerced to be ≥ [minDelayMs]. Defaults
     *   to [NextGenAdsConfig.splashTimeoutMs]; `0` disables the timeout (bounded only by the load's
     *   own retry budget).
     * @param retryOnFailure when `false` (the default, from [NextGenAdsConfig.splashRetryOnFailure])
     *   the splash load is a **single** attempt — a failed load proceeds at once and never fires
     *   retry requests. Set `true` to keep the helper's retry/backoff so the load keeps trying
     *   (warming the cache for a later screen).
     * @param onComplete run once, on the main thread, when the splash should be dismissed.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        adUnitId: String,
        minDelayMs: Long = NextGenAdsConfig.splashMinDelayMs,
        timeoutMs: Long = NextGenAdsConfig.splashTimeoutMs,
        retryOnFailure: Boolean = NextGenAdsConfig.splashRetryOnFailure,
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

        if (!NextGenAds.canShowAds(AdFormat.INTERSTITIAL)) {
            // Premium / kill-switch: no ad, but still respect the minimum splash time.
            proceedAfterMinDelay()
            return@runOnMain
        }

        val helper = Interstitials.get(adUnitId)

        // Fail-fast splash: cap the load to a single attempt so a failed splash load doesn't fire
        // retry requests (during or after the splash). Restored the moment the load resolves — safe
        // because the helper runs only one in-flight load at a time, so nothing else races on it.
        // The raw override is saved (not the effective value) so a unit that follows
        // NextGenAdsConfig.maxRetries keeps following it once the splash is done.
        val savedMaxRetries = helper.maxRetriesOverride
        if (!retryOnFailure) helper.maxRetries = 0

        // Hard ceiling on the whole splash: past this we leave no matter what. timeoutMs > minDelayMs
        // is enforced so the timeout can never cut the minimum splash time short.
        val cappedTimeout = if (timeoutMs <= 0) 0 else timeoutMs.coerceAtLeast(minDelayMs)
        val timeoutRunnable = Runnable {
            NextGenAds.log("SplashAd: ad not ready within ${cappedTimeout}ms, proceeding: $adUnitId")
            finishOnce() // in-flight load is left running to warm the cache for later
        }
        if (cappedTimeout > 0) handler.postDelayed(timeoutRunnable, cappedTimeout)

        helper.load { loaded ->
            if (!retryOnFailure) helper.maxRetriesOverride = savedMaxRetries // restore before anything else
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
                // Only pop the interstitial while the app is actually resumed in the foreground. If it
                // was backgrounded during the splash, showing now would surface the ad on the user's
                // return — when they expect an app-open ad, not this cold-start interstitial. Keep it
                // cached and leave navigation to the host (its foreground-return path routes to Main).
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    // Backgrounded during the splash: don't pop this cold-start interstitial on the
                    // user's return (they'd expect an app-open, not this). Keep the ad cached — but
                    // still guarantee we leave the splash: defer completion to the next foreground so
                    // onComplete fires exactly once instead of never (which would trap the user on the
                    // splash once the host has handed completion to this flow).
                    NextGenAds.log("SplashAd: app not resumed (backgrounded during splash) — deferring proceed: $adUnitId")
                    lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            lifecycle.removeObserver(this)
                            finishOnce() // proceed off the splash; the loaded ad stays cached for later
                        }
                    })
                    return@postDelayed
                }
                // show() calls onComplete via its dismiss callback; if it can't show, proceed now.
                if (!helper.show(activity, onComplete = { finishOnce() })) finishOnce()
            }, wait)
        }
    }
}
