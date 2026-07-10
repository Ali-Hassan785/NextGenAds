package com.alihassan.nextgenadscompose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.consent.ConsentManager

/**
 * Remembers the shared [ConsentManager]. Gather consent from a splash / first screen, then
 * initialize the SDK once consent allows:
 *
 * ```
 * val consent = rememberConsentManager()
 * val activity = LocalContext.current.findActivity()!!
 * LaunchedEffect(Unit) {
 *     consent.gatherConsent(activity) {
 *         if (consent.canRequestAds) NextGenAds.initialize(activity, APP_ID) { /* preload */ }
 *     }
 * }
 * ```
 *
 * @param testDeviceHashedId optional UMP test-device hash (debug only) so the consent form can be
 *   exercised from any region; leave `null` in production.
 */
@Composable
fun rememberConsentManager(testDeviceHashedId: String? = null): ConsentManager {
    val context = LocalContext.current
    return remember(context, testDeviceHashedId) {
        ConsentManager.getInstance(context, testDeviceHashedId)
    }
}
