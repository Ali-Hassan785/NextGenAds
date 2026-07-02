package com.alihassan.nextgenads.events

import android.util.Log
import com.alihassan.nextgenads.NextGenAds
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.util.EnumMap

/**
 * Measures live **fill** and **show** rates per ad format by tallying the library's event stream.
 * Register once (typically from `Application.onCreate`):
 *
 * ```
 * val tracker = ShowRateTracker()
 * NextGenAds.registerEventListener(tracker)
 * // …later, dump the numbers:
 * Log.d("Ads", tracker.report())
 * ```
 *
 * Definitions (all per format):
 * - **requested** — SDK requests fired, including retries.
 * - **loaded / failed** — request outcomes.
 * - **fill%** = loaded ÷ requested — success rate of a request attempt (demand-driven).
 * - **impressions / shown** — ads actually seen (impression = the canonical "seen" signal for every
 *   format; shown = full-screen present callbacks).
 * - **use%** = impressions ÷ loaded — the share of loaded ads that were actually shown. This is the
 *   number the preload / retry / await-in-flight optimizations move: it climbs toward 100% when
 *   loaded inventory isn't wasted.
 *
 * Callbacks arrive on the main thread, so counters need no locking.
 */
class ShowRateTracker @JvmOverloads constructor(
    /** When true, logs a one-line summary for the format after each request/load/fail/impression. */
    private val logEachEvent: Boolean = true,
) : AdEventListener {

    private class Counters {
        var requested = 0
        var loaded = 0
        var failed = 0
        var shown = 0
        var impressions = 0
        var clicked = 0
    }

    private val stats = EnumMap<AdFormat, Counters>(AdFormat::class.java)

    private fun counters(format: AdFormat): Counters = stats.getOrPut(format) { Counters() }

    override fun onAdRequested(format: AdFormat, adUnitId: String) {
        counters(format).requested++
        logLine(format)
    }

    override fun onAdLoaded(format: AdFormat, adUnitId: String) {
        counters(format).loaded++
        logLine(format)
    }

    override fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) {
        counters(format).failed++
        logLine(format)
    }

    override fun onAdShown(format: AdFormat, adUnitId: String) {
        counters(format).shown++
    }

    override fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) {
        logLine(format)
    }

    override fun onAdImpression(format: AdFormat, adUnitId: String) {
        counters(format).impressions++
        logLine(format)
    }

    override fun onAdClicked(format: AdFormat, adUnitId: String) {
        counters(format).clicked++
    }

    /** Resets all counters (e.g. between test runs). */
    fun reset() = stats.clear()

    /** Compact one-line summary for a single format, e.g. `NATIVE req=5 load=4 fill=80% use=75%`. */
    fun summaryFor(format: AdFormat): String {
        val c = counters(format)
        return "$format req=${c.requested} load=${c.loaded} fail=${c.failed} " +
            "imp=${c.impressions} fill=${pct(c.loaded, c.requested)} use=${pct(c.impressions, c.loaded)}"
    }

    /** Multi-line report across every format seen so far. */
    fun report(): String {
        if (stats.isEmpty()) return "ShowRateTracker: no ad events yet"
        val sb = StringBuilder("── Ad show-rate report ──\n")
        sb.append(
            "%-22s %4s %4s %4s %5s %5s %5s %5s\n".format(
                "FORMAT", "REQ", "LOAD", "FAIL", "SHOW", "IMP", "FILL", "USE",
            ),
        )
        for ((format, c) in stats) {
            sb.append(
                "%-22s %4d %4d %4d %5d %5d %5s %5s\n".format(
                    format.name, c.requested, c.loaded, c.failed, c.shown, c.impressions,
                    pct(c.loaded, c.requested), pct(c.impressions, c.loaded),
                ),
            )
        }
        sb.append("fill% = loaded/requested · use% = impressions/loaded (shown ÷ loaded inventory)")
        return sb.toString()
    }

    /** Logs [report] to logcat under the [NextGenAds.TAG] tag. */
    fun logReport() = Log.d(NextGenAds.TAG, "\n" + report())

    private fun logLine(format: AdFormat) {
        if (logEachEvent) Log.d(NextGenAds.TAG, "ShowRate ${summaryFor(format)}")
    }

    private fun pct(part: Int, whole: Int): String =
        if (whole <= 0) "—" else "${(part * 100) / whole}%"
}
