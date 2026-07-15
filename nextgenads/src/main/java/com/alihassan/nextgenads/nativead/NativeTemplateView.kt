package com.alihassan.nextgenads.nativead

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
import com.alihassan.nextgenads.events.AdFormat
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/**
 * A self-contained view that renders a Next-Gen [NativeAd] and shows a shimmer placeholder until an
 * ad is bound. Use one of the built-in [NativeTemplate]s, or supply **your own layout**.
 *
 * **Built-in templates** — set from XML with `app:ngad_template="medium"`, or in code via
 * [setTemplate].
 *
 * **Custom templates** — provide your own layout via `app:ngad_customLayout="@layout/my_native"`,
 * or in code via [setCustomTemplate]. A shimmer placeholder is **auto-generated** from your layout
 * ([ShimmerSkeleton]) unless you supply your own via `app:ngad_customShimmer` / the `shimmer`
 * argument, so no per-template shimmer XML is needed. Two ways to bind:
 * - *ID-contract* (simplest): make the layout's root a
 *   [NativeAdView][com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView] and give its
 *   asset views the library IDs below. Binding, asset registration and click/impression tracking
 *   are then handled for you — no code needed beyond pointing at the layout.
 *   Recognised IDs: `ngad_headline`, `ngad_body`, `ngad_cta`, `ngad_icon`, `ngad_advertiser`,
 *   `ngad_stars`, `ngad_media`, `ngad_collapse` (any you omit is simply skipped).
 * - *Custom binder* (full control): pass a `binder` to [setCustomTemplate] to bind assets yourself
 *   for a layout with arbitrary IDs — you are then responsible for calling
 *   `NativeAdView.registerNativeAd(ad, mediaView)` inside it.
 *
 * Bind an ad with [setNativeAd] (or let [NativeAdHelper.populate] do it for you), and call
 * [destroy] from the host's `onDestroy`.
 */
class NativeTemplateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), NextGenAds.PremiumAware {

    /** The active built-in template. Meaningful only when [isCustomTemplate] is `false`. */
    var template: NativeTemplate = NativeTemplate.MEDIUM
        private set

    /** `true` when a caller-supplied layout is active (via [setCustomTemplate]) rather than a [template]. */
    val isCustomTemplate: Boolean
        get() = customLayoutRes != 0

    private var nativeAdView: NativeAdView? = null
    private var shimmerContainer: ShimmerFrameLayout? = null
    private var boundAd: NativeAd? = null

    @LayoutRes
    private var customLayoutRes: Int = 0
    private var customBinder: ((NativeAdView, NativeAd) -> Unit)? = null

    /**
     * How the ad's media is scaled inside its `MediaView`. Defaults to [ImageView.ScaleType.CENTER_CROP]
     * so the media fills the slot whatever its aspect ratio — otherwise a media whose ratio differs
     * from the view is letterboxed, leaving grey bars from the view's background. Set
     * [ImageView.ScaleType.FIT_CENTER] to show the whole creative instead (may show bars). Applies to
     * the `ngad_media` view on the next [setNativeAd] for ID-contract templates.
     */
    var mediaScaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP

    init {
        var initial = NativeTemplate.MEDIUM
        var customLayout = 0
        var customShimmer = 0
        attrs?.let { attributeSet ->
            val typed = context.obtainStyledAttributes(attributeSet, R.styleable.NativeTemplateView)
            initial = NativeTemplate.fromName(typed.getString(R.styleable.NativeTemplateView_ngad_template))
            customLayout = typed.getResourceId(R.styleable.NativeTemplateView_ngad_customLayout, 0)
            customShimmer = typed.getResourceId(R.styleable.NativeTemplateView_ngad_customShimmer, 0)
            typed.recycle()
        }
        // A custom layout, if given, wins over the built-in template attribute.
        if (customLayout != 0) setCustomTemplate(customLayout, customShimmer) else setTemplate(initial)
    }

    /** Swaps to a built-in [template], re-inflating its ad view and shimmer. */
    fun setTemplate(template: NativeTemplate) {
        this.template = template
        this.customLayoutRes = 0
        this.customBinder = null
        // Older templates ship a hand-tuned shimmer; creative ones leave it 0 and get an
        // auto-generated skeleton from their layout.
        applyLayout(template.layout, template.shimmer, autoShimmer = true)
    }

