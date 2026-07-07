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
    // Callbacks waiting on the single in-flight load. Concurrent load() callers all get notified,
    // instead of every caller-after-the-first being silently dropped (which would leave a splash
    // gate's loadAndShow waiting forever).
    private val pending = mutableListOf<(Boolean) -> Unit>()

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

    /**
     * Preloads the ad if not already available / in flight. Safe to call from any thread; state is
     * mutated (and [onResult] delivered) on the main thread. Concurrent callers while one load is
     * in flight are parked and all notified with that load's result.
     */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) = NextGenAds.runOnMain {
        if (!NextGenAds.canRequest()) {
            onResult?.invoke(false)
            return@runOnMain
        }
        if (isReady) {
            onResult?.invoke(true)
            return@runOnMain
        }
        onResult?.let { pending.add(it) }
        if (loading) return@runOnMain // a load is already in flight; this caller is parked in `pending`
        loading = true
        // Defer the request until the SDK is ready so preloads issued during app start are queued
        // rather than fired at an uninitialized SDK (which would fail and burn the retry budget).
        NextGenAds.whenInitialized { requestAd() }
    }

    private fun flushPending(loaded: Boolean) {
        val waiters = pending.toList()
        pending.clear()
        waiters.forEach { it(loaded) }
    }

    private fun requestAd() {
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
                        flushPending(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        appOpenAd = null
                        NextGenAds.log("AppOpen failed ($adUnitId): $adError")
                        NextGenAds.dispatchFailedToLoad(AdFormat.APP_OPEN, adUnitId, adError)
                        if (retryCount < maxRetries && !NextGenAds.isRequestPaused()) {
                            // Keep `loading` true and the waiters parked: the load isn't over until
                            // the retry budget is spent. Settling them now would make loadAndShow
                            // give up seconds before the retry succeeds — a lost show.
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({
                                if (NextGenAds.canRequest()) {
                                    requestAd()
                                } else { // breaker tripped / ads disabled during the backoff wait
                                    loading = false
                                    retryCount = 0
                                    flushPending(false)
                                }
                            }, delayMs)
                        } else {
                            loading = false
                            retryCount = 0 // reset budget so a later load() can retry afresh
                            flushPending(false)
                        }
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
            // The activity may have died while the load was in flight — keep the ad cached for the
            // next foreground instead of showing over a dead window.
            if (loaded && isReady && !activity.isFinishing && !activity.isDestroyed) {
                show(activity, onDismiss)
            } else {
                onDismiss()
            }
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
        if (!NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("AppOpen show skipped ($adUnitId): a full-screen ad is already showing")
            onDismiss()
            return false
        }

        // Committed: take ownership so a concurrent show()/load() can't grab the same ad.
        showing = true
        appOpenAd = null
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("AppOpen shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.APP_OPEN, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    showing = false
                    NextGenAds.endFullScreenShow()
                    lastShownElapsed = SystemClock.elapsedRealtime()
                    if (autoReload) load()
                    NextGenAds.dispatchDismissed(AdFormat.APP_OPEN, adUnitId)
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    showing = false
                    NextGenAds.endFullScreenShow()
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

    /**
     * Drops the cached ad and cancels any in-flight load / retry — used when ads are disabled at
     * runtime (e.g. the user goes premium). A currently-showing ad is left to finish.
     */
    fun clear() = NextGenAds.runOnMain {
        if (showing) return@runOnMain
        handler.removeCallbacksAndMessages(null)
        appOpenAd = null
        loading = false
        retryCount = 0
        flushPending(false)
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

    /** Drops every cached app-open ad across all units (e.g. on going premium / low memory). */
    @JvmStatic
    fun clearAll() = helpers.values.forEach { it.clear() }

    /** Convenience: request (if needed) and show [adUnitId] on demand, e.g. from a splash gate. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
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
 * **When it requests and shows:** on a genuine background→foreground transition an already-loaded
 * (non-expired) ad shows immediately. Otherwise a load starts at that moment and the ad is shown
 * only if it lands within [loadTimeoutMs] **and** the app is still in the foreground on an allowed
 * activity — an ad that arrives later is never popped over app content mid-session (policy-safe);
 * it stays cached so the *next* return shows instantly. It does **not** request on install / cold
 * start.
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
     * The window (ms) after a foreground transition during which a just-requested ad may still be
     * shown. An ad that loads after the window (slow network) is **not** shown mid-session — that
     * would pop a full-screen ad at an unexpected moment — but stays cached so the next return
     * shows it instantly. `0` never shows a late-loading ad: the on-return request only warms the
     * cache for the next return.
     */
    @Volatile
    var loadTimeoutMs = 5_000L

    /**
     * Bumped every foreground/background transition; a pending show-on-load from a previous
     * foreground session is invalidated by comparing its captured epoch. Main thread only.
     */
    private var foregroundEpoch = 0

    init {
        application.registerActivityLifecycleCallbacks(this)
        // Lifecycle.addObserver enforces the main thread — marshal so install() is thread-agnostic.
        NextGenAds.runOnMain { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
        // No load here: nothing is requested until the app genuinely returns from the background.
    }

    /** Called by [ProcessLifecycleOwner] when the app enters the foreground. */
    override fun onStart(owner: LifecycleOwner) {
        foregroundEpoch++
        val wasCold = coldStart
        coldStart = false
        if (!enabled || !NextGenAds.canShowAds()) return
        // The first foreground of a cold start is NOT a return-from-background — request nothing.
        if (wasCold && !showOnColdStart) return
        val activity = currentActivity.get() ?: return
        if (isSkipped(activity)) {
            NextGenAds.log("AppOpen skipped on ${activity.javaClass.simpleName}")
            return
        }

        // Cached ad ready: the ideal path — show instantly over the returning activity.
        if (helper.isReady) {
            helper.show(activity)
            return
        }

        // Nothing cached: request now, but only show if the ad lands inside the show window while
        // the app is still foregrounded on an allowed activity. A late ad stays cached for the next
        // return instead of popping over app content mid-session.
        val epoch = foregroundEpoch
        val deadline = SystemClock.elapsedRealtime() + loadTimeoutMs
        helper.load { loaded ->
            if (!loaded || !enabled) return@load
            if (epoch != foregroundEpoch) return@load // app was backgrounded (or re-foregrounded) since
            if (loadTimeoutMs <= 0L || SystemClock.elapsedRealtime() > deadline) {
                NextGenAds.log("AppOpen loaded after the show window — cached for the next return")
                return@load
            }
            val current = currentActivity.get() ?: return@load
            if (current.isFinishing || current.isDestroyed || isSkipped(current)) return@load
            helper.show(current)
        }
    }

    /** Called by [ProcessLifecycleOwner] when the app leaves the foreground. */
    override fun onStop(owner: LifecycleOwner) {
        foregroundEpoch++ // invalidate any pending show-on-load from this session
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
