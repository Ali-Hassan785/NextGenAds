package com.alihassan.nextgenads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the dev-tooling additions on [NextGenAds]: surfaced mediation [NextGenAds.initializationStatus],
 * and the video-audio [NextGenAds.setAppVolume] / [NextGenAds.setAppMuted] passthroughs. (The Ad
 * Inspector opens on-device UI and needs a registered test device, so it's verified manually, not here.)
 */
@RunWith(AndroidJUnit4::class)
class NextGenAdsDevToolingTest {

    @Test
    fun initializationStatusIsPopulatedAfterInit() {
        ensureInitialized()
        val status = NextGenAds.initializationStatus
        assertNotNull("initializationStatus must be non-null after init", status)
        // The adapter map must be readable (it may be empty when no mediation adapters are present).
        assertNotNull("adapterStatusMap must be accessible", status!!.adapterStatusMap)
    }

    @Test
    fun appVolumeAndMuteAreSafeBeforeAndAfterInit() {
        // Before init these just queue behind whenInitialized — must not throw.
        NextGenAds.setAppVolume(0.3f)
        NextGenAds.setAppMuted(true)

        ensureInitialized()

        // After init, including out-of-range values that are clamped internally — must not throw.
        NextGenAds.setAppVolume(0f)
        NextGenAds.setAppVolume(1f)
        NextGenAds.setAppVolume(2f) // clamped to 1
        NextGenAds.setAppVolume(-5f) // clamped to 0
        NextGenAds.setAppMuted(false)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Reaching here without an exception is the assertion.
    }

    private fun ensureInitialized() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            NextGenAds.initialize(instrumentation.targetContext, APP_ID) { latch.countDown() }
        }
        assertTrue("SDK did not initialize in time", latch.await(30, TimeUnit.SECONDS))
    }

    private companion object {
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
    }
}