    /**
     * Swaps to a caller-supplied [layout] (its root must be a
     * [NativeAdView][com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView]).
     *
     * @param shimmer explicit placeholder whose root is a `ShimmerFrameLayout`. Leave `0` (default)
     *   to let a shimmer be **auto-generated** from [layout] (see [autoShimmer]).
     * @param autoShimmer when `true` (default) and no [shimmer] is given, a shimmering grey skeleton
     *   is built automatically from [layout] via [ShimmerSkeleton] — no separate shimmer XML needed.
     *   Set `false` to show no placeholder at all (the ad simply appears once it binds).
     * @param binder optional asset binder for layouts that don't use the library's `ngad_*` IDs.
     *   When supplied it fully replaces the default binding, so it **must** register the assets and
     *   call `registerNativeAd(ad, mediaView)` itself. Leave `null` to use the ID-contract binding.
     */
    @JvmOverloads
    fun setCustomTemplate(
        @LayoutRes layout: Int,
        @LayoutRes shimmer: Int = 0,
        autoShimmer: Boolean = true,
        binder: ((NativeAdView, NativeAd) -> Unit)? = null,
    ) {
        this.customLayoutRes = layout
        this.customBinder = binder
        applyLayout(layout, shimmer, autoShimmer)
    }

    /**
     * Re-inflates the ad view for [layout] (resetting any bound ad) and installs its shimmer: an
     * explicit [shimmer] if given, else an auto-generated skeleton when [autoShimmer] is set.
     */
    private fun applyLayout(@LayoutRes layout: Int, @LayoutRes shimmer: Int, autoShimmer: Boolean) {
        removeAllViews()
        boundAd?.destroy()
        boundAd = null

        val inflater = LayoutInflater.from(context)
        val adView = inflater.inflate(layout, this, false) as? NativeAdView
            ?: throw IllegalArgumentException(
                "NativeTemplateView: the template layout's root must be a " +
                    "com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView",
            )
        addView(adView)
        nativeAdView = adView

        shimmerContainer = when {
            shimmer != 0 -> {
                val view = inflater.inflate(shimmer, this, false) as? ShimmerFrameLayout
                if (view == null) NextGenAds.log("NativeTemplateView: custom shimmer root is not a ShimmerFrameLayout — ignoring")
                view?.also { addView(it) }
            }
            // No explicit shimmer: derive one from the ad layout so callers needn't design one.
            autoShimmer -> ShimmerSkeleton.fromLayout(context, layout).also { addView(it) }
            else -> null
        }
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
        // Full-control path: the caller binds and registers its own arbitrary-ID layout.
        customBinder?.let { bindCustom ->
            bindCustom(adView, ad)
            return
        }

        val headline = adView.findViewById<TextView?>(R.id.ngad_headline)
        val body = adView.findViewById<TextView?>(R.id.ngad_body)
        val cta = adView.findViewById<TextView?>(R.id.ngad_cta)
        val icon = adView.findViewById<ImageView?>(R.id.ngad_icon)
        val advertiser = adView.findViewById<TextView?>(R.id.ngad_advertiser)
        val stars = adView.findViewById<RatingBar?>(R.id.ngad_stars)
        val media = adView.findViewById<MediaView?>(R.id.ngad_media)

        // Clip the media to its rounded background. Done in code so it works on API 24+ (the
        // android:clipToOutline XML attribute only takes effect on API 31+).
        media?.clipToOutline = true
        // Keep the icon square — never clip it to rounded corners. Set explicitly (not just omitted)
        // so it overrides any android:clipToOutline="true" a template's XML declares, on every API.
        icon?.clipToOutline = false
        // Fill the media slot so a media whose aspect ratio differs from the view doesn't letterbox
        // and expose the view's (grey) background as side/top bars.
        media?.imageScaleType = mediaScaleType

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


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NextGenAds.registerAdSlot(this)
    }

    override fun onDetachedFromWindow() {
        NextGenAds.unregisterAdSlot(this)
        super.onDetachedFromWindow()
    }

    /** Ads disabled at runtime (e.g. the user goes premium) — release the ad and hide the slot. */
    override fun onAdsDisabled() {
        boundAd?.destroy()
        boundAd = null
        showError()
    }

    /**
     * This slot always shows a native ad, so a single-format toggle for [AdFormat.NATIVE]
     * ([NextGenAds.setFormatEnabled]) hides it. Without this override the default `null` meant a
     * per-format NATIVE toggle left the on-screen ad visible (only a full premium purge cleared it).
     */
    override val slotAdFormat: AdFormat
        get() = AdFormat.NATIVE

    /** Releases the bound ad and stops the shimmer. Call from the host's `onDestroy`. */
    fun destroy() {
        shimmerContainer?.stopShimmer()
        shimmerContainer = null
        boundAd?.destroy()
        boundAd = null
        nativeAdView = null
        customBinder = null
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
