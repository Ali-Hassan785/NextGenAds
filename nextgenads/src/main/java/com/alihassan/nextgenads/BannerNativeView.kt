package com.alihassan.nextgenads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.alihassan.nextgenads.banner.BannerAdHelper
import com.alihassan.nextgenads.banner.BannerSize
import com.alihassan.nextgenads.events.AdFormat
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.nativead.NativeTemplate
import com.alihassan.nextgenads.nativead.NativeTemplateView
import com.google.android.libraries.ads.mobile.sdk.banner.AdView

/** Whether a [BannerNativeView] renders a banner or a native ad. */
enum class AdType {
    BANNER,
    NATIVE;

    /** The [AdFormat] this view type reports to the library's per-format toggles / events. */
    internal fun toAdFormat(): AdFormat = if (this == BANNER) AdFormat.BANNER else AdFormat.NATIVE

    companion object {
        /** Maps the `ngad_ad_type` xml enum value to an [AdType] (defaults to [NATIVE]). */
        @JvmStatic
        fun fromAttr(value: Int): AdType = if (value == 0) BANNER else NATIVE
    }
}

/**
 * One drop-in view for both banner and native placements.
 *
 * Configure the type, native template, ad unit, the remote-config flag, and premium state — the
 * view decides whether to load and what to render, showing a shimmer while loading and hiding
 * itself when ads are suppressed (premium user, remote flag off, or no fill).
 *
 * XML:
 * ```
 * <com.alihassan.nextgenads.BannerNativeView
 *     android:id="@+id/adView"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:ngad_ad_type="nativead"
 *     app:ngad_template="medium" />
 * ```
 * Code:
 * ```
 * adView.load(adUnitId = AD_UNIT, remoteEnabled = remoteConfig.getBoolean("home_native"))
 * ```
 * Premium is handled globally via [NextGenAds.premium] / [NextGenAds.premiumProvider].
 */
class BannerNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), NextGenAds.PremiumAware {

    /** Banner or native. Can be overridden per [load] call. */
    var adType: AdType = AdType.NATIVE

    /** Template used when [adType] is [AdType.NATIVE]. Can be overridden per [load] call. */
    var nativeTemplate: NativeTemplate = NativeTemplate.MEDIUM

    /** Banner size used when [adType] is [AdType.BANNER]. Can be overridden per [load] call. */
    var bannerSize: BannerSize = BannerSize.ADAPTIVE

    private var nativeView: NativeTemplateView? = null

    init {
        attrs?.let {
            val typed = context.obtainStyledAttributes(it, R.styleable.BannerNativeView)
            adType = AdType.fromAttr(typed.getInt(R.styleable.BannerNativeView_ngad_ad_type, 1))
            nativeTemplate =
                NativeTemplate.fromName(typed.getString(R.styleable.BannerNativeView_ngad_template))
            bannerSize =
                BannerSize.fromName(typed.getString(R.styleable.BannerNativeView_ngad_banner_size))
            typed.recycle()
        }
    }

    /**
     * Loads an ad into this view.
     *
     * The ad is loaded only when ads are allowed ([NextGenAds.canShowAds]), the [remoteEnabled]
     * remote-config flag is `true`, and [adUnitId] is non-blank; otherwise the view is hidden.
     *
     * @param remoteEnabled the remote-config value for this placement.
     * @param adType optional override of the configured [adType].
     * @param nativeTemplate optional override of the configured [nativeTemplate].
     * @param bannerSize optional override of the configured [bannerSize] (used only for banners).
     */
    @JvmOverloads
    fun load(
        adUnitId: String,
        remoteEnabled: Boolean = true,
        adType: AdType = this.adType,
        nativeTemplate: NativeTemplate = this.nativeTemplate,
        bannerSize: BannerSize = this.bannerSize,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        this.adType = adType
        this.nativeTemplate = nativeTemplate
        this.bannerSize = bannerSize

        if (!NextGenAds.canShowAds(adType.toAdFormat()) || !remoteEnabled || adUnitId.isBlank()) {
            hide()
            return
        }

        visibility = View.VISIBLE
        when (adType) {
            AdType.NATIVE -> loadNative(adUnitId, onLoaded, onFailed)
            AdType.BANNER -> loadBanner(adUnitId, onLoaded, onFailed)
        }
    }

    private fun loadNative(adUnitId: String, onLoaded: (() -> Unit)?, onFailed: (() -> Unit)?) {
        val view = ensureNativeView()
        NativeAdHelper.populate(
            view,
            adUnitId,
            onLoaded = { onLoaded?.invoke() },
            onFailed = {
                hide() // collapse the whole placement when no ad could be loaded
                onFailed?.invoke()
            },
        )
    }

    private fun loadBanner(adUnitId: String, onLoaded: (() -> Unit)?, onFailed: (() -> Unit)?) {
        val activity = context.findActivity()
        if (activity == null) {
            NextGenAds.log("BannerNativeView requires an Activity context to load a banner")
            hide()
            return
        }
        nativeView?.destroy() // release any previously bound native ad before switching to a banner
        nativeView = null
        BannerAdHelper.loadAdaptiveBanner(
            activity,
            this,
            adUnitId,
            size = bannerSize,
            onLoaded = { onLoaded?.invoke() },
            onFailed = { onFailed?.invoke() },
        )
    }

    private fun ensureNativeView(): NativeTemplateView {
        val existing = nativeView
        if (existing != null && existing.template == nativeTemplate) {
            existing.showShimmer()
            return existing
        }
        destroyBannerChildren() // a previous banner placement must be released, not just detached
        removeAllViews()
        val view = NativeTemplateView(context).also {
            it.setTemplate(nativeTemplate)
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        nativeView = view
        return view
    }

    private fun hide() {
        nativeView?.destroy() // release the bound native ad so it isn't leaked
        destroyBannerChildren()
        removeAllViews()
        nativeView = null
        visibility = View.GONE
    }

    /** Releases banner / native ad resources. Call from the host's `onDestroy`. */
    fun destroy() {
        nativeView?.destroy()
        destroyBannerChildren()
        nativeView = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NextGenAds.registerAdSlot(this)
    }

    override fun onDetachedFromWindow() {
        NextGenAds.unregisterAdSlot(this)
        super.onDetachedFromWindow()
    }

    /** Ads disabled at runtime (e.g. the user goes premium) — release and hide any shown ad. */
    override fun onAdsDisabled() = hide()

    /** Reported so a single-format toggle ([NextGenAds.setFormatEnabled]) hides only matching slots. */
    override val slotAdFormat: AdFormat
        get() = adType.toAdFormat()

    /** Destroys any banner [AdView] children so a replaced/discarded banner isn't leaked. */
    private fun destroyBannerChildren() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? AdView)?.destroy()
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
