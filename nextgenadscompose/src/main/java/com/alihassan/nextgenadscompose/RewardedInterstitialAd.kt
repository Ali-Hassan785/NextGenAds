package com.alihassan.nextgenadscompose

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.rewardedinterstitial.RewardedInterstitials
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem

/**
 * Compose controller for a single rewarded-interstitial ad unit. Obtain one with
 * [rememberRewardedInterstitialAd]. Like a rewarded ad, [onReward] fires only on a completed view;
 * [onDismiss] always fires when the ad closes.
 */
@Stable
class RewardedInterstitialAdController internal constructor(
    private val adUnitId: String,
    private val activityProvider: () -> Activity?,
) {
    private val helper get() = RewardedInterstitials.get(adUnitId)

    /** A non-expired ad is cached and ready to show right now. */
    val isReady: Boolean get() = helper.isReady

    /** Warms the cache so a later [show] is instant. */
    fun preload(remoteEnabled: Boolean = true) {
        helper.load(remoteEnabled = remoteEnabled)
    }

    /** Shows a preloaded ad if ready; otherwise invokes [onDismiss] immediately. */
    fun show(onReward: (RewardItem) -> Unit, onDismiss: () -> Unit = {}) {
        val activity = activityProvider() ?: return onDismiss()
        helper.show(activity, onReward, onDismiss)
    }

    /** Shows the cached ad instantly, or fetches one on demand (behind a loading cover) and shows it. */
    fun loadAndShow(
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = 10_000L,
        onDismiss: () -> Unit = {},
    ) {
        val activity = activityProvider() ?: return onDismiss()
        helper.loadAndShow(activity, onReward, timeoutMs, onDismiss)
    }
}

/**
 * Remembers a [RewardedInterstitialAdController] for [adUnitId], optionally preloading on first
 * composition.
 */
@Composable
fun rememberRewardedInterstitialAd(
    adUnitId: String,
    preload: Boolean = true,
    remoteEnabled: Boolean = true,
): RewardedInterstitialAdController {
    val context = LocalContext.current
    val controller = remember(adUnitId) {
        RewardedInterstitialAdController(adUnitId) { context.findActivity() }
    }
    LaunchedEffect(adUnitId, preload, remoteEnabled) {
        if (preload) controller.preload(remoteEnabled)
    }
    return controller
}
