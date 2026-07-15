package com.alihassan.nextgenads.nativead

import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alihassan.nextgenads.events.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the per-format hide bug: [NativeTemplateView] implements
 * `NextGenAds.PremiumAware` but originally never overrode `slotAdFormat`, so it defaulted to `null`
 * and `NextGenAds.setFormatEnabled(NATIVE, false)` (which hides only slots whose `slotAdFormat`
 * equals the toggled format) left an on-screen native ad visible. It must report [AdFormat.NATIVE].
 *
 * The view inflates a themed template in its constructor, so it's built on the main thread with a
 * Material3 context.
 */
@RunWith(AndroidJUnit4::class)
class NativeTemplateViewSlotFormatTest {

    @Test
    fun reportsNativeFormatSoPerFormatToggleHidesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val themed = ContextThemeWrapper(
            instrumentation.targetContext,
            com.google.android.material.R.style.Theme_Material3_DayNight,
        )

        var slotFormat: AdFormat? = null
        instrumentation.runOnMainSync {
            slotFormat = NativeTemplateView(themed).slotAdFormat
        }

        assertEquals(AdFormat.NATIVE, slotFormat)
    }
}
