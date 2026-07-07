package com.alihassan.nextgenads.nativead

import androidx.annotation.LayoutRes
import com.alihassan.nextgenads.R

/**
 * The built-in native ad templates. The first six ship a hand-tuned [shimmer] placeholder; the
 * newer "creative" templates leave [shimmer] as `0`, so a matching shimmer is auto-generated from
 * the layout at runtime (see [ShimmerSkeleton]) — no separate shimmer XML.
 *
 * - [SMALL]       – compact card, no media (good for list rows).
 * - [MEDIUM]      – icon + headline + body + media + CTA (the all-rounder).
 * - [LARGE]       – media-forward card for full-width slots / dialogs.
 * - [BANNER]      – single-line strip that mimics a banner footer.
 * - [MEDIA_LEFT]  – media on the left, headline + body top-right, CTA bottom-right.
 * - [COLLAPSIBLE] – media on top with a down-arrow control that collapses the media, leaving a
 *   compact ad.
 * - [HERO]        – cinematic full-width media up top with the "Ad" badge overlaid, then icon +
 *   headline, body and a bold CTA. Great for splash / interstitial-style placements.
 * - [FEED]        – sponsored-post styling: icon + advertiser header, headline, media, body, CTA —
 *   drops naturally into a content feed.
 * - [SPOTLIGHT]   – centred composition (icon, headline, rating, body, media, CTA all centred) for
 *   dialogs and empty states.
 */
enum class NativeTemplate(
    @field:LayoutRes @get:LayoutRes val layout: Int,
    @field:LayoutRes @get:LayoutRes val shimmer: Int = 0,
) {
    SMALL(R.layout.ngad_native_small, R.layout.ngad_shimmer_native_small),
    MEDIUM(R.layout.ngad_native_medium, R.layout.ngad_shimmer_native_medium),
    LARGE(R.layout.ngad_native_large, R.layout.ngad_shimmer_native_large),
    BANNER(R.layout.ngad_native_banner, R.layout.ngad_shimmer_native_banner),
    MEDIA_LEFT(R.layout.ngad_native_media_left, R.layout.ngad_shimmer_native_media_left),
    COLLAPSIBLE(R.layout.ngad_native_collapsible, R.layout.ngad_shimmer_native_collapsible),

    // Creative templates — shimmer auto-generated from the layout (no shimmer XML needed).
    HERO(R.layout.ngad_native_hero),
    FEED(R.layout.ngad_native_feed),
    SPOTLIGHT(R.layout.ngad_native_spotlight);

    companion object {
        /**
         * Resolves an `ngad_template` value by name (case-insensitive, e.g. `"hero"` → [HERO]),
         * falling back to [MEDIUM] for a `null` or unrecognised name. Name-based so templates are
         * referenced by their enum name rather than a brittle integer index.
         */
        @JvmStatic
        fun fromName(name: String?): NativeTemplate =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
    }
}
