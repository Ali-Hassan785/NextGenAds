package com.alihassan.nextgenads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the central [NextGenAds] coordination logic that every ad helper depends on — the
 * show/request gates, the full-screen exclusivity lock, the request circuit breaker, and request
 * counting/event dispatch. These are deterministic state machines, so no real ad network is needed;
 * failures are injected as [LoadAdError]s.
 *
 * Instrumented (not local unit) because [NextGenAds] builds a real `Handler`/`Looper`. Every test
 * restores the shared singleton to defaults in [setUp]/[tearDown]; none of them call
 * [NextGenAds.initialize], so the one-way `initialized` flag stays untouched for other tests.
 */
@RunWith(AndroidJUnit4::class)
class NextGenAdsCoreTest {

    private var savedMaxFailures = 0
    private var savedCooldownMs = 0L

    @Before
    fun setUp() {
        savedMaxFailures = NextGenAdsConfig.maxRequestFailures
        savedCooldownMs = NextGenAdsConfig.requestCooldownMs
        resetToDefaults()
    }

    @After
    fun tearDown() {
        NextGenAdsConfig.maxRequestFailures = savedMaxFailures
        NextGenAdsConfig.requestCooldownMs = savedCooldownMs
        resetToDefaults()
    }

    private fun resetToDefaults() {
        NextGenAds.enabled = true
        NextGenAds.adsLoadEnabled = true
        NextGenAds.premium = false
        NextGenAds.premiumProvider = { false }
        NextGenAds.consentProvider = null
        AdFormat.values().forEach { NextGenAds.setFormatEnabled(it, true) }
        NextGenAds.resetRequestBreaker()
        NextGenAds.resetRequestCounts()
        NextGenAds.endFullScreenShow() // release the exclusivity gate if a test left it held
    }

    // ---------------------------------------------------------------------------------------------
    // Show / request gates
    // ---------------------------------------------------------------------------------------------

    @Test
    fun defaultsAllowShowingAndRequesting() {
        assertTrue(NextGenAds.canShowAds())
        assertTrue(NextGenAds.canRequest())
        AdFormat.values().forEach {
            assertTrue("$it should be allowed by default", NextGenAds.canShowAds(it))
        }
    }

    @Test
    fun premiumSuppressesAds() {
        NextGenAds.premium = true
        assertFalse(NextGenAds.canShowAds())
        assertFalse(NextGenAds.canRequest())
    }

    @Test
    fun killSwitchSuppressesAds() {
        NextGenAds.enabled = false
        assertFalse(NextGenAds.canShowAds())
        assertFalse(NextGenAds.canRequest())
    }

    @Test
    fun remoteLoadToggleSuppressesAds() {
        NextGenAds.adsLoadEnabled = false
        assertFalse(NextGenAds.canShowAds())
        assertFalse(NextGenAds.canRequest())
    }

    @Test
    fun dynamicPremiumProviderSuppressesAds() {
        NextGenAds.premiumProvider = { true }
        assertFalse(NextGenAds.canShowAds())
        assertFalse(NextGenAds.canRequest())
    }

    @Test
    fun consentGateBlocksRequestsButNotShowState() {
        NextGenAds.consentProvider = { false }
        assertFalse("consent=false must block new requests", NextGenAds.canRequest())
        assertTrue("consent gate must not affect showing a loaded ad", NextGenAds.canShowAds())

        NextGenAds.consentProvider = { true }
        assertTrue(NextGenAds.canRequest())
    }

    @Test
    fun perFormatToggleIsolatesFormats() {
        NextGenAds.interstitialAdsEnabled = false

        assertFalse(NextGenAds.canShowAds(AdFormat.INTERSTITIAL))
        assertFalse(NextGenAds.canRequest(AdFormat.INTERSTITIAL))
        assertFalse(NextGenAds.isFormatEnabled(AdFormat.INTERSTITIAL))

        // Other formats and the format-agnostic gate are unaffected.
        assertTrue(NextGenAds.canShowAds(AdFormat.BANNER))
        assertTrue(NextGenAds.canShowAds(AdFormat.APP_OPEN))
        assertTrue(NextGenAds.canShowAds())

        NextGenAds.interstitialAdsEnabled = true
        assertTrue(NextGenAds.canShowAds(AdFormat.INTERSTITIAL))
    }

