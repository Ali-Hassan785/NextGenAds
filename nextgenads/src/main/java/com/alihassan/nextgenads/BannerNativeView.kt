package com.alihassan.nextgenads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.alihassan.nextgenads.banner.BannerAdHelper
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.nativead.NativeTemplate
import com.alihassan.nextgenads.nativead.NativeTemplateView

/** Whether a [BannerNativeView] renders a banner or a native ad. */
enum class AdType {
    BANNER,
    NATIVE;

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
 *     app:ngad_ad_type="native"
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
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Banner or native. Can be overridden per [load] call. */
    var adType: AdType = AdType.NATIVE

    /** Template used when [adType] is [AdType.NATIVE]. Can be overridden per [load] call. */
    var nativeTemplate: NativeTemplate = NativeTemplate.MEDIUM

    private var nativeView: NativeTemplateView? = null

    init {
        attrs?.let {
            val typed = context.obtainStyledAttributes(it, R.styleable.BannerNativeView)
            adType = AdType.fromAttr(typed.getInt(R.styleable.BannerNativeView_ngad_ad_type, 1))
            nativeTemplate =
                NativeTemplate.fromAttr(typed.getInt(R.styleable.BannerNativeView_ngad_template, 1))
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
     */
    @JvmOverloads
    fun load(
        adUnitId: String,
        remoteEnabled: Boolean = true,
        adType: AdType = this.adType,
        nativeTemplate: NativeTemplate = this.nativeTemplate,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        this.adType = adType
        this.nativeTemplate = nativeTemplate

        if (!NextGenAds.canShowAds() || !remoteEnabled || adUnitId.isBlank()) {
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
        removeAllViews()
        nativeView = null
        visibility = View.GONE
    }

    /** Releases native ad resources. Call from the host's `onDestroy`. */
    fun destroy() {
        nativeView?.destroy()
        nativeView = null
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
