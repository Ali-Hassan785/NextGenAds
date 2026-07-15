package com.alihassan.nextgenads

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.banner.BannerAdHelper
import com.alihassan.nextgenads.events.AdFormat
import com.alihassan.nextgenads.events.ShowRateTracker
import com.alihassan.nextgenads.interstitial.Interstitials
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.rewarded.RewardedAds
import com.alihassan.nextgenads.rewardedinterstitial.RewardedInterstitials
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end **fill-rate** check across every ad format. After initializing the SDK it fires one
 * real load per format against Google's always-filling test units, then asserts that
 * [ShowRateTracker] reports **fill ≥ 90%** (loaded ÷ requested) for each — proving every format's
 * request pipeline actually loads an ad. Retries are set to 0 so fill is a clean single-request
 * measurement (a filled request reads 100%, a genuine no-fill reads 0%).
 *
 * This is an integration test: it needs a device/emulator with network. "Fill" is the headlessly
 * measurable half of show-rate; "use" (impressions ÷ loaded) additionally needs the ad to be
 * *presented*, which is driven by the host app's show triggers, not something a test can force.
 */
@RunWith(AndroidJUnit4::class)
class AdFillRateIntegrationTest {

    private val tracker = ShowRateTracker(logEachEvent = false)

    // Google's official test units (always fill with test ads) — one per format.
    private companion object {
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"
        const val APP_OPEN_UNIT = "ca-app-pub-3940256099942544/9257395921"
        const val REWARDED_UNIT = "ca-app-pub-3940256099942544/5224354917"
        const val REWARDED_INT_UNIT = "ca-app-pub-3940256099942544/5354046379"
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
        const val BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"

        const val LOAD_TIMEOUT_MS = 45_000L
        const val INIT_TIMEOUT_MS = 30_000L
        const val MIN_FILL_PCT = 90
    }

    private var savedNativeRetries = 0
    private var savedBannerRetries = 0

    @Before
    fun setUp() {
        NextGenAds.enabled = true
        NextGenAds.adsLoadEnabled = true
        NextGenAds.premium = false
        NextGenAds.premiumProvider = { false }
        NextGenAds.consentProvider = null
        AdFormat.values().forEach { NextGenAds.setFormatEnabled(it, true) }
        NextGenAds.resetRequestBreaker()
        NextGenAds.resetRequestCounts()

        // Zero retries so requested == 1 per format and fill% is unambiguous.
        Interstitials.get(INTERSTITIAL_UNIT).maxRetries = 0
        AppOpenAds.get(APP_OPEN_UNIT).maxRetries = 0
        RewardedAds.get(REWARDED_UNIT).maxRetries = 0
        RewardedInterstitials.get(REWARDED_INT_UNIT).maxRetries = 0
        savedNativeRetries = NativeAdHelper.maxRetries
        savedBannerRetries = BannerAdHelper.maxRetries
        NativeAdHelper.maxRetries = 0
        BannerAdHelper.maxRetries = 0

        NextGenAds.registerEventListener(tracker)
    }

    @After
    fun tearDown() {
        NextGenAds.unregisterEventListener(tracker)
        NativeAdHelper.maxRetries = savedNativeRetries
        BannerAdHelper.maxRetries = savedBannerRetries
    }

    @Test
    fun everyAdFormatFillsAtLeast90Percent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Initialize (idempotent) and wait for completion.
        val initDone = CountDownLatch(1)
        instrumentation.runOnMainSync {
            NextGenAds.initialize(instrumentation.targetContext, APP_ID) { initDone.countDown() }
        }
        assertTrue(
            "SDK did not initialize within ${INIT_TIMEOUT_MS}ms",
            initDone.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )

        val scenario = ActivityScenario.launch(AdFillTestActivity::class.java)
        try {
            // Fire exactly one load per format. Banner needs a real Activity; the rest don't.
            scenario.onActivity { activity ->
                Interstitials.get(INTERSTITIAL_UNIT).load()
                AppOpenAds.get(APP_OPEN_UNIT).load()
                RewardedAds.get(REWARDED_UNIT).load()
                RewardedInterstitials.get(REWARDED_INT_UNIT).load()
                NativeAdHelper.load(NATIVE_UNIT, onLoaded = {}, onFailed = {})
                BannerAdHelper.preload(activity, BANNER_UNIT, count = 1)
            }

            val formats = listOf(
                AdFormat.INTERSTITIAL,
                AdFormat.APP_OPEN,
                AdFormat.REWARDED,
                AdFormat.REWARDED_INTERSTITIAL,
                AdFormat.NATIVE,
                AdFormat.BANNER,
            )

            // Poll until every format has loaded at least one ad, or time out.
            val deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val rows = readRowsOnMain()
                if (formats.all { (rows[it.name]?.loaded ?: 0) >= 1 }) break
                Thread.sleep(200)
            }

            val rows = readRowsOnMain()
            val report = formats.joinToString("\n") { f ->
                val r = rows[f.name]
                "  $f req=${r?.requested ?: 0} load=${r?.loaded ?: 0} fill=${r?.fillPct ?: "—"}"
            }
            android.util.Log.i("AdFillRate", "Fill-rate per format:\n$report")

            formats.forEach { f ->
                val r = rows[f.name] ?: error("no load events recorded for $f\n$report")
                assertTrue("$f loaded no ad within ${LOAD_TIMEOUT_MS}ms\n$report", r.loaded >= 1)
                val fillPct = r.loaded * 100 / r.requested
                assertTrue(
                    "$f fill $fillPct% is below the required $MIN_FILL_PCT%\n$report",
                    fillPct >= MIN_FILL_PCT,
                )
            }
        } finally {
            scenario.close()
        }
    }

    /**
     * Reads the tracker snapshot on the main thread — the tracker's counters are written on the main
     * thread by the event stream, so reading there avoids a cross-thread race on its (non-synchronized)
     * map.
     */
    private fun readRowsOnMain(): Map<String, ShowRateTracker.FormatRow> {
        var rows: Map<String, ShowRateTracker.FormatRow> = emptyMap()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rows = tracker.snapshot().associateBy { it.format }
        }
        return rows
    }
}
