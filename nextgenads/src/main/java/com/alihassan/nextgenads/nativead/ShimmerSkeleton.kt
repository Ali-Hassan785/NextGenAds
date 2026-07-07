package com.alihassan.nextgenads.nativead

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView

/**
 * Builds a shimmer placeholder **automatically from an ad layout**, so callers don't have to design
 * (and keep in sync) a separate shimmer for every custom native template.
 *
 * [fromLayout] inflates the given layout, then replaces every leaf content view (text, image, media,
 * rating, button …) with a rounded grey block of the same size and position, and wraps the result in
 * a [ShimmerFrameLayout] whose sweep animation the host ([NativeTemplateView]) starts and stops.
 *
 * Leaves are **replaced** with plain [View]s rather than merely re-styled, for two reasons:
 * - The layout's [MediaView] is surface-backed; a `SurfaceView` ignores normal z-order and keeps
 *   drawing even after its parent is set to `GONE`, so a re-styled media view would never hide.
 * - The inflated copy would otherwise duplicate the real ad view's `ngad_*` IDs in the same window,
 *   which breaks `findViewById` and view-state saving.
 */
object ShimmerSkeleton {

    /** Grey used for every placeholder block. Neutral enough to read on light and dark cards. */
    private const val BLOCK_COLOR = 0xFFE1E4EA.toInt()
    private const val BLOCK_RADIUS_DP = 6f
    private const val MIN_TEXT_HEIGHT_DP = 12
    private const val MIN_TEXT_WIDTH_DP = 96
    private const val MIN_LEAF_SIZE_DP = 40

    /**
     * Inflates [layout] and returns a shimmering grey skeleton of it, ready to be added to a parent.
     * The layout is never bound to an ad — it is used purely for its shape, and its ad-SDK views
     * (`MediaView`, `NativeAdView`) never reach the window.
     */
    @JvmStatic
    fun fromLayout(context: Context, @LayoutRes layout: Int): ShimmerFrameLayout {
        val shimmer = ShimmerFrameLayout(context)
        shimmer.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val content = LayoutInflater.from(context).inflate(layout, shimmer, false)
        skeletonize(content)
        shimmer.addView(content)
        return shimmer
    }

    /** Recurses into real containers; replaces every leaf (and [MediaView]) with a grey block. */
    private fun skeletonize(view: View) {
        // A MediaView is a ViewGroup but has no ad content to recurse into — treat it as a leaf.
        if (view is ViewGroup && view !is MediaView) {
            // Drop the container's own id to avoid colliding with the real ad view's ids.
            view.id = View.NO_ID
            // Snapshot first: replacing children mutates the list we're iterating.
            val children = (0 until view.childCount).map { view.getChildAt(it) }
            children.forEach { child -> replaceIfLeaf(view, child) }
            return
        }
    }

    /** If [child] is a leaf, swap it for a grey block in [parent]; otherwise recurse into it. */
    private fun replaceIfLeaf(parent: ViewGroup, child: View) {
        if (child is ViewGroup && child !is MediaView) {
            skeletonize(child)
            return
        }
        val index = parent.indexOfChild(child)
        parent.removeViewAt(index)
        parent.addView(block(child), index)
    }

    /** Builds a rounded grey [View] that occupies the same slot as [source]. */
    private fun block(source: View): View {
        val density = source.resources.displayMetrics.density
        val view = View(source.context)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = BLOCK_RADIUS_DP * density
            setColor(BLOCK_COLOR)
        }
        // Reuse the source's layout params so margins / weight / explicit sizes are preserved.
        val lp = source.layoutParams
        if (lp != null) {
            view.layoutParams = lp
            // Text is usually taller/wider-floored than icons; give sensible minimums for wrap sizes.
            val isText = source is TextView
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                view.minimumWidth = ((if (isText) MIN_TEXT_WIDTH_DP else MIN_LEAF_SIZE_DP) * density).toInt()
            }
            if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                view.minimumHeight = ((if (isText) MIN_TEXT_HEIGHT_DP else MIN_LEAF_SIZE_DP) * density).toInt()
            }
        }
        return view
    }
}
