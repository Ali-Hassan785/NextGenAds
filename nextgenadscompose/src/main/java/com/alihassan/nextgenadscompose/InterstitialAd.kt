package com.alihassan.nextgenadscompose

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.interstitial.Interstitials

/**
 * Compose controller for a single interstitial ad unit. Obtain one with [rememberInterstitialAd]
 * and call [show] / [loadAndShow] from a click handler. Backed by the shared
 * [Interstitials] registry, so the cached ad is reused across screens.
 */
@Stable
class InterstitialAdController internal constructor(
    private val adUnitId: String,
    private val activityProvider: () -> Activity?,
) {
    private val helper get() = Interstitials.get(adUnitId)

    /** A non-expired ad is cached and ready to show right now. */
    val isReady: Boolean get() = helper.isReady

    /** Warms the cache so a later [show] is instant. */
    fun preload(remoteEnabled: Boolean = true) {
        helper.load(remoteEnabled = remoteEnabled)
    }

    /**
     * Shows a preloaded ad if ready; otherwise invokes [onComplete] immediately so the caller can
     * proceed. Use [loadAndShow] to fetch on demand behind a loading cover.
     */
    fun show(onComplete: () -> Unit = {}) {
        val activity = activityProvider() ?: return onComplete()
        helper.show(activity, onComplete)
    }

    /**
     * Shows the cached ad instantly, or fetches one on demand (behind a loading cover) and shows it
     * as soon as it lands. [onComplete] fires after dismissal, on failure, or on [timeoutMs] timeout.
     */
    fun loadAndShow(timeoutMs: Long = 8_000L, onComplete: () -> Unit = {}) {
        val activity = activityProvider() ?: return onComplete()
        helper.loadAndShow(activity, timeoutMs, onComplete)
    }

    /** Counter-gated show: shows on every [nth]-th call. See [Interstitials.showEvery]. */
    fun showEvery(
        nth: Int,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onComplete: () -> Unit = {},
    ): Boolean {
        val activity = activityProvider() ?: run { onComplete(); return false }
        return helper.showEvery(activity, nth, forceLoad, timeoutMs, onComplete)
    }

    /** Shows on the first call, then every [nth]-th call after. See [Interstitials.showFirstThenEvery]. */
    fun showFirstThenEvery(
        nth: Int,
        forceLoad: Boolean = false,
        timeoutMs: Long = 0L,
        onComplete: () -> Unit = {},
    ): Boolean {
        val activity = activityProvider() ?: run { onComplete(); return false }
        return helper.showFirstThenEvery(activity, nth, forceLoad, timeoutMs, onComplete)
    }
}

/**
 * Remembers an [InterstitialAdController] for [adUnitId], optionally preloading it on first
 * composition.
 *
 * ```
 * val interstitial = rememberInterstitialAd(INTERSTITIAL_UNIT)
 * Button(onClick = { interstitial.loadAndShow { navigateNext() } }) { Text("Next") }
 * ```
 */
@Composable
fun rememberInterstitialAd(
    adUnitId: String,
    preload: Boolean = true,
    remoteEnabled: Boolean = true,
): InterstitialAdController {
    val context = LocalContext.current
    val controller = remember(adUnitId) {
        InterstitialAdController(adUnitId) { context.findActivity() }
    }
    LaunchedEffect(adUnitId, preload, remoteEnabled) {
        if (preload) controller.preload(remoteEnabled)
    }
    return controller
}
