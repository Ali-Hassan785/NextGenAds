package com.alihassn.nextgenSample

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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
import com.google.android.material.chip.ChipGroup
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
import com.alihassan.nextgenads.consent.ConsentManager
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.alihassan.nextgenads.events.ShowRateTracker
import com.alihassan.nextgenads.interstitial.Interstitials
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.rewarded.RewardedAds
import com.alihassan.nextgenads.rewardedinterstitial.RewardedInterstitials
import com.alihassan.nextgenads.nativead.NativeTemplate

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

    /** Index of the show-rate (USE) column in the stats table — the cell we color by threshold. */
    private val useColumnIndex = ShowRateTracker.COLUMNS.indexOf("USE")

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

        // 2. Banner.
        collapsibleCheck = findViewById(R.id.cbCollapsible)
        findViewById<Button>(R.id.btnPreloadBanner).setOnClickListener { preloadBanner() }
        findViewById<Button>(R.id.btnShowBanner).setOnClickListener { showBanner() }
        findViewById<Button>(R.id.btnLoadShowBanner).setOnClickListener { showBanner() }

        // 3. Native.
        findViewById<Button>(R.id.btnPreloadNative).setOnClickListener { preloadNative() }
        findViewById<Button>(R.id.btnShowNative).setOnClickListener { showNative() }
        findViewById<Button>(R.id.btnLoadShowNative).setOnClickListener { showNative() }
        findViewById<Button>(R.id.btnCustomNative).setOnClickListener {
            startActivity(Intent(this, CustomNativeActivity::class.java))
        }

        // 4. Interstitial.
        findViewById<Button>(R.id.btnPreloadInterstitial).setOnClickListener { preloadInterstitial() }
        findViewById<Button>(R.id.btnShowInterstitial).setOnClickListener { showInterstitial() }
        findViewById<Button>(R.id.btnLoadShowInterstitial).setOnClickListener { loadAndShowInterstitial() }
        findViewById<Button>(R.id.btnCounterInterstitial).setOnClickListener { showInterstitialByCounter() }

        // 5. Rewarded.
        findViewById<Button>(R.id.btnPreloadRewarded).setOnClickListener { preloadRewarded() }
        findViewById<Button>(R.id.btnShowRewarded).setOnClickListener { showRewardedWithDialog() }

        // 6. Rewarded interstitial.
        findViewById<Button>(R.id.btnPreloadRewardedInt).setOnClickListener { preloadRewardedInterstitial() }
        findViewById<Button>(R.id.btnShowRewardedInt).setOnClickListener { showRewardedInterstitialWithDialog() }

        // 7. App open.
        findViewById<Button>(R.id.btnPreloadAppOpen).setOnClickListener { preloadAppOpen() }
        findViewById<Button>(R.id.btnShowAppOpen).setOnClickListener { showAppOpen() }

        // Gather consent and initialize automatically as soon as the screen opens, so ads are ready
        // without a manual tap (there is no consent button — this is the only trigger).
        gatherConsentAndInit()
    }

    // --- 1. Consent + init -------------------------------------------------

    private fun gatherConsentAndInit() {
        // UMP debug facilities (test-device hash + forced EEA geography) must never reach release
        // builds — they would force the consent form on real users and skew ad serving.
        val testDeviceHash = if (BuildConfig.DEBUG) "445FDBFFE2FFB7A0A4CA9ADF81FE4675" else null
        val consent = ConsentManager.getInstance(this, testDeviceHash)

        // Once consent has already been gathered (or isn't required), don't re-present the form on
        // subsequent button taps — just make sure the SDK is initialized and move on.
        if (consent.canRequestAds) {
            setStatus("Initializing SDK…")
            NextGenAds.initialize(this, APP_ID) {
                setStatus("Initialized ✓  — you can preload / show ads now")
            }
            return
        }

        setStatus("Gathering consent…")
        consent.gatherConsent(this, forceEea = testDeviceHash != null) { error ->
            if (error != null) {
                setStatus("Consent error: ${error.message}")
            }
            if (!consent.canRequestAds) {
                setStatus("Ads not allowed (consent not granted)")
                return@gatherConsent
            }
            setStatus("Initializing SDK…")
            NextGenAds.initialize(this, APP_ID) {
                setStatus("Initialized ✓  — you can preload / show ads now")
            }
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

    private fun preloadBanner() {
        if (!ensureReady()) return
        val size = selectedBannerSize()
        BannerAdHelper.preload(this, BANNER_UNIT, count = 1, size = size)
        setStatus("Preloading banner (${size.name.lowercase()})…")
    }

    /**
     * Single banner code path: attaches a preloaded banner instantly, or loads one on demand behind
     * a shimmer. The "Collapsible banner" checkbox is the single boolean that folds the collapsible
     * flow in here — when checked, a collapsible banner anchored at the bottom is requested (it shows
     * larger on first impression and collapses via the SDK's expand/collapse control); otherwise a
     * normal banner is shown. Both use the selected size.
     */
    private fun showBanner() {
        if (!ensureReady()) return
        val size = selectedBannerSize()
        val collapsible = collapsibleCheck.isChecked
        val label = if (collapsible) "collapsible banner" else "banner"
        setStatus("Showing $label (${size.name.lowercase()})…")
        BannerAdHelper.loadAdaptiveBanner(
            activity = this,
            container = bannerContainer,
            adUnitId = BANNER_UNIT,
            collapsible = if (collapsible) BannerCollapsible.BOTTOM else null,
            size = size,
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
        else -> NativeTemplate.MEDIUM
    }

    private fun selectedAdType(): AdType =
        if (adTypeGroup.checkedRadioButtonId == R.id.rbTypeBanner) AdType.BANNER else AdType.NATIVE

    /** Warms the cache for whichever format the unified view is set to. */
    private fun preloadNative() {
        if (!ensureReady()) return
        if (selectedAdType() == AdType.BANNER) {
            // Warm the SAME size the unified view will request, else the preloaded (adaptive) banner
            // won't match the selected fixed size and the cache is bypassed.
            val size = selectedBannerSize()
            BannerAdHelper.preload(this, BANNER_UNIT, count = 1, size = size)
            setStatus("Preloading banner (unified view, ${size.name.lowercase()})…")
        } else {
            NativeAdHelper.preload(NATIVE_UNIT, count = 1)
            setStatus("Preloading native…")
        }
    }

    /**
     * Loads an ad into the single [BannerNativeView] based on the selected ad type — a banner, or a
     * native ad rendered with the chosen template (instant if preloaded, otherwise on demand).
     */
    private fun showNative() {
        if (!ensureReady()) return
        val adType = selectedAdType()
        if (adType == AdType.BANNER) {
            val size = selectedBannerSize()
            setStatus("Showing banner (unified view, ${size.name.lowercase()})…")
            nativeAdView.load(
                adUnitId = BANNER_UNIT,
                remoteEnabled = true,
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
            adUnitId = NATIVE_UNIT,
            remoteEnabled = true, // your remote-config flag for this placement
            adType = AdType.NATIVE,
            nativeTemplate = template,
            onLoaded = { setStatus("Native shown ✓ (${template.name.lowercase()})") },
            onFailed = { setStatus("Native failed to load") },
        )
    }

    // --- 4. Interstitial ---------------------------------------------------

    private fun preloadInterstitial() {
        if (!ensureReady()) return
        Interstitials.preload(INTERSTITIAL_UNIT)
        setStatus("Preloading interstitial…")
    }

    /** Shows a preloaded interstitial, or loads a fresh one on demand when none is cached. */
    private fun showInterstitial() {
        if (!ensureReady()) return
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        setStatus(if (helper.isReady) "Showing interstitial…" else "No ad preloaded — loading a fresh interstitial…")
        // Show the cached ad instantly if ready, otherwise request one on demand and show it.
        helper.loadAndShow(this, timeoutMs = 8_000L) { setStatus("Interstitial dismissed ✓") }
    }

    /** Loads an interstitial on demand and shows it as soon as it is ready. */
    private fun loadAndShowInterstitial() {
        if (!ensureReady()) return
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        if (helper.isReady) {
            setStatus("Showing interstitial…")
            helper.show(this, onDismiss = { setStatus("Interstitial dismissed ✓") })
            return
        }
        setStatus("Loading interstitial…")
        helper.load { success ->
            if (success) {
                setStatus("Showing interstitial…")
                helper.show(this, onDismiss = { setStatus("Interstitial dismissed ✓") })
            } else {
                setStatus("Interstitial failed to load")
            }
        }
    }

    /**
     * Counter-gated interstitial: shows on the 1st click and then on every 4th click after that
     * (clicks 1, 5, 9, 13 …). The in-between clicks warm the cache via [Interstitials.preload] so the
     * gated-in click has an ad ready and shows instantly; `forceLoad = true` is the fallback that
     * loads on demand (bounded by a 5s timeout) if the preload hasn't landed yet — e.g. on the very
     * first click, or after the splash interstitial consumed the same ad unit.
     */
    private fun showInterstitialByCounter() {
        if (!ensureReady()) return
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        // Kick off a preload the moment the counter is first used, so even click #1 is warm.
        if (!helper.isReady) Interstitials.preload(INTERSTITIAL_UNIT)
        counterClicks++
        val shown = helper.showFirstThenEvery(this, nth = 4, forceLoad = true, timeoutMs = 5_000L) {
            val load = if (helper.lastLoadMs >= 0) " · loaded in ${helper.lastLoadMs}ms" else ""
            setStatus("Interstitial dismissed ✓ (click #$counterClicks$load)")
        }
        if (!shown) {
            // A non-show click: warm the next ad so the gated-in click shows without a load wait.
            Interstitials.preload(INTERSTITIAL_UNIT)
            val nextShowAt = ((counterClicks - 1) / 4 + 1) * 4 + 1
            setStatus("Click #$counterClicks — next ad at click #$nextShowAt")
        }
    }

    // --- 5. Rewarded -------------------------------------------------------

    private fun preloadRewarded() {
        if (!ensureReady()) return
        RewardedAds.preload(REWARDED_UNIT)
        setStatus("Preloading rewarded…")
    }

    /** Asks the user to opt in, then shows a preloaded rewarded ad — or loads a fresh one on demand. */
    private fun showRewardedWithDialog() {
        if (!ensureReady()) return
        val helper = RewardedAds.get(REWARDED_UNIT)
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
                        earned = true
                        setStatus("Reward earned ✓ ${reward.amount} ${reward.type}")
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Reward earned 🎉")
                            .setMessage("You earned ${reward.amount} ${reward.type}.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onDismiss = {
                        if (!earned) setStatus("Rewarded closed — no reward")
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- 6. Rewarded interstitial -----------------------------------------

    private fun preloadRewardedInterstitial() {
        if (!ensureReady()) return
        RewardedInterstitials.preload(REWARDED_INT_UNIT)
        setStatus("Preloading rewarded interstitial…")
    }

    /** Asks the user to opt in, then shows a preloaded rewarded interstitial — or loads a fresh one. */
    private fun showRewardedInterstitialWithDialog() {
        if (!ensureReady()) return
        val helper = RewardedInterstitials.get(REWARDED_INT_UNIT)
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
                        earned = true
                        setStatus("Reward earned ✓ ${reward.amount} ${reward.type}")
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Reward earned 🎉")
                            .setMessage("You earned ${reward.amount} ${reward.type}.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onDismiss = {
                        if (!earned) setStatus("Rewarded interstitial closed — no reward")
                    },
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- 7. App open -------------------------------------------------------

    private fun preloadAppOpen() {
        if (!ensureReady()) return
        AppOpenAds.preload(SampleApp.APP_OPEN_UNIT)
        setStatus("Preloading app open…")
    }

    /**
     * Shows the app-open ad on demand. Note: it also shows automatically when you background the
     * app and return — that flow is wired in [SampleApp] via `AppOpenAdManager.install`.
     */
    private fun showAppOpen() {
        if (!ensureReady()) return
        val helper = AppOpenAds.get(SampleApp.APP_OPEN_UNIT)
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

    private fun ensureReady(): Boolean {
        if (!NextGenAds.isInitialized()) {
            Toast.makeText(this, "Gather consent & initialize first", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun setStatus(text: String) {
        status.text = "Status: $text"
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
                    // Muted header, light body — legible and quiet on the dark strip; a flagged show
                    // rate overrides with orange (< 95%) or red (< 80%).
                    setTextColor(
                        warnColor
                            ?: if (header) Color.parseColor("#969BA6") else Color.parseColor("#E3E5EA"),
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
            value < 80 -> Color.parseColor("#E5484D") // red — poor show rate
            value < 95 -> Color.parseColor("#F5A623") // orange — below target
            else -> null                              // healthy
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        NextGenAds.unregisterEventListener(statsListener)
        nativeAdView.destroy()
        super.onDestroy()
    }

    companion object {
        // Google's official sample / test ids — replace with your own AdMob ids for release.
        private const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        private const val BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"
        private const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
        private const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"
        private const val REWARDED_UNIT = "ca-app-pub-3940256099942544/5224354917"
        private const val REWARDED_INT_UNIT = "ca-app-pub-3940256099942544/5354046379"
    }
}
