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
 * A self-contained view that renders a Next-Gen [NativeAd] using one of the four built-in
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
        shimmerContainer?.let {
            it.visibility = View.VISIBLE
            it.startShimmer()
        }
        nativeAdView?.visibility = View.GONE
    }

    private fun hideShimmer() {
        shimmerContainer?.let {
            it.stopShimmer()
            it.visibility = View.GONE
        }
    }

    /** Binds [ad] to the current template and reveals it. */
    fun setNativeAd(ad: NativeAd) {
        val adView = nativeAdView ?: return
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

        // Attaches the ad to the NativeAdView and starts impression / click tracking. The media
        // view is optional — compact templates (small, banner) pass null and show no media, which
        // is policy-compliant, while still tracking the registered icon / headline / CTA assets.
        adView.registerNativeAd(ad, media)
    }

    /** Releases the bound ad and stops the shimmer. Call from the host's `onDestroy`. */
    fun destroy() {
        shimmerContainer?.stopShimmer()
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
