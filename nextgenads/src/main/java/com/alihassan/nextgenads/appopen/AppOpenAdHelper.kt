package com.alihassan.nextgenads.appopen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.R
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

/** Which full-screen cover the app-open flow raises to bridge the show→render gap. */
enum class AppOpenCoverStyle {
    /** Branded "Welcome back" cover — app icon + [AppOpenAdHelper.welcomeTitle]. The default. */
    WELCOME,

    /**
     * Plain "Loading ad…" cover — the same minimal spinner the interstitial loader uses, with no
     * branding. Use it on a splash (see `SplashAppOpenAd`), where a branded "Welcome back" would be
     * out of place and only a loading indicator is wanted.
     */
    LOADING,
}

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

    /**
     * Optional artificial dwell (ms) on a "Showing ad…" cover before an already-available ad opens,
     * so it doesn't pop in abruptly. Defaults to `0` — a ready ad shows **instantly** (smoothest).
     * This is separate from the "Loading ad…" cover [loadAndShow] shows while genuinely fetching an
     * ad, which always appears (it hides real network latency, not an artificial delay).
     */
    var loadingOverlayMs = 0L

    /**
     * Title on the "Welcome back" cover shown during the app-open flow. Set from the host app to
     * localise / rebrand it (e.g. `AppOpenAds.get(unit).welcomeTitle = "Good to see you"`). `null`
     * (default) falls back to the `ngad_welcome_title` string resource — which the app can also
     * override by redeclaring that string.
     */
    var welcomeTitle: CharSequence? = null

    /**
     * Subtitle shown while an app-open ad is being fetched on demand. `null` (default) falls back to
     * the `ngad_welcome_loading` string resource.
     */
    var loadingText: CharSequence? = null

    /**
     * Subtitle shown during the brief show→render bridge right before the ad opens. `null` (default)
     * falls back to the `ngad_welcome_showing` string resource.
     */
    var showingText: CharSequence? = null

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
     *
     * [coverStyle] picks the full-screen cover used while fetching and bridging to the ad: the branded
     * [AppOpenCoverStyle.WELCOME] (default) or the plain [AppOpenCoverStyle.LOADING] spinner.
     *
     * [canShow] is re-checked the instant the ad lands (before it is shown); if it returns `false`
     * the ad is kept cached and [onDismiss] fires instead. The auto-show manager uses this to bail
     * when the app was backgrounded during the fetch, so a late ad never pops over app content.
     */
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        timeoutMs: Long = 0L,
        coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
        canShow: () -> Boolean = { true },
        onDismiss: () -> Unit = {},
    ) {
        if (!NextGenAds.canShowAds() || showing) {
            onDismiss()
            return
        }
        if (isReady) {
            show(activity, onDismiss, coverStyle = coverStyle)
            return
        }

        // Always cover the genuine on-demand fetch — this isn't an artificial delay, it hides real
        // network latency so the user isn't left on a frozen screen. show() reveals the ad the moment
        // it loads (flipping the caption to "Just a moment…") and drops the cover when it renders. The
        // [coverStyle] picks the branded Welcome-back cover or the plain "Loading ad…" one.
        val overlay = showLoadingOverlay(activity, activity.welcomeLoadingCaption(), coverStyle)

        // Guard so the timeout and the load result can't both proceed.
        var settled = false
        val timeoutRunnable = Runnable {
            if (settled) return@Runnable
            settled = true
            overlay?.let { removeLoadingOverlay(it) }
            NextGenAds.log("AppOpen load timed out ($adUnitId); proceeding")
            onDismiss()
        }
        if (timeoutMs > 0) handler.postDelayed(timeoutRunnable, timeoutMs)

        load { loaded ->
            if (settled) return@load // timeout already let the caller proceed
            settled = true
            handler.removeCallbacks(timeoutRunnable)
            // The activity may have died — or the app may have been backgrounded ([canShow]) — while
            // the load was in flight; keep the ad cached for the next foreground rather than showing
            // over a dead window / app content.
            if (loaded && isReady && !activity.isFinishing && !activity.isDestroyed && canShow()) {
                show(activity, onDismiss, overlay)
            } else {
                overlay?.let { removeLoadingOverlay(it) }
                onDismiss()
            }
        }
    }

    /**
     * Shows the ad if a fresh one is ready, no other app-open ad is showing and the frequency cap
     * allows it, then preloads the next one.
     *
     * @param preloadedOverlay a full-screen loader already on screen (e.g. the "Loading ad…" cover
     *   raised by [loadAndShow] during the fetch). When non-null it is reused — its text is flipped
     *   to "Showing ad…" for the interlude — so there's no remove/re-add flicker between phases.
     * @param showCover when `true` (default) a full-screen cover bridges the show→render gap. Pass
     *   `false` when the caller's own screen already covers that gap and any cover would be redundant.
     *   Ignored when [preloadedOverlay] is supplied (that cover is always reused).
     * @param coverStyle which cover to raise when [showCover] is on: the branded [AppOpenCoverStyle.WELCOME]
     *   (default) or the plain [AppOpenCoverStyle.LOADING] spinner — e.g. a splash gate uses `LOADING`
     *   so a "Welcome back" cover never appears on the splash.
     * @return `true` if the ad is being shown. When `false`, [onDismiss] has already been invoked
     *   synchronously so the caller can proceed immediately (no ad was available), and a fresh load
     *   has been kicked off.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        onDismiss: () -> Unit = {},
        preloadedOverlay: View? = null,
        showCover: Boolean = true,
        coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
    ): Boolean {
        if (!NextGenAds.canShowAds() || showing) {
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onDismiss()
            return false
        }
        val ad = appOpenAd
        val now = SystemClock.elapsedRealtime()
        val capped = minIntervalMs > 0 && lastShownElapsed > 0 && now - lastShownElapsed < minIntervalMs
        if (ad == null || isExpired || capped) {
            if (isExpired) appOpenAd = null
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onDismiss()
            if (autoReload) load() // opt-in: make the next attempt have a fresh ad ready
            return false
        }
        if (!NextGenAds.tryBeginFullScreenShow()) {
            // Another full-screen ad (any format) is on screen — never stack. Ad stays cached.
            NextGenAds.log("AppOpen show skipped ($adUnitId): a full-screen ad is already showing")
            preloadedOverlay?.let { removeLoadingOverlay(it) }
            onDismiss()
            return false
        }

        // Committed: take ownership so a concurrent show()/load() can't grab the same ad.
        showing = true
        appOpenAd = null

        var overlay: View? = preloadedOverlay
        fun dismissOverlay() {
            overlay?.let { removeLoadingOverlay(it) }
            overlay = null
        }

        // The show never happened (activity/app went away during the interlude): put the ad back for
        // the next return and free the full-screen slot.
        fun abortShow() {
            showing = false
            appOpenAd = ad
            NextGenAds.endFullScreenShow()
        }

        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.runOnMain { dismissOverlay() }
                NextGenAds.log("AppOpen shown: $adUnitId")
                NextGenAds.dispatchShown(AdFormat.APP_OPEN, adUnitId)
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    dismissOverlay()
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
                    dismissOverlay()
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
        if (loadingOverlayMs <= 0) {
            // No artificial dwell, but still bridge the show→render gap with the cover so the screen
            // isn't left frozen while the SDK brings the ad up (app-open render is ~0.5–1s): reuse a
            // carried-over cover (from a fetch) or raise one now, flip it to "Just a moment…", show
            // immediately, and drop it the moment the ad renders (callbacks above). A caller that owns
            // the screen (splash) can opt out via showCover=false, or pick the plain LOADING cover so
            // no "Welcome back" cover appears.
            overlay = overlay?.also { setOverlayText(it, activity.welcomeShowingCaption()) }
                ?: if (showCover) showLoadingOverlay(activity, activity.welcomeShowingCaption(), coverStyle) else null
            ad.show(activity)
            return true
        }

        // Full-screen "Showing ad…" interlude: cover the screen for loadingOverlayMs, then open the
        // ad. The overlay is a view attached to the activity's own decor (not a separate Dialog
        // window) so it fills the whole screen and fades in smoothly with no window-handoff flash.
        // It stays up until the ad actually renders (removed in the shown/failed callbacks above) so
        // the underlying screen never shows through. Reuse loadAndShow's Welcome-back cover when it
        // handed one in (flip its subtitle) so there's no flicker between the two phases. A caller
        // that owns the screen (splash) can opt out via showCover=false, or pick the plain LOADING cover.
        overlay = overlay?.also { setOverlayText(it, activity.welcomeShowingCaption()) }
            ?: if (showCover) showLoadingOverlay(activity, activity.welcomeShowingCaption(), coverStyle) else null
        handler.postDelayed({
            val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
            if (activity.isFinishing || activity.isDestroyed || !appInForeground) {
                // The user left (home button / activity died) during the interlude — showing now
                // would pop an ad at an unexpected moment. Keep it cached for the next return.
                dismissOverlay()
                abortShow()
                onDismiss()
                return@postDelayed
            }
            ad.show(activity)
        }, loadingOverlayMs)
        return true
    }

    /** The fetch subtitle: the host-set [loadingText], or the `ngad_welcome_loading` resource. */
    private fun Activity.welcomeLoadingCaption(): CharSequence =
        loadingText ?: getString(R.string.ngad_welcome_loading)

    /** The pre-show subtitle: the host-set [showingText], or the `ngad_welcome_showing` resource. */
    private fun Activity.welcomeShowingCaption(): CharSequence =
        showingText ?: getString(R.string.ngad_welcome_showing)

    /**
     * Attaches a full-screen cover to the activity's decor view and fades it in. For
     * [AppOpenCoverStyle.WELCOME] the cover is branded with the host app's own icon + the
     * [welcomeTitle]; for [AppOpenCoverStyle.LOADING] it's the plain "Loading ad…" spinner with no
     * branding. [caption] sets the state subtitle (e.g. "Getting things ready…" → "Just a moment…").
     * Returns the attached view (or `null` if it couldn't be attached), to be passed to
     * [removeLoadingOverlay] once the ad renders.
     */
    private fun showLoadingOverlay(
        activity: Activity,
        caption: CharSequence,
        style: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
    ): View? = runCatching {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val layoutRes = when (style) {
            AppOpenCoverStyle.WELCOME -> R.layout.ngad_view_appopen_welcome
            AppOpenCoverStyle.LOADING -> R.layout.ngad_view_ad_loading
        }
        val view = LayoutInflater.from(activity).inflate(layoutRes, root, false)
        setOverlayText(view, caption)
        // Branding is Welcome-only; the plain LOADING cover is just a spinner + caption.
        if (style == AppOpenCoverStyle.WELCOME) {
            view.findViewById<TextView?>(R.id.ngad_appopen_title)?.text =
                welcomeTitle ?: activity.getString(R.string.ngad_welcome_title)
            // Brand the cover with the host app's launcher icon so it reads as the app itself, not an ad.
            runCatching {
                val pm = activity.packageManager
                view.findViewById<ImageView?>(R.id.ngad_appopen_icon)
                    ?.setImageDrawable(pm.getApplicationIcon(activity.applicationInfo))
            }
        }
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.FILL,
        )
        // Sit above everything (incl. system-bar backgrounds) and swallow touches to the screen below.
        view.isClickable = true
        view.elevation = 1_000_000f
        view.alpha = 0f
        root.addView(view)
        view.bringToFront()
        view.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start()
        // Gentle rise+settle on the brand block so the cover feels premium, not a hard cut. Only the
        // Welcome layout has this block; on the plain LOADING cover the lookup is null (no-op).
        view.findViewById<View?>(R.id.ngad_appopen_content)?.let { content ->
            content.translationY = 16f * activity.resources.displayMetrics.density
            content.animate()
                .translationY(0f)
                .setDuration(WELCOME_RISE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        view
    }.getOrNull()

    /**
     * Updates the state subtitle on a cover raised by [showLoadingOverlay] (e.g. loading → showing).
     * Works for both cover layouts — the Welcome cover's subtitle and the plain loader's caption.
     */
    private fun setOverlayText(overlay: View, caption: CharSequence) {
        val label = overlay.findViewById<TextView?>(R.id.ngad_appopen_subtitle)
            ?: overlay.findViewById<TextView?>(R.id.ngad_ad_loading_text)
        label?.text = caption
    }

    private fun removeLoadingOverlay(view: View) {
        if (view.parent !is ViewGroup) return
        view.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
        }.start()
        // Guard against the end-action never firing (e.g. detached window): hard-remove shortly after.
        view.postDelayed({ (view.parent as? ViewGroup)?.removeView(view) }, OVERLAY_FADE_MS + 50)
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

        /** Fade duration (ms) for the loading overlay's enter/exit animation. */
        private const val OVERLAY_FADE_MS = 180L

        /** Duration (ms) of the Welcome-back brand block's rise-and-settle entrance. */
        private const val WELCOME_RISE_MS = 420L
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
    ) = get(adUnitId).loadAndShow(activity, timeoutMs, onDismiss = onDismiss)
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
     * Which full-screen cover to raise while showing the auto app-open: the branded
     * [AppOpenCoverStyle.WELCOME] (default) or the plain [AppOpenCoverStyle.LOADING] spinner. Set it
     * to `LOADING` if you don't want the "Welcome back" cover on foreground returns.
     */
    @Volatile
    var coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME

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

    /**
     * The [foregroundEpoch] for which an app-open show is armed but not yet placed, because the
     * foreground activity at [onStart] was transient (null mid-transition, or a finishing splash on
     * a warm relaunch). It is retried on each [onActivityResumed] / [onActivityStarted] and fires
     * once a real, non-skipped activity is up — so a warm return through a self-finishing splash
     * still shows the ad over the actual content instead of skipping it or rendering behind the
     * finishing screen. `-1` means nothing is pending. Main thread only.
     */
    private var pendingShowEpoch = -1

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
        pendingShowEpoch = -1
        if (!enabled || !NextGenAds.canShowAds()) return
        // The first foreground of a cold start is NOT a return-from-background — request nothing.
        if (wasCold && !showOnColdStart) return
        // Arm the show for this foreground session and try now. If the foreground activity is still
        // transient (null mid-transition, or a finishing splash on a warm relaunch), the attempt is
        // deferred and retried from onActivityResumed / onActivityStarted, so the ad lands over the
        // real content rather than being skipped or drawn behind the finishing screen.
        pendingShowEpoch = foregroundEpoch
        tryPendingShow()
    }

    /**
     * Places the armed foreground show once a real activity is up. No-ops if nothing is pending for
     * the current session, or while the resolved activity is transient (absent / finishing /
     * destroyed) — leaving it pending for the next resume. A genuinely displayed skipped screen
     * (e.g. a paywall the user opened) consumes the pending show without displaying an ad.
     */
    private fun tryPendingShow() {
        if (pendingShowEpoch != foregroundEpoch) return
        if (!enabled || !NextGenAds.canShowAds()) return
        val activity = currentActivity.get()
        // Not settled yet — wait for the next onActivityResumed / onActivityStarted.
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        if (isSkipped(activity)) {
            // A real, non-finishing skipped screen: respect the skip and don't keep waiting.
            pendingShowEpoch = -1
            NextGenAds.log("AppOpen skipped on ${activity.javaClass.simpleName}")
            return
        }
        pendingShowEpoch = -1
        showOrLoad(activity)
    }

    /** Shows a cached ad instantly over [activity], or requests one behind the loading cover. */
    private fun showOrLoad(activity: Activity) {
        // Cached ad ready: the ideal path — show instantly over the returning activity.
        if (helper.isReady) {
            helper.show(activity, coverStyle = coverStyle)
            return
        }
        // loadTimeoutMs == 0 means "warm the cache only, never show a late ad" — request without a
        // cover and don't show on this return.
        if (loadTimeoutMs <= 0L) {
            helper.load()
            return
        }
        // Nothing cached: request now behind a "Loading ad…" cover, but only show if the ad lands
        // inside the show window while the app is still foregrounded on an allowed activity. A late
        // ad (past loadTimeoutMs) drops the cover and stays cached for the next return instead of
        // popping over app content mid-session — loadAndShow handles both the cover and that timeout.
        val epoch = foregroundEpoch
        helper.loadAndShow(
            activity = activity,
            timeoutMs = loadTimeoutMs,
            coverStyle = coverStyle,
            canShow = {
                // Re-checked the instant the ad lands: still enabled, same foreground session, and the
                // current activity is a live, non-skipped screen.
                val current = currentActivity.get()
                enabled && epoch == foregroundEpoch && current != null &&
                    !current.isFinishing && !current.isDestroyed && !isSkipped(current)
            },
        )
    }

    /** Called by [ProcessLifecycleOwner] when the app leaves the foreground. */
    override fun onStop(owner: LifecycleOwner) {
        foregroundEpoch++ // invalidate any pending show-on-load from this session
        pendingShowEpoch = -1
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        tryPendingShow()
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = WeakReference(activity)
        tryPendingShow()
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
