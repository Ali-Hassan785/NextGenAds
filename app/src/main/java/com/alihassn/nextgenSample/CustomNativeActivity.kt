package com.alihassn.nextgenSample

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.nativead.NativeTemplateView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView

/**
 * Showcases the library's **custom native templates** and **auto-generated shimmer** — five
 * placements, each rendered with a caller-supplied layout instead of one of the six built-in
 * [com.alihassan.nextgenads.nativead.NativeTemplate]s:
 *
 * 1. [customNativeShowcase] — a professional store-style card set from XML; its shimmer is
 *    auto-generated from the layout (no shimmer XML).
 * 2. [customNativeFeed] — a professional feed row set from XML; shimmer auto-generated.
 * 3. [customNativeXml] — a gradient card that supplies its own hand-made shimmer, to show that's
 *    still supported.
 * 4. [customNativeCode] — a compact row set in code via [NativeTemplateView.setCustomTemplate];
 *    shimmer auto-generated.
 * 5. [customNativeBinder] — a layout with arbitrary IDs bound by a caller-supplied binder; shimmer
 *    auto-generated.
 *
 * All five ride the standard [NativeAdHelper] cache / retry / expiry pipeline.
 */
class CustomNativeActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var customNativeShowcase: NativeTemplateView
    private lateinit var customNativeFeed: NativeTemplateView
    private lateinit var customNativeXml: NativeTemplateView
    private lateinit var customNativeCode: NativeTemplateView
    private lateinit var customNativeBinder: NativeTemplateView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_custom_native)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.customNativeRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.tvCustomStatus)
        customNativeShowcase = findViewById(R.id.customNativeShowcase)


        customNativeFeed = findViewById(R.id.customNativeFeed)

//      customNativeFeed.setCustomTemplate(listOf(R))

        customNativeXml = findViewById(R.id.customNativeXml)
        customNativeCode = findViewById(R.id.customNativeCode)
        customNativeBinder = findViewById(R.id.customNativeBinder)

        // Slot 4: point at a custom layout from code. No shimmer arg → one is auto-generated from
        // the layout. ID-contract, so no binder needed.
        customNativeCode.setCustomTemplate(R.layout.demo_native_compact)

        // Slot 5: a layout with arbitrary IDs — bind the assets ourselves and register them. Still
        // no shimmer XML: the placeholder is auto-generated from the layout's shape.
        customNativeBinder.setCustomTemplate(R.layout.demo_native_binder) { adView, ad ->
            val title = adView.findViewById<TextView>(R.id.demo_title)
            val desc = adView.findViewById<TextView>(R.id.demo_desc)
            val action = adView.findViewById<TextView>(R.id.demo_action)
            val media = adView.findViewById<MediaView>(R.id.demo_media)

            title.text = ad.headline
            desc.text = ad.body
            action.text = ad.callToAction ?: getString(R.string.demo_learn_more)

            // Fill the media slot so a mismatched aspect ratio doesn't leave grey letterbox bars.
            media?.imageScaleType = ImageView.ScaleType.CENTER_CROP

            // Register each asset so the SDK tracks clicks / impressions on it…
            adView.headlineView = title
            adView.bodyView = desc
            adView.callToActionView = action
            // …then attach the ad (media is optional).
            adView.registerNativeAd(ad, media)
        }

        findViewById<Button>(R.id.btnReloadCustom).setOnClickListener { loadAll() }

        loadAll()
    }

    /** (Re)fills all slots. Each shows its shimmer until an ad binds, then refills the cache. */
    private fun loadAll() {
        if (!NextGenAds.isInitialized()) {
            status.text = "Gather consent & initialize on the main screen first, then reload."
            return
        }
        status.text = "Loading five custom-template native ads…"
        // refill = true keeps the per-unit cache warm so a reload binds instantly.
        listOf(
            customNativeShowcase,
            customNativeFeed,
            customNativeXml,
            customNativeCode,
            customNativeBinder,
        ).forEach { NativeAdHelper.populate(it, NATIVE_UNIT, refill = true) }
    }

    override fun onDestroy() {
        customNativeShowcase.destroy()
        customNativeFeed.destroy()
        customNativeXml.destroy()
        customNativeCode.destroy()
        customNativeBinder.destroy()
        super.onDestroy()
    }

    private companion object {
        /** Google's official native test ad unit — replace with your own for release. */
        const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
    }
}
