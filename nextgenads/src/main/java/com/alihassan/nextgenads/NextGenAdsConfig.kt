package com.alihassan.nextgenads

/**
 * Default, app-wide tuning for the NextGenAds library. Override any value once (e.g. from
 * `Application.onCreate`) — every helper reads these live:
 *
 * ```
 * NextGenAdsConfig.maxRequestFailures = 5
 * NextGenAdsConfig.requestCooldownMs = 2 * 60 * 1000L
 * ```
 *
 * The two values below drive the global **request circuit breaker** (see [NextGenAds.canRequest]).
 * On a slow / offline connection, ad requests fail repeatedly; hammering the SDK wastes battery,
 * data and retry budget. Once [maxRequestFailures] requests fail in a row without a single success,
 * the library stops issuing **new** ad requests for [requestCooldownMs] — already-cached ads still
 * show — then automatically resumes. A single success at any point resets the failure count.
 */
object NextGenAdsConfig {

    /**
     * Number of consecutive failed ad requests (across all formats, no successful load in between)
     * that trips the cooldown. Default `3`.
     */
    @JvmStatic
    @Volatile
    var maxRequestFailures: Int = 3

    /**
     * How long, in milliseconds, to pause issuing new ad requests once [maxRequestFailures] is hit.
     * Default 3 minutes. Cached ads keep showing during the pause.
     */
    @JvmStatic
    @Volatile
    var requestCooldownMs: Long = 3 * 60 * 1000L
}