    @Test
    fun formatToggleAccessorsRoundTrip() {
        NextGenAds.bannerAdsEnabled = false
        NextGenAds.appOpenAdsEnabled = false
        assertFalse(NextGenAds.bannerAdsEnabled)
        assertFalse(NextGenAds.appOpenAdsEnabled)
        assertTrue(NextGenAds.nativeAdsEnabled) // untouched
        assertFalse(NextGenAds.isFormatEnabled(AdFormat.BANNER))
        assertFalse(NextGenAds.isFormatEnabled(AdFormat.APP_OPEN))
    }

    // ---------------------------------------------------------------------------------------------
    // Full-screen exclusivity gate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fullScreenGateAllowsOnlyOneHolder() {
        assertFalse(NextGenAds.isFullScreenAdShowing())

        assertTrue("first acquire wins", NextGenAds.tryBeginFullScreenShow())
        assertTrue(NextGenAds.isFullScreenAdShowing())
        assertFalse("second acquire is refused while held", NextGenAds.tryBeginFullScreenShow())

        NextGenAds.endFullScreenShow()
        assertFalse(NextGenAds.isFullScreenAdShowing())
        assertTrue("acquire succeeds again after release", NextGenAds.tryBeginFullScreenShow())
        NextGenAds.endFullScreenShow()
    }

    // ---------------------------------------------------------------------------------------------
    // Request circuit breaker
    // ---------------------------------------------------------------------------------------------

    @Test
    fun breakerTripsOnConsecutiveNetworkFailuresThenAutoResumes() {
        NextGenAdsConfig.maxRequestFailures = 3
        NextGenAdsConfig.requestCooldownMs = 400L
        NextGenAds.resetRequestBreaker()

        repeat(2) { NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NETWORK_ERROR)) }
        assertFalse("must not trip before the threshold", NextGenAds.isRequestPaused())

        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NETWORK_ERROR))
        assertTrue("must trip at the threshold", NextGenAds.isRequestPaused())
        assertFalse("a paused breaker blocks new requests", NextGenAds.canRequest())
        assertTrue(NextGenAds.requestCooldownRemainingMs() > 0L)

        Thread.sleep(500)
        assertFalse("must auto-resume after the cooldown", NextGenAds.isRequestPaused())
        assertTrue(NextGenAds.canRequest())
        assertEquals(0L, NextGenAds.requestCooldownRemainingMs())
    }

    @Test
    fun noFillNeverTripsBreaker() {
        NextGenAdsConfig.maxRequestFailures = 3
        NextGenAds.resetRequestBreaker()
        repeat(6) { NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NO_FILL)) }
        assertFalse("NO_FILL is demand, not connectivity — must never pause", NextGenAds.isRequestPaused())
    }

    @Test
    fun timeoutFailuresTripBreaker() {
        NextGenAdsConfig.maxRequestFailures = 2
        NextGenAdsConfig.requestCooldownMs = 60_000L
        NextGenAds.resetRequestBreaker()
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.TIMEOUT))
        assertFalse(NextGenAds.isRequestPaused())
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.TIMEOUT))
        assertTrue(NextGenAds.isRequestPaused())
    }

    @Test
    fun successResetsFailureStreak() {
        NextGenAdsConfig.maxRequestFailures = 3
        NextGenAds.resetRequestBreaker()
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NETWORK_ERROR))
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.TIMEOUT))
        NextGenAds.recordRequestSuccess() // clears the streak
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NETWORK_ERROR))
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.TIMEOUT))
        assertFalse("only 2 failures since the reset — below the threshold of 3", NextGenAds.isRequestPaused())
    }

    @Test
    fun resetRequestBreakerClearsCooldown() {
        NextGenAdsConfig.maxRequestFailures = 1
        NextGenAdsConfig.requestCooldownMs = 60_000L
        NextGenAds.resetRequestBreaker()
        NextGenAds.recordRequestFailure(loadError(LoadAdError.ErrorCode.NETWORK_ERROR))
        assertTrue(NextGenAds.isRequestPaused())
        NextGenAds.resetRequestBreaker()
        assertFalse(NextGenAds.isRequestPaused())
    }

    // ---------------------------------------------------------------------------------------------
    // Request counting + event dispatch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun countRequestIncrementsAndDispatchesOncePerCall() {
        val unit = "core-test-unit"
        val latch = CountDownLatch(2)
        val requested = CopyOnWriteArrayList<String>()
        val listener = object : AdEventListener {
            override fun onAdRequested(format: AdFormat, adUnitId: String) {
                requested.add("$format:$adUnitId")
                latch.countDown()
            }
        }
        NextGenAds.registerEventListener(listener)
        try {
            assertEquals(0, NextGenAds.requestCount(unit))
            assertEquals(1, NextGenAds.countRequest(AdFormat.NATIVE, unit))
            assertEquals(2, NextGenAds.countRequest(AdFormat.NATIVE, unit))
            assertEquals(2, NextGenAds.requestCount(unit))

            assertTrue(
                "onAdRequested must dispatch once per countRequest",
                latch.await(3, TimeUnit.SECONDS),
            )
            assertEquals(listOf("NATIVE:$unit", "NATIVE:$unit"), requested)
        } finally {
            NextGenAds.unregisterEventListener(listener)
        }
    }

    @Test
    fun resetRequestCountsClearsCounters() {
        val unit = "core-test-reset-unit"
        NextGenAds.countRequest(AdFormat.BANNER, unit)
        assertEquals(1, NextGenAds.requestCount(unit))
        NextGenAds.resetRequestCounts()
        assertEquals(0, NextGenAds.requestCount(unit))
    }

    // ---------------------------------------------------------------------------------------------
    // Per-format hide (registered inline ad slots)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun clearFormatHidesOnlyMatchingFormatSlots() {
        val nativeSlot = RecordingSlot(AdFormat.NATIVE)
        val bannerSlot = RecordingSlot(AdFormat.BANNER)
        val unknownSlot = RecordingSlot(null)
        NextGenAds.registerAdSlot(nativeSlot)
        NextGenAds.registerAdSlot(bannerSlot)
        NextGenAds.registerAdSlot(unknownSlot)
        try {
            // Turning the NATIVE format off must hide only NATIVE-reporting slots.
            NextGenAds.setFormatEnabled(AdFormat.NATIVE, false)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync() // clearFormat runs on main

            assertEquals("NATIVE slot must be hidden by the NATIVE toggle", 1, nativeSlot.disabledCount)
            assertEquals("BANNER slot must NOT be hidden by the NATIVE toggle", 0, bannerSlot.disabledCount)
            assertEquals("a null-format slot is only hidden by a full purge", 0, unknownSlot.disabledCount)
        } finally {
            NextGenAds.unregisterAdSlot(nativeSlot)
            NextGenAds.unregisterAdSlot(bannerSlot)
            NextGenAds.unregisterAdSlot(unknownSlot)
        }
    }

    @Test
    fun fullPurgeHidesEverySlotIncludingUnknownFormat() {
        val nativeSlot = RecordingSlot(AdFormat.NATIVE)
        val unknownSlot = RecordingSlot(null)
        NextGenAds.registerAdSlot(nativeSlot)
        NextGenAds.registerAdSlot(unknownSlot)
        try {
            NextGenAds.clearAllAds()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, nativeSlot.disabledCount)
            assertEquals("full purge hides even null-format slots", 1, unknownSlot.disabledCount)
        } finally {
            NextGenAds.unregisterAdSlot(nativeSlot)
            NextGenAds.unregisterAdSlot(unknownSlot)
        }
    }

    /** A fake inline ad slot that records how many times it was asked to hide. */
    private class RecordingSlot(private val format: AdFormat?) : NextGenAds.PremiumAware {
        @Volatile
        var disabledCount = 0
        override fun onAdsDisabled() {
            disabledCount++
        }

        override val slotAdFormat: AdFormat?
            get() = format
    }

    private fun loadError(code: LoadAdError.ErrorCode): LoadAdError =
        LoadAdError(code, "test-$code", null)
}
