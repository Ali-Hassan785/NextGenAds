package com.alihassan.nextgenads.events

import android.os.SystemClock
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
        // Load-time timing: measured from the most recent request to its load.
        var lastRequestElapsed = 0L
        var lastLoadMs = 0L
        var totalLoadMs = 0L
        var timedLoads = 0

        /** Average load time (ms) across timed loads, or -1 when nothing has loaded yet. */
        val avgLoadMs: Long get() = if (timedLoads > 0) totalLoadMs / timedLoads else -1L
    }

    private val stats = EnumMap<AdFormat, Counters>(AdFormat::class.java)

    private fun counters(format: AdFormat): Counters = stats.getOrPut(format) { Counters() }

    override fun onAdRequested(format: AdFormat, adUnitId: String) {
        val c = counters(format)
        c.requested++
        c.lastRequestElapsed = SystemClock.elapsedRealtime()
        logLine(format)
    }

    override fun onAdLoaded(format: AdFormat, adUnitId: String) {
        val c = counters(format)
        c.loaded++
        if (c.lastRequestElapsed > 0L) {
            val ms = SystemClock.elapsedRealtime() - c.lastRequestElapsed
            c.lastLoadMs = ms
            c.totalLoadMs += ms
            c.timedLoads++
        }
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
            "imp=${c.impressions} fill=${pct(c.loaded, c.requested)} use=${pct(c.impressions, c.loaded)} " +
            "avg=${c.avgLoadMs.takeIf { it >= 0 }?.let { "${it}ms" } ?: "—"}"
    }

    /**
     * Immutable per-format rows for building a real table (e.g. an Android `TableLayout`) instead of
     * parsing [report]'s text. One entry per format seen so far, in insertion order.
     */
    fun snapshot(): List<FormatRow> = stats.map { (format, c) ->
        FormatRow(
            format = format.name,
            requested = c.requested,
            loaded = c.loaded,
            failed = c.failed,
            shown = c.shown,
            impressions = c.impressions,
            fillPct = pct(c.loaded, c.requested),
            usePct = pct(c.impressions, c.loaded),
            avgLoadMs = c.avgLoadMs,
        )
    }

    /**
     * Multi-line report across every format seen so far, rendered as a bordered box-drawing table
     * that stays aligned in any monospace view (logcat, …). For an on-screen widget prefer
     * [snapshot] with a real `TableLayout`.
     */
    fun report(): String {
        val rows = snapshot()
        if (rows.isEmpty()) return "Ad show-rate report\n(no ad events yet)"

        val body = rows.map { it.cells() }
        // Column width = widest cell (header or any row), plus one space of padding on each side.
        val widths = IntArray(COLUMNS.size) { col ->
            (body.map { it[col].length } + COLUMNS[col].length).maxOrNull()!! + 2
        }

        fun rule(left: String, mid: String, right: String): String =
            widths.joinToString(mid, prefix = left, postfix = right) { "─".repeat(it) }

        fun row(cells: List<String>): String =
            cells.mapIndexed { i, cell ->
                val pad = widths[i] - cell.length - 1
                if (LEFT_ALIGNED[i]) " $cell${" ".repeat(pad)}" else "${" ".repeat(pad)}$cell "
            }.joinToString("│", prefix = "│", postfix = "│")

        return buildString {
            append("Ad show-rate report\n")
            append(rule("┌", "┬", "┐")).append('\n')
            append(row(COLUMNS)).append('\n')
            append(rule("├", "┼", "┤")).append('\n')
            body.forEach { append(row(it)).append('\n') }
            append(rule("└", "┴", "┘")).append('\n')
            append("fill% = loaded/requested · use% = impressions/loaded · avg ms = mean load time")
        }
    }

    /** Logs [report] to logcat under the [NextGenAds.TAG] tag. */
    fun logReport() = Log.d(NextGenAds.TAG, "\n" + report())

    private fun logLine(format: AdFormat) {
        if (logEachEvent) Log.d(NextGenAds.TAG, "ShowRate ${summaryFor(format)}")
    }

    private fun pct(part: Int, whole: Int): String =
        if (whole <= 0) "—" else "${(part * 100) / whole}%"

    /**
     * One format's row of the report. [cells] returns the display strings in [COLUMNS] order, so a
     * `TableLayout` can add a header row from [COLUMNS] and a data row from each snapshot entry's
     * cells without knowing the column layout.
     */
    data class FormatRow(
        val format: String,
        val requested: Int,
        val loaded: Int,
        val failed: Int,
        val shown: Int,
        val impressions: Int,
        val fillPct: String,
        val usePct: String,
        /** Mean load time in ms across timed loads, or -1 if nothing has loaded. */
        val avgLoadMs: Long,
    ) {
        fun cells(): List<String> = listOf(
            format,
            requested.toString(),
            loaded.toString(),
            failed.toString(),
            shown.toString(),
            impressions.toString(),
            fillPct,
            usePct,
            if (avgLoadMs >= 0) "$avgLoadMs" else "—",
        )
    }

    companion object {
        /** Column headers in cell order — shared by [report] and [snapshot]/[FormatRow.cells]. */
        @JvmField
        val COLUMNS = listOf("FORMAT", "REQ", "LOAD", "FAIL", "SHOW", "IMP", "FILL", "USE", "AVG ms")

        /** Per-column alignment: FORMAT left, everything else right. */
        @JvmField
        val LEFT_ALIGNED = booleanArrayOf(true, false, false, false, false, false, false, false, false)
    }
}
