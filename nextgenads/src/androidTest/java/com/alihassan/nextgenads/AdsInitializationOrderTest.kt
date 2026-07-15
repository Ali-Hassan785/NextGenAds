package com.alihassan.nextgenads

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.interstitial.Interstitials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies that a full-screen ad request is **deferred until [NextGenAds.initialize] completes** —
 * i.e. the ad is only "initialized" (requested against the SDK) *after* the SDK itself is
 * initialized. Both [com.alihassan.nextgenads.appopen.AppOpenAdHelper] and
 * [com.alihassan.nextgenads.interstitial.InterstitialAdHelper] route their request through
 * [NextGenAds.whenInitialized], so a `load()` issued during app start (before init) must be queued
 * rather than fired at an uninitialized SDK, then replayed the moment init finishes.
 *
 * The observable signal is [NextGenAds.requestCount]: [NextGenAds.countRequest] runs at the very
 * start of each helper's `requestAd()`, so the count is `0` while the request is still queued and
 * becomes `>= 1` once it actually fires.
 *
 * This is an instrumented test (not a local unit test) because [NextGenAds] uses a real
 * `Handler`/`Looper` and drives the real Google Mobile Ads SDK — both of which require a device.
 *
 * Note: [NextGenAds.initialized] is process-wide and one-way (false → true), so the whole
 * before/after sequence for both formats lives in a single test; it can't be split without a later
 * method seeing an already-initialized SDK.
 */
@RunWith(AndroidJUnit4::class)
class AdsInitializationOrderTest {

    @Before
    fun setUp() {
        // Reset every gate to its default so `canRequest()` passes and nothing from a prior test
        // suppresses the request we're about to observe.
        NextGenAds.enabled = true
        NextGenAds.adsLoadEnabled = true
        NextGenAds.premium = false
        NextGenAds.premiumProvider = { false }
        NextGenAds.consentProvider = null
        NextGenAds.resetRequestBreaker()
        NextGenAds.resetRequestCounts()
        // Drop any ad / in-flight load left parked on these shared helpers by an earlier test, so
        // load() below starts a genuinely fresh request path (a parked load would never re-queue).
        AppOpenAds.get(APP_OPEN_UNIT).clear()
        Interstitials.get(INTERSTITIAL_UNIT).clear()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun appOpenAndInterstitialAreRequestedOnlyAfterSdkInitialized() {
        // The pre-init premise only holds while the SDK hasn't been initialized yet. If a prior test
        // already initialized it (init is one-way), skip rather than report a false failure.
        assumeFalse(
            "SDK already initialized — cannot verify pre-init deferral",
            NextGenAds.isInitialized(),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // --- Before init: issue both loads. Each should be QUEUED, not fired. ---
        AppOpenAds.get(APP_OPEN_UNIT).load()
        Interstitials.get(INTERSTITIAL_UNIT).load()
        // Let the load()'s runOnMain bodies execute (they add the request to whenInitialized's queue).
        instrumentation.waitForIdleSync()

        assertEquals(
            "App-open ad must NOT be requested before NextGenAds.initialize()",
            0,
            NextGenAds.requestCount(APP_OPEN_UNIT),
        )
        assertEquals(
            "Interstitial ad must NOT be requested before NextGenAds.initialize()",
            0,
            NextGenAds.requestCount(INTERSTITIAL_UNIT),
        )

        // --- Initialize the SDK and wait for completion. ---
        val initDone = CountDownLatch(1)
        NextGenAds.initialize(context, APP_ID) { initDone.countDown() }
        assertTrue(
            "NextGenAds.initialize did not complete within ${INIT_TIMEOUT_MS}ms",
            initDone.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("NextGenAds should report initialized after init", NextGenAds.isInitialized())

        // --- After init: the queued requests must now have fired for both formats. ---
        awaitRequestFired(APP_OPEN_UNIT)
        awaitRequestFired(INTERSTITIAL_UNIT)

        assertTrue(
            "App-open ad must be requested after NextGenAds.initialize() completes",
            NextGenAds.requestCount(APP_OPEN_UNIT) >= 1,
        )
        assertTrue(
            "Interstitial ad must be requested after NextGenAds.initialize() completes",
            NextGenAds.requestCount(INTERSTITIAL_UNIT) >= 1,
        )
    }

    /** Polls until [unit] has fired at least one request, or fails after [REQUEST_TIMEOUT_MS]. */
    private fun awaitRequestFired(unit: String) {
        val deadline = SystemClock.uptimeMillis() + REQUEST_TIMEOUT_MS
        while (NextGenAds.requestCount(unit) < 1) {
            if (SystemClock.uptimeMillis() > deadline) {
                fail("Queued request for $unit never fired after init (requestCount still 0)")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        // Google's official test IDs — always fill with test ads; safe to ship in a test.
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val APP_OPEN_UNIT = "ca-app-pub-3940256099942544/9257395921"
        const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"

        const val INIT_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
