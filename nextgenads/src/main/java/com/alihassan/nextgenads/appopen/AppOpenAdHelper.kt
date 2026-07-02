package com.alihassan.nextgenads.appopen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.events.AdFormat
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and shows a single app-open ad unit (Next-Gen SDK) with automatic preloading and
 * exponential-backoff retries. App-open ads are the full-screen ads shown while the app is being
 * brought to the foreground.
 *
 * Two app-open specifics are handled here that the other helpers don't need:
 * - **Expiry**: an app-open ad is only valid for [AD_VALIDITY_MS] (4h) after it loads; a stale ad
 *   is dropped and refetched rather than shown.
 * - **No overlap**: [show] refuses to start a second ad while one is already on screen.
 *
 * For the common "show whenever the user returns to the app" behaviour, prefer [AppOpenAdManager],
 * which drives this helper from the process lifecycle. Obtain instances through [AppOpenAds.get] so
 * the same cached ad is reused. Requires [NextGenAds.initialize]; load requests issued earlier are
 * queued until the SDK is ready.
 */
class AppOpenAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var appOpenAd: AppOpenAd? = null
    private var loadElapsed = 0L
    private var loading = false
    private var showing = false
    private var retryCount = 0
    private var lastShownElapsed = 0L

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    /** Minimum gap (ms) between two app-open ads. `0` disables frequency capping. */
    var minIntervalMs = 0L

    /** `true` while an app-open ad is currently on screen. */
    val isShowing: Boolean
        get() = showing

    /** A non-expired ad is cached and ready to show. */
    val isReady: Boolean
        get() = appOpenAd != null && !isExpired

    private val isExpired: Boolean
        get() = SystemClock.elapsedRealtime() - loadElapsed >= AD_VALIDITY_MS

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canShowAds()) {
            onResult?.invoke(false)
            return
        }
        if (isReady) {
            onResult?.invoke(true)
            return
        }
        if (loading) return
        loading = true
        // Defer the request until the SDK is ready so preloads issued during app start are queued
        // rather than fired at an uninitialized SDK (which would fail and burn the retry budget).
        NextGenAds.whenInitialized { requestAd(onResult) }
    }

    private fun requestAd(onResult: ((Boolean) -> Unit)?) {
        NextGenAds.countRequest(AdFormat.APP_OPEN, adUnitId)
        AppOpenAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) {
                    NextGenAds.runOnMain {
                        appOpenAd = ad
                        loadElapsed = SystemClock.elapsedRealtime()
                        loading = false
                        retryCount = 0
                        NextGenAds.log("AppOpen loaded: $adUnitId")
                        NextGenAds.dispatchLoaded(AdFormat.APP_OPEN, adUnitId)
                        onResult?.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        appOpenAd = null
                        loading = false
                        NextGenAds.log("AppOpen failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.APP_OPEN, adUnitId, adError)
                        if (retryCount < maxRetries) {
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({ load() }, delayMs)
                        }
                        onResult?.invoke(false)
                    }
                }
            },
        )
    }

    /**
     * On-demand "request and show": show the cached ad immediately if one is ready, otherwise
     * request one and show it the moment it loads. Ideal for a splash gate.
     *
     * [timeoutMs] bounds the wait: if the ad hasn't loaded by then, [onDismiss] fires so the caller
     * can proceed into the app, and the in-flight load is left to finish for the next opportunity
     * (a late ad is never shown over app content). `0` waits indefinitely for the load result.
     *
     * [onDismiss] is invoked exactly once — after the ad is dismissed, on load failure, on timeout,
     * or synchronously when ads are disabled / one is already showing.
     */
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ) {
        if (!NextGenAds.canShowAds() || showing) {
            onDismiss()
            return
        }
        if (isReady) {
            show(activity, onDismiss)
            return
        }

        // Guard so the timeout and the load result can't both proceed.
        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            NextGenAds.log("AppOpen load timed out ($adUnitId); proceeding")
            onDismiss()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load // timeout already let the caller proceed
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            if (loaded && isReady) show(activity, onDismiss) else onDismiss()
        }
    }

    /**
     * Shows the ad if a fresh one is ready, no other app-open ad is showing and the frequency cap
     * allows it, then preloads the next one.
     *
     * @return `true` if the ad is being shown. When `false`, [onDismiss] has already been invoked
     *   synchronously so the caller can proceed immediately (no ad was available), and a fresh load
     *   has been kicked off.
     */
    @JvmOverloads
    fun show(activity: Activity, onDismiss: () -> Unit = {}): Boolean {
        if (!NextGenAds.canShowAds() || showing) {
            onDismiss()
            return false
        }
        val ad = appOpenAd
        val now = SystemClock.elapsedRealtime()
        val capped = minIntervalMs > 0 && lastShownElapsed > 0 && now - lastShownElapsed < minIntervalMs
        if (ad == null || isExpired || capped) {
            if (isExpired) appOpenAd = null
            onDismiss()
            load() // make sure the next attempt has a fresh ad ready
            return false
        }

        showing = true
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("AppOpen shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.APP_OPEN, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    appOpenAd = null
                    showing = false
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    load()
                    NextGenAds.dispatchDismissed(AdFormat.APP_OPEN, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    appOpenAd = null
                    showing = false
                    NextGenAds.log("AppOpen show failed ($adUnitId): $fullScreenContentError")
                    NextGenAds.dispatchFailedToShow(AdFormat.APP_OPEN, adUnitId, fullScreenContentError)
                    load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {
                NextGenAds.dispatchImpression(AdFormat.APP_OPEN, adUnitId)
            }

            override fun onAdClicked() {
                NextGenAds.dispatchClicked(AdFormat.APP_OPEN, adUnitId)
            }

            override fun onAdPaid(value: AdValue) {
                NextGenAds.dispatchPaid(AdFormat.APP_OPEN, adUnitId, value, ad.getResponseInfo())
            }
        }
        NextGenAds.log("AppOpen show requested: $adUnitId")
        ad.show(activity)
        return true
    }

    companion object {
        /** App-open ads are valid for 4 hours after loading; a stale ad must be refetched. */
        const val AD_VALIDITY_MS = 4 * 60 * 60 * 1000L
    }
}

