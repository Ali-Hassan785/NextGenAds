package com.alihassan.nextgenadscompose

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.NextGenAdsConfig
import com.alihassan.nextgenads.rewarded.RewardedAds
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem

/**
 * Compose controller for a single rewarded ad unit. Obtain one with [rememberRewardedAd]. The
 * [onReward] you pass to [show] / [loadAndShow] fires only when the user actually earns the reward;
 * [onComplete] always fires when the ad closes.
 */
@Stable
class RewardedAdController internal constructor(
    private val adUnitId: String,
    private val activityProvider: () -> Activity?,
) {
    private val helper get() = RewardedAds.get(adUnitId)

    /** A non-expired ad is cached and ready to show right now. */
    val isReady: Boolean get() = helper.isReady

    /** Warms the cache so a later [show] is instant. */
    fun preload(remoteEnabled: Boolean = true) {
        helper.load(remoteEnabled = remoteEnabled)
    }

    /**
     * Shows a preloaded ad if ready; otherwise invokes [onComplete] immediately. [onReward] fires
     * with the earned [RewardItem] only if the user completes the ad.
     */
    fun show(onReward: (RewardItem) -> Unit, onComplete: () -> Unit = {}) {
        val activity = activityProvider() ?: return onComplete()
        helper.show(activity, onReward, onComplete)
    }

    /**
     * Shows the cached ad instantly, or fetches one on demand (behind a loading cover) and shows it.
     * [onReward] fires only on a completed view; [onComplete] always fires when the ad closes or on
     * a [timeoutMs] timeout, which defaults to [NextGenAdsConfig.rewardedForceShowTimeoutMs].
     */
    fun loadAndShow(
        onReward: (RewardItem) -> Unit,
        timeoutMs: Long = NextGenAdsConfig.rewardedForceShowTimeoutMs,
        onComplete: () -> Unit = {},
    ) {
        val activity = activityProvider() ?: return onComplete()
        helper.loadAndShow(activity, onReward, timeoutMs, onComplete)
    }
}

/**
 * Remembers a [RewardedAdController] for [adUnitId], optionally preloading it on first composition.
 *
 * ```
 * val rewarded = rememberRewardedAd(REWARDED_UNIT)
 * Button(onClick = {
 *     rewarded.loadAndShow(onReward = { grant(it.amount) }, onComplete = { })
 * }) { Text("Watch to earn") }
 * ```
 */
@Composable
fun rememberRewardedAd(
    adUnitId: String,
    preload: Boolean = true,
    remoteEnabled: Boolean = true,
): RewardedAdController {
    val context = LocalContext.current
    val controller = remember(adUnitId) {
        RewardedAdController(adUnitId) { context.findActivity() }
    }
    LaunchedEffect(adUnitId, preload, remoteEnabled) {
        if (preload) controller.preload(remoteEnabled)
    }
    return controller
}
