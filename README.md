# NextGenAds

**A premium Android ad-helper library for Google's Next-Gen Mobile Ads SDK — banner, native,
interstitial, rewarded, rewarded-interstitial and app-open, with UMP consent, preloading, per-unit
caching, retry/backoff, a premium kill-switch, twelve native templates, a ready-made splash flow and
a first-class Jetpack Compose wrapper.**

NextGenAds wraps Google's **Next-Gen Mobile Ads SDK** (`com.google.android.libraries.ads.mobile.sdk`)
and the **User Messaging Platform (UMP)** behind small, opinionated helpers that are tuned for **show
rate** and **fill rate**. Every SDK callback is marshalled to the **main thread**, so you can touch UI
from any callback, and the whole surface is annotated with `@JvmStatic` / `@JvmOverloads` so it reads
naturally from **Java** as well as Kotlin.

> Current version: **1.4.0**

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Using with Claude Code](#using-with-claude-code)
- [Quick start](#quick-start)
- [Consent (UMP / GDPR)](#consent-ump--gdpr)
- [Initialization](#initialization)
- [Defaults (`NextGenAdsConfig`)](#defaults-nextgenadsconfig)
- [Banner ads](#banner-ads)
- [Native ads](#native-ads)
- [Interstitial ads](#interstitial-ads)
- [Rewarded ads](#rewarded-ads)
- [Rewarded interstitial ads](#rewarded-interstitial-ads)
- [App open ads](#app-open-ads)
- [Splash screen (`SplashAdGate`)](#splash-screen-splashadgate)
- [Jetpack Compose](#jetpack-compose)
- [Premium / ad-free users](#premium--ad-free-users)
- [Ad events (analytics & revenue)](#ad-events-analytics--revenue)
- [Show rate & fill rate](#show-rate--fill-rate)
- [Test ad unit IDs](#test-ad-unit-ids)
- [ProGuard / R8](#proguard--r8)
- [Troubleshooting](#troubleshooting)
- [API reference](#api-reference)
- [License](#license)

---

## Features

| Format | Entry point | Preload | Cache | Highlights |
| --- | --- | :---: | :---: | --- |
| Banner | `BannerAdHelper` / `BannerNativeView` | ✅ | ✅ per unit + size | Anchored & inline adaptive, fixed IAB sizes, **collapsible** (top/bottom), shimmer |
| Native | `NativeAdHelper` / `BannerNativeView` | ✅ | ✅ per unit | **12 templates** + bring-your-own layouts + auto-shimmer |
| Interstitial | `Interstitials` / `SplashAd` | ✅ | ✅ per unit | Frequency cap, counter gating, on-demand load-and-show, splash flow |
| Rewarded | `RewardedAds` | ✅ | ✅ per unit | Reward callback |
| Rewarded interstitial | `RewardedInterstitials` | ✅ | ✅ per unit | Reward callback |
| App open | `AppOpenAds` / `AppOpenAdManager` | ✅ | ✅ per unit | 4 h expiry + auto-show on foreground |
| Consent | `ConsentManager` | — | — | UMP GDPR flow |
| Setup | `NextGenAdsBootstrap` | — | — | One-call consent → init + connectivity recovery + app-open |

- **One-call setup** — `NextGenAdsBootstrap` gathers UMP consent, initializes in the correct order,
  and installs connectivity recovery + the app-open manager.
- **Manifest App ID** — `NextGenAds.initialize` reads the AdMob App ID from the manifest meta-data
  UMP already needs, so there's a single source of truth.
- **Premium-aware everywhere** — a single gate (`NextGenAds.canShowAds()`) is honoured by every
  helper; flipping to premium at runtime **purges caches and hides shown ads**.
- **Tuned for show rate** — preload + per-unit cache, exponential-backoff retries, a shared request
  circuit breaker, connectivity recovery, and stale-ad expiry across all formats.
- **Remote-config toggles** — an app-wide `adsLoadEnabled` plus a per-format switch for each format.
- **Shimmer placeholders** while ads load — hand-tuned for built-ins, **auto-generated** for custom
  layouts.
- **Main-thread safety** — every ad callback is delivered on the main thread.
- **One analytics hook** — a single `AdEventListener` for every format, including **paid-revenue**
  for ROAS, plus a built-in `ShowRateTracker`.
- **Jetpack Compose wrapper** — `nextgenads-compose` mirrors every format with `@Composable` /
  `remember*` APIs, reusing the same caches and logic.

---

## Requirements

| | |
| --- | --- |
| `minSdk` | 24 |
| `compileSdk` | 37 |
| Java | 11 (source & target) |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.2.10 |
| Ads SDK | `ads-mobile-sdk` 1.2.1 (Next-Gen) |
| UMP | `user-messaging-platform` 4.0.0 |
| Shimmer | `com.facebook.shimmer:shimmer` 0.5.0 |
| Lifecycle | `androidx.lifecycle:lifecycle-process` 2.6.2 |

The Ads SDK, UMP, Shimmer, `lifecycle-process`, and the Google Play In-App Update library are exposed
transitively (`api`), so you don't declare them yourself when consuming NextGenAds.

---

## Installation

### 1. Add the dependency

**Via JitPack (recommended):**

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()                                    // transitive Ads SDK + UMP
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```
```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads:1.4.0")
}
```

> **Note the group.** This repo publishes two modules (`nextgenads` and `nextgenads-compose`), so
> each is addressed with JitPack's multi-module group `com.github.<user>.<repo>` — i.e.
> `com.github.Ali-Hassan785.NextGenAds`. The flat `com.github.Ali-Hassan785:nextgenads:1.4.0` also
> resolves, but on a multi-module repo JitPack turns it into an **aggregate** that pulls in *both*
> modules — so an XML-only app would drag in the Compose wrapper and all of Jetpack Compose. Prefer
> the module coordinate above.

**Or as a local module:**

```kotlin
// settings.gradle.kts
include(":app", ":nextgenads")
// app/build.gradle.kts
dependencies { implementation(project(":nextgenads")) }
```

**Or as a private dependency (GitHub Packages):**

NextGenAds also publishes to a **private, authenticated** Maven repo on GitHub Packages. Auth uses
your **GitHub username** and a **Personal Access Token (classic)** with the **`read:packages`** scope
(GitHub Packages does not accept an arbitrary password).

1. **Create a token** — GitHub → *Settings → Developer settings → Personal access tokens →
   Tokens (classic)* → generate one with `read:packages` and read access to the repo.

2. **Store the credentials outside the repo** — in `~/.gradle/gradle.properties` (never committed):

   ```properties
   gpr.user=your_github_username
   gpr.key=ghp_your_personal_access_token_here
   ```

3. **Add the private repo + dependency:**

   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven {
               url = uri("https://maven.pkg.github.com/Ali-Hassan785/NextGenAds")
               credentials {
                   username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                   password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
               }
           }
       }
   }
   ```
   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation("com.github.Ali-Hassan785:nextgenads:1.4.0")
   }
   ```

> In CI, set `GITHUB_ACTOR` and `GITHUB_TOKEN` as secrets instead of using `gradle.properties`.
> **Never** hard-code the token in a committed `build.gradle.kts`.

### 2. Declare your AdMob App ID in the manifest

```xml
<application ...>
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
</application>
```

> `INTERNET` and `ACCESS_NETWORK_STATE` are declared by the library and merged automatically — you
> don't add them. The AdMob App ID is **required**: UMP reads it to fetch your consent configuration,
> and `NextGenAds.initialize` reads it as the single source of truth for the SDK (see
> [Initialization](#initialization)).

---

## Using with Claude Code

This repo ships a **Claude Code skill** that teaches Claude to integrate NextGenAds properly —
consent-first ordering, every format's API, and the traps that silently cost fill. It's
self-contained, so it works in your app without this README.

Install it either way:

```shell
# As a plugin (updatable)
/plugin marketplace add Ali-Hassan785/NextGenAds
/plugin install nextgenads@nextgenads
```

```shell
# Or just copy the folder — into your project, or ~/.claude/skills/ for every project
cp -r plugins/nextgenads/skills/nextgenads-integration <your-app>/.claude/skills/
```

Then ask for what you want:

> "Add an app-open ad on the splash and a banner on the main screen."

Claude reads the ad units and setup you already have, and wires the rest. It's verified against
`1.4.0` — if you're on an older version, tell it which.

---

## Quick start

The whole lifecycle is **gather consent → initialize → preload → show**, and
[`NextGenAdsBootstrap`](#one-call-bootstrap-nextgenadsbootstrap) wires the first two for you. Declare
your ad units once (the library never hard-codes any), configure in `Application`, then bring ads up
on your first screen.

```kotlin
// AdUnits.kt — one source of truth for every unit id.
object AdUnits {
    const val BANNER       = "ca-app-pub-3940256099942544/9214589741"
    const val NATIVE       = "ca-app-pub-3940256099942544/2247696110"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED     = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_INT = "ca-app-pub-3940256099942544/5354046379"
    const val APP_OPEN     = "ca-app-pub-3940256099942544/9257395921"
}
```

```kotlin
// 1. Application.onCreate — connectivity recovery + auto-show app-open (skip your splash):
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NextGenAdsBootstrap.configure(
            application = this,
            appOpenUnitId = AdUnits.APP_OPEN,
            skipAppOpenOn = listOf(SplashActivity::class.java),
        )
    }
}
```

```kotlin
// 2. First screen (splash / main) — consent → initialize (App ID from the manifest) → preload:
NextGenAdsBootstrap.gatherConsentThenInitialize(
    activity = this,
    testDeviceHashedId = if (BuildConfig.DEBUG) TEST_DEVICE_HASH else null,   // null in release
    testDeviceIds = TEST_DEVICE_IDS,
) {
    // Ads are ready here — warm the caches for an instant first show.
    Interstitials.preload(AdUnits.INTERSTITIAL)
    NativeAdHelper.preload(AdUnits.NATIVE, count = 2)
}
```

```kotlin
// 3. Show a native ad (XML drop-in view):
findViewById<BannerNativeView>(R.id.adView).load(adUnitId = AdUnits.NATIVE)

// 4. Show an interstitial at a transition (onComplete always fires, so navigation is uniform):
Interstitials.get(AdUnits.INTERSTITIAL).show(this) { goToNextScreen() }
```

**From Java** the bootstrap reads the same — a `Runnable` for the ready callback:

```java
NextGenAdsBootstrap.configure(this, AdUnits.APP_OPEN,
        Collections.singletonList(SplashActivity.class));

NextGenAdsBootstrap.gatherConsentThenInitialize(this, testHash, TEST_DEVICE_IDS, () -> {
    Interstitials.preload(AdUnits.INTERSTITIAL);
});
```

Prefer a real splash gate that shows an ad while your splash is up? See
[Splash screen](#splash-screen-splashadgate).

---

## Consent (UMP / GDPR)

`ConsentManager` wraps the User Messaging Platform. It must be called **before** the SDK is
initialized — a pre-consent ad request is refused. Once you use `ConsentManager`, it also wires
`NextGenAds.consentProvider` automatically, so no helper can fire a request before consent is
resolved.

```kotlin
val consent = ConsentManager.getInstance(this)

consent.gatherConsent(this) { error ->
    if (error != null) Log.w("Ads", "Consent error: ${error.message}")
    if (consent.canRequestAds) {
        NextGenAds.initialize(this) { /* preload */ }
    }
}

// Privacy options entry-point (e.g. from a Settings screen):
if (consent.isPrivacyOptionsRequired) {
    consent.showPrivacyOptionsForm(this) { /* dismissed */ }
}
```

### Testing the consent form outside the EEA

Pass your device's hashed id (printed in Logcat on first run) to `getInstance`, then `forceEea = true`
so the form actually appears:

```kotlin
ConsentManager.getInstance(this, "33BE2250B43518CCDA7DE426D04EE231")
    .gatherConsent(this, forceEea = true) { /* form will show */ }
```

> Never ship a test-device hash or `forceEea = true` in a release build — guard them behind
> `BuildConfig.DEBUG`. (`NextGenAdsBootstrap.gatherConsentThenInitialize` does exactly this: it
> forces the EEA geography only when a non-null `testDeviceHashedId` is supplied.)

| Member | Description |
| --- | --- |
| `getInstance(context, testDeviceHashedId? = null)` | The shared manager; the hash registers a UMP test device. |
| `canRequestAds: Boolean` | `true` once ads may be requested (consent obtained or not required). |
| `isPrivacyOptionsRequired: Boolean` | `true` when a "Privacy options" entry-point must be shown. |
| `gatherConsent(activity, forceEea = false, onComplete)` | Updates consent info and shows the form if required. |
| `showPrivacyOptionsForm(activity, onDismissed)` | Presents the privacy options form. |
| `reset()` | Clears all consent state (testing only). |

---

## Initialization

Declare your AdMob App ID once in `AndroidManifest.xml` (UMP needs it there anyway), then initialize
**without repeating the id** — the library reads it from that manifest entry, so there's a single
source of truth and no second copy in code to drift out of sync:

```kotlin
NextGenAds.initialize(
    context = this,
    testDeviceIds = listOf("YOUR_TEST_DEVICE_ID"),   // optional, safe to omit
) {
    // Runs on the main thread once ready — a good place to preload.
}
```

**From Java:**

```java
NextGenAds.initialize(this, testDeviceIds, () -> {
    Interstitials.preload(AdUnits.INTERSTITIAL);
});
```

> **Watch the format.** The **App ID** uses a tilde (`ca-app-pub-…~…`); an ad **unit** id uses a
> slash (`ca-app-pub-…/…`) and is *not* interchangeable. Passing a unit id fails only later, at
> request time, with a cryptic `INVALID_REQUEST` — so the library logs a clear **warning** when the
> resolved id isn't of the form `ca-app-pub-…~…`, and a clear **error** (and skips init, requesting
> nothing) when the manifest meta-data is missing or blank.

Prefer to supply the id in code (e.g. from `BuildConfig` or remote config)? Pass it explicitly:

```kotlin
NextGenAds.initialize(this, appId = "ca-app-pub-XXXX~YYYY", testDeviceIds = listOf(/* … */)) { }
```

Initialization runs off the main thread (as the Next-Gen SDK requires) and the callback is delivered
on the main thread. Calling `initialize` again after it finishes just runs the callback immediately.
**Any load/preload issued before init completes is queued and replayed** once the SDK is ready — so
you can preload eagerly without racing initialization.

### One-call bootstrap (`NextGenAdsBootstrap`)

Consent must be gathered **before** initialization, and app-open + connectivity recovery are usually
wired the same way in every app. `NextGenAdsBootstrap` packages that sequence. It's app-agnostic (you
pass your own units / ids), holds no state, and is built entirely from the library's own public
pieces (`ConsentManager`, `NextGenAds`, `AppOpenAdManager`), so you can keep wiring those yourself if
you prefer.

```kotlin
// Application.onCreate — connectivity recovery + auto-show app-open. Returns the manager to tune:
val appOpen: AppOpenAdManager? = NextGenAdsBootstrap.configure(
    application = this,
    appOpenUnitId = AdUnits.APP_OPEN,                    // omit / null to skip the app-open manager
    skipAppOpenOn = listOf(SplashActivity::class.java),  // screens the app-open must not cover
    // connectivityRecovery = true                       // default
)
appOpen?.apply {
    loadTimeoutMs = 5_000L
    showOnColdStart = false
    coverStyle = AppOpenCoverStyle.WELCOME
}

// First screen — consent → initialize (App ID from the manifest) → onReady:
NextGenAdsBootstrap.gatherConsentThenInitialize(
    activity = this,
    testDeviceHashedId = if (BuildConfig.DEBUG) TEST_DEVICE_HASH else null,   // null in release
    testDeviceIds = TEST_DEVICE_IDS,
) {
    Interstitials.preload(AdUnits.INTERSTITIAL)          // ads are ready here
}
```

Register your own ad-event listeners and set any `NextGenAdsConfig` overrides around these calls; the
bootstrap touches neither, so it never clobbers your config. If you supply the App ID in code instead
of the manifest, call `ConsentManager` + `NextGenAds.initialize` yourself.

### `NextGenAds` — key members

| Member | Description |
| --- | --- |
| `initialize(context, testDeviceIds = [], onComplete? )` | Initializes the SDK once, reading the App ID from the manifest. |
| `initialize(context, appId, testDeviceIds = [], onComplete? )` | Same, with the App ID supplied explicitly. |
| `isInitialized(): Boolean` | Whether initialization has completed. |
| `initializationStatus: InitializationStatus?` | Per-mediation-adapter status after init (`null` until then). |
| `enabled: Boolean` | Local master kill-switch (default `true`). Setting `false` purges & hides all ads. |
| `adsLoadEnabled: Boolean` | Remote-config master switch (default `true`); folds into the gate like `enabled`. |
| `loggingEnabled: Boolean` | Verbose Logcat under tag `NextGenAds` (default `true`). |
| `setFormatEnabled(format, enabled)` / `isFormatEnabled(format)` | Per-format remote toggles; also `bannerAdsEnabled`, `nativeAdsEnabled`, `interstitialAdsEnabled`, `rewardedAdsEnabled`, `rewardedInterstitialAdsEnabled`, `appOpenAdsEnabled`. |
| `setAppVolume(0f..1f)` / `setAppMuted(bool)` | Mirror your app's volume/mute onto video & rewarded ad audio (clamped; safe pre-init). |
| `openAdInspector(onClosed?)` | Opens the on-device Ad Inspector (test devices only). |
| `enableConnectivityRecovery(context)` | Re-warm caches & clear the breaker when the network returns. |
| `isRequestPaused()` / `resetRequestBreaker()` | Circuit-breaker state (see [Defaults](#defaults-nextgenadsconfig)). |
| `isFullScreenAdShowing()` | `true` while any full-screen ad from this library is on screen. |
| `premium`, `premiumProvider`, `canShowAds()`, `refreshPremiumState()`, `clearAllAds()` | See [Premium](#premium--ad-free-users). |
| `registerEventListener` / `unregisterEventListener` | See [Ad events](#ad-events-analytics--revenue). |

---

## Defaults (`NextGenAdsConfig`)

`NextGenAdsConfig` is the one place to set the timings each format would otherwise take as a per-call
argument or a per-ad-unit property — the splash timer, the on-demand ("forced") show timeout, the
retry budget, the frequency cap, the circuit breaker. Set only what differs from the default; every
value is read **live**, so a mid-session change applies on the next call with no re-init.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NextGenAdsConfig.splashTimeoutMs = 6_000L      // leave the splash sooner
        NextGenAdsConfig.forceShowTimeoutMs = 5_000L   // cap every on-demand interstitial fetch
        NextGenAdsConfig.minIntervalMs = 60_000L       // ≥ 60s between interstitials, per unit
    }
}
```

> Re-asserting a value that already equals the default on every process start is discouraged — set
> only what you actually change.

Nothing here is a hard override — it is the **default** each call site and helper falls back to, so an
explicit argument or an explicit per-unit property still wins for that one call / unit:

```kotlin
SplashAd.show(this, UNIT, timeoutMs = 3_000L) { goToMain() }   // this call ignores splashTimeoutMs
Interstitials.get(UNIT).maxRetries = 5                         // this unit ignores maxRetries
```

| Property | Default | Description |
| --- | --- | --- |
| `splashMinDelayMs` | `1_500` | Minimum time the splash stays up before an ad may show; also the floor waited out when the ad fails or ads are off. |
| `splashTimeoutMs` | `8_000` | Maximum wait for the splash ad before proceeding regardless. Coerced ≥ `splashMinDelayMs`; `0` disables it. |
| `splashRetryOnFailure` | `false` | Whether a failed splash load keeps retrying in the background. `false` = single attempt. |
| `forceShowTimeoutMs` | `8_000` | Bound on an on-demand interstitial / app-open fetch (`loadAndShow`, forced counter shows). `0` waits for the load result. |
| `rewardedForceShowTimeoutMs` | `10_000` | Bound on an on-demand rewarded / rewarded-interstitial fetch — longer, since the user opted in to watch. |
| `maxRetries` | `3` | Automatic reload attempts after a failed load, with 1s/2s/4s… backoff. |
| `adValidityMs` | `55 min` | How long a cached full-screen/native/banner ad stays valid; stale ads are dropped and re-requested. (App-open is excluded — its 4 h validity is a fixed SDK rule.) |
| `autoReload` | `false` | Whether helpers auto-request the next ad after each show. `true` trades extra requests for a higher show rate. |
| `minIntervalMs` | `0` | Minimum gap between two full-screen ads of the same format — the frequency cap, **enforced per ad unit** for interstitial and app-open. `0` = no cap. |
| `loadingOverlayMs` | `0` | Artificial dwell on a "Showing ad…" cover before an **already-cached** ad opens. `0` = a ready ad shows instantly. |
| `minLoadingCoverMs` | `500` | Minimum time the "Loading ad…" cover stays up during a genuine fetch, so a warm fetch reads as loading instead of a flash. |
| `appOpenLoadTimeoutMs` | `5_000` | Default for `AppOpenAdManager.loadTimeoutMs`: the window after a foreground return during which a just-requested app-open may still show. |
| `maxRequestFailures` | `3` | Consecutive **network/timeout** request failures (any format, no success in between) that trip the cooldown. |
| `requestCooldownMs` | `3 min` | How long to pause **new** requests once tripped. Cached ads keep showing; one success resets the count. |

> `minIntervalMs` is the app-wide frequency cap **per ad unit**, not a single global timer — two
> different interstitial units are capped independently.
>
> Banner and native are deliberately **not** configured here: they are singletons, so set their
> tuning directly on `BannerAdHelper` / `NativeAdHelper` (e.g. `BannerAdHelper.maxRetries = 3`).
> Mirroring them into `NextGenAdsConfig` would only create a second source of truth.

---

## Banner ads

Anchored/inline adaptive and fixed-size banners with a shimmer placeholder and optional preloading.
The container is **collapsed** on no-fill so no empty gap is left behind.

```kotlin
// Preload (e.g. right after init):
BannerAdHelper.preload(activity = this, adUnitId = AdUnits.BANNER, count = 1)

// Show into any ViewGroup — attaches a preloaded banner instantly, else loads behind a shimmer:
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.bannerContainer),
    adUnitId = AdUnits.BANNER,
    onLoaded = { /* shown */ },
    onFailed = { error -> /* no fill — container is collapsed automatically */ },
)
```

> **Adaptive banners now work from `onCreate`.** An adaptive banner derives its width from the
> container. If `loadAdaptiveBanner` (or `BannerNativeView.load`) runs before the container is
> measured, the helper now **waits one layout pass** and requests with the real container width
> instead of falling back to the full-screen width — so a banner in a padded card or split pane no
> longer overflows or logs "Not enough space to show the full ad". Fixed sizes and already-laid-out
> containers are unaffected.

| Member | Default | Description |
| --- | --- | --- |
| `maxCachePerUnit` | `2` | Max preloaded banners cached per ad unit (and size). |
| `maxRetries` | `2` | Reload attempts after a failed load (backoff 1s/2s/4s). |
| `adValidityMs` | `55 min` | Preloaded-banner cache validity; stale banners are destroyed on poll. |
| `preload(activity, adUnitId, count = 1, widthDp, size = ADAPTIVE, remoteEnabled = true)` | — | Warms the cache. |
| `loadAdaptiveBanner(activity, container, adUnitId, refill = false, collapsible = null, size = ADAPTIVE, onLoaded, onFailed, remoteEnabled = true)` | — | Shows a banner. |
| `containerWidthDp(activity, container)` | — | The content width to pass as `widthDp` for a padded slot. |
| `clearAll()` | — | Destroys the pool and hides banners in populated containers. |

### Banner sizes

Pass a `BannerSize` to `preload` / `loadAdaptiveBanner` (or `BannerNativeView.load(bannerSize = …)`,
or the `app:ngad_banner_size` XML attribute) to request a size other than the default full-width
adaptive banner. The preload cache is keyed by **ad unit *and* size**, so a banner preloaded at one
size is never attached to a request for another.

| `BannerSize` | Dimensions | Use for |
| --- | --- | --- |
| `ADAPTIVE` *(default)* | full width × adaptive height | Pinned top/bottom banner slots (large anchored adaptive). |
| `ADAPTIVE_INLINE` | full width × taller adaptive height | Banners inside scrolling content / feeds. |
| `BANNER` | 320 × 50 | Fixed standard banner. |
| `LARGE_BANNER` | 320 × 100 | Fixed taller banner. |
| `FULL_BANNER` | 468 × 60 | Tablets. |
| `LEADERBOARD` | 728 × 90 | Tablets. |
| `MEDIUM_RECTANGLE` | 300 × 250 | In-content MREC. |

```kotlin
// Preload and show a 300×250 MREC:
BannerAdHelper.preload(this, AdUnits.BANNER, count = 1, size = BannerSize.MEDIUM_RECTANGLE)
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.mrecContainer),
    adUnitId = AdUnits.BANNER,
    size = BannerSize.MEDIUM_RECTANGLE,
)
```

Fixed sizes ignore `widthDp` and are centered in their container; adaptive sizes use the container's
content width.

### Collapsible banners

Pass a `BannerCollapsible` to request a **collapsible banner** — it shows as a larger overlay on the
first impression and collapses to the anchored banner. Anchor it at the edge where the banner sits.

```kotlin
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.bannerContainer),   // pin the container to that edge
    adUnitId = AdUnits.BANNER,
    collapsible = BannerCollapsible.BOTTOM,            // or BannerCollapsible.TOP
    onLoaded = { /* shown */ },
    onFailed = { error -> /* no fill */ },
)
```

Collapsible requests always load **fresh** (the preload cache holds standard banners), so `refill`
has no effect for them.

### A note on the three `BANNER` names

There are three unrelated things named "BANNER" — they are **not** interchangeable:

| Symbol | What it is |
| --- | --- |
| `AdType.BANNER` | A **real AdMob banner** (an `AdView`), rendered via `BannerNativeView` / `BannerAdHelper`. |
| `NativeTemplate.BANNER` | A **native ad** styled as a slim single-line banner strip (no `AdView` — it's a native creative). |
| `BannerSize.BANNER` | The **fixed 320×50 size** you pass to a banner request. |

> A common mistake is passing `NativeTemplate.BANNER` where a `BannerSize` was expected (or vice
> versa). If you want a fixed-size AdMob banner, use `BannerSize.BANNER`; if you want a native
> creative that *looks* like a banner strip, use `NativeTemplate.BANNER` on a native placement.

---

## Native ads

Two ways to render native ads: a drop-in XML view, or the lower-level helper + template view.

### 1. `BannerNativeView` — one drop-in view (banner **or** native)

```xml
<com.alihassan.nextgenads.BannerNativeView
    android:id="@+id/adView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ngad_ad_type="nativead"
    app:ngad_template="medium" />
```
```kotlin
val adView = findViewById<BannerNativeView>(R.id.adView)
adView.load(
    adUnitId = AdUnits.NATIVE,
    remoteEnabled = remoteConfig.getBoolean("home_native"), // your flag; false hides the view
    nativeTemplate = NativeTemplate.MEDIA_LEFT,             // optional override
    onLoaded = { /* shown */ },
    onFailed = { /* failed — the view hides itself */ },
)
// In onDestroy():
adView.destroy()
```

The view loads only when ads are allowed, `remoteEnabled` is `true`, and `adUnitId` is non-blank;
otherwise it hides itself. It shows a shimmer while loading and prefers a cached ad for instant fill.

**XML attributes**

| Attribute | Values | Default |
| --- | --- | --- |
| `app:ngad_ad_type` | `banner`, `nativead` | `nativead` |
| `app:ngad_template` | any template name — `small`, `medium`, `large`, `banner`, `media_left`, `collapsible`, `hero`, `feed`, `spotlight`, `action_top`, `half_media`, `stacked` | `medium` |
| `app:ngad_banner_size` | banner size name (used when `ngad_ad_type="banner"`) — `adaptive`, `adaptive_inline`, `banner`, `large_banner`, `full_banner`, `leaderboard`, `medium_rectangle` | `adaptive` |
| `app:ngad_customLayout` | a `@layout` reference (overrides `ngad_template`) | — |
| `app:ngad_customShimmer` | a `@layout` reference (else auto-generated) | — |

### 2. `NativeAdHelper` + `NativeTemplateView` (lower level)

```kotlin
// Preload into the per-unit cache:
NativeAdHelper.preload(AdUnits.NATIVE, count = 2)

// Bind into a template view (shimmer until ready, cache-first). refill re-warms the cache:
NativeAdHelper.populate(templateView, AdUnits.NATIVE, refill = true)

// Or get the raw ad:
NativeAdHelper.load(AdUnits.NATIVE, onLoaded = { ad -> /* … */ })

// Free cached ads (e.g. on logout / low memory):
NativeAdHelper.clear(AdUnits.NATIVE)   // or clear() for all units
```

`NativeAdHelper` defaults: `maxCachePerUnit = 3`, `maxRetries = 3`, `adValidityMs = 55 min`.

### Built-in templates (12)

| Template | Layout |
| --- | --- |
| `SMALL` | Compact row: icon + headline + body + CTA (no media). |
| `MEDIUM` | Icon + headline + rating + body + media + CTA (the all-rounder). |
| `LARGE` | Media-forward card for full-width slots / dialogs. |
| `BANNER` | Single-line strip that mimics a banner footer (no media). |
| `MEDIA_LEFT` | Media on the left, headline + body top-right, CTA bottom-right. |
| `COLLAPSIBLE` | Media on top with a down-arrow control that collapses the media into a compact ad. |
| `HERO` | Cinematic full-width media up top with the "Ad" badge overlaid, then icon + headline, body and a bold CTA. |
| `FEED` | Sponsored-post styling (icon + advertiser header, headline, media, body, CTA) for content feeds. |
| `SPOTLIGHT` | Centred composition (icon, headline, rating, body, media, CTA) for dialogs / empty states. |
| `ACTION_TOP` | CTA pinned at the top, with icon, headline, advertiser, rating, body and media below it. |
| `HALF_MEDIA` | Card split ~50/50: media on the left half, headline + advertiser + rating + body + CTA on the right. |
| `STACKED` | Compact card: "Ad" badge + headline on top, a full-width 120dp media, then a full-width CTA. |

Select any by name from XML (`app:ngad_template="hero"`) or in code (`NativeTemplate.HERO`). All
templates use a ripple CTA, an "Ad" attribution badge and Roboto typography. The creative templates
(`HERO`, `FEED`, `SPOTLIGHT`, `ACTION_TOP`, `STACKED`) ship no shimmer XML — one is auto-generated
from the layout.

### Your own custom template

Supply your **own layout** and the same shimmer / cache / retry / tracking pipeline drives it.

**A. ID-contract (no code)** — make the layout's root a `NativeAdView` and give the asset views the
library IDs. Binding, asset registration and click/impression tracking are then automatic.

```xml
<!-- res/layout/my_native.xml -->
<com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView ...>
    <ImageView android:id="@+id/ngad_icon" ... />
    <TextView  android:id="@+id/ngad_headline" ... />
    <TextView  android:id="@+id/ngad_body" ... />
    <com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
        android:id="@+id/ngad_media" ... />
    <TextView  android:id="@+id/ngad_cta" ... />
</com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView>
```

Recognised IDs (include only the ones you want): `ngad_headline`, `ngad_body`, `ngad_cta`,
`ngad_icon`, `ngad_advertiser`, `ngad_stars`, `ngad_media`, `ngad_collapse`.

Point a view at it — from XML (overrides `app:ngad_template`) or in code:

```xml
<com.alihassan.nextgenads.nativead.NativeTemplateView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ngad_customLayout="@layout/my_native" />   <!-- shimmer auto-generated -->
```
```kotlin
templateView.setCustomTemplate(R.layout.my_native)   // shimmer auto-generated from the layout
NativeAdHelper.populate(templateView, AdUnits.NATIVE)
```

**B. Custom binder (full control)** — for a layout with arbitrary IDs, bind the assets yourself; you
are then responsible for registering them and calling `registerNativeAd`:

```kotlin
templateView.setCustomTemplate(R.layout.my_native) { adView, ad ->
    val title = adView.findViewById<TextView>(R.id.my_title)
    title.text = ad.headline
    adView.headlineView = title
    val media = adView.findViewById<MediaView>(R.id.my_media)
    media.imageScaleType = ImageView.ScaleType.CENTER_CROP
    adView.registerNativeAd(ad, media)
}
```

`setCustomTemplate(layout, shimmer = 0, autoShimmer = true, binder = null)` — omit `shimmer` and keep
`autoShimmer = true` and `NativeTemplateView` builds a shimmer from your layout (`ShimmerSkeleton`);
pass `autoShimmer = false` to show no placeholder. You can also call
`ShimmerSkeleton.fromLayout(context, R.layout.my_native)` directly.

**Media scaling** — `NativeTemplateView.mediaScaleType` (default `ImageView.ScaleType.CENTER_CROP`)
controls how native media fills its `MediaView`, so a creative whose aspect ratio differs from the
slot fills it instead of letterboxing. Set `FIT_CENTER` to show the whole creative.

### Theming

Override any of these colors in your app's `colors.xml` to re-theme **every** template at once (same
resource names win at merge time):

```xml
<color name="ngad_surface">#FFFFFFFF</color>       <!-- card background -->
<color name="ngad_stroke">#FFEDEFF3</color>        <!-- card border -->
<color name="ngad_headline">#FF0B0E14</color>      <!-- headline text -->
<color name="ngad_body">#FF5E6470</color>          <!-- body / advertiser text -->
<color name="ngad_cta">#FF2563EB</color>           <!-- CTA fill -->
<color name="ngad_cta_text">#FFFFFFFF</color>      <!-- CTA text -->
<color name="ngad_cta_ripple">#52FFFFFF</color>    <!-- CTA touch ripple -->
<color name="ngad_ad_badge">#FFFFC861</color>      <!-- "Ad" badge fill -->
<color name="ngad_ad_badge_text">#FF5A4500</color>
<color name="ngad_media_bg">#FFEFF2F7</color>      <!-- media placeholder -->
<color name="ngad_shimmer_block">#FFE9ECF1</color>
```

---

## Interstitial ads

```kotlin
// Preload once (reused across screens via the registry):
Interstitials.preload(AdUnits.INTERSTITIAL)

// …or observe the result. `true` once the ad is cached (immediately, if one already is); `false` if
// the load was refused (remoteEnabled off, premium, kill-switch) or failed after retries are spent:
Interstitials.preload(AdUnits.INTERSTITIAL) { loaded ->
    if (loaded) enableContinueButton()
}

// Show — onComplete is always called (immediately if no ad was ready):
Interstitials.get(AdUnits.INTERSTITIAL).show(this) { goToNextScreen() }

// Load on demand and show as soon as it's ready (bounded by a timeout):
Interstitials.loadAndShow(this, AdUnits.INTERSTITIAL, timeoutMs = 5_000L) { goToNextScreen() }
```

`onComplete` always fires — after the ad is dismissed, on failure/timeout, or synchronously when ads
are disabled — so your navigation stays uniform. On-demand fetches (`loadAndShow`, and forced counter
shows) default to `NextGenAdsConfig.forceShowTimeoutMs` (8 s); past the bound the caller proceeds and
the in-flight load warms the cache for next time.

Cached interstitials expire after ~1 hour on AdMob's side; the helper drops a stale ad instead of
burning the show on an "ad expired" failure. Only one full-screen ad (any format) can be on screen at
a time — a `show()` while another is presenting is refused and the ad stays cached (see
`NextGenAds.isFullScreenAdShowing()`).

**Per-unit tuning:**

```kotlin
val helper = Interstitials.get(AdUnits.INTERSTITIAL)
helper.maxRetries = 3               // exponential backoff on load failure (1s, 2s, 4s …)
helper.minIntervalMs = 60_000       // frequency cap for this unit; 0 disables
helper.adValidityMs = 55 * 60_000L  // cached-ad expiry; stale ads are dropped, never shown
helper.autoReload = true            // request the next ad automatically after each dismissal
helper.loadingOverlayMs = 1_000L    // brief "Showing ad…" interlude before a ready ad opens; 0 off
val ready = helper.isReady          // non-expired ad cached
```

### Counter-gated shows

Show on every Nth trigger without tracking a counter yourself. The counter is app-wide per unit;
`onComplete` still fires on the in-between calls so your flow stays uniform.

```kotlin
// Show on the 3rd, 6th, 9th … call:
Interstitials.showEvery(this, AdUnits.INTERSTITIAL, nth = 3) { startNextLevel() }

// Show on the 1st call, then every 4th after (1, 5, 9, 13 …):
Interstitials.showFirstThenEvery(this, AdUnits.INTERSTITIAL, nth = 4) { startNextLevel() }

// Reset the counter (e.g. on a new session):
Interstitials.get(AdUnits.INTERSTITIAL).resetTriggerCount()
```

Both accept `forceLoad = true` (bounded by `timeoutMs`) to load on demand when the gate opens with no
cached ad, instead of skipping.

---

## Rewarded ads

```kotlin
RewardedAds.preload(AdUnits.REWARDED)

RewardedAds.get(AdUnits.REWARDED).show(
    activity = this,
    onReward = { reward -> grantCoins(reward.amount) },  // reward.amount: Int, reward.type: String
    onComplete = { /* closed, with or without a reward */ },
)
```

`show(...)` returns `false` (and calls `onComplete` immediately) when no ad is ready — a good moment
to show your own "ad not ready" message, or preload for next time. For an on-demand fetch behind a
loading cover, use `loadAndShow` (default timeout `NextGenAdsConfig.rewardedForceShowTimeoutMs`,
10 s):

```kotlin
RewardedAds.loadAndShow(
    activity = this,
    adUnitId = AdUnits.REWARDED,
    onReward = { grantCoins(it.amount) },
    onComplete = { /* closed / no ad */ },
)
```

A common pattern is to confirm with a dialog first:

```kotlin
AlertDialog.Builder(this)
    .setTitle("Earn a reward")
    .setMessage("Watch a short video to earn your reward?")
    .setPositiveButton("Watch") { _, _ ->
        RewardedAds.get(AdUnits.REWARDED).show(this, onReward = { grantCoins(it.amount) })
    }
    .setNegativeButton("Cancel", null)
    .show()
```

---

## Rewarded interstitial ads

Identical API to rewarded, via `RewardedInterstitials`:

```kotlin
RewardedInterstitials.preload(AdUnits.REWARDED_INT)

RewardedInterstitials.get(AdUnits.REWARDED_INT).show(
    activity = this,
    onReward = { reward -> grantCoins(reward.amount) },
    onComplete = { /* closed */ },
)

// Or on demand:
RewardedInterstitials.loadAndShow(this, AdUnits.REWARDED_INT, onReward = { grantCoins(it.amount) })
```

---

## App open ads

Full-screen ads shown while the user brings the app to the foreground. App-open ads expire **4 hours**
after loading — the helper tracks this and silently refetches a stale ad rather than showing it.

### Auto-show on foreground (recommended)

`AppOpenAdManager` wires itself to the process lifecycle and shows an ad each time the app returns to
the foreground. Install it once, in `Application.onCreate()` (or via `NextGenAdsBootstrap.configure`):

```kotlin
AppOpenAdManager.install(this, AdUnits.APP_OPEN)
    .skipOn(SplashActivity::class.java)   // never cover these screens
```

On a genuine background→foreground transition a cached (non-expired) ad shows instantly. If none is
cached, one is requested at that moment and shown only when it loads within `loadTimeoutMs`
(default 5 s) while the app is still foregrounded — an ad that arrives later is **never** popped over
app content mid-session; it stays cached so the *next* return shows instantly.

The first foreground after a cold start is skipped by default — set `showOnColdStart = true` to opt
in. Pause auto-showing with `AppOpenAdManager.get()?.enabled = false`. Activities implementing
`HideAppOpenAd` (or registered via `skipOn`) are never covered, the SDK's own ad activities are always
skipped, and an app-open never stacks on another full-screen ad.

```kotlin
AppOpenAdManager.get()?.apply {
    enabled = true
    showOnColdStart = false
    coverStyle = AppOpenCoverStyle.WELCOME   // branded "Welcome back" cover; or .LOADING (plain spinner)
    loadTimeoutMs = 5_000L
}
```

### Manual control

```kotlin
AppOpenAds.preload(AdUnits.APP_OPEN)

// Show at your own chosen moment; onComplete fires immediately if no ad is ready.
AppOpenAds.get(AdUnits.APP_OPEN).show(activity) { proceed() }

// Or request-and-show on demand (e.g. from a splash gate):
AppOpenAds.loadAndShow(activity, AdUnits.APP_OPEN, timeoutMs = 8_000L) { proceed() }
```

| Member | Default | Purpose |
| --- | --- | --- |
| `isReady` / `isShowing` | — | A non-expired ad is cached / an app-open is on screen. |
| `maxRetries` | `3` | Reload attempts after a failed load (1s/2s/4s backoff). |
| `minIntervalMs` | `0` | Minimum gap between two app-open ads; `0` disables capping. |
| `welcomeTitle` / `loadingText` / `showingText` | — | Localise / rebrand the cover copy. |
| `AppOpenAdManager.loadTimeoutMs` | `5_000` | On-return show window (from `NextGenAdsConfig.appOpenLoadTimeoutMs`). |
| `AppOpenAdManager.skipOn(...)` / `allowOn(...)` | — | Exclude / re-include activity classes from auto-show. |

---

## Splash screen (`SplashAdGate`)

`SplashAdGate` implements the standard launch pattern in one call: on a **cold start** it shows an
**interstitial**, and on a **warm / hot start** it shows an **app-open** ad — both while your splash is
up, each held for a minimum delay (branding always visible) and bounded by a timeout (a slow or failed
load can never trap the user). `onComplete` fires exactly once, so you just navigate onward there.

Cold-vs-warm can't be detected inside the gate (only the host `Activity` survives a config-change
recreation correctly): resolve it once with `SplashAdGate.consumeColdStart()`, persist it across
recreation, and pass it in.

```kotlin
class SplashActivity : AppCompatActivity() {

    private var coldStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Resolve cold vs warm once; keep it stable across a config-change recreation.
        coldStart = savedInstanceState?.getBoolean(KEY_COLD) ?: SplashAdGate.consumeColdStart()

        if (savedInstanceState == null) {
            NextGenAdsBootstrap.gatherConsentThenInitialize(
                activity = this,
                testDeviceHashedId = if (BuildConfig.DEBUG) TEST_DEVICE_HASH else null,
                testDeviceIds = TEST_DEVICE_IDS,
            ) {
                SplashAdGate.show(
                    activity = this,
                    coldStart = coldStart,
                    interstitialUnitId = AdUnits.INTERSTITIAL,
                    appOpenUnitId = AdUnits.APP_OPEN,
                    coldStartAdType = SplashAdType.INTERSTITIAL,   // cold → interstitial
                    warmStartAdType = SplashAdType.APP_OPEN,       // warm/hot → app-open
                    onComplete = { goToMain() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COLD, coldStart)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java)); finish()
    }

    private companion object { const val KEY_COLD = "ngad_splash_cold_start" }
}
```

> Add your splash to `AppOpenAdManager.skipOn(SplashActivity::class.java)` so the auto-show manager
> doesn't also fire an app-open over the one the gate already shows on a warm relaunch. Keep a
> watchdog that navigates on if consent/init stalls (e.g. no network), and cancel it once init
> completes — the sample's `SplashActivity` shows the full pattern.

Behaviour of the splash ad, at a glance:

| Situation | What happens |
| --- | --- |
| Ad loads fast | Wait out `minDelayMs`, show it, `onComplete` on dismiss. |
| Ad loads slowly | Shown as soon as it lands (past `minDelayMs`), up to `timeoutMs`. |
| Ad never loads | `onComplete` fires at `timeoutMs`; the in-flight load keeps warming the cache. |
| Ads disabled / premium | `onComplete` fires after `minDelayMs`; no request is made. |

For the cold-start-only case you can also call `SplashAd.show(activity, adUnitId, …)` (interstitial)
directly — it's what the gate delegates to.

---

## Jetpack Compose

For Compose apps, add the companion **`nextgenads-compose`** module — thin `@Composable` / `remember*`
wrappers over the same helpers. No ad logic is duplicated: you get the same preload cache, retries,
premium purge, shimmer, and full-screen exclusivity. It ships **no resources of its own** — it renders
the library's native templates and shimmers, so everything matches the View/XML side.

### Add the dependency

The compose artifact exposes `:nextgenads` transitively (`api`), so you only add the one line:

```kotlin
// via JitPack — note the multi-module group (com.github.<user>.<repo>)
implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads-compose:1.4.0")

// …or as a local module
implementation(project(":nextgenadscompose"))
```

Enable Compose in your app module (`android { buildFeatures { compose = true } }`) and call
`NextGenAds.initialize(...)` once after consent, exactly as on the XML side — the composables don't
initialize the SDK themselves.

### Inline ads — banner & native

Both are backed by `BannerNativeView`: shimmer while loading, collapse on no-fill, auto-hide when
premium / remote-off, and auto-destroy when they leave composition.

```kotlin
import com.alihassan.nextgenadscompose.BannerAd
import com.alihassan.nextgenadscompose.NativeAd

BannerAd(adUnitId = AdUnits.BANNER, size = BannerSize.ADAPTIVE)        // MREC, 320x50, … also supported
NativeAd(adUnitId = AdUnits.NATIVE, template = NativeTemplate.MEDIUM)  // any of the 12 templates
```

Each accepts `modifier`, `remoteEnabled`, `onLoaded`, `onFailed`. Need to flip banner ↔ native at
runtime? Use the unified `NextGenAdView(adType = …)` that both delegate to.

### Full-screen ads — interstitial / rewarded / rewarded-interstitial / app-open

Each format has a `remember*` that returns a controller (and preloads on first composition by
default). The controller resolves the host `Activity` from `LocalContext` when you show.

```kotlin
val interstitial = rememberInterstitialAd(AdUnits.INTERSTITIAL)
Button(onClick = { interstitial.loadAndShow { goNext() } }) { Text("Next") }
// also: interstitial.show { }, showEvery(nth = 3) { }, showFirstThenEvery(nth = 4) { }

val rewarded = rememberRewardedAd(AdUnits.REWARDED)
rewarded.loadAndShow(onReward = { grant(it.amount) }, onComplete = { })

val rewardedInt = rememberRewardedInterstitialAd(AdUnits.REWARDED_INT)
rewardedInt.loadAndShow(onReward = { grant(it.amount) })

val appOpen = rememberAppOpenAd(AdUnits.APP_OPEN)
appOpen.loadAndShow(coverStyle = AppOpenCoverStyle.WELCOME) { proceed() }   // WELCOME cover by default
```

### Consent & ad events

```kotlin
// Gather consent, then initialize (see the Consent / Initialization sections):
val consent = rememberConsentManager(testDeviceHashedId = /* debug only */ null)

// Register an app-wide listener for the composition's lifetime (auto-unregisters):
AdEventsEffect(remember { object : AdEventListener {
    override fun onAdPaid(f: AdFormat, id: String, v: AdValue, r: ResponseInfo?) { logRevenue(v) }
} })
```

### Compose API reference

| Composable / helper | Returns / does |
| --- | --- |
| `BannerAd(adUnitId, size, …)` | Inline banner (shimmer + no-fill collapse). |
| `NativeAd(adUnitId, template, …)` | Inline native ad with a built-in template. |
| `NextGenAdView(adType, …)` | Unified inline ad (switch banner ↔ native). |
| `rememberInterstitialAd(unit)` | `InterstitialAdController` (`show`, `loadAndShow`, `showEvery`, `showFirstThenEvery`). |
| `rememberRewardedAd(unit)` | `RewardedAdController` (`show` / `loadAndShow` with `onReward`). |
| `rememberRewardedInterstitialAd(unit)` | `RewardedInterstitialAdController`. |
| `rememberAppOpenAd(unit)` | `AppOpenAdController` (`loadAndShow`, `coverStyle`). |
| `AdEventsEffect(listener)` | Registers an `AdEventListener` for the composition's lifetime. |
| `rememberConsentManager(hash?)` | The shared `ConsentManager`. |
| `rememberInAppUpdateManager(type, …)` | Lifecycle-bound Play in-app updater. |
| `rememberInAppReviewManager { }` | Lifecycle-bound Play in-app review; `launchReview { next() }`. |

---

## Premium / ad-free users

Every helper consults a single gate:

```
NextGenAds.canShowAds() == enabled && adsLoadEnabled && !premium && !premiumProvider()
```

```kotlin
// Static flag:
NextGenAds.premium = user.hasActiveSubscription

// Or dynamic (evaluated on every ad request):
NextGenAds.premiumProvider = { billingRepository.isPremium() }
NextGenAds.refreshPremiumState()   // apply a premiumProvider change right now

// Or disable all ads entirely:
NextGenAds.enabled = false
```

**Runtime purge.** Setting `premium = true` (or `enabled = false`, or `adsLoadEnabled = false`)
doesn't just stop *new* requests — it immediately **drops every format's cached ad and hides any
banner/native already on screen**, so a mid-session purchase removes ads at once and frees their
memory. Nothing is requested again while premium.

- `BannerNativeView` and `NativeTemplateView` register themselves while attached and hide on purge.
- Banners shown via `BannerAdHelper.loadAdaptiveBanner` are cleared from their containers.
- Because `premiumProvider` is evaluated lazily, call `NextGenAds.refreshPremiumState()` after your
  billing state flips so the purge runs.
- Trigger the purge directly anytime (logout, low memory) with `NextGenAds.clearAllAds()`, or drop a
  single format with `NextGenAds.clearFormat(AdFormat.NATIVE)`.

---

## Ad events (analytics & revenue)

Register a single `AdEventListener` once and receive **every** ad lifecycle event from **every**
format — request, load, show, dismiss, impression, click, paid-revenue and reward — without threading
callbacks through each call site. This is the recommended hook for analytics and ROAS / ad-revenue
measurement. Per-call callbacks (`onComplete`, `onReward`, …) still fire; events are additive.

All callbacks are delivered on the **main thread**, and one listener throwing never stops the others.

```kotlin
NextGenAds.registerEventListener(object : AdEventListener {
    override fun onAdImpression(format: AdFormat, adUnitId: String) {
        analytics.logImpression(format.name, adUnitId)
    }

    override fun onAdClicked(format: AdFormat, adUnitId: String) {
        analytics.logClick(format.name, adUnitId)
    }

    // Estimated revenue — forward to Firebase `ad_impression` for ROAS measurement.
    override fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue, responseInfo: ResponseInfo?) {
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "NextGenAds")
            putString(FirebaseAnalytics.Param.AD_FORMAT, format.name)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, adUnitId)
            putDouble(FirebaseAnalytics.Param.VALUE, value.valueMicros / 1_000_000.0)
            putString(FirebaseAnalytics.Param.CURRENCY, value.currencyCode)
        })
    }

    override fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) {
        // …grant the reward / track completion
    }
})
```

Every method has a no-op default, so implement only what you need. Call
`NextGenAds.unregisterEventListener(listener)` to stop receiving events. `AdFormat` values: `BANNER`,
`NATIVE`, `INTERSTITIAL`, `REWARDED`, `REWARDED_INTERSTITIAL`, `APP_OPEN`.

| Event | Banner | Native | Interstitial | Rewarded | Rewarded-int. | App-open |
|-------|:------:|:------:|:------------:|:--------:|:-------------:|:--------:|
| `onAdRequested` / `onAdLoaded` / `onAdFailedToLoad` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdImpression` / `onAdClicked` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdPaid` (revenue) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdShown` / `onAdDismissed` / `onAdFailedToShow` | — | — | ✓ | ✓ | ✓ | ✓ |
| `onUserEarnedReward` | — | — | — | ✓ | ✓ | — |

> Banners and native ads are inline, so they raise `onAdImpression` rather than `onAdShown`.

---

## Show rate & fill rate

### Built-in tracker (`ShowRateTracker`)

`ShowRateTracker` is an `AdEventListener` that tallies live **fill** and **use** rates per format.
Register it once and dump the numbers whenever you like:

```kotlin
val tracker = ShowRateTracker()
NextGenAds.registerEventListener(tracker)

// …later:
tracker.logReport()                 // pretty box-drawing table in Logcat (tag NextGenAds)
val rows = tracker.snapshot()       // List<FormatRow> for an on-screen TableLayout
```

Definitions (per format): **fill% = loaded ÷ requested** (demand-driven, mostly server-side), and
**use% = impressions ÷ loaded** — the share of loaded ads that were actually shown. `use%` is the
number the preload / retry / await-in-flight optimizations move; it climbs toward 100% when loaded
inventory isn't wasted.

### Tips

- **Preload** every format right after `initialize` completes — the single biggest lever on show
  rate. Pass `refill = true` to `populate` / `loadAdaptiveBanner` (or set `autoReload = true` on
  full-screen helpers) so consuming a cached ad automatically warms the next one.
- Cached ads **expire** (~1 h for interstitial/rewarded/native/banner, 4 h for app-open). The helpers
  drop stale inventory instead of failing the show — tune via `adValidityMs`.
- Keep `BannerNativeView` / `NativeTemplateView` instances around and rebind, rather than recreating.
- Bump `NativeAdHelper.maxCachePerUnit` / `BannerAdHelper.maxCachePerUnit` for high-traffic screens.
- On a flaky connection the shared **circuit breaker** pauses new requests briefly (cached ads still
  show) and auto-resumes; `NextGenAds.enableConnectivityRecovery(context)` re-warms caches when the
  network returns.
- **Fill rate is mostly server-side**: configure **mediation** in the AdMob console and use real ad
  unit ids. Test ids always fill, but only with test creatives. Inspect adapter readiness after init
  via `NextGenAds.initializationStatus` (a `NOT_READY` adapter silently forfeits that network's fill).

---

## Test ad unit IDs

Google's official test ids (safe during development — replace before release):

| Format | App id / Unit id |
| --- | --- |
| App id | `ca-app-pub-3940256099942544~3347511713` |
| Adaptive banner | `ca-app-pub-3940256099942544/9214589741` |
| Native | `ca-app-pub-3940256099942544/2247696110` |
| Interstitial | `ca-app-pub-3940256099942544/1033173712` |
| Rewarded | `ca-app-pub-3940256099942544/5224354917` |
| Rewarded interstitial | `ca-app-pub-3940256099942544/5354046379` |
| App open | `ca-app-pub-3940256099942544/9257395921` |

---

## ProGuard / R8

No extra configuration is needed in your app: the library's public API survives R8 through normal
reference-based keep analysis, the shipped `consumer-rules.keep` keeps the XML-inflated `NativeAdView`
/ `MediaView` and the library's `BannerNativeView` / `NativeTemplateView` (so a minified host can't
strip them and break native inflation in release), and the Next-Gen Ads SDK, UMP and Shimmer bring
their own consumer keep rules.

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| **"Cannot initialize: no AdMob App ID found"** | Missing `com.google.android.gms.ads.APPLICATION_ID` meta-data, or pass the id explicitly via `initialize(context, appId, …)`. |
| **App ID warning / `INVALID_REQUEST` on first load** | The id isn't `ca-app-pub-…~…` — you likely passed an ad **unit** id (`…/…`). Use the App ID (tilde). |
| **Consent never gathers / "Ads not allowed"** | Missing App ID meta-data, or no GDPR message published in the AdMob console. |
| **Consent form never appears** | Expected outside the EEA. To test, pass your test-device hash to `ConsentManager.getInstance` and `forceEea = true`. |
| **No ads show with test ids** | Confirm `NextGenAds.isInitialized()`, and that `premium` / `enabled` / `adsLoadEnabled` / the per-format toggle aren't suppressing ads. |
| **Adaptive banner overflows / clips** | Loading before layout is now handled automatically; if you still see it, pass the container's content width via `BannerAdHelper.containerWidthDp(...)`. |
| **Native media has grey side/top bars** | The creative's aspect ratio differs from the slot. `mediaScaleType` defaults to `CENTER_CROP` to fill it; use `FIT_CENTER` to show the whole creative with bars. |
| **Splash never proceeds** | `SplashAdGate` / `SplashAd` always fire `onComplete` by `timeoutMs`; ensure you call `initialize` first and navigate in `onComplete`. |
| **JitPack build fails** | Bleeding-edge AGP/SDK — try `openjdk21` in `jitpack.yml`, or distribute the AAR directly. |

---

## API reference

```
com.alihassan.nextgenads
├── NextGenAds                          // initialize, enabled/adsLoadEnabled, premium, premiumProvider,
│                                       //   canShowAds, refreshPremiumState, clearAllAds/clearFormat,
│                                       //   per-format toggles, setAppVolume/Muted, openAdInspector,
│                                       //   enableConnectivityRecovery, register/unregisterEventListener
├── NextGenAdsBootstrap                 // configure(...) + gatherConsentThenInitialize(...)
├── NextGenAdsConfig                    // app-wide defaults: splash timers, force-show timeouts,
│                                       //   retry budget, frequency cap, circuit breaker
├── BannerNativeView                    // drop-in banner/native View (premium-aware)
├── AdType                              // BANNER, NATIVE
├── events.AdEventListener              // app-wide ad events (request/load/impression/click/paid/reward)
├── events.AdFormat                     // BANNER, NATIVE, INTERSTITIAL, REWARDED, REWARDED_INTERSTITIAL, APP_OPEN
├── events.ShowRateTracker              // live fill%/use% tally; report(), snapshot()
├── consent.ConsentManager              // UMP consent
├── banner.BannerAdHelper               // adaptive + collapsible banners, preload/loadAdaptiveBanner, clearAll()
├── banner.BannerCollapsible            // TOP, BOTTOM
├── banner.BannerSize                   // ADAPTIVE, ADAPTIVE_INLINE, BANNER, LARGE_BANNER, FULL_BANNER, LEADERBOARD, MEDIUM_RECTANGLE
├── nativead.NativeAdHelper             // native loading + cache, preload/populate/load, clear()
├── nativead.NativeTemplate             // 12 templates (SMALL … STACKED)
├── nativead.NativeTemplateView         // renders a NativeTemplate or a custom layout (setCustomTemplate)
├── nativead.ShimmerSkeleton            // fromLayout(context, layout) → auto shimmer
├── interstitial.Interstitials          // registry → InterstitialAdHelper (showEvery / showFirstThenEvery)
├── interstitial.SplashAd               // splash interstitial (min delay + timeout)
├── rewarded.RewardedAds                // registry → RewardedAdHelper
├── rewardedinterstitial.RewardedInterstitials  // registry → RewardedInterstitialAdHelper
├── appopen.AppOpenAds                  // registry → AppOpenAdHelper
├── appopen.AppOpenAdManager            // auto-show on foreground (process lifecycle), skipOn(...)
├── appopen.AppOpenCoverStyle           // WELCOME, LOADING
├── appopen.HideAppOpenAd               // marker interface: never cover this Activity
└── splash.SplashAdGate                 // cold=interstitial / warm-hot=app-open, consumeColdStart()
                                        //   + splash.SplashAdType (INTERSTITIAL, APP_OPEN)

com.alihassan.nextgenadscompose         // Jetpack Compose wrapper (separate artifact)
├── BannerAd / NativeAd / NextGenAdView
├── rememberInterstitialAd / rememberRewardedAd / rememberRewardedInterstitialAd / rememberAppOpenAd
├── AdEventsEffect / rememberConsentManager
└── rememberInAppUpdateManager / rememberInAppReviewManager
```

Full KDoc is available on every public class and member in the source.

---

## License

[MIT](LICENSE) © Ali Hassan
