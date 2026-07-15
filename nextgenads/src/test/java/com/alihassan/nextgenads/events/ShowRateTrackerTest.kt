package com.alihassan.nextgenads.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM unit tests for [ShowRateTracker]'s counting and percentage logic. The tracker touches
 * `SystemClock`/`Log`, so these rely on `testOptions.unitTests.isReturnDefaultValues = true` (set in
 * this module's build.gradle) to stub those out. `logEachEvent = false` keeps the tracker off `Log`
 * entirely except for the (unexercised) report dump.
 *
 * fill% = loaded / requested · use% = impressions / loaded.
 */
class ShowRateTrackerTest {

    private fun tracker() = ShowRateTracker(logEachEvent = false)

    @Test
    fun fillAndUsePercentagesFromEvents() {
        val t = tracker()
        val u = "unit"
        repeat(5) { t.onAdRequested(AdFormat.INTERSTITIAL, u) }
        repeat(4) { t.onAdLoaded(AdFormat.INTERSTITIAL, u) }
        repeat(3) { t.onAdImpression(AdFormat.INTERSTITIAL, u) }
        t.onAdShown(AdFormat.INTERSTITIAL, u)

        val row = t.snapshot().single()
        assertEquals("INTERSTITIAL", row.format)
        assertEquals(5, row.requested)
        assertEquals(4, row.loaded)
        assertEquals(3, row.impressions)
        assertEquals(1, row.shown)
        assertEquals("80%", row.fillPct) // 4 / 5
        assertEquals("75%", row.usePct) // 3 / 4
    }

    @Test
    fun percentagesAreIntegerTruncated() {
        val t = tracker()
        repeat(3) { t.onAdRequested(AdFormat.NATIVE, "u") }
        t.onAdLoaded(AdFormat.NATIVE, "u") // 1 / 3 = 33.3% -> truncated to 33%
        assertTrue(t.summaryFor(AdFormat.NATIVE).contains("fill=33%"))
    }

    @Test
    fun zeroDenominatorRendersDash() {
        val t = tracker()
        val summary = t.summaryFor(AdFormat.REWARDED) // no requests / loads yet
        assertTrue("fill% with 0 requests must be a dash", summary.contains("fill=—"))
        assertTrue("use% with 0 loads must be a dash", summary.contains("use=—"))
    }

    @Test
    fun formatsAreCountedIndependently() {
        val t = tracker()
        t.onAdRequested(AdFormat.BANNER, "b")
        t.onAdRequested(AdFormat.NATIVE, "n")
        t.onAdLoaded(AdFormat.NATIVE, "n")

        val rows = t.snapshot().associateBy { it.format }
        assertEquals(1, rows.getValue("BANNER").requested)
        assertEquals(0, rows.getValue("BANNER").loaded)
        assertEquals(1, rows.getValue("NATIVE").requested)
        assertEquals(1, rows.getValue("NATIVE").loaded)
    }

    @Test
    fun avgLoadMsIsDashUntilATimedLoad() {
        val t = tracker()
        // Under isReturnDefaultValues, SystemClock.elapsedRealtime() == 0, so the timing branch
        // (guarded by lastRequestElapsed > 0) never fires and avg stays "not yet loaded" (-1).
        t.onAdRequested(AdFormat.APP_OPEN, "u")
        t.onAdLoaded(AdFormat.APP_OPEN, "u")
        assertEquals(-1L, t.snapshot().single().avgLoadMs)
        assertTrue(t.summaryFor(AdFormat.APP_OPEN).contains("avg=—"))
    }

    @Test
    fun resetClearsAllCounters() {
        val t = tracker()
        t.onAdRequested(AdFormat.BANNER, "b")
        assertFalse(t.snapshot().isEmpty())
        t.reset()
        assertTrue(t.snapshot().isEmpty())
        assertTrue(t.report().contains("no ad events yet"))
    }

    @Test
    fun reportRendersHeaderAndDataRows() {
        val t = tracker()
        t.onAdRequested(AdFormat.NATIVE, "n")
        t.onAdLoaded(AdFormat.NATIVE, "n")
        val r = t.report()
        assertTrue(r.contains("FORMAT"))
        assertTrue(r.contains("NATIVE"))
        assertTrue(r.contains("fill%"))
    }
}
