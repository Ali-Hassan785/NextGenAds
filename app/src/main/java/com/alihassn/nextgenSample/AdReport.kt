package com.alihassn.nextgenSample

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.lang.ref.WeakReference

/**
 * Shake-to-open ad diagnostics, tallied **per ad unit** — not per format.
 *
 * That distinction is the whole point: the library's own [com.alihassan.nextgenads.events.ShowRateTracker]
 * (which drives [MainActivity]'s bottom stats table) groups by [AdFormat], so if you run three
 * different interstitial units they all collapse into one `INTERSTITIAL` row and a single unit with
 * no fill is invisible. This report gives every unit its own row, its own fill% / use% and its own
 * last error, so you can see *which* unit is underperforming.
 *
 * Needs **no manifest permission**: the accelerometer is permission-free. Install once from
 * [AdsBootstrap.configure]; shake the device three times on any screen to open the report, and
 * long-press the report to copy it.
 *
 * Counters are fed from [NextGenAds]'s event stream, which the library delivers on the **main
 * thread** — so the map below needs no locking.
 */
object AdReport : AdEventListener {

    /** Live per-unit counters, keyed by ad unit id, in first-seen order. */
    private val stats = LinkedHashMap<String, UnitStats>()

    private var dialog: AlertDialog? = null

    /** The activity [dialog] is attached to, so it can be dismissed before that activity dies. */
    private var dialogOwner: WeakReference<Activity> = WeakReference(null)
    private var installed = false

    /**
     * Registers the per-unit tracker and starts watching for shakes.
     *
     * @param enabled pass `BuildConfig.DEBUG` — when `false` nothing is registered at all, so there
     *   is no listener, no sensor and no cost in release. A shake gesture in a shipped app would
     *   eventually be found by real users.
     * @param units label → ad unit id. Pre-seeded so a unit that is *never requested* still appears
     *   (as `REQ 0`) instead of silently missing — usually the fastest way to spot a screen that
     *   isn't wired up. Units seen in events but absent here are added automatically.
     */
    fun install(
        app: Application,
        enabled: Boolean = true,
        units: Map<String, String> = emptyMap(),
    ) {
        if (installed || !enabled) return
        installed = true
        units.forEach { (label, id) ->
            // Two labels on one id (e.g. a splash and an in-app placement still sharing a unit)
            // can only ever be one row — the SDK reports per id. Name it after both rather than
            // letting the last one win, so the shared row is never read as one placement's numbers.
            val existing = stats[id]?.label
            stats[id] = UnitStats(if (existing == null) label else "$existing+$label")
        }
        NextGenAds.registerEventListener(this)
        ShakeWatcher.attach(app)
    }

