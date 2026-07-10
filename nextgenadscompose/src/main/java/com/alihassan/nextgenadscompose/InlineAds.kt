package com.alihassan.nextgenadscompose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.alihassan.nextgenads.AdType
import com.alihassan.nextgenads.BannerNativeView
import com.alihassan.nextgenads.banner.BannerSize
import com.alihassan.nextgenads.nativead.NativeTemplate

/**
 * A banner ad for Compose, backed by the library's [BannerNativeView]. It shows a shimmer while
 * loading, collapses to zero height on no-fill, and hides itself automatically when ads are
 * suppressed (premium user, remote flag off) — the same behaviour as the XML view.
 *
 * ```
 * BannerAd(adUnitId = BANNER_UNIT, size = BannerSize.ADAPTIVE)
 * ```
 *
 * @param remoteEnabled your remote-config flag for this placement; `false` hides the slot.
 * @param onLoaded invoked when a banner is shown.
 * @param onFailed invoked when the placement collapses because no ad could be loaded.
 */
@Composable
fun BannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    size: BannerSize = BannerSize.ADAPTIVE,
    remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    NextGenAdView(
        adUnitId = adUnitId,
        modifier = modifier,
        adType = AdType.BANNER,
        bannerSize = size,
        remoteEnabled = remoteEnabled,
        onLoaded = onLoaded,
        onFailed = onFailed,
    )
}

/**
 * A native ad for Compose, rendered with one of the built-in [NativeTemplate]s and backed by the
 * library's [BannerNativeView] (shimmer, no-fill collapse, premium auto-hide all handled).
 *
 * ```
 * NativeAd(adUnitId = NATIVE_UNIT, template = NativeTemplate.MEDIUM)
 * ```
 */
@Composable
fun NativeAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    template: NativeTemplate = NativeTemplate.MEDIUM,
    remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    NextGenAdView(
        adUnitId = adUnitId,
        modifier = modifier,
        adType = AdType.NATIVE,
        template = template,
        remoteEnabled = remoteEnabled,
        onLoaded = onLoaded,
        onFailed = onFailed,
    )
}

/**
 * The unified inline-ad composable that both [BannerAd] and [NativeAd] delegate to. Wraps a single
 * [BannerNativeView] via [AndroidView]: the view is created once, (re)loads whenever an input that
 * affects the request changes, and is destroyed when the composable leaves composition so no ad is
 * leaked. Prefer [BannerAd] / [NativeAd] unless you need to switch [adType] dynamically.
 */
@Composable
fun NextGenAdView(
    adUnitId: String,
    modifier: Modifier = Modifier,
    adType: AdType = AdType.NATIVE,
    template: NativeTemplate = NativeTemplate.MEDIUM,
    bannerSize: BannerSize = BannerSize.ADAPTIVE,
    remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {},
    onFailed: () -> Unit = {},
) {
    val context = LocalContext.current
    // Prefer the Activity context: a banner load requires one (native works with either).
    val viewContext = remember(context) { context.findActivity() ?: context }
    val adView = remember(viewContext) { BannerNativeView(viewContext) }

    // Keep the latest callbacks without restarting the load effect on every recomposition.
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentOnFailed by rememberUpdatedState(onFailed)

    // (Re)load only when an input that changes the request changes — not on every recomposition.
    LaunchedEffect(adView, adUnitId, adType, template, bannerSize, remoteEnabled) {
        adView.load(
            adUnitId = adUnitId,
            remoteEnabled = remoteEnabled,
            adType = adType,
            nativeTemplate = template,
            bannerSize = bannerSize,
            onLoaded = { currentOnLoaded() },
            onFailed = { currentOnFailed() },
        )
    }

    AndroidView(
        factory = { adView },
        modifier = modifier.fillMaxWidth(),
        onRelease = { it.destroy() },
    )
}
