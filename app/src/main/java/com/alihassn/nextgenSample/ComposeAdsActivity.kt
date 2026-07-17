package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alihassan.nextgenads.NextGenAds
import com.alihassan.nextgenads.appopen.AppOpenCoverStyle
import com.alihassan.nextgenads.banner.BannerSize
import com.alihassan.nextgenads.events.AdEventListener
import com.alihassan.nextgenads.events.AdFormat
import com.alihassan.nextgenads.events.ShowRateTracker
import com.alihassan.nextgenads.nativead.NativeTemplate
import com.alihassan.nextgenads.update.UpdateType
import com.alihassan.nextgenadscompose.AdEventsEffect
import com.alihassan.nextgenadscompose.BannerAd
import com.alihassan.nextgenadscompose.NativeAd
import com.alihassan.nextgenadscompose.rememberAppOpenAd
import com.alihassan.nextgenadscompose.rememberInAppReviewManager
import com.alihassan.nextgenadscompose.rememberInAppUpdateManager
import com.alihassan.nextgenadscompose.rememberInterstitialAd
import com.alihassan.nextgenadscompose.rememberRewardedAd
import com.alihassan.nextgenadscompose.rememberRewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

/**
 * The Jetpack Compose counterpart to [MainActivity]: it demonstrates **every** ad format from the
 * `:nextgenadscompose` wrapper — inline banner & native, interstitial, rewarded, rewarded
 * interstitial, app-open — plus in-app update / review, all with `@Composable` / `remember*` APIs.
 *
 * Reached from [MainActivity]'s "Open Compose ads screen" button. It relies on the SDK already being
 * initialized there (consent + `NextGenAds.initialize` run on the first screen); if you land here
 * before that, the status line says so and nothing loads.
 */
class ComposeAdsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Follow the app's persisted day/night choice (AppCompatDelegate drives the config, so
            // isSystemInDarkTheme() reflects it) with plain Material3 color schemes.
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ComposeAdsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ComposeAdsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember {
        mutableStateOf(
            if (NextGenAds.isInitialized()) "SDK ready ✓ — try the ads below"
            else "SDK not initialized — open this from the main screen first",
        )
    }

    // Full-screen ad controllers (preloaded per the AdsConfig flags so a Show is instant).
    val interstitial = rememberInterstitialAd(AdUnits.INTERSTITIAL, preload = AdsConfig.interstitial)
    val rewarded = rememberRewardedAd(AdUnits.REWARDED, preload = AdsConfig.rewarded)
    val rewardedInt = rememberRewardedInterstitialAd(AdUnits.REWARDED_INT, preload = AdsConfig.rewardedInterstitial)
    val appOpen = rememberAppOpenAd(AdUnits.APP_OPEN, preload = AdsConfig.appOpen)

    val updater = rememberInAppUpdateManager(updateType = UpdateType.FLEXIBLE, autoCheck = false) {
        onUpdateAvailable = { status = "Update available — starting download…" }
        onNoUpdateAvailable = { status = "App is up to date ✓" }
        onUpdateFailed = { code -> status = "Update failed (code $code)" }
        onError = { e -> status = "Update check error: ${e.message}" }
    }
    val review = rememberInAppReviewManager {
        onError = { e -> status = "Review error: ${e.message}" }
    }

    var premium by remember { mutableStateOf(NextGenAds.premium) }
    var bannerSize by remember { mutableStateOf(BannerSize.ADAPTIVE) }
    var template by remember { mutableStateOf(NativeTemplate.MEDIUM) }

    // When set, an opt-in confirm dialog is shown; confirming runs its action (show the ad).
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }

    // Live fill/show-rate stats (bottom panel). Reuses the app-wide tracker registered in SampleApp;
    // an event listener bumps [tick] so the snapshot re-reads and the table recomposes.
    var tick by remember { mutableIntStateOf(0) }
    val statsListener = remember {
        object : AdEventListener {
            override fun onAdRequested(format: AdFormat, adUnitId: String) { tick++ }
            override fun onAdLoaded(format: AdFormat, adUnitId: String) { tick++ }
            override fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) { tick++ }
            override fun onAdShown(format: AdFormat, adUnitId: String) { tick++ }
            override fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) { tick++ }
            override fun onAdImpression(format: AdFormat, adUnitId: String) { tick++ }
            override fun onAdClicked(format: AdFormat, adUnitId: String) { tick++ }
            override fun onAdDismissed(format: AdFormat, adUnitId: String) { tick++ }
        }
    }
    AdEventsEffect(statsListener)
    val statsRows = remember(tick) { SampleApp.showRate.snapshot() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Text(
                text = "  Compose Ads",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Text(text = "Status: $status", fontFamily = FontFamily.Monospace)

        // Premium toggle — mirrors MainActivity: turning it on purges & hides every ad at runtime.
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (premium) "Premium: ON (ad-free)" else "Premium: OFF")
                Switch(
                    checked = premium,
                    onCheckedChange = {
                        premium = it
                        NextGenAds.premium = it
                        status = if (it) "Premium ON — ads purged & hidden" else "Premium OFF — ads allowed"
                    },
                )
            }
        }

        // Compose splash flow: cold start (fresh process) shows an interstitial, a warm / hot
        // relaunch shows an app-open. Launches ComposeSplashActivity, which runs consent → init →
        // splash ad → back here.
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, ComposeSplashActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run Compose splash (cold = interstitial / warm = app-open)")
        }

        // ---- Inline: Banner ----
        // Full-bleed ad card: the ad view spans the card's full width (no inner horizontal padding),
        // so a fixed 320×50 / 300×250 MREC isn't clipped by nested padding on ~360dp-wide screens.
        InlineAdCard(
            title = "Banner",
            chips = {
                ChipRow(
                    options = listOf(
                        "Adaptive" to BannerSize.ADAPTIVE,
                        "320×50" to BannerSize.BANNER,
                        "MREC" to BannerSize.MEDIUM_RECTANGLE,
                    ),
                    selected = bannerSize,
                    onSelect = { bannerSize = it },
                )
            },
        ) {
            BannerAd(
                adUnitId = AdUnits.BANNER,
                size = bannerSize,
                remoteEnabled = AdsConfig.banner,
                onLoaded = { status = "Banner shown ✓ (${bannerSize.name.lowercase()})" },
                onFailed = { status = "Banner failed / no fill" },
            )
        }

        // ---- Inline: Native ----
        InlineAdCard(
            title = "Native",
            chips = {
                ChipRow(
                    options = listOf(
                        "Medium" to NativeTemplate.MEDIUM,
                        "Small" to NativeTemplate.SMALL,
                        "Large" to NativeTemplate.LARGE,
                        "Hero" to NativeTemplate.HERO,
                        "Half-media" to NativeTemplate.HALF_MEDIA,
                        "Stacked" to NativeTemplate.STACKED,
                    ),
                    selected = template,
                    onSelect = { template = it },
                )
            },
        ) {
            NativeAd(
                adUnitId = AdUnits.NATIVE,
                template = template,
                remoteEnabled = AdsConfig.native,
                onLoaded = { status = "Native shown ✓ (${template.name.lowercase()})" },
                onFailed = { status = "Native failed / no fill" },
            )
        }

        // ---- Interstitial ----
        AdSection("Interstitial") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    interstitial.preload(AdsConfig.interstitial)
                    status = "Preloading interstitial…"
                }) { Text("Preload") }
                Button(onClick = {
                    confirm = ConfirmSpec(
                        title = "Show interstitial?",
                        message = "Watch a full-screen interstitial ad now?",
                        confirm = "Show",
                    ) {
                        status = if (interstitial.isReady) "Showing interstitial…" else "Loading interstitial…"
                        interstitial.loadAndShow { status = "Interstitial dismissed ✓" }
                    }
                }) { Text("Load & Show") }
            }
        }

        // ---- Rewarded ----
        AdSection("Rewarded") {
            Button(onClick = {
                confirm = ConfirmSpec(
                    title = "Earn a reward",
                    message = "Watch a short video to earn your reward?",
                    confirm = "Watch",
                ) {
                    status = "Loading rewarded…"
                    rewarded.loadAndShow(
                        onReward = { status = "Reward earned ✓ +${it.amount} ${it.type}" },
                        onComplete = { if (!status.startsWith("Reward")) status = "Rewarded closed — no reward" },
                    )
                }
            }) { Text("Watch to earn") }
        }

        // ---- Rewarded interstitial ----
        AdSection("Rewarded interstitial") {
            Button(onClick = {
                confirm = ConfirmSpec(
                    title = "Earn a reward",
                    message = "Watch a short ad to earn your reward?",
                    confirm = "Watch",
                ) {
                    status = "Loading rewarded interstitial…"
                    rewardedInt.loadAndShow(
                        onReward = { status = "Reward earned ✓ +${it.amount} ${it.type}" },
                        onComplete = { if (!status.startsWith("Reward")) status = "Rewarded interstitial closed" },
                    )
                }
            }) { Text("Watch to earn") }
        }

        // ---- App open ----
        AdSection("App open") {
            Button(onClick = {
                status = if (appOpen.isReady) "Showing app open…" else "Loading app open…"
                // Branded "Welcome back" cover while fetching / bridging to the ad.
                appOpen.loadAndShow(coverStyle = AppOpenCoverStyle.WELCOME) {
                    status = "App open dismissed ✓"
                }
            }) { Text("Show app open (Welcome back)") }
        }

        // ---- In-app update / review ----
        AdSection("In-app update & review") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    status = "Checking for update…"
                    updater.checkForUpdate()
                }) { Text("Check update") }
                OutlinedButton(onClick = {
                    review.launchReview { status = "Review flow finished ✓" }
                }) { Text("Ask review") }
            }
        }

        // ---- Live ad stats (bottom) ----
        StatsPanel(rows = statsRows, onReset = { SampleApp.showRate.reset(); tick++ })
    }

    // Opt-in confirm dialog shown before interstitial / rewarded / rewarded-interstitial ads.
    confirm?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(spec.title) },
            text = { Text(spec.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    spec.onConfirm()
                }) { Text(spec.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel") }
            },
        )
    }
}