    /** Opens the report. Public so any other trigger can raise it (debug menu, 7-tap, …). */
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true) return // a shake mid-dialog must not stack a second one

        // Build from the builder's themed context so the report follows the app's Light/Dark choice
        // (ThemeOverlay.NextGen.Dialog) instead of hard-coding colours.
        val builder = MaterialAlertDialogBuilder(activity)
        val ctx = builder.context
        val pad = (16 * activity.resources.displayMetrics.density).toInt()

        val text = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE // the box-drawing table only aligns in monospace
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE),
            )
            this.text = buildReport()
            setOnLongClickListener {
                val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Ad report", this.text))
                Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // The per-unit table is wide: scroll both ways rather than wrapping, which would destroy
        // the column alignment.
        val scroll = ScrollView(ctx).apply {
            addView(HorizontalScrollView(ctx).apply { addView(text) })
        }

        dialog = builder
            .setTitle("Ad report · per unit")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Ad Inspector") { _, _ ->
                // Live per-unit mediation / request debugging. Registered test devices only.
                NextGenAds.openAdInspector { error ->
                    if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Reset") { _, _ ->
                stats.values.forEach { it.reset() }
                NextGenAds.resetRequestCounts()
                Toast.makeText(activity, "Counters reset", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { dialog = null }
            .show()
        dialogOwner = WeakReference(activity)
    }

    /**
     * Dismisses the report if it is attached to [activity], which is about to die. Without this, a
     * rotation with the report open would leak its window.
     */
    private fun dismissFor(activity: Activity) {
        if (dialogOwner.get() !== activity) return
        runCatching { dialog?.dismiss() }
        dialog = null
        dialogOwner = WeakReference(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Event stream → per-unit counters
    // ---------------------------------------------------------------------------------------------

    private fun unit(format: AdFormat, adUnitId: String): UnitStats =
        stats.getOrPut(adUnitId) { UnitStats(adUnitId.substringAfterLast('/')) }
            .also { it.format = format } // resolved from the first event for a pre-seeded unit

    override fun onAdRequested(format: AdFormat, adUnitId: String) = unit(format, adUnitId).run {
        requested++
        lastRequestElapsed = SystemClock.elapsedRealtime()
    }

    override fun onAdLoaded(format: AdFormat, adUnitId: String) = unit(format, adUnitId).run {
        loaded++
        if (lastRequestElapsed > 0L) {
            totalLoadMs += SystemClock.elapsedRealtime() - lastRequestElapsed
            timedLoads++
            lastRequestElapsed = 0L
        }
    }

    override fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) =
        unit(format, adUnitId).run {
            failed++
            lastError = error.code.toString() // e.g. NO_FILL — the "why" behind a low fill%
        }

    override fun onAdShown(format: AdFormat, adUnitId: String) {
        unit(format, adUnitId).shown++
    }

    override fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) {
        unit(format, adUnitId).lastError = "show: ${error.code}"
    }

    override fun onAdImpression(format: AdFormat, adUnitId: String) {
        unit(format, adUnitId).impressions++
    }

    override fun onAdClicked(format: AdFormat, adUnitId: String) {
        unit(format, adUnitId).clicked++
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    private fun buildReport(): String = buildString {
        if (stats.isEmpty()) {
            append("(no ad units tracked yet)")
        } else {
            append(renderTable(stats.values.map { it.cells() } + listOf(totalRow())))
            append("\nfill% = loaded/requested · use% = impressions/loaded · avg = mean load ms")
        }

        append("\n\nSDK ").append(if (NextGenAds.isInitialized()) "ready" else "not initialized")
        append(" · ads ").append(if (NextGenAds.canShowAds()) "on" else "off")
        if (NextGenAds.premium) append(" · premium")

        val cooldown = NextGenAds.requestCooldownRemainingMs()
        append("\nRequest breaker: ")
        append(if (cooldown > 0) "PAUSED ${cooldown / 1000}s" else "open")

        // Per-unit failure reasons, listed only for the units that actually have one.
        val failures = stats.values.filter { it.lastError != null }
        if (failures.isNotEmpty()) {
            append("\n\nLast error per unit")
            failures.forEach { append("\n  ").append(it.label).append(": ").append(it.lastError) }
        }

        // A NOT_READY adapter silently forfeits that network's fill — a usual suspect behind low fill%.
        NextGenAds.initializationStatus?.adapterStatusMap?.takeIf { it.isNotEmpty() }?.let { map ->
            append("\n\nMediation adapters")
            map.forEach { (name, status) ->
                append("\n  ").append(name.substringAfterLast('.'))
                append(": ").append(status.initializationState)
            }
        }

        // The full ids, so a report pasted into a bug is unambiguous about which unit is which.
        append("\n\nUnit ids")
        stats.forEach { (id, s) -> append("\n  ").append(s.label).append(" = ").append(id) }
    }

    /** Aggregate across every unit — the "general" numbers, kept as one row under the detail. */
    private fun totalRow(): List<String> {
        val requested = stats.values.sumOf { it.requested }
        val loaded = stats.values.sumOf { it.loaded }
        val failed = stats.values.sumOf { it.failed }
        val shown = stats.values.sumOf { it.shown }
        val impressions = stats.values.sumOf { it.impressions }
        val timed = stats.values.sumOf { it.timedLoads }
        val totalMs = stats.values.sumOf { it.totalLoadMs }
        return listOf(
            "TOTAL", "", "$requested", "$loaded", "$failed", "$shown", "$impressions",
            pct(loaded, requested), pct(impressions, loaded),
            if (timed > 0) "${totalMs / timed}" else "—",
        )
    }

    /** Box-drawing table over [COLUMNS] that stays aligned in any monospace view. */
    private fun renderTable(rows: List<List<String>>): String {
        // Column width = widest cell (header or any row), plus a space of padding each side.
        val widths = IntArray(COLUMNS.size) { col ->
            (rows.map { it[col].length } + COLUMNS[col].length).max() + 2
        }

        fun rule(left: String, mid: String, right: String): String =
            widths.joinToString(mid, prefix = left, postfix = right) { "─".repeat(it) }

        fun row(cells: List<String>): String =
            cells.mapIndexed { i, cell ->
                val padding = widths[i] - cell.length - 1
                if (LEFT_ALIGNED[i]) " $cell${" ".repeat(padding)}" else "${" ".repeat(padding)}$cell "
            }.joinToString("│", prefix = "│", postfix = "│")

        return buildString {
            append(rule("┌", "┬", "┐")).append('\n')
            append(row(COLUMNS)).append('\n')
            append(rule("├", "┼", "┤")).append('\n')
            rows.forEach { append(row(it)).append('\n') }
            append(rule("└", "┴", "┘"))
        }
    }

    private fun pct(part: Int, whole: Int): String =
        if (whole <= 0) "—" else "${(part * 100) / whole}%"

    /** Column headers, in cell order. */
    private val COLUMNS =
        listOf("UNIT", "FORMAT", "REQ", "LOAD", "FAIL", "SHOW", "IMP", "FILL", "USE", "AVG")

    /** UNIT and FORMAT read left, every count reads right. */
    private val LEFT_ALIGNED =
        booleanArrayOf(true, true, false, false, false, false, false, false, false, false)

    /** One ad unit's live counters. */
    private class UnitStats(val label: String) {
        var format: AdFormat? = null
        var requested = 0
        var loaded = 0
        var failed = 0
        var shown = 0
        var impressions = 0
        var clicked = 0
        var lastError: String? = null

        /** Set at request, cleared at load — so load time is measured per attempt. */
        var lastRequestElapsed = 0L
        var totalLoadMs = 0L
        var timedLoads = 0

        fun reset() {
            requested = 0; loaded = 0; failed = 0; shown = 0; impressions = 0; clicked = 0
            lastError = null; lastRequestElapsed = 0L; totalLoadMs = 0L; timedLoads = 0
        }

        fun cells(): List<String> = listOf(
            label,
            format?.name ?: "—", // still "—" for a pre-seeded unit that was never requested
            "$requested", "$loaded", "$failed", "$shown", "$impressions",
            pct(loaded, requested),
            pct(impressions, loaded),
            if (timedLoads > 0) "${totalLoadMs / timedLoads}" else "—",
        )
    }

    /**
     * Process-wide shake detector. The accelerometer is sampled **only while an activity is
     * resumed** — backgrounding unregisters it, so it costs nothing when the app isn't in front.
     */
    private object ShakeWatcher : Application.ActivityLifecycleCallbacks, SensorEventListener {

        // A real shake is a sustained back-and-forth, not one jolt: REQUIRED_SHAKES peaks, each at
        // least SLOP_MS apart, within RESET_MS of each other. A pocket bump can't reach that.
        private const val THRESHOLD_G_SQ = 7.29f // (2.7 g)², squared to skip a sqrt on every sample
        private const val SLOP_MS = 500L
        private const val RESET_MS = 3_000L
        private const val COOLDOWN_MS = 2_000L
        private const val MIN_SAMPLE_GAP_MS = 40L
        private const val REQUIRED_SHAKES = 3

        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private var current: WeakReference<Activity> = WeakReference(null)

        private var lastSampleMs = 0L
        private var lastShakeMs = 0L
        private var lastTriggerMs = 0L
        private var shakeCount = 0

        fun attach(app: Application) {
            val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            // Resolved once. A device with no accelerometer (rare, but some emulators) simply gets no
            // shake trigger — AdReport.show(activity) still works from any trigger you wire yourself.
            accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensorManager = sm
            app.registerActivityLifecycleCallbacks(this)
        }

        override fun onActivityResumed(activity: Activity) {
            current = WeakReference(activity) // weak: this object outlives every activity
            val sm = sensorManager ?: return
            val sensor = accelerometer ?: return
            shakeCount = 0
            // SENSOR_DELAY_UI (~60ms) catches a shake easily and is far cheaper than GAME / FASTEST.
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        override fun onActivityPaused(activity: Activity) {
            sensorManager?.unregisterListener(this) // stop sampling the moment we leave the foreground
            if (current.get() === activity) current = WeakReference(null)
        }

        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            // registerListener's rate is only a hint; a sensor may deliver faster. Cheap guard.
            if (now - lastSampleMs < MIN_SAMPLE_GAP_MS) return
            lastSampleMs = now

            // Nothing is allocated on this path — it runs many times a second.
            val g = SensorManager.GRAVITY_EARTH
            val x = event.values[0] / g
            val y = event.values[1] / g
            val z = event.values[2] / g
            if (x * x + y * y + z * z < THRESHOLD_G_SQ) return

            if (now - lastShakeMs < SLOP_MS) return // same peak, not a new shake
            if (now - lastShakeMs > RESET_MS) shakeCount = 0 // gave up halfway: start over
            lastShakeMs = now
            if (++shakeCount < REQUIRED_SHAKES) return

            shakeCount = 0
            if (now - lastTriggerMs < COOLDOWN_MS) return
            lastTriggerMs = now
            current.get()?.let { AdReport.show(it) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) = AdReport.dismissFor(activity)
    }
}
