package com.alihassan.nextgenads.nativead

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/**
 * A self-contained view that renders a Next-Gen [NativeAd] using one of the six built-in
 * [NativeTemplate]s and shows a shimmer placeholder until an ad is bound.
 *
 * Set the template from XML with `app:ngad_template="medium"`, or in code via [setTemplate].
 * Bind an ad with [setNativeAd] (or let [NativeAdHelper.populate] do it for you), and call
 * [destroy] from the host's `onDestroy`.
 */
class NativeTemplateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var template: NativeTemplate = NativeTemplate.MEDIUM
        private set

    private var nativeAdView: NativeAdView? = null
    private var shimmerContainer: ShimmerFrameLayout? = null
    private var boundAd: NativeAd? = null

    init {
        var initial = NativeTemplate.MEDIUM
        attrs?.let { attributeSet ->
            val typed = context.obtainStyledAttributes(attributeSet, R.styleable.NativeTemplateView)
            val value = typed.getInt(R.styleable.NativeTemplateView_ngad_template, 1)
            initial = NativeTemplate.fromAttr(value)
            typed.recycle()
        }
        setTemplate(initial)
    }

    /** Swaps the active template, re-inflating the ad view and shimmer. */
    fun setTemplate(template: NativeTemplate) {
        this.template = template
        removeAllViews()
        boundAd?.destroy()
        boundAd = null

        val inflater = LayoutInflater.from(context)
        val adView = inflater.inflate(template.layout, this, false) as NativeAdView
        val shimmer = inflater.inflate(template.shimmer, this, false) as ShimmerFrameLayout
        addView(adView)
        addView(shimmer)
        nativeAdView = adView
        shimmerContainer = shimmer
        showShimmer()
    }

    /** Shows the shimmer placeholder and hides the ad. */
    fun showShimmer() {
        // Re-show the view itself in case a previous failure collapsed it.
        visibility = View.VISIBLE
        shimmerContainer?.let {
            it.visibility = View.VISIBLE
            it.startShimmer()
        }
        nativeAdView?.visibility = View.GONE
    }

    /**
     * Stops the shimmer and collapses the view. Call when no ad could be loaded so the placeholder
     * doesn't shimmer forever over an empty slot.
     */
    fun showError() {
        hideShimmer()
        nativeAdView?.visibility = View.GONE
        visibility = View.GONE
    }

    private fun hideShimmer() {
        shimmerContainer?.let {
            it.stopShimmer()
            it.visibility = View.GONE
        }
    }

    /** Binds [ad] to the current template and reveals it. */
    fun setNativeAd(ad: NativeAd) {
        val adView = nativeAdView
        if (adView == null) {
            // The view was destroy()ed while this ad was still in flight (e.g. a populate() waiter
            // landing after the host screen closed) — release the ad instead of leaking it.
            ad.destroy()
            return
        }
        boundAd?.destroy()
        boundAd = ad
        bind(adView, ad)
        hideShimmer()
        adView.visibility = View.VISIBLE
    }

    private fun bind(adView: NativeAdView, ad: NativeAd) {
        val headline = adView.findViewById<TextView?>(R.id.ngad_headline)
        val body = adView.findViewById<TextView?>(R.id.ngad_body)
        val cta = adView.findViewById<TextView?>(R.id.ngad_cta)
        val icon = adView.findViewById<ImageView?>(R.id.ngad_icon)
        val advertiser = adView.findViewById<TextView?>(R.id.ngad_advertiser)
        val stars = adView.findViewById<RatingBar?>(R.id.ngad_stars)
        val media = adView.findViewById<MediaView?>(R.id.ngad_media)

        // Clip the icon / media to their rounded backgrounds. Done in code so it works on API 24+
        // (the android:clipToOutline XML attribute only takes effect on API 31+).
        icon?.clipToOutline = true
        media?.clipToOutline = true

        // Register asset views so the SDK tracks clicks / impressions on them.
        adView.headlineView = headline
        adView.bodyView = body
        adView.callToActionView = cta
        adView.iconView = icon
        adView.advertiserView = advertiser
        adView.starRatingView = stars

        headline?.text = ad.headline
        body?.applyOrHide(ad.body)
        cta?.applyOrHide(ad.callToAction)
        advertiser?.applyOrHide(ad.advertiser)

        val iconDrawable = ad.icon?.drawable
        if (iconDrawable != null) {
            icon?.setImageDrawable(iconDrawable)
            icon?.visibility = View.VISIBLE
        } else {
            icon?.visibility = View.GONE
        }

        val rating = ad.starRating
        if (stars != null) {
            if (rating != null && rating > 0) {
                stars.rating = rating.toFloat()
                stars.visibility = View.VISIBLE
            } else {
                stars.visibility = View.GONE
            }
        }

        // Collapsible template control: the down-arrow collapses the media. Wired up before
        // registerNativeAd and deliberately NOT registered as an ad asset, so tapping it controls
        // the card instead of opening the ad. Other templates don't contain this view, so the
        // lookup is null and skipped.
        bindCollapsibleControls(adView, media)

        // Attaches the ad to the NativeAdView and starts impression / click tracking. The media
        // view is optional — compact templates (small, banner) pass null and show no media, which
        // is policy-compliant, while still tracking the registered icon / headline / CTA assets.
        adView.registerNativeAd(ad, media)
    }

    /**
     * Wires the collapse (down-arrow) control used by [NativeTemplate.COLLAPSIBLE]. Tapping the
     * arrow collapses the placement: it hides [media] and then removes the arrow itself, leaving a
     * compact ad. No-ops on templates that lack the control.
     */
    private fun bindCollapsibleControls(adView: NativeAdView, media: MediaView?) {
        val collapse = adView.findViewById<ImageView?>(R.id.ngad_collapse)

        if (collapse != null && media != null) {
            // Reset to the expanded state on every (re)bind, since the view may be recycled.
            media.visibility = View.VISIBLE
            collapse.visibility = View.VISIBLE
            collapse.setOnClickListener {
                // One-way collapse: hide the media, then drop the control that manages it.
                media.visibility = View.GONE
                collapse.visibility = View.GONE
            }
        }
    }


    /** Releases the bound ad and stops the shimmer. Call from the host's `onDestroy`. */
    fun destroy() {
        shimmerContainer?.stopShimmer()
        shimmerContainer = null
        boundAd?.destroy()
        boundAd = null
        nativeAdView = null
    }

    private fun TextView.applyOrHide(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            visibility = View.GONE
        } else {
            this.text = text
            visibility = View.VISIBLE
        }
    }
}
