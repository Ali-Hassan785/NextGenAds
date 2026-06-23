package com.alihassan.nextgenads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point for the NextGenAds library, wrapping the Google **Next Generation Mobile Ads SDK**
 * (`com.google.android.libraries.ads.mobile.sdk`).
 *
 * Call [initialize] once (after consent has been gathered) before requesting any ad. Initialization
 * runs on a background thread — as the Next-Gen SDK requires — and the completion callback is
 * delivered on the main thread, which is a good place to start preloading.
 */
object NextGenAds {

    const val TAG = "NextGenAds"

    /** Master kill-switch. Set to `false` for premium / ad-free users — every helper honours it. */
    @Volatile
    @JvmStatic
    var enabled: Boolean = true

    /** Toggle verbose logcat output. */
    @Volatile
    @JvmStatic
    var loggingEnabled: Boolean = true

    /**
     * Set to `true` once the user has an active IAP / premium purchase. While `true`, no ad is
     * ever requested or shown. For dynamic billing state, wire [premiumProvider] instead.
     */
    @Volatile
    @JvmStatic
    var premium: Boolean = false

    /**
     * Optional dynamic premium check (e.g. read your billing repository). Evaluated on every ad
     * request; if it returns `true`, ads are suppressed. Defaults to always-false.
     */
    @JvmStatic
    var premiumProvider: () -> Boolean = { false }

    /** Single gate every helper consults: ads are allowed only when enabled and not premium. */
    @JvmStatic
    fun canShowAds(): Boolean = enabled && !premium && !premiumProvider()

    @Volatile
    private var initialized = false
    private val initializing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = mutableListOf<Runnable>()

    /**
     * Initializes the Next-Gen Mobile Ads SDK.
     *
     * @param appId your AdMob/Ad Manager app id (e.g. `ca-app-pub-xxx~yyy`).
     * @param testDeviceIds device ids that should always receive test ads (safe to ship empty).
     * @param onComplete invoked on the main thread once initialization finishes — start preloading
     *   interstitials / native ads here.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        appId: String,
        testDeviceIds: List<String> = emptyList(),
        onComplete: Runnable? = null,
    ) {
        if (initialized) {
            onComplete?.run()
            return
        }
        synchronized(this) {
            onComplete?.let { pendingCallbacks.add(it) }
            if (!initializing.compareAndSet(false, true)) return
        }

        // Request configuration must be applied before initialization.
        if (testDeviceIds.isNotEmpty()) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
            )
        }

        val appContext = context.applicationContext
        // The Next-Gen SDK requires initialization off the main thread to avoid ANRs.
        Thread({
            MobileAds.initialize(appContext, InitializationConfig.Builder(appId).build()) {
                initialized = true
                initializing.set(false)
                log("GMA Next-Gen SDK initialized")
                val callbacks: List<Runnable>
                synchronized(this) {
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                mainHandler.post { callbacks.forEach { it.run() } }
            }
        }, "NextGenAds-init").start()
    }

    @JvmStatic
    fun isInitialized(): Boolean = initialized

    /**
     * Runs [action] on the main thread. The Next-Gen SDK delivers ad callbacks on a background
     * thread, so any callback that touches UI (shimmer, views) must be marshalled through here.
     */
    internal fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    internal fun log(message: String) {
        if (loggingEnabled) Log.d(TAG, message)
    }

    internal fun log(message: String, throwable: Throwable?) {
        if (loggingEnabled) Log.w(TAG, message, throwable)
    }
}
