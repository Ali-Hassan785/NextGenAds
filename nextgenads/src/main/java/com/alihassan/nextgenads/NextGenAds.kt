package com.alihassan.nextgenads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import java.util.concurrent.CopyOnWriteArrayList
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

    /**
     * Work queued before initialization finished: `initialize`'s `onComplete` callbacks plus any
     * load/preload requests routed through [whenInitialized]. Drained once, in order, on the main
     * thread when the SDK reports ready. Guarded by `synchronized(this)`.
     */
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
     * Runs [action] on the main thread once the SDK is initialized.
     *
     * If initialization has already completed the action is posted to run on the next main-loop
     * tick; otherwise it is queued and replayed — in submission order — when initialization
     * finishes. This lets helpers accept preload/load requests issued during app start (before
     * [initialize] has completed) without dropping them or hammering the uninitialized SDK, where
     * requests would fail and waste retry budget.
     *
     * Note: if [initialize] is never called, queued actions never run.
     */
    @JvmStatic
    fun whenInitialized(action: Runnable) {
        if (!initialized) {
            synchronized(this) {
                // Re-check under the lock: initialize() may have flushed the queue between the
                // volatile read above and acquiring the monitor. Without this, a late enqueue
                // could sit in the queue forever.
                if (!initialized) {
                    pendingCallbacks.add(action)
                    return
                }
            }
        }
        mainHandler.post { action.run() }
    }

    // ---------------------------------------------------------------------------------------------
    // Ad events
    //
    // A single, app-wide stream of every ad lifecycle event (load / show / dismiss / impression /
    // click / paid-revenue / reward) across all formats. Helpers call the `dispatch*` functions;
    // each is marshalled to the main thread and delivered to every registered listener, with one
    // listener's exception isolated so it can't suppress the rest.
    // ---------------------------------------------------------------------------------------------

    private val eventListeners = CopyOnWriteArrayList<AdEventListener>()

    /**
     * Registers an [AdEventListener] to receive every ad event from every format. Typically called
     * once from `Application.onCreate`. Re-registering the same instance is a no-op.
     */
    @JvmStatic
    fun registerEventListener(listener: AdEventListener) {
        if (!eventListeners.contains(listener)) eventListeners.add(listener)
    }

    /** Removes a previously [registerEventListener]ed listener. */
    @JvmStatic
    fun unregisterEventListener(listener: AdEventListener) {
        eventListeners.remove(listener)
    }

    /** Delivers [block] to every registered listener on the main thread, isolating failures. */
    private fun dispatch(block: (AdEventListener) -> Unit) {
        if (eventListeners.isEmpty()) return
        runOnMain {
            for (listener in eventListeners) {
                try {
                    block(listener)
                } catch (t: Throwable) {
                    log("AdEventListener threw", t)
                }
            }
        }
    }

    internal fun dispatchLoaded(format: AdFormat, adUnitId: String) =
        dispatch { it.onAdLoaded(format, adUnitId) }

    internal fun dispatchFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) =
        dispatch { it.onAdFailedToLoad(format, adUnitId, error) }

    internal fun dispatchShown(format: AdFormat, adUnitId: String) =
        dispatch { it.onAdShown(format, adUnitId) }

    internal fun dispatchFailedToShow(
        format: AdFormat,
        adUnitId: String,
        error: FullScreenContentError,
    ) = dispatch { it.onAdFailedToShow(format, adUnitId, error) }

    internal fun dispatchDismissed(format: AdFormat, adUnitId: String) =
        dispatch { it.onAdDismissed(format, adUnitId) }

    internal fun dispatchImpression(format: AdFormat, adUnitId: String) =
        dispatch { it.onAdImpression(format, adUnitId) }

    internal fun dispatchClicked(format: AdFormat, adUnitId: String) =
        dispatch { it.onAdClicked(format, adUnitId) }

    internal fun dispatchPaid(
        format: AdFormat,
        adUnitId: String,
        value: AdValue,
        responseInfo: ResponseInfo? = null,
    ) = dispatch { it.onAdPaid(format, adUnitId, value, responseInfo) }

    internal fun dispatchReward(format: AdFormat, adUnitId: String, reward: RewardItem) =
        dispatch { it.onUserEarnedReward(format, adUnitId, reward) }

    /**
     * Runs [action] on the main thread. The Next-Gen SDK delivers ad callbacks on a background
     * thread, so any callback that touches UI (shimmer, views) must be marshalled through here.
     */
    internal fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    // ---------------------------------------------------------------------------------------------
    // Request counting (testing / diagnostics)
    //
    // Cumulative count of ad *requests* issued per ad unit for this process. Helpers call
    // [countRequest] at the moment they fire a request to the SDK; the running total is logged so a
    // tester can see how many times a unit has been requested (and spot duplicate / runaway loads).
    // ---------------------------------------------------------------------------------------------

    private val requestCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Increments and returns the cumulative request count for [adUnitId]. */
    internal fun countRequest(format: AdFormat, adUnitId: String): Int {
        val count = requestCounts.merge(adUnitId, 1, Int::plus) ?: 1
        log("$format requesting: $adUnitId (request #$count for this unit)")
        return count
    }

    /** Current cumulative request count for [adUnitId] (0 if none issued yet). */
    @JvmStatic
    fun requestCount(adUnitId: String): Int = requestCounts[adUnitId] ?: 0

    /** Resets all request counters (e.g. between test runs). */
    @JvmStatic
    fun resetRequestCounts() = requestCounts.clear()

    internal fun log(message: String) {
        if (loggingEnabled) Log.d(TAG, message)
    }

    internal fun log(message: String, throwable: Throwable?) {
        if (loggingEnabled) Log.w(TAG, message, throwable)
    }
}
