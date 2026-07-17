package com.alihassan.nextgenadscompose

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.NextGenAdsConfig
import com.alihassan.nextgenads.appopen.AppOpenAdManager
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.appopen.AppOpenCoverStyle

/**
 * Compose controller for an on-demand app-open ad unit (e.g. a splash gate). Obtain one with
 * [rememberAppOpenAd]. For the "show whenever the user returns to the app" behaviour, install
 * [AppOpenAdManager] once from `Application.onCreate` instead — that is process-scoped, not tied to
 * a composable.
 */
@Stable
class AppOpenAdController internal constructor(
    private val adUnitId: String,
    private val activityProvider: () -> Activity?,
) {
    private val helper get() = AppOpenAds.get(adUnitId)

    /** A non-expired ad is cached and ready to show right now. */
    val isReady: Boolean get() = helper.isReady

    /** Warms the cache so a later [show] is instant. */
    fun preload(remoteEnabled: Boolean = true) {
        helper.load(remoteEnabled = remoteEnabled)
    }

    /**
     * Shows the cached ad instantly, or fetches one on demand (behind [coverStyle]'s cover) and
     * shows it as soon as it lands. [onComplete] fires after dismissal, on failure, or on a
     * [timeoutMs] timeout so the caller can proceed into the app; the timeout defaults to
     * [NextGenAdsConfig.forceShowTimeoutMs].
     *
     * [coverStyle] defaults to the branded [AppOpenCoverStyle.WELCOME] "Welcome back" cover; pass
     * [AppOpenCoverStyle.LOADING] for the plain spinner (e.g. on a splash).
     */
    fun loadAndShow(
        timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
        coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
        onComplete: () -> Unit = {},
    ) {
        val activity = activityProvider() ?: return onComplete()
        helper.loadAndShow(activity, timeoutMs, coverStyle = coverStyle, onComplete = onComplete)
    }
}

/**
 * Remembers an [AppOpenAdController] for [adUnitId], optionally preloading it on first composition.
 */
@Composable
fun rememberAppOpenAd(
    adUnitId: String,
    preload: Boolean = true,
    remoteEnabled: Boolean = true,
): AppOpenAdController {
    val context = LocalContext.current
    val controller = remember(adUnitId) {
        AppOpenAdController(adUnitId) { context.findActivity() }
    }
    LaunchedEffect(adUnitId, preload, remoteEnabled) {
        if (preload) controller.preload(remoteEnabled)
    }
    return controller
}
