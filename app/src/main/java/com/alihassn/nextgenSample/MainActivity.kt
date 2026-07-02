package com.alihassn.nextgenSample

import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alihassan.nextgenads.AdType
import com.alihassan.nextgenads.BannerNativeView
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenAds
import com.alihassan.nextgenads.banner.BannerAdHelper
import com.alihassan.nextgenads.consent.ConsentManager
import com.alihassan.nextgenads.interstitial.Interstitials
import com.alihassan.nextgenads.nativead.NativeAdHelper
import com.alihassan.nextgenads.rewarded.RewardedAds
import com.alihassan.nextgenads.rewardedinterstitial.RewardedInterstitials
import com.alihassan.nextgenads.nativead.NativeTemplate

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var bannerContainer: FrameLayout
    private lateinit var nativeAdView: BannerNativeView
    private lateinit var templateGroup: RadioGroup

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
        // Long-press the status text to dump the live fill/show-rate report (also logged to logcat).
        status.setOnLongClickListener {
            SampleApp.showRate.logReport()
            setStatus(SampleApp.showRate.report())
            true
        }
        bannerContainer = findViewById(R.id.bannerContainer)
        nativeAdView = findViewById(R.id.nativeAdView)
        templateGroup = findViewById(R.id.rgTemplate)

        // 1. Consent → initialize.
        findViewById<Button>(R.id.btnConsent).setOnClickListener { gatherConsentAndInit() }

        // 2. Banner.
        findViewById<Button>(R.id.btnPreloadBanner).setOnClickListener { preloadBanner() }
        findViewById<Button>(R.id.btnShowBanner).setOnClickListener { showBanner() }
        findViewById<Button>(R.id.btnLoadShowBanner).setOnClickListener { showBanner() }

        // 3. Native.
        findViewById<Button>(R.id.btnPreloadNative).setOnClickListener { preloadNative() }
        findViewById<Button>(R.id.btnShowNative).setOnClickListener { showNative() }
        findViewById<Button>(R.id.btnLoadShowNative).setOnClickListener { showNative() }

        // 4. Interstitial.
        findViewById<Button>(R.id.btnPreloadInterstitial).setOnClickListener { preloadInterstitial() }
        findViewById<Button>(R.id.btnShowInterstitial).setOnClickListener { showInterstitial() }
        findViewById<Button>(R.id.btnLoadShowInterstitial).setOnClickListener { loadAndShowInterstitial() }

        // 5. Rewarded.
        findViewById<Button>(R.id.btnPreloadRewarded).setOnClickListener { preloadRewarded() }
        findViewById<Button>(R.id.btnShowRewarded).setOnClickListener { showRewardedWithDialog() }

        // 6. Rewarded interstitial.
        findViewById<Button>(R.id.btnPreloadRewardedInt).setOnClickListener { preloadRewardedInterstitial() }
        findViewById<Button>(R.id.btnShowRewardedInt).setOnClickListener { showRewardedInterstitialWithDialog() }

        // 7. App open.
        findViewById<Button>(R.id.btnPreloadAppOpen).setOnClickListener { preloadAppOpen() }
        findViewById<Button>(R.id.btnShowAppOpen).setOnClickListener { showAppOpen() }
    }

    // --- 1. Consent + init -------------------------------------------------

    private fun gatherConsentAndInit() {
        setStatus("Gathering consent…")
        val consent = ConsentManager.getInstance(this,"445FDBFFE2FFB7A0A4CA9ADF81FE4675")
        consent.gatherConsent(this) { error ->
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

    private fun preloadBanner() {
        if (!ensureReady()) return
        BannerAdHelper.preload(this, BANNER_UNIT, count = 1)
        setStatus("Preloading banner…")
    }

    /** Attaches a preloaded banner instantly, or loads one on demand behind a shimmer. */
    private fun showBanner() {
        if (!ensureReady()) return
        setStatus("Showing banner…")
        BannerAdHelper.loadAdaptiveBanner(
            activity = this,
            container = bannerContainer,
            adUnitId = BANNER_UNIT,
            onLoaded = { setStatus("Banner shown ✓") },
            onFailed = { error -> setStatus("Banner failed: ${error.message}") },
        )
    }

    // --- 3. Native ---------------------------------------------------------

    private fun selectedTemplate(): NativeTemplate = when (templateGroup.checkedRadioButtonId) {
        R.id.rbSmall -> NativeTemplate.SMALL
        R.id.rbLarge -> NativeTemplate.LARGE
        R.id.rbBanner -> NativeTemplate.BANNER
        R.id.rbMediaLeft -> NativeTemplate.MEDIA_LEFT
        else -> NativeTemplate.MEDIUM
    }

    private fun preloadNative() {
        if (!ensureReady()) return
        NativeAdHelper.preload(NATIVE_UNIT, count = 1)
        setStatus("Preloading native…")
    }

    /** Binds an ad into the chosen template (instant if preloaded, otherwise loads on demand). */
    private fun showNative() {
        if (!ensureReady()) return
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

    /** Shows a preloaded interstitial if one is ready. */
    private fun showInterstitial() {
        if (!ensureReady()) return
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        if (!helper.isReady) {
            setStatus("No interstitial ready — preload first")
            return
        }
        setStatus("Showing interstitial…")
        helper.show(this) { setStatus("Interstitial dismissed ✓") }
    }

    /** Loads an interstitial on demand and shows it as soon as it is ready. */
    private fun loadAndShowInterstitial() {
        if (!ensureReady()) return
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        if (helper.isReady) {
            setStatus("Showing interstitial…")
            helper.show(this) { setStatus("Interstitial dismissed ✓") }
            return
        }
        setStatus("Loading interstitial…")
        helper.load { success ->
            if (success) {
                setStatus("Showing interstitial…")
                helper.show(this) { setStatus("Interstitial dismissed ✓") }
            } else {
                setStatus("Interstitial failed to load")
            }
        }
    }

    // --- 5. Rewarded -------------------------------------------------------

    private fun preloadRewarded() {
        if (!ensureReady()) return
        RewardedAds.preload(REWARDED_UNIT)
        setStatus("Preloading rewarded…")
    }

    /** Asks the user to opt in via a dialog, then shows the rewarded ad if one is ready. */
    private fun showRewardedWithDialog() {
        if (!ensureReady()) return
        val helper = RewardedAds.get(REWARDED_UNIT)
        if (!helper.isReady) {
            setStatus("No rewarded ad ready — preload first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Earn a reward")
            .setMessage("Watch a short video to earn your reward?")
            .setPositiveButton("Watch") { _, _ ->
                setStatus("Showing rewarded…")
                var earned = false
                helper.show(
                    activity = this,
                    onReward = { reward ->
                        earned = true
                        setStatus("Reward earned ✓ ${reward.amount} ${reward.type}")
                        AlertDialog.Builder(this)
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

    /** Asks the user to opt in via a dialog, then shows the rewarded interstitial if ready. */
    private fun showRewardedInterstitialWithDialog() {
        if (!ensureReady()) return
        val helper = RewardedInterstitials.get(REWARDED_INT_UNIT)
        if (!helper.isReady) {
            setStatus("No rewarded interstitial ready — preload first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Earn a reward")
            .setMessage("Watch a short ad to earn your reward?")
            .setPositiveButton("Watch") { _, _ ->
                setStatus("Showing rewarded interstitial…")
                var earned = false
                helper.show(
                    activity = this,
                    onReward = { reward ->
                        earned = true
                        setStatus("Reward earned ✓ ${reward.amount} ${reward.type}")
                        AlertDialog.Builder(this)
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
        if (!helper.isReady) {
            setStatus("No app-open ad ready — preload first")
            helper.load()
            return
        }
        setStatus("Showing app open…")
        helper.show(this) { setStatus("App open dismissed ✓") }
    }

    // --- helpers -----------------------------------------------------------

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

    override fun onDestroy() {
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
