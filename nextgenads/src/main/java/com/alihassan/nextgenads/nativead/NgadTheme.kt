package com.alihassan.nextgenads.nativead

import android.content.Context
import android.view.ContextThemeWrapper
import com.alihassan.nextgenads.NextGenAdsConfig

/**
 * Wraps a [Context] so ad layouts inflated from it resolve the `ngad_*` palette attributes (CTA and
 * "Ad" badge colours). The overlay is [NextGenAdsConfig.adThemeOverlay]; its default maps those
 * attributes to the host's Material3 tokens, so every ad follows the app's colours, and swapping the
 * overlay re-styles all ads in one place.
 *
 * Applied at every ad-inflation site — native templates, auto-generated shimmer skeletons, and the
 * full-screen loading / "Welcome back" covers — so the palette attrs always resolve regardless of
 * whether the host theme declares them.
 */
internal object NgadTheme {

    /**
     * Returns [context] wrapped in the configured ad overlay, or [context] unchanged when the
     * overlay is disabled ([NextGenAdsConfig.adThemeOverlay] == 0) or already applied (idempotent, so
     * re-wrapping a view's own context never stacks overlays).
     */
    fun wrap(context: Context): Context {
        val overlay = NextGenAdsConfig.adThemeOverlay
        if (overlay == 0) return context
        // Guard against double-wrapping (e.g. a view built from an already-themed context).
        if (context is Wrapped && context.overlayRes == overlay) return context
        return Wrapped(context, overlay)
    }

    /** A [ContextThemeWrapper] that remembers which overlay it applied, so [wrap] stays idempotent. */
    private class Wrapped(base: Context, val overlayRes: Int) : ContextThemeWrapper(base, overlayRes)
}