/** Registry that keeps one [AppOpenAdHelper] per ad unit alive for reuse across screens. */
object AppOpenAds {

    private val helpers = ConcurrentHashMap<String, AppOpenAdHelper>()

    @JvmStatic
    fun get(adUnitId: String): AppOpenAdHelper =
        helpers.getOrPut(adUnitId) { AppOpenAdHelper(adUnitId) }

    /** Convenience: preload an ad unit. */
    @JvmStatic
    fun preload(adUnitId: String) = get(adUnitId).load()

    /** Convenience: request (if needed) and show [adUnitId] on demand, e.g. from a splash gate. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        adUnitId: String,
        activity: Activity,
        timeoutMs: Long = 0L,
        onDismiss: () -> Unit = {},
    ) = get(adUnitId).loadAndShow(activity, timeoutMs, onDismiss)
}

/**
 * Drop-in manager that shows an app-open ad each time the user brings the app back to the
 * foreground, keeping the ad warm in between. Wire it once, typically from `Application.onCreate`
 * **after** [NextGenAds.initialize]:
 *
 * ```
 * AppOpenAdManager.install(this, "ca-app-pub-…/appopen")
 * ```
 *
 * The first foreground after a cold start is skipped by default ([showOnColdStart]) — at that point
 * the ad usually isn't loaded yet and showing one over your splash hurts UX. Set [enabled] to
 * `false` to pause auto-showing (e.g. while a different full-screen flow is running); the premium /
 * kill-switch state in [NextGenAds] is always honoured regardless.
 */
class AppOpenAdManager private constructor(
    application: Application,
    adUnitId: String,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private val helper = AppOpenAds.get(adUnitId)
    // Held weakly: this manager lives for the whole process, so a hard Activity reference would
    // leak it. Cleared on pause too, but the weak ref is the real safety net.
    private var currentActivity: WeakReference<Activity> = WeakReference(null)
    private var coldStart = true

    /** Auto-show on foreground. Set `false` to suspend without tearing the manager down. */
    @Volatile
    var enabled = true

    /** Whether to show an ad on the very first foreground (cold start). Off by default. */
    @Volatile
    var showOnColdStart = false

    init {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        helper.load()
    }

    /** Called by [ProcessLifecycleOwner] when the app enters the foreground. */
    override fun onStart(owner: LifecycleOwner) {
        val wasCold = coldStart
        coldStart = false
        if (!enabled) return
        if (wasCold && !showOnColdStart) {
            helper.load() // warm up for the next foreground instead
            return
        }
        val activity = currentActivity.get()
        if (activity != null) helper.show(activity) else helper.load()
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity.get() === activity) currentActivity = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        @Volatile
        private var instance: AppOpenAdManager? = null

        /**
         * Installs (once) the foreground auto-show manager for [adUnitId]. Safe to call repeatedly;
         * the first call wins and subsequent calls return the existing manager.
         */
        @JvmStatic
        fun install(application: Application, adUnitId: String): AppOpenAdManager =
            instance ?: synchronized(this) {
                instance ?: AppOpenAdManager(application, adUnitId).also { instance = it }
            }

        /** The installed manager, or `null` if [install] hasn't been called. */
        @JvmStatic
        fun get(): AppOpenAdManager? = instance
    }
}
