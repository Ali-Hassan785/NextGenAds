# NextGenAds

A lightweight, premium Android ad-helper library that wraps Google's **Next-Gen Mobile Ads SDK**
(`com.google.android.libraries.ads.mobile.sdk`) and the **User Messaging Platform (UMP)**. It gives
you drop-in helpers for every common ad format with preloading, per-unit caching, GDPR consent, a
global premium kill-switch, and five polished native-ad templates — tuned for **show rate** and
**fill rate**.

> All SDK callbacks are marshalled to the main thread, so you can safely touch UI from any helper
> callback. The library is written in Kotlin with full `@JvmStatic` / `@JvmOverloads` annotations,
> so it is fully usable from Java too.

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Consent (UMP / GDPR)](#consent-ump--gdpr)
- [Initialization](#initialization)
- [Banner ads](#banner-ads)
- [Native ads](#native-ads)
- [Interstitial ads](#interstitial-ads)
- [Rewarded ads](#rewarded-ads)
- [Rewarded interstitial ads](#rewarded-interstitial-ads)
- [App open ads](#app-open-ads)
- [Premium / ad-free users](#premium--ad-free-users)
- [Ad events (analytics & revenue)](#ad-events-analytics--revenue)
- [Theming the native templates](#theming-the-native-templates)
- [Show rate & fill rate tips](#show-rate--fill-rate-tips)
- [Test ad unit IDs](#test-ad-unit-ids)
- [ProGuard / R8](#proguard--r8)
- [Troubleshooting](#troubleshooting)
- [API reference](#api-reference)
- [Building the library](#building-the-library)
- [License](#license)

---

## Features

| Format | Helper | Preload | Caching | Notes |
| --- | --- | --- | --- | --- |
| Banner | `BannerAdHelper` | ✅ | ✅ per unit | Anchored adaptive banners + shimmer |
| Native | `NativeAdHelper` / `BannerNativeView` | ✅ | ✅ per unit | 5 premium templates |
| Interstitial | `Interstitials` | ✅ | ✅ per unit | Frequency cap + backoff retries |
| Rewarded | `RewardedAds` | ✅ | ✅ per unit | Reward callback |
| Rewarded interstitial | `RewardedInterstitials` | ✅ | ✅ per unit | Reward callback |
| App open | `AppOpenAds` / `AppOpenAdManager` | ✅ | ✅ per unit | 4h expiry + auto show on foreground |
| Consent | `ConsentManager` | — | — | UMP GDPR flow |

- **Global gating** — `NextGenAds.enabled` / `premium` / `premiumProvider` are honoured by every helper.
- **Shimmer placeholders** while ads load.
- **Main-thread safety** — every ad callback is delivered on the main thread.
- **Ad events** — one `AdEventListener` for all formats: load, impression, click, **paid-revenue** & reward (analytics / ROAS). See [Ad events](#ad-events-analytics--revenue).

---

## Requirements

| | |
| --- | --- |
| `minSdk` | 24 |
| `compileSdk` | 37 |
| Ads SDK | `ads-mobile-sdk` 1.2.1 (Next-Gen, beta) |
| UMP | `user-messaging-platform` 4.0.0 |
| Shimmer | `com.facebook.shimmer:shimmer` 0.5.0 |

The Ads SDK, UMP, and Shimmer are pulled in transitively (`api`) — you do **not** need to declare
them yourself when consuming via JitPack or Maven.

---

## Installation

### Via JitPack (recommended)

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
    implementation("com.github.Ali-Hassan785:nextgenads:1.0.0")
}
```

### As a local module

```kotlin
// settings.gradle.kts
include(":app", ":nextgenads")
// app/build.gradle.kts
dependencies { implementation(project(":nextgenads")) }
```

### Manifest — add your AdMob app id

```xml
<application ...>
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
</application>
```

> `INTERNET` and `ACCESS_NETWORK_STATE` are declared by the library and merged automatically.
> The AdMob app id is **required** — UMP reads it to fetch your consent configuration; without it,
> consent never gathers.

---

## Quick start

```kotlin
// 1. Gather consent, then initialize (e.g. on a splash screen).
val consent = ConsentManager.getInstance(this)
consent.gatherConsent(this) {
    if (consent.canRequestAds) {
        NextGenAds.initialize(this, APP_ID) {
            // 2. Warm caches for a high show rate.
            NativeAdHelper.preload(NATIVE_UNIT, count = 2)
            Interstitials.preload(INTERSTITIAL_UNIT)
        }
    }
}

// 3. Show a native ad (XML view).
findViewById<BannerNativeView>(R.id.adView).load(adUnitId = NATIVE_UNIT)

// 4. Show an interstitial at a transition.
Interstitials.get(INTERSTITIAL_UNIT).show(this) { goToNextScreen() }
```

---

## Consent (UMP / GDPR)

`ConsentManager` wraps the User Messaging Platform. Call it once on launch **before** initializing
the SDK.

```kotlin
val consent = ConsentManager.getInstance(this)

consent.gatherConsent(this) { error ->
    if (error != null) Log.w("Ads", "Consent error: ${error.message}")
    if (consent.canRequestAds) {
        NextGenAds.initialize(this, APP_ID) { /* preload */ }
    }
}

// Privacy options entry-point (e.g. from Settings):
if (consent.isPrivacyOptionsRequired) {
    consent.showPrivacyOptionsForm(this) { /* dismissed */ }
}
```

### Testing the consent form outside the EEA

Pass your device's hashed id (printed in Logcat on first run) to `getInstance`. The device is then
treated as a test device and the EEA geography is forced so the form actually appears:

```kotlin
ConsentManager.getInstance(this, "33BE2250B43518CCDA7DE426D04EE231")
    .gatherConsent(this) { /* form will show */ }
```

| Member | Description |
| --- | --- |
| `canRequestAds: Boolean` | `true` once ads may be requested (consent obtained or not required). |
| `isPrivacyOptionsRequired: Boolean` | `true` when a "Privacy options" entry-point must be shown. |
| `gatherConsent(activity, forceEea, onComplete)` | Updates consent info and shows the form if required. |
| `showPrivacyOptionsForm(activity, onDismissed)` | Presents the privacy options form. |
| `reset()` | Clears all consent state (testing). |

---

## Initialization

```kotlin
NextGenAds.initialize(
    context = this,
    appId = "ca-app-pub-XXXX~YYYY",
    testDeviceIds = listOf("YOUR_TEST_DEVICE_ID"),   // optional
    onComplete = Runnable { /* runs on the main thread when ready */ },
)
```

Initialization runs off the main thread (as the Next-Gen SDK requires) and the callback is delivered
on the main thread — a good place to start preloading. Calling `initialize` again after it has
finished simply runs the callback immediately.

| Member | Description |
| --- | --- |
| `initialize(context, appId, testDeviceIds, onComplete)` | Initializes the SDK once. |
| `isInitialized(): Boolean` | Whether initialization has completed. |
| `enabled: Boolean` | Master kill-switch (default `true`). |
| `loggingEnabled: Boolean` | Verbose Logcat under tag `NextGenAds` (default `true`). |
| `premium`, `premiumProvider`, `canShowAds()` | See [Premium](#premium--ad-free-users). |

---

## Banner ads

Anchored adaptive banners with a shimmer placeholder and optional preloading.

```kotlin
// Preload (e.g. right after init):
BannerAdHelper.preload(activity = this, adUnitId = BANNER_UNIT, count = 1)

// Show into any ViewGroup — attaches a preloaded banner instantly, else loads behind a shimmer:
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.bannerContainer),
    adUnitId = BANNER_UNIT,
    onLoaded = { /* shown */ },
    onFailed = { error -> /* no fill — container is collapsed */ },
)
```

| Member | Default | Description |
| --- | --- | --- |
| `maxCachePerUnit` | `2` | Max preloaded banners cached per ad unit. |
| `preload(activity, adUnitId, count)` | `count = 1` | Warms the cache. |
| `loadAdaptiveBanner(activity, container, adUnitId, refill, onLoaded, onFailed)` | `refill = true` | Shows a banner; collapses the container on no-fill. |

---

## Native ads

There are two ways to render native ads.

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
    adUnitId = NATIVE_UNIT,
    remoteEnabled = remoteConfig.getBoolean("home_native"), // your flag; false hides the view
    nativeTemplate = NativeTemplate.MEDIA_LEFT,             // optional override
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
| `app:ngad_template` | `small`, `medium`, `large`, `banner`, `media_left` | `medium` |

### 2. `NativeAdHelper` + `NativeTemplateView` (lower level)

```kotlin
// Preload into the per-unit cache:
NativeAdHelper.preload(NATIVE_UNIT, count = 2)

// Bind into a template view (shimmer until ready, cache-first, auto-refill):
NativeAdHelper.populate(templateView, NATIVE_UNIT)

// Or get the raw ad:
NativeAdHelper.load(NATIVE_UNIT, onLoaded = { ad -> /* … */ })

// Free cached ads (e.g. on logout / low memory):
NativeAdHelper.clear(NATIVE_UNIT)   // or clear() for all units
```

### The five templates

| Template | Layout |
| --- | --- |
| `SMALL` | Compact row: icon + headline + body + CTA (no media). |
| `MEDIUM` | Icon + headline + rating + body + media + CTA (the all-rounder). |
| `LARGE` | Media-forward card for full-width slots / dialogs. |
| `BANNER` | Single-line strip that mimics a banner footer (no media). |
| `MEDIA_LEFT` | Media on the left, headline + body top-right, CTA bottom-right. |

All templates use rounded, clipped media, a ripple CTA, an "Ad" attribution badge, and
high-quality Roboto typography.

---

## Interstitial ads

```kotlin
// Preload once (reused across screens via the registry):
Interstitials.preload(INTERSTITIAL_UNIT)

// Show — onDismiss is always called (immediately if no ad was ready):
Interstitials.get(INTERSTITIAL_UNIT).show(this) {
    goToNextScreen()
}

// Tune behaviour:
val helper = Interstitials.get(INTERSTITIAL_UNIT)
helper.maxRetries = 3        // exponential backoff on load failure (1s, 2s, 4s …)
helper.minIntervalMs = 60_000 // frequency cap; 0 disables
val ready = helper.isReady
```

A fresh ad is requested automatically after each dismissal.

### Show on every Nth call (counter)

For the common "show an interstitial every few transitions" pattern, use `showOnCount` instead of
tracking a counter yourself. It increments an app-wide counter for that ad unit (helpers are shared)
and only shows on every Nth call; `onDismiss` still fires on the in-between calls so your flow stays
uniform:

```kotlin
// Show an ad on every 3rd level completion (1st and 2nd just continue):
Interstitials.showOnCount(this, INTERSTITIAL_UNIT, every = 3) {
    startNextLevel()
}

// Reset the counter (e.g. on a new session):
Interstitials.get(INTERSTITIAL_UNIT).resetCounter()
```

The readiness check and `minIntervalMs` cap still apply, so a counted call skips showing if no ad is
ready yet.

---

## Rewarded ads

```kotlin
RewardedAds.preload(REWARDED_UNIT)

RewardedAds.get(REWARDED_UNIT).show(
    activity = this,
    onReward = { reward -> grantCoins(reward.amount) },  // reward.amount: Int, reward.type: String
    onDismiss = { /* closed, with or without a reward */ },
)
```

A common pattern is to confirm with a dialog first:

```kotlin
AlertDialog.Builder(this)
    .setTitle("Earn a reward")
    .setMessage("Watch a short video to earn your reward?")
    .setPositiveButton("Watch") { _, _ ->
        RewardedAds.get(REWARDED_UNIT).show(this, onReward = { grantCoins(it.amount) })
    }
    .setNegativeButton("Cancel", null)
    .show()
```

---

## Rewarded interstitial ads

Identical API to rewarded, via `RewardedInterstitials`:

```kotlin
RewardedInterstitials.preload(REWARDED_INT_UNIT)

RewardedInterstitials.get(REWARDED_INT_UNIT).show(
    activity = this,
    onReward = { reward -> grantCoins(reward.amount) },
    onDismiss = { /* closed */ },
)
```

---

## App open ads

Full-screen ads shown while the user brings the app to the foreground. App-open ads expire 4 hours
after loading — the helper tracks this and silently refetches a stale ad rather than showing it.

### Auto show on foreground (recommended)

`AppOpenAdManager` wires itself to the process lifecycle and shows an ad each time the app returns
to the foreground, keeping the next one warm in between. Install it once, after `initialize`:

```kotlin
// In Application.onCreate(), after NextGenAds.initialize(...):
AppOpenAdManager.install(this, APP_OPEN_UNIT)
```

The first foreground after a cold start is skipped by default (the ad usually isn't ready yet and
showing one over your splash hurts UX) — set `showOnColdStart = true` to opt in. Pause auto-showing
at any time with `AppOpenAdManager.get()?.enabled = false`; the premium / kill-switch gate in
`NextGenAds` is always honoured.

### Manual control

```kotlin
AppOpenAds.preload(APP_OPEN_UNIT)

// Show at your own chosen moment; onDismiss fires immediately if no ad is ready.
AppOpenAds.get(APP_OPEN_UNIT).show(activity) { proceed() }
```

| Member | Default | Purpose |
| --- | --- | --- |
| `isReady` | — | A non-expired ad is cached and ready. |
| `isShowing` | — | An app-open ad is currently on screen. |
| `maxRetries` | `3` | Reload attempts after a failed load (1s/2s/4s backoff). |
| `minIntervalMs` | `0` | Minimum gap between two app-open ads; `0` disables capping. |

---

## Premium / ad-free users

Every helper consults a single gate: `NextGenAds.canShowAds() == enabled && !premium && !premiumProvider()`.

```kotlin
// Static flag:
NextGenAds.premium = user.hasActiveSubscription

// Or dynamic (evaluated on every ad request):
NextGenAds.premiumProvider = { billingRepository.isPremium() }

// Or disable all ads entirely:
NextGenAds.enabled = false
```

While suppressed, no ad is requested or shown, and `BannerNativeView` hides itself automatically.

---

## Ad events (analytics & revenue)

Register a single `AdEventListener` once and receive **every** ad lifecycle event from **every**
format — load, show, dismiss, impression, click, paid-revenue and reward — without threading
callbacks through each call site. This is the recommended hook for analytics and ROAS / ad-revenue
measurement. The per-call callbacks (`onResult`, `onDismiss`, `onReward`, …) still fire as before;
events are additive.

All callbacks are delivered on the **main thread**, and one listener throwing never stops the others
from being notified.

```kotlin
NextGenAds.registerEventListener(object : AdEventListener {
    override fun onAdImpression(format: AdFormat, adUnitId: String) {
        analytics.logImpression(format.name, adUnitId)
    }

    override fun onAdClicked(format: AdFormat, adUnitId: String) {
        analytics.logClick(format.name, adUnitId)
    }

    // Estimated revenue — forward to Firebase `ad_impression` for ROAS measurement.
    override fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue) {
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

Every method has a no-op default, so implement only the ones you need. Call
`NextGenAds.unregisterEventListener(listener)` to stop receiving events.

| Event | Banner | Native | Interstitial | Rewarded | Rewarded-int. | App-open |
|-------|:------:|:------:|:------------:|:--------:|:-------------:|:--------:|
| `onAdLoaded` / `onAdFailedToLoad` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdImpression` / `onAdClicked` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdPaid` (revenue) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `onAdShown` / `onAdDismissed` / `onAdFailedToShow` | — | — | ✓ | ✓ | ✓ | ✓ |
| `onUserEarnedReward` | — | — | — | ✓ | ✓ | — |

> Banners and native ads are inline, so they raise `onAdImpression` rather than `onAdShown`.

---

## Theming the native templates

Override any of these colors in your app's `colors.xml` to re-theme **every** template at once
(same resource names win at merge time):

```xml
<color name="ngad_surface">#FFFFFFFF</color>      <!-- card background -->
<color name="ngad_stroke">#FFEDEFF3</color>       <!-- card border -->
<color name="ngad_headline">#FF0B0E14</color>     <!-- headline text -->
<color name="ngad_body">#FF5E6470</color>         <!-- body / advertiser text -->
<color name="ngad_cta">#FF2563EB</color>          <!-- CTA fill -->
<color name="ngad_cta_text">#FFFFFFFF</color>     <!-- CTA text -->
<color name="ngad_cta_ripple">#52FFFFFF</color>   <!-- CTA touch ripple -->
<color name="ngad_ad_badge">#FFFFC861</color>     <!-- "Ad" badge fill -->
<color name="ngad_ad_badge_text">#FF5A4500</color>
<color name="ngad_media_bg">#FFEFF2F7</color>     <!-- media placeholder -->
<color name="ngad_shimmer_block">#FFE9ECF1</color>
```

### Custom layouts

To supply your own native layout, root it in
`com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView` and use these view ids so the
library can bind the assets:

| View id | Type | Asset |
| --- | --- | --- |
| `@id/ngad_headline` | `TextView` | Headline (required) |
| `@id/ngad_body` | `TextView` | Body |
| `@id/ngad_cta` | `TextView` / `Button` | Call to action |
| `@id/ngad_icon` | `ImageView` | App / brand icon |
| `@id/ngad_advertiser` | `TextView` | Advertiser |
| `@id/ngad_stars` | `RatingBar` | Star rating |
| `@id/ngad_media` | `MediaView` | Media (image/video) — optional |

---

## Show rate & fill rate tips

- **Preload** every format right after `initialize` completes, and again on dismissal (the library
  auto-refills caches after a cached ad is consumed).
- Keep `BannerNativeView` / `NativeTemplateView` instances around and rebind, rather than recreating.
- Bump `NativeAdHelper.maxCachePerUnit` / `BannerAdHelper.maxCachePerUnit` for high-traffic screens.
- **Fill rate is mostly server-side**: configure **mediation** in the AdMob console and use real ad
  unit ids. Test ids always fill but only with test creatives.
- Don't gate requests behind slow remote-config reads on the critical path — cache the flag.

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

The library ships consumer ProGuard rules (`consumer-rules.keep`), so no extra configuration is
needed in your app. The Next-Gen Ads SDK and UMP bring their own keep rules too.

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| **Consent never gathers / "Ads not allowed"** | Missing `com.google.android.gms.ads.APPLICATION_ID` meta-data in the app manifest, or no GDPR message published in the AdMob console. |
| **Consent form never appears** | Expected outside the EEA. To test, pass your test-device hash to `ConsentManager.getInstance`. |
| **`Animators may only be run on Looper threads`** | Fixed in this library — all callbacks run on the main thread. If you see it, you're touching shimmer off the main thread in your own code. |
| **No ads show with test ids** | Make sure `NextGenAds.initialize` finished (`isInitialized()`), and that `premium`/`enabled` aren't suppressing ads. |
| **JitPack build fails** | Bleeding-edge AGP/SDK — try `openjdk21` in `jitpack.yml`, or distribute the AAR directly. |

---

## API reference

```
com.alihassan.nextgenads
├── NextGenAds                         // initialize, enabled, premium, canShowAds, isInitialized,
│                                      //   registerEventListener / unregisterEventListener
├── BannerNativeView                   // drop-in banner/native View
├── AdType                             // BANNER, NATIVE
├── events.AdEventListener             // app-wide ad events (load/impression/click/paid/reward)
├── events.AdFormat                    // BANNER, NATIVE, INTERSTITIAL, REWARDED, REWARDED_INTERSTITIAL, APP_OPEN
├── consent.ConsentManager             // UMP consent
├── banner.BannerAdHelper              // adaptive banners
├── nativead.NativeAdHelper            // native loading + cache
├── nativead.NativeTemplate            // SMALL, MEDIUM, LARGE, BANNER, MEDIA_LEFT
├── nativead.NativeTemplateView        // renders a NativeTemplate
├── interstitial.Interstitials         // registry  → InterstitialAdHelper
├── rewarded.RewardedAds               // registry  → RewardedAdHelper
├── rewardedinterstitial.RewardedInterstitials  // registry → RewardedInterstitialAdHelper
├── appopen.AppOpenAds                  // registry  → AppOpenAdHelper
└── appopen.AppOpenAdManager            // auto show on foreground (process lifecycle)
```

Full KDoc is available on every public class and member in the source.

---

## Building the library

```bash
# Build the AAR
./gradlew :nextgenads:assembleRelease
# → nextgenads/build/outputs/aar/nextgenads-release.aar

# Publish to your local Maven cache (~/.m2)
./gradlew :nextgenads:publishReleasePublicationToMavenLocal

# Run the sample app
./gradlew :app:installDebug
```

Releasing a new version: bump `version` in `nextgenads/build.gradle.kts`, commit, then
`git tag -a <version> -m "…" && git push origin main --tags`, and fetch the tag on JitPack.

---

## License

[MIT](LICENSE) © Ali Hassan