/** Spec for the opt-in confirm dialog shown before a full-screen ad; [onConfirm] runs on Confirm. */
private data class ConfirmSpec(
    val title: String,
    val message: String,
    val confirm: String,
    val onConfirm: () -> Unit,
)

/** A titled card whose ad content spans the full card width (title/chips stay inset). */
@Composable
private fun InlineAdCard(
    title: String,
    chips: @Composable () -> Unit,
    ad: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Box(Modifier.padding(horizontal = 16.dp)) { chips() }
            ad() // full card width — no horizontal padding, so fixed banners aren't clipped
        }
    }
}

/** Bottom fill/show-rate table, fed from the app-wide [ShowRateTracker]. */
@Composable
private fun StatsPanel(rows: List<ShowRateTracker.FormatRow>, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ad stats", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Text(
                text = "fill = load/req · use = imp/load",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Header + rows share one horizontal scroll so their columns stay aligned.
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                StatsRow(ShowRateTracker.COLUMNS, header = true)
                if (rows.isEmpty()) {
                    Text(
                        text = "no ad events yet",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    rows.forEach { StatsRow(it.cells(), header = false) }
                }
            }
        }
    }
}

/** One fixed-width, monospaced stats row (FORMAT left-aligned, numbers right-aligned). */
@Composable
private fun StatsRow(cells: List<String>, header: Boolean) {
    Row {
        cells.forEachIndexed { i, text ->
            val leftAligned = i < ShowRateTracker.LEFT_ALIGNED.size && ShowRateTracker.LEFT_ALIGNED[i]
            Text(
                text = text,
                modifier = Modifier
                    .width(if (i == 0) 92.dp else 46.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (leftAligned) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/** A titled card wrapping one ad demo. */
@Composable
private fun AdSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            },
        )
    }
}

/** A wrapping row of single-select [FilterChip]s — chips flow onto the next line so every option
 *  stays visible (no horizontal scrolling needed even when there are many, e.g. native templates). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

