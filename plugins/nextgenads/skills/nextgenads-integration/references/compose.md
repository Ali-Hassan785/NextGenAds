# Jetpack Compose

A separate artifact wrapping the same helpers — same caching, same gates, same event stream. Anything
process-scoped (init, consent, the app-open manager) still belongs in `Application` / your activity,
not in a composable.

## Dependency

```kotlin
implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads-compose:1.4.0")
```

It depends on `:nextgenads`, so you don't need both lines — but adding both is harmless.

**`nextgenads-compose` was not published before `1.1.0`.** A coordinate below that does not resolve.

## Inline ads

```kotlin
@Composable fun BannerAd(adUnitId: String, modifier: Modifier = Modifier,
    size: BannerSize = BannerSize.ADAPTIVE, remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {}, onFailed: () -> Unit = {})

@Composable fun NativeAd(adUnitId: String, modifier: Modifier = Modifier,
    template: NativeTemplate = NativeTemplate.MEDIUM, remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {}, onFailed: () -> Unit = {})

@Composable fun NextGenAdView(adUnitId: String, modifier: Modifier = Modifier,
    adType: AdType = AdType.NATIVE, template: NativeTemplate = NativeTemplate.MEDIUM,
    bannerSize: BannerSize = BannerSize.ADAPTIVE, remoteEnabled: Boolean = true,
    onLoaded: () -> Unit = {}, onFailed: () -> Unit = {})
```

```kotlin
Column {
    NativeAd(AdUnits.NATIVE, template = NativeTemplate.LARGE, modifier = Modifier.fillMaxWidth())
    BannerAd(AdUnits.BANNER, modifier = Modifier.fillMaxWidth())
}
```

Each shows its shimmer while loading and hides itself when ads are disabled. `NextGenAdView` is the
`BannerNativeView` equivalent — one slot that switches on `adType`. Sizes, templates and the theming
rules are identical to XML: see `references/inline-ads.md`.

## Full-screen ads

Each `remember*` returns a `@Stable` controller. `preload = true` (default) warms the cache on first
composition.

```kotlin
@Composable fun rememberInterstitialAd(adUnitId: String, preload: Boolean = true,
                                       remoteEnabled: Boolean = true): InterstitialAdController

@Stable class InterstitialAdController {
    val isReady: Boolean
    fun preload(remoteEnabled: Boolean = true)
    fun show(onComplete: () -> Unit = {})
    fun loadAndShow(timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {})
    fun showEvery(nth: Int, forceLoad: Boolean = false,
                  timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {}): Boolean
    fun showFirstThenEvery(nth: Int, forceLoad: Boolean = false,
                  timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {}): Boolean
}
```

```kotlin
val interstitial = rememberInterstitialAd(AdUnits.INTERSTITIAL)
Button(onClick = { interstitial.loadAndShow { navigate() } }) { Text("Next") }
```

```kotlin
@Composable fun rememberRewardedAd(adUnitId: String, preload: Boolean = true,
                                   remoteEnabled: Boolean = true): RewardedAdController
@Composable fun rememberRewardedInterstitialAd(adUnitId: String, preload: Boolean = true,
                                   remoteEnabled: Boolean = true): RewardedInterstitialAdController

@Stable class RewardedAdController {   // and RewardedInterstitialAdController
    val isReady: Boolean
    fun preload(remoteEnabled: Boolean = true)
    fun show(onReward: (RewardItem) -> Unit, onComplete: () -> Unit = {})
    fun loadAndShow(onReward: (RewardItem) -> Unit,
                    timeoutMs: Long = NextGenAdsConfig.rewardedForceShowTimeoutMs,
                    onComplete: () -> Unit = {})
}
```

```kotlin
val rewarded = rememberRewardedAd(AdUnits.REWARDED)
Button(onClick = {
    var earned = false
    rewarded.loadAndShow(
        onReward = { earned = true; credit(it.amount) },
        onComplete = { if (!earned) toast("Watch the full video") },
    )
}) { Text("Earn coins") }
```

`onComplete` still fires **exactly once**, and only one full-screen ad may show at a time — the rules
in `references/fullscreen-ads.md` apply unchanged.

## App-open

```kotlin
@Composable fun rememberAppOpenAd(adUnitId: String, preload: Boolean = true,
                                  remoteEnabled: Boolean = true): AppOpenAdController

@Stable class AppOpenAdController {
    val isReady: Boolean
    fun preload(remoteEnabled: Boolean = true)
    fun loadAndShow(timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
                    coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
                    onComplete: () -> Unit = {})
}
```

This controller is for **on-demand** shows only (e.g. a Compose splash):

```kotlin
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val appOpen = rememberAppOpenAd(AdUnits.SPLASH_APP_OPEN)
    LaunchedEffect(Unit) {
        appOpen.loadAndShow(coverStyle = AppOpenCoverStyle.LOADING, onComplete = onDone)
    }
}
```

The **foreground-return manager stays in `Application.onCreate`** (`AppOpenAdManager.install` /
`NextGenAdsBootstrap.configure`). It is process-scoped and must outlive every composable — do not try
to drive it from Compose.

## Consent and events

```kotlin
@Composable fun rememberConsentManager(testDeviceHashedId: String? = null): ConsentManager
@Composable fun AdEventsEffect(listener: AdEventListener)
```

`AdEventsEffect` registers the listener for the composition's lifetime and unregisters on dispose —
use it for screen-scoped tracking, not app-wide analytics (register those once in `Application`).

Consent must still be gathered **before** `NextGenAds.initialize`; `rememberConsentManager` gives you
the manager, but the ordering rule in `references/setup.md` is unchanged.

## Non-ad extras

```kotlin
@Composable fun rememberInAppReviewManager(configure: InAppReviewManager.() -> Unit = {}): InAppReviewManager
@Composable fun rememberInAppUpdateManager(updateType: UpdateType = UpdateType.FLEXIBLE,
    autoCheck: Boolean = true, configure: InAppUpdateManager.() -> Unit = {}): InAppUpdateManager
```

Both require a `ComponentActivity` host and throw otherwise.

## Enabling Compose in the host

Standard requirements — the wrapper adds nothing unusual:

```kotlin
android { buildFeatures { compose = true } }
// plus the Compose compiler plugin (org.jetbrains.kotlin.plugin.compose) on Kotlin 2.x
```
