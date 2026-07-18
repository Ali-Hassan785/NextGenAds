package com.alihassn.nextgenSample

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alihassan.nextgenads.AdType
import com.alihassan.nextgenads.BannerNativeView
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.banner.BannerAdHelper
import com.alihassan.nextgenads.banner.BannerCollapsible
import com.alihassan.nextgenads.banner.BannerSize
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.alihassan.nextgenads.events.ShowRateTracker
import com.alihassan.nextgenads.interstitial.Interstitials
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.nativead.NativeAdPreloader
import com.alihassan.nextgenads.rewarded.RewardedAds
import com.alihassan.nextgenads.rewardedinterstitial.RewardedInterstitials
import com.alihassan.nextgenads.nativead.NativeTemplate
import com.alihassan.nextgenads.update.InAppUpdateManager
import com.alihassan.nextgenads.update.UpdateType

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var statsTable: TableLayout
    private lateinit var bannerContainer: FrameLayout
    private lateinit var nativeAdView: BannerNativeView
    private lateinit var templateGroup: ChipGroup
    private lateinit var adTypeGroup: RadioGroup
    private lateinit var bannerSizeGroup: ChipGroup
    private lateinit var collapsibleCheck: CheckBox

    /** Total clicks on the counter-interstitial button — drives the "1st, then every 4th" gate. */
    private var counterClicks = 0

    /** Demo tally of rewards granted via the rewarded flow (a real app would credit the user). */
    private var rewardBalance = 0

    /** Index of the show-rate (USE) column in the stats table — the cell we color by threshold. */
    private val useColumnIndex = ShowRateTracker.COLUMNS.indexOf("USE")

    /** Google Play in-app updater; created in [onCreate] and driven by the activity lifecycle. */
    private lateinit var appUpdater: InAppUpdateManager

    /** Refreshes the bottom stats panel whenever any ad event moves the show-rate counters. */
    private val statsListener = object : AdEventListener {
        override fun onAdRequested(format: AdFormat, adUnitId: String) = refreshStats()
        override fun onAdLoaded(format: AdFormat, adUnitId: String) = refreshStats()
        override fun onAdFailedToLoad(
            format: AdFormat,
            adUnitId: String,
            error: com.google.android.libraries.ads.mobile.sdk.common.LoadAdError,
        ) = refreshStats()
        override fun onAdShown(format: AdFormat, adUnitId: String) = refreshStats()
        override fun onAdFailedToShow(
            format: AdFormat,
            adUnitId: String,
            error: com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError,
        ) = refreshStats()
        override fun onAdImpression(format: AdFormat, adUnitId: String) = refreshStats()
        override fun onAdClicked(format: AdFormat, adUnitId: String) = refreshStats()
        override fun onAdDismissed(format: AdFormat, adUnitId: String) = refreshStats()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.tvStatus)
        // Long-press the status text to dump the live fill/show-rate report to logcat.
        status.setOnLongClickListener {
            SampleApp.showRate.logReport()
            true
        }

        setupThemeToggle()

        // Bottom-pinned live stats table. Refreshes on every ad event; tap to reset the counters.
        statsTable = findViewById(R.id.statsTable)
        statsTable.setOnClickListener {
            SampleApp.showRate.reset()
            refreshStats()
        }
        refreshStats()
        NextGenAds.registerEventListener(statsListener)

        bannerContainer = findViewById(R.id.bannerContainer)
        nativeAdView = findViewById(R.id.nativeAdView)
        templateGroup = findViewById(R.id.rgTemplate)
        adTypeGroup = findViewById(R.id.rgAdType)
        bannerSizeGroup = findViewById(R.id.rgBannerSize)

        // Premium toggle — demonstrates the runtime purge (all cached ads dropped, shown ads hidden).
        findViewById<Button>(R.id.btnPremiumToggle).setOnClickListener { togglePremium(it as Button) }

        // Jump to the Jetpack Compose screen that shows every ad format via :nextgenadscompose.
        findViewById<Button>(R.id.btnComposeAds).setOnClickListener {
            startActivity(Intent(this, ComposeAdsActivity::class.java))
        }

        // 2. Banner.
        collapsibleCheck = findViewById(R.id.cbCollapsible)
        findViewById<Button>(R.id.btnPreloadBanner).setOnClickListener { warmBannerCache() }
        findViewById<Button>(R.id.btnShowBanner).setOnClickListener { loadAndDisplayBanner() }
        findViewById<Button>(R.id.btnLoadShowBanner).setOnClickListener { loadAndDisplayBanner() }

        // 3. Native.
        findViewById<Button>(R.id.btnPreloadNative).setOnClickListener { warmNativeAdCache() }
        findViewById<Button>(R.id.btnShowNative).setOnClickListener { loadAndDisplayNativeAd() }
        findViewById<Button>(R.id.btnLoadShowNative).setOnClickListener { loadAndDisplayNativeAd() }
        findViewById<Button>(R.id.btnCustomNative).setOnClickListener {
            startActivity(Intent(this, CustomNativeActivity::class.java))
        }
        findViewById<Button>(R.id.btnPreloadOpenScreen).setOnClickListener { preloadNativeAndOpenScreen() }

        // 4. Interstitial.
        // The interstitial loading-cover copy is customisable from the host app (localise / rebrand).
        // Leave a field unset to keep the module default, or override the ngad_ad_* string resources.
        Interstitials.get(AdUnits.INTERSTITIAL).apply {
            loadingText = getString(R.string.interstitial_loading)
            showingText = getString(R.string.interstitial_showing)
        }
        findViewById<Button>(R.id.btnPreloadInterstitial).setOnClickListener { warmInterstitialCache() }
        findViewById<Button>(R.id.btnShowInterstitial).setOnClickListener { showCachedInterstitial() }
        findViewById<Button>(R.id.btnLoadShowInterstitial).setOnClickListener { loadAndPresentInterstitial() }
        findViewById<Button>(R.id.btnCounterInterstitial).setOnClickListener { presentCounterGatedInterstitial() }

        // 5. Rewarded.
        findViewById<Button>(R.id.btnPreloadRewarded).setOnClickListener { warmRewardedCache() }
        findViewById<Button>(R.id.btnShowRewarded).setOnClickListener { confirmThenPresentRewarded() }

        // 6. Rewarded interstitial.
        findViewById<Button>(R.id.btnPreloadRewardedInt).setOnClickListener { warmRewardedInterstitialCache() }
        findViewById<Button>(R.id.btnShowRewardedInt).setOnClickListener { confirmThenPresentRewardedInterstitial() }

        // 7. App open.
        findViewById<Button>(R.id.btnPreloadAppOpen).setOnClickListener { warmAppOpenCache() }
        findViewById<Button>(R.id.btnShowAppOpen).setOnClickListener { showCachedAppOpenAd() }

        // Per-format on/off switches: ON = that ad may load & show, OFF = it won't.
        setupAdToggles()

        // 8. In-app update — check Google Play for a newer build (independent of the ads SDK).
        setupInAppUpdate()

        // Gather consent and initialize automatically as soon as the screen opens, so ads are ready
        // without a manual tap (there is no consent button — this is the only trigger).
        gatherConsentThenInitialize()
    }

    // --- 8. In-app update --------------------------------------------------

    /**
     * Wires the Google Play in-app updater. [InAppUpdateManager.with] binds it to this activity's
     * lifecycle (auto-resuming a stalled immediate update and re-prompting a completed flexible
     * download), so we only configure the callbacks and kick off a check.
     *
     * Note: in-app updates only surface for a build installed from Google Play (or internal app
     * sharing). On this sideloaded debug build the check simply reports "no update" — that's expected.
     */
    private fun setupInAppUpdate() {
        appUpdater = InAppUpdateManager.with(this).apply {
            // Flexible by default; a high-priority (5) release auto-escalates to a blocking update.
            updateType = UpdateType.FLEXIBLE
            onUpdateAvailable = { setStatus("Update available — starting download…") }
            onNoUpdateAvailable = { setStatus("App is up to date ✓") }
            onDownloadProgress = { done, total ->
                if (total > 0) setStatus("Downloading update… ${done * 100 / total}%")
            }
            // Leaving onFlexibleUpdateDownloaded unset uses the built-in "RESTART" snackbar prompt.
            onUpdateCanceled = { setStatus("Update cancelled") }
            onUpdateFailed = { code -> setStatus("Update failed (code $code)") }
            onError = { error -> setStatus("Update check error: ${error.message}") }
        }
        appUpdater.checkForUpdate()
    }

    // --- 1. Consent + init -------------------------------------------------

    private fun gatherConsentThenInitialize() {
        // Consent + init are centralised in AdsBootstrap (the same handshake the splashes use); the
        // App ID is read from the manifest. Just report readiness back to the status line here.
        setStatus("Gathering consent & initializing…")
        AdsBootstrap.gatherConsentThenInitialize(this) {
            setStatus("Initialized ✓  — you can preload / show ads now")
        }
    }

    // --- 2. Banner ---------------------------------------------------------

    /** The banner size chosen in the Size chip group (defaults to full-width adaptive). */
    private fun selectedBannerSize(): BannerSize = when (bannerSizeGroup.checkedChipId) {
        R.id.rbSizeInline -> BannerSize.ADAPTIVE_INLINE
        R.id.rbSizeBanner -> BannerSize.BANNER
        R.id.rbSizeLarge -> BannerSize.LARGE_BANNER
        R.id.rbSizeFull -> BannerSize.FULL_BANNER
        R.id.rbSizeLeaderboard -> BannerSize.LEADERBOARD
        R.id.rbSizeMrec -> BannerSize.MEDIUM_RECTANGLE
        else -> BannerSize.ADAPTIVE
    }

    private fun warmBannerCache() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.banner, "Banner")) return
        val size = selectedBannerSize()
        BannerAdHelper.preload(this, AdUnits.BANNER, count = 1, size = size, remoteEnabled = AdsConfig.banner)
        setStatus("Preloading banner (${size.name.lowercase()})…")
    }

    /**
     * Single banner code path: attaches a preloaded banner instantly, or loads one on demand behind
     * a shimmer. The "Collapsible banner" checkbox is the single boolean that folds the collapsible
     * flow in here — when checked, a collapsible banner anchored at the bottom is requested (it shows
     * larger on first impression and collapses via the SDK's expand/collapse control); otherwise a
     * normal banner is shown. Both use the selected size.
     */
    private fun loadAndDisplayBanner() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.banner, "Banner")) return
        val size = selectedBannerSize()
        val collapsible = collapsibleCheck.isChecked
        val label = if (collapsible) "collapsible banner" else "banner"
        setStatus("Showing $label (${size.name.lowercase()})…")
        BannerAdHelper.loadAdaptiveBanner(
            activity = this,
            container = bannerContainer,
            adUnitId = AdUnits.BANNER,
            collapsible = if (collapsible) BannerCollapsible.BOTTOM else null,
            size = size,
            remoteEnabled = AdsConfig.banner,
            onLoaded = {
                val hint = if (collapsible) " — tap the arrow to collapse" else ""
                setStatus("${label.replaceFirstChar { it.uppercase() }} shown ✓ (${size.name.lowercase()})$hint")
            },
            onFailed = { error -> setStatus("$label failed: ${error.message}") },
        )
    }

    // --- 3. Native ---------------------------------------------------------

    private fun selectedTemplate(): NativeTemplate = when (templateGroup.checkedChipId) {
        R.id.rbSmall -> NativeTemplate.SMALL
        R.id.rbLarge -> NativeTemplate.LARGE
        R.id.rbBanner -> NativeTemplate.BANNER
        R.id.rbMediaLeft -> NativeTemplate.MEDIA_LEFT
        R.id.rbCollapsible -> NativeTemplate.COLLAPSIBLE
        R.id.rbHero -> NativeTemplate.HERO
        R.id.rbFeed -> NativeTemplate.FEED
        R.id.rbSpotlight -> NativeTemplate.SPOTLIGHT
        R.id.rbActionTop -> NativeTemplate.ACTION_TOP
        R.id.rbHalfMedia -> NativeTemplate.HALF_MEDIA
        R.id.rbStacked -> NativeTemplate.STACKED
        else -> NativeTemplate.MEDIUM
    }

    private fun selectedAdType(): AdType =
        if (adTypeGroup.checkedRadioButtonId == R.id.rbTypeBanner) AdType.BANNER else AdType.NATIVE

    /** Warms the cache for whichever format the unified view is set to. */
    private fun warmNativeAdCache() {
        if (!ensureSdkInitialized()) return
        val banner = selectedAdType() == AdType.BANNER
        if (!ensureRemoteEnabled(if (banner) AdsConfig.banner else AdsConfig.native, if (banner) "Banner" else "Native")) return
        if (selectedAdType() == AdType.BANNER) {
            // Warm the SAME size the unified view will request, else the preloaded (adaptive) banner
            // won't match the selected fixed size and the cache is bypassed.
            val size = selectedBannerSize()
            BannerAdHelper.preload(this, AdUnits.BANNER, count = 1, size = size, remoteEnabled = AdsConfig.banner)
            setStatus("Preloading banner (unified view, ${size.name.lowercase()})…")
        } else {
            NativeAdHelper.preload(AdUnits.NATIVE, count = 1, remoteEnabled = AdsConfig.native)
            setStatus("Preloading native…")
        }
    }

    /**
     * Cross-screen preload: warm a native ad on **this** screen, then open [PreloadedNativeActivity],
     * which binds it instantly with `NativeAdPreloader.showInto`. This is the Splash→next-screen
     * pattern — the loading latency is hidden behind the screen transition. Nothing but the ad unit
     * crosses the boundary; the preloader keys its state by unit, so both screens just name
     * [AdUnits.NATIVE].
     */
    private fun preloadNativeAndOpenScreen() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.native, "Native")) return
        NativeAdPreloader.preload(AdUnits.NATIVE, remoteEnabled = AdsConfig.native)
        setStatus("Preloading native, opening the next screen…")
        startActivity(Intent(this, PreloadedNativeActivity::class.java))
    }

    /**
     * Loads an ad into the single [BannerNativeView] based on the selected ad type — a banner, or a
     * native ad rendered with the chosen template (instant if preloaded, otherwise on demand).
     */
    private fun loadAndDisplayNativeAd() {
        if (!ensureSdkInitialized()) return
        val adType = selectedAdType()
        val allowed = if (adType == AdType.BANNER) AdsConfig.banner else AdsConfig.native
        if (!ensureRemoteEnabled(allowed, if (adType == AdType.BANNER) "Banner" else "Native")) return
        if (adType == AdType.BANNER) {
            val size = selectedBannerSize()
            setStatus("Showing banner (unified view, ${size.name.lowercase()})…")
            nativeAdView.load(
                adUnitId = AdUnits.BANNER,
                remoteEnabled = AdsConfig.banner,
                adType = AdType.BANNER,
                bannerSize = size,
                onLoaded = { setStatus("Banner shown ✓ (unified view, ${size.name.lowercase()})") },
                onFailed = { setStatus("Banner failed to load") },
            )
            return
        }
        val template = selectedTemplate()
        setStatus("Showing native (${template.name.lowercase()})…")
        nativeAdView.load(
            adUnitId = AdUnits.NATIVE,
            remoteEnabled = AdsConfig.native,
            adType = AdType.NATIVE,
            nativeTemplate = template,
            onLoaded = { setStatus("Native shown ✓ (${template.name.lowercase()})") },
            onFailed = { setStatus("Native failed to load") },
        )
    }

    // --- 4. Interstitial ---------------------------------------------------

    private fun warmInterstitialCache() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.interstitial, "Interstitial")) return
        Interstitials.preload(AdUnits.INTERSTITIAL, remoteEnabled = AdsConfig.interstitial)
        setStatus("Preloading interstitial…")
    }

    /** Shows a preloaded interstitial, or loads a fresh one on demand when none is cached. */
    private fun showCachedInterstitial() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.interstitial, "Interstitial")) return
        val helper = Interstitials.get(AdUnits.INTERSTITIAL)
        setStatus(if (helper.isReady) "Showing interstitial…" else "No ad preloaded — loading a fresh interstitial…")
        // Show the cached ad instantly if ready, otherwise request one on demand and show it.
        helper.loadAndShow(this, timeoutMs = 8_000L) { setStatus("Interstitial dismissed ✓") }
    }

    /** Loads an interstitial on demand and shows it as soon as it is ready. */
    private fun loadAndPresentInterstitial() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.interstitial, "Interstitial")) return
        val helper = Interstitials.get(AdUnits.INTERSTITIAL)
        setStatus(if (helper.isReady) "Showing interstitial…" else "Loading interstitial…")
        // loadAndShow raises the full-screen loading cover over the real on-demand fetch (instead of
        // the bare status line load { … } left the screen on), then shows the ad the moment it lands.
        helper.loadAndShow(this, timeoutMs = 8_000L) { setStatus("Interstitial dismissed ✓") }
    }

    /**
     * Counter-gated interstitial: shows on the 1st click and then on every 4th click after that
     * (clicks 1, 5, 9, 13 …). The in-between clicks warm the cache via [Interstitials.preload] so the
     * gated-in click has an ad ready and shows instantly; `forceLoad = true` is the fallback that
     * loads on demand (bounded by a 5s timeout) if the preload hasn't landed yet — e.g. on the very
     * first click, or after the splash interstitial consumed the same ad unit.
     */
    private fun presentCounterGatedInterstitial() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.interstitial, "Interstitial")) return
        val helper = Interstitials.get(AdUnits.INTERSTITIAL)
        // Kick off a preload the moment the counter is first used, so even click #1 is warm.
        if (!helper.isReady) Interstitials.preload(AdUnits.INTERSTITIAL, remoteEnabled = AdsConfig.interstitial)
        counterClicks++
        val shown = helper.showFirstThenEvery(this, nth = 4, forceLoad = true, timeoutMs = 5_000L) {
            val load = if (helper.lastLoadMs >= 0) " · loaded in ${helper.lastLoadMs}ms" else ""
            setStatus("Interstitial dismissed ✓ (click #$counterClicks$load)")
        }
        if (!shown) {
            // A non-show click: warm the next ad so the gated-in click shows without a load wait.
            Interstitials.preload(AdUnits.INTERSTITIAL, remoteEnabled = AdsConfig.interstitial)
            val nextShowAt = ((counterClicks - 1) / 4 + 1) * 4 + 1
            setStatus("Click #$counterClicks — next ad at click #$nextShowAt")
        }
    }

    // --- 5. Rewarded -------------------------------------------------------

    private fun warmRewardedCache() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.rewarded, "Rewarded")) return
        setStatus("Preloading rewarded…")
        // Report the load result back to the UI instead of firing the preload blind.
        RewardedAds.get(AdUnits.REWARDED).load(remoteEnabled = AdsConfig.rewarded) { loaded ->
            setStatus(if (loaded) "Rewarded preloaded ✓ — ready to show" else "Rewarded preload failed")
        }
    }

    /** Asks the user to opt in, then shows a preloaded rewarded ad — or loads a fresh one on demand. */
    private fun confirmThenPresentRewarded() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.rewarded, "Rewarded")) return
        val helper = RewardedAds.get(AdUnits.REWARDED)
        MaterialAlertDialogBuilder(this)
            .setTitle("Earn a reward")
            .setMessage("Watch a short video to earn your reward?")
            .setPositiveButton("Watch") { _, _ ->
                setStatus(if (helper.isReady) "Showing rewarded…" else "Loading a fresh rewarded ad…")
                var earned = false
                helper.loadAndShow(
                    activity = this,
                    timeoutMs = 10_000L,
                    onReward = { reward ->
                        // Fires only when the user actually earns it. Grant the reward here — this
                        // demo just tallies a balance; a real app would credit the user's account.
                        earned = true
                        rewardBalance += reward.amount
                        setStatus("Reward earned ✓ +${reward.amount} ${reward.type} (balance: $rewardBalance)")
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Reward earned 🎉")
                            .setMessage("You earned ${reward.amount} ${reward.type}.\nNew balance: $rewardBalance.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onComplete = {
                        // Always fires when the ad closes — distinguish "earned" from "closed early".
                        setStatus(
                            if (earned) "Rewarded closed — reward granted ✓ (balance: $rewardBalance)"
                            else "Rewarded closed — no reward",
                        )
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- 6. Rewarded interstitial -----------------------------------------

    private fun warmRewardedInterstitialCache() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.rewardedInterstitial, "Rewarded interstitial")) return
        setStatus("Preloading rewarded interstitial…")
        // Report the load result back to the UI instead of firing the preload blind.
        RewardedInterstitials.get(AdUnits.REWARDED_INT).load(true) { loaded ->
            setStatus(
                if (loaded) "Rewarded interstitial preloaded ✓ — ready to show"
                else "Rewarded interstitial preload failed",
            )
        }
    }

    /** Asks the user to opt in, then shows a preloaded rewarded interstitial — or loads a fresh one. */
    private fun confirmThenPresentRewardedInterstitial() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.rewardedInterstitial, "Rewarded interstitial")) return
        val helper = RewardedInterstitials.get(AdUnits.REWARDED_INT)
        MaterialAlertDialogBuilder(this)
            .setTitle("Earn a reward")
            .setMessage("Watch a short ad to earn your reward?")
            .setPositiveButton("Watch") { _, _ ->
                setStatus(if (helper.isReady) "Showing rewarded interstitial…" else "Loading a fresh rewarded interstitial…")
                var earned = false
                helper.loadAndShow(
                    activity = this,
                    timeoutMs = 10_000L,
                    onReward = { reward ->
                        // Fires only when the user actually earns it. Grant the reward here — this
                        // demo just tallies a balance; a real app would credit the user's account.
                        earned = true
                        rewardBalance += reward.amount
                        setStatus("Reward earned ✓ +${reward.amount} ${reward.type} (balance: $rewardBalance)")
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Reward earned 🎉")
                            .setMessage("You earned ${reward.amount} ${reward.type}.\nNew balance: $rewardBalance.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onComplete = {
                        // Always fires when the ad closes — distinguish "earned" from "closed early".
                        setStatus(
                            if (earned) "Rewarded interstitial closed — reward granted ✓ (balance: $rewardBalance)"
                            else "Rewarded interstitial closed — no reward",
                        )
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- 7. App open -------------------------------------------------------

    private fun warmAppOpenCache() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.appOpen, "App open")) return
        AppOpenAds.preload(AdUnits.APP_OPEN, remoteEnabled = AdsConfig.appOpen)
        setStatus("Preloading app open…")
    }

    /**
     * Shows the app-open ad on demand. Note: it also shows automatically when you background the
     * app and return — that flow is wired in [SampleApp] via `AppOpenAdManager.install`.
     */
    private fun showCachedAppOpenAd() {
        if (!ensureSdkInitialized() || !ensureRemoteEnabled(AdsConfig.appOpen, "App open")) return
        val helper = AppOpenAds.get(AdUnits.APP_OPEN)
        setStatus(if (helper.isReady) "Showing app open…" else "No ad preloaded — loading a fresh app-open ad…")
        // Show the cached ad instantly if ready, otherwise request one on demand and show it.
        helper.loadAndShow(this, timeoutMs = 8_000L) { setStatus("App open dismissed ✓") }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Flips [NextGenAds.premium]. Turning it on immediately purges every cached ad and hides the
     * shown banner/native (via the library's runtime purge); no new ad is requested while premium.
     */
    private fun togglePremium(button: Button) {
        val premium = !NextGenAds.premium
        NextGenAds.premium = premium
        button.text = if (premium) "Premium: ON (tap to allow ads)" else "Premium: OFF (tap to go ad-free)"
        setStatus(if (premium) "Premium ON — all ads purged & hidden, none will load" else "Premium OFF — ads allowed again")
    }

    private fun ensureSdkInitialized(): Boolean {
        if (!NextGenAds.isInitialized()) {
            Toast.makeText(this, "Gather consent & initialize first", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /**
     * Gates a show trigger on its [AdsConfig] flag. Returns `true` when the ad is enabled; otherwise
     * updates the status line and returns `false` so the caller skips the show.
     */
    private fun ensureRemoteEnabled(enabled: Boolean, label: String): Boolean {
        if (!enabled) setStatus("$label disabled")
        return enabled
    }

    /** Binds each per-format switch to its [AdsConfig] flag. */
    private fun setupAdToggles() {
        bindAdToggle(R.id.swBannerEnabled, AdFormat.BANNER, "Banner", AdsConfig.banner) { AdsConfig.banner = it }
        bindAdToggle(R.id.swNativeEnabled, AdFormat.NATIVE, "Native", AdsConfig.native) { AdsConfig.native = it }
        bindAdToggle(R.id.swInterstitialEnabled, AdFormat.INTERSTITIAL, "Interstitial", AdsConfig.interstitial) { AdsConfig.interstitial = it }
        bindAdToggle(R.id.swRewardedEnabled, AdFormat.REWARDED, "Rewarded", AdsConfig.rewarded) { AdsConfig.rewarded = it }
        bindAdToggle(R.id.swRewardedIntEnabled, AdFormat.REWARDED_INTERSTITIAL, "Rewarded interstitial", AdsConfig.rewardedInterstitial) { AdsConfig.rewardedInterstitial = it }
        bindAdToggle(R.id.swAppOpenEnabled, AdFormat.APP_OPEN, "App open", AdsConfig.appOpen) { AdsConfig.appOpen = it }
    }

    /**
     * Wires one format switch. Flipping it updates the app-side [AdsConfig] flag (passed into the
     * library as `remoteEnabled` at each load) and mirrors it to the library's per-format switch —
     * so turning it OFF also drops that format's cached ads and hides any of its ads already on
     * screen (banner / native), not just future loads.
     */
    private fun bindAdToggle(
        id: Int,
        format: AdFormat,
        label: String,
        initial: Boolean,
        setFlag: (Boolean) -> Unit,
    ) {
        val switch = findViewById<MaterialSwitch>(id)
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked ->
            setFlag(checked)
            NextGenAds.setFormatEnabled(format, checked)
            if (!checked && format == AdFormat.BANNER) hideBannerContainer()
            setStatus("$label ads ${if (checked) "ON" else "OFF"}")
        }
    }

    /** Clears the standalone banner container (the library purge only hides BannerNativeView slots). */
    private fun hideBannerContainer() {
        bannerContainer.removeAllViews()
        bannerContainer.visibility = View.GONE
    }

    private fun setStatus(text: String) {
        status.text = "Status: $text"
    }

    /**
     * Wires the header day/night toggle to [ThemePrefs]. The current mode is reflected first (before
     * the listener is attached, so restoring state doesn't re-trigger it); a tap then persists the
     * new mode and applies it, recreating the activity in the chosen theme — which re-themes the app
     * chrome and every ad template together.
     */
    private fun setupThemeToggle() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.themeToggle)
        group.check(
            when (ThemePrefs.getMode(this)) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
                else -> R.id.btnThemeAuto
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != ThemePrefs.getMode(this)) ThemePrefs.setMode(this, mode)
        }
    }

    /** Rebuilds the bottom stats table from the app-wide show-rate tracker (main-thread safe). */
    private fun refreshStats() {
        statsTable.post {
            statsTable.removeAllViews()
            statsTable.addView(statsRow(ShowRateTracker.COLUMNS, header = true))
            val rows = SampleApp.showRate.snapshot()
            if (rows.isEmpty()) {
                statsTable.addView(statsRow(listOf("no ad events yet"), header = false))
            } else {
                rows.forEach { statsTable.addView(statsRow(it.cells(), header = false)) }
            }
        }
    }

    /** Builds one table row; FORMAT column is left-aligned, the rest right-aligned (see COLUMNS). */
    private fun statsRow(cells: List<String>, header: Boolean): TableRow = TableRow(this).apply {
        cells.forEachIndexed { i, text ->
            // Flag a low show rate (the USE column = impressions/loaded) so weak placements stand out.
            val warnColor = if (!header && i == useColumnIndex) showRateWarnColor(text) else null
            addView(
                TextView(this@MainActivity).apply {
                    this.text = text
                    typeface = Typeface.MONOSPACE
                    // Bold the show-rate cell when it's flagged, so the color reads clearly.
                    setTypeface(typeface, if (header || warnColor != null) Typeface.BOLD else Typeface.NORMAL)
                    textSize = 12f
                    // Muted header, primary body — both are DayNight color roles so they stay legible
                    // in light and dark; a flagged show rate overrides with amber (< 95%) or red (< 80%).
                    setTextColor(
                        warnColor
                            ?: getColor(if (header) R.color.ng_muted else R.color.ng_text),
                    )
                    val leftAligned = i < ShowRateTracker.LEFT_ALIGNED.size && ShowRateTracker.LEFT_ALIGNED[i]
                    gravity = if (leftAligned) Gravity.START else Gravity.END
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    layoutParams = TableRow.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }
    }

    /**
     * Color for a show-rate cell (e.g. "82%"): orange below 95%, red below 80%, and `null` (keep the
     * default light color) at 95%+ or when there's no rate yet ("—"). Drives the stats-table warning.
     */
    private fun showRateWarnColor(pct: String): Int? {
        val value = pct.removeSuffix("%").toIntOrNull() ?: return null
        return when {
            value < 80 -> getColor(R.color.ng_danger) // red — poor show rate
            value < 95 -> getColor(R.color.ng_warn)   // amber — below target
            else -> null                              // healthy
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        NextGenAds.unregisterEventListener(statsListener)
        nativeAdView.destroy()
        super.onDestroy()
    }

}
