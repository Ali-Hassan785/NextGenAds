package com.alihassan.nextgenads.nativead

import androidx.annotation.LayoutRes
import com.alihassan.nextgenads.R

/**
 * The five built-in native ad templates, each paired with a matching shimmer placeholder.
 *
 * - [SMALL]      – compact card, no media (good for list rows).
 * - [MEDIUM]     – icon + headline + body + media + CTA (the all-rounder).
 * - [LARGE]      – media-forward card for full-width slots / dialogs.
 * - [BANNER]     – single-line strip that mimics a banner footer.
 * - [MEDIA_LEFT] – media on the left, headline + body top-right, CTA bottom-right.
 */
enum class NativeTemplate(
    @field:LayoutRes @get:LayoutRes val layout: Int,
    @field:LayoutRes @get:LayoutRes val shimmer: Int,
) {
    SMALL(R.layout.ngad_native_small, R.layout.ngad_shimmer_native_small),
    MEDIUM(R.layout.ngad_native_medium, R.layout.ngad_shimmer_native_medium),
    LARGE(R.layout.ngad_native_large, R.layout.ngad_shimmer_native_large),
    BANNER(R.layout.ngad_native_banner, R.layout.ngad_shimmer_native_banner),
    MEDIA_LEFT(R.layout.ngad_native_media_left, R.layout.ngad_shimmer_native_media_left);

    companion object {
        /** Maps an `ngad_template` xml enum value to a [NativeTemplate] (defaults to [MEDIUM]). */
        @JvmStatic
        fun fromAttr(value: Int): NativeTemplate = when (value) {
            0 -> SMALL
            1 -> MEDIUM
            2 -> LARGE
            3 -> BANNER
            4 -> MEDIA_LEFT
            else -> MEDIUM
        }
    }
}
