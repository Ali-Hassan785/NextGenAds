package com.alihassan.nextgenadscompose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdEventListener

/**
 * Registers an app-wide [AdEventListener] for the lifetime of this composition and unregisters it
 * automatically when the composable leaves. Use it to drive analytics / revenue tracking or refresh
 * UI on ad events without threading callbacks through every call site.
 *
 * Pass a **stable** listener (wrap it in `remember { }`) so it registers once:
 * ```
 * val listener = remember {
 *     object : AdEventListener {
 *         override fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue, responseInfo: ResponseInfo?) {
 *             analytics.logAdRevenue(value)
 *         }
 *     }
 * }
 * AdEventsEffect(listener)
 * ```
 *
 * The effect is keyed on [listener] identity: a new instance re-registers (disposing the old one),
 * so a remembered listener stays registered for the whole composition.
 */
@Composable
fun AdEventsEffect(listener: AdEventListener) {
    DisposableEffect(listener) {
        NextGenAds.registerEventListener(listener)
        onDispose { NextGenAds.unregisterEventListener(listener) }
    }
}
