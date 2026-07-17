package com.alihassan.nextgenads

import android.app.Activity
import android.app.Application
import com.alihassan.nextgenads.appopen.AppOpenAdManager
import com.alihassan.nextgenads.consent.ConsentManager

/**
 * Optional one-call setup for the sequence every app repeats to bring ads up: gather UMP consent,
 * **then** initialize the SDK — the order matters, because a pre-consent ad request is refused — plus
 * (optionally) install the foreground-return app-open manager and connectivity recovery.
 *
 * Everything here is built from the library's own public pieces ([ConsentManager], [NextGenAds],
 * [AppOpenAdManager]); this object just wires them in the right order so callers don't have to
 * remember it. Use it, or wire those pieces yourself if you want full control. It holds no state — an
 * app supplies its own ad-unit ids, test-device ids and skip-list, so the same code serves any app
 * that depends on this library.
 *
 * Typical use:
 * ```
 * // Application.onCreate:
 * NextGenAdsBootstrap.configure(this, appOpenUnitId = APP_OPEN_UNIT,
 *     skipAppOpenOn = listOf(SplashActivity::class.java))
 *
 * // First screen (splash / main):
 * NextGenAdsBootstrap.gatherConsentThenInitialize(this, testDeviceHashedId, testDeviceIds) {
 *     Interstitials.preload(INTERSTITIAL_UNIT)   // ads are ready here
 * }
 * ```
 */
object NextGenAdsBootstrap {

    /**
     * Process-level setup — call once from `Application.onCreate` (safe before consent/init; nothing
     * here requests an ad). Enables connectivity recovery and, when [appOpenUnitId] is non-blank,
     * installs the auto-show app-open manager, excluding [skipAppOpenOn] (e.g. your splash / paywall).
     *
     * Registering ad-event listeners, tuning [NextGenAdsConfig], and customising the app-open cover
     * are intentionally left to the caller — do those around this call.
     *
     * @return the installed [AppOpenAdManager] for further tuning (`loadTimeoutMs`, `coverStyle`, …),
     *   or `null` when no [appOpenUnitId] was given.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        application: Application,
        appOpenUnitId: String? = null,
        skipAppOpenOn: List<Class<out Activity>> = emptyList(),
        connectivityRecovery: Boolean = true,
    ): AppOpenAdManager? {
        if (connectivityRecovery) NextGenAds.enableConnectivityRecovery(application)
        return appOpenUnitId
            ?.takeIf { it.isNotBlank() }
            ?.let { unit ->
                AppOpenAdManager.install(application, unit).apply {
                    if (skipAppOpenOn.isNotEmpty()) skipOn(*skipAppOpenOn.toTypedArray())
                }
            }
    }

    /**
     * Gathers UMP consent, **then** initializes the SDK, then runs [onReady] on the main thread — the
     * correct order, since an ad request before consent is refused. The App ID is read from the
     * manifest's `com.google.android.gms.ads.APPLICATION_ID` (the single-source-of-truth path). If you
     * supply the App ID in code instead, call [ConsentManager] + [NextGenAds.initialize] yourself.
     * [onReady] always runs (after init), so callers can navigate on uniformly.
     *
     * When a [testDeviceHashedId] is given (debug only — never in release), that device is registered
     * with UMP and the EEA debug geography is forced so the consent form appears from any region.
     *
     * @param testDeviceHashedId debug-only device hash (find it in logcat); `null` in release builds.
     * @param testDeviceIds devices that always receive test ads (safe to ship empty).
     * @param onReady run once on the main thread when consent has been gathered and the SDK is ready.
     */
    @JvmStatic
    @JvmOverloads
    fun gatherConsentThenInitialize(
        activity: Activity,
        testDeviceHashedId: String? = null,
        testDeviceIds: List<String> = emptyList(),
        onReady: Runnable,
    ) {
        ConsentManager.getInstance(activity, testDeviceHashedId)
            .gatherConsent(activity, forceEea = testDeviceHashedId != null) {
                NextGenAds.initialize(activity, testDeviceIds) { onReady.run() }
            }
    }
}
