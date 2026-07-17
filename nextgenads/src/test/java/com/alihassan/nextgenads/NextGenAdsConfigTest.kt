package com.alihassan.nextgenads

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM unit tests for the [ConfigDefault] delegate that backs every per-helper property whose
 * default lives in [NextGenAdsConfig] — the "follow the config until the host pins it" contract the
 * real helpers rely on. The helpers themselves touch `Handler`/`Looper`, so the delegate is
 * exercised through a stand-in holder rather than a real [com.alihassan.nextgenads.interstitial.InterstitialAdHelper].
 */
class NextGenAdsConfigTest {

    /** Mirrors how a helper declares a config-defaulted property. */
    private class Holder {
        val retriesDefault = ConfigDefault { NextGenAdsConfig.maxRetries }
        var maxRetries: Int by retriesDefault
        var minIntervalMs: Long by ConfigDefault { NextGenAdsConfig.minIntervalMs }
    }

    @After
    fun restoreDefaults() {
        NextGenAdsConfig.maxRetries = 3
        NextGenAdsConfig.minIntervalMs = 0L
    }

    @Test
    fun unsetPropertyReadsTheConfigDefault() {
        NextGenAdsConfig.maxRetries = 7
        NextGenAdsConfig.minIntervalMs = 60_000L

        val holder = Holder()

        assertEquals(7, holder.maxRetries)
        assertEquals(60_000L, holder.minIntervalMs)
    }

    @Test
    fun configChangeAfterConstructionIsStillPickedUp() {
        // The whole point of the delegate over a `var x = NextGenAdsConfig.x` initializer: a helper
        // constructed before the host finishes tuning must not snapshot the pre-tuning value.
        val holder = Holder()
        assertEquals(3, holder.maxRetries)

        NextGenAdsConfig.maxRetries = 5

        assertEquals(5, holder.maxRetries)
    }

    @Test
    fun assigningPropertyPinsItAgainstLaterConfigChanges() {
        val holder = Holder()
        holder.maxRetries = 1

        NextGenAdsConfig.maxRetries = 9

        assertEquals("an explicit per-unit value must win over the config", 1, holder.maxRetries)
    }

    @Test
    fun oneInstancePinningDoesNotAffectAnother() {
        val pinned = Holder()
        val following = Holder()
        pinned.maxRetries = 1

        NextGenAdsConfig.maxRetries = 9

        assertEquals(1, pinned.maxRetries)
        assertEquals(9, following.maxRetries)
    }

    @Test
    fun clearingTheRawOverrideRestoresConfigFollowing() {
        // The path SplashAd/SplashAppOpenAd take: suppress retries for the splash load, then restore
        // the saved raw override. A unit that was following the config must keep following it.
        val holder = Holder()
        val savedOverride = holder.retriesDefault.override
        assertNull("a fresh helper follows the config", savedOverride)

        holder.maxRetries = 0 // splash suppresses retries
        assertEquals(0, holder.maxRetries)

        holder.retriesDefault.override = savedOverride // splash restores
        NextGenAdsConfig.maxRetries = 4

        assertEquals("the unit must follow the config again after the splash", 4, holder.maxRetries)
    }
}
