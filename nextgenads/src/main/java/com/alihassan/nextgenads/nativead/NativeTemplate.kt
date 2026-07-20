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
 * - [FULLSCREEN]  – a full-height card: the media stretches to fill all the leftover vertical space
 *   (with the "Ad" badge overlaid), then icon + headline/advertiser, rating, body and a bold CTA
 *   pinned to the bottom. For full-screen slots — a host that gives the `NativeTemplateView`
 *   `match_parent` height (splash / interstitial-style screens). Ships its own shimmer.
 * - [FEED]        – sponsored-post styling: icon + advertiser header, headline, media, body, CTA —
 *   drops naturally into a content feed.
 * - [SPOTLIGHT]   – centred composition (icon, headline, rating, body, media, CTA all centred) for
 *   dialogs and empty states.
 * - [ACTION_TOP]  – call-to-action pinned at the top, with the icon, headline, advertiser, rating,
 *   body and media stacked below it. For slots where the action should be first in reach (bottom
 *   sheets opening upward, thumb-friendly footers).
 * - [HALF_MEDIA]  – the card is split ~50/50: media fills the left half (with the "Ad" badge), and
 *   the right half stacks icon + headline, advertiser, rating, body and a CTA pinned to the bottom.
 *   A compact, list-friendly card that still shows sizeable media.
 * - [STACKED]     – a compact card: the small "Ad" badge and headline on top, then a full-width
 *   120dp media, then a full-width CTA at the bottom. No icon or body.
 * - [TITLE_ONLY]  – a title-forward card: the "Ad" badge on the left with the headline beside it on
 *   top, then the media below the title, then a full-width CTA at the bottom. No icon, body,
 *   advertiser or rating — the leanest media card.
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
    // Full-height card: media fills the leftover space, so it ships its own shimmer (a weighted
    // media block would collapse under the auto-generated wrap-height skeleton).
    FULLSCREEN(R.layout.ngad_native_fullscreen, R.layout.ngad_shimmer_native_fullscreen),
    FEED(R.layout.ngad_native_feed),
    SPOTLIGHT(R.layout.ngad_native_spotlight),
    ACTION_TOP(R.layout.ngad_native_action_top),
    HALF_MEDIA(R.layout.ngad_native_half_media,R.layout.ngad_shimmer_native_half_media),
    STACKED(R.layout.ngad_native_stacked),
    TITLE_ONLY(R.layout.ngad_native_titleonly);

    companion object {
        /**
         * Resolves an `ngad_template` value by name, falling back to [MEDIUM] for a `null` or
         * unrecognised name. Name-based so templates are referenced by their enum name rather than a
         * brittle integer index.
         *
         * Matching is case-insensitive and separator-insensitive, so `"hero"`, `"TITLE_ONLY"`,
         * `"title_only"`, `"title-only"` and `"titleonly"` all resolve — any non-alphanumeric
         * characters (`_`, `-`, spaces) are ignored on both sides.
         */
        @JvmStatic
        fun fromName(name: String?): NativeTemplate {
            if (name == null) return MEDIUM
            val key = name.normalizeTemplateName()
            return entries.firstOrNull { it.name.normalizeTemplateName() == key } ?: MEDIUM
        }

        /** Lower-cases and strips every non-alphanumeric char, so `"TITLE_ONLY"` == `"titleonly"`. */
        private fun String.normalizeTemplateName(): String =
            lowercase().filter { it.isLetterOrDigit() }
    }
}
