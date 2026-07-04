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

    /**
     * When `true`, the helper automatically requests the next ad after one is shown/dismissed (and
     * when [show] finds none ready). Default `false` so a single [show] / [loadAndShow] issues a
     * **single** request — warm the next one explicitly via [load] / [AppOpenAds.preload]. This
     * prevents the "requested twice per show" behaviour. [AppOpenAdManager] re-warms itself via the
     * dismiss callback, so it doesn't need this on.
     */
    var autoReload = false

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
        if (!NextGenAds.canRequest()) {
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
            if (autoReload) load() // opt-in: make the next attempt have a fresh ad ready
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
                    if (autoReload) load()
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
                    if (autoReload) load()
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
 * Marker interface: an [Activity] implementing it is never covered by the auto-shown app-open ad.
 * Use it for splash, onboarding, paywall or in-app-purchase screens where a full-screen ad would
 * hurt UX (or violate policy):
 *
 * ```
 * class SplashActivity : AppCompatActivity(), HideAppOpenAd { … }
 * ```
 *
 * Alternative for activities you can't edit (e.g. from another library): register the class with
 * [AppOpenAdManager.skipOn].
 */
interface HideAppOpenAd

/**
 * Drop-in manager that shows an app-open ad each time the user brings the app back to the
 * foreground. Wire it once, typically from `Application.onCreate` **after** [NextGenAds.initialize]:
 *
 * ```
 * AppOpenAdManager.install(this, "ca-app-pub-…/appopen")
 *     .skipOn(SplashActivity::class.java, PaywallActivity::class.java)
 * ```
 *
 * **When it requests:** only on a genuine background→foreground transition, and only on demand at
 * that moment (via the helper's `loadAndShow`). It does **not** request on install / cold start, and
 * it does **not** pre-warm a "next" ad after showing one — so no ad is ever requested before the app
 * has actually been backgrounded, and a request made on return is always coupled to a show (it waits
 * for the load; see [loadTimeoutMs]). The trade-off is that the ad loads at the moment of return
 * rather than being instantly ready.
 *
 * The first foreground of a cold start is skipped by default ([showOnColdStart]) since it isn't a
 * return-from-background. Set [enabled] to `false` to pause auto-showing (e.g. while a different
 * full-screen flow is running); the premium / kill-switch state in [NextGenAds] is always honoured.
 *
 * Per-activity exclusion: activities implementing [HideAppOpenAd] or registered via [skipOn] are
 * skipped — nothing is requested or shown on them. Ad activities of the Mobile Ads SDK itself are
 * always skipped, so an app-open can never stack on top of another full-screen ad.
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
    // Activity classes excluded from auto-show (exact class match). ConcurrentHashMap-backed so
    // skipOn/allowOn can be called from any thread while onStart reads on main.
    private val skipped = ConcurrentHashMap.newKeySet<Class<out Activity>>()

    /** Auto-show on foreground. Set `false` to suspend without tearing the manager down. */
    @Volatile
    var enabled = true

    /** Whether to show an ad on the very first foreground (cold start). Off by default. */
    @Volatile
    var showOnColdStart = false

    /**
     * Excludes [activities] from the auto-shown app-open ad — foregrounding onto any of them keeps
     * the cached ad for the next allowed screen instead of showing it. Returns `this` for chaining
     * off [install]. For activities you own, implementing [HideAppOpenAd] works without
     * registration.
     */
    fun skipOn(vararg activities: Class<out Activity>): AppOpenAdManager = apply {
        skipped.addAll(activities)
    }

    /** Removes [activities] from the [skipOn] exclusion list. */
    fun allowOn(vararg activities: Class<out Activity>): AppOpenAdManager = apply {
        activities.forEach { skipped.remove(it) }
    }

    /** `true` when the auto-show must not cover [activity]. */
    private fun isSkipped(activity: Activity): Boolean =
        activity is HideAppOpenAd ||
            activity.javaClass in skipped ||
            // Never stack on top of another full-screen ad: the Mobile Ads SDK hosts interstitial /
            // rewarded / app-open content in its own activities.
            activity.javaClass.name.startsWith("com.google.android.libraries.ads") ||
            activity.javaClass.name.startsWith("com.google.android.gms.ads")

    /**
     * Upper bound (ms) on the on-foreground load before proceeding without showing. Default `0`
     * waits for the load result (itself bounded by the helper's retry budget) so a request made on
     * return actually results in a show — a positive value risks requesting an ad that then times
     * out unshown. Set a bound only if you'd rather skip the ad than wait on a slow network.
     */
    @Volatile
    var loadTimeoutMs = 0L

    init {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // No load here: nothing is requested until the app genuinely returns from the background.
    }

    /** Called by [ProcessLifecycleOwner] when the app enters the foreground. */
    override fun onStart(owner: LifecycleOwner) {
        val wasCold = coldStart
        coldStart = false
        if (!enabled) return
        // The first foreground of a cold start is NOT a return-from-background — request nothing.
        if (wasCold && !showOnColdStart) return
        val activity = currentActivity.get() ?: return
        if (isSkipped(activity)) {
            NextGenAds.log("AppOpen skipped on ${activity.javaClass.simpleName}")
            return
        }
        // Genuine background→foreground: request and show on demand. No pre-warm and no post-show
        // reload (autoReload stays off), so a request only ever happens here — at the moment of a
        // real return — and never for a "next" ad after one is shown.
        helper.loadAndShow(activity, loadTimeoutMs)
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
