# NextGenAds

A lightweight, premium Android ad-helper library that wraps Google's **Next-Gen Mobile Ads SDK**
(`com.google.android.libraries.ads.mobile.sdk`) and the **User Messaging Platform (UMP)**. It gives
you drop-in helpers for every common ad format — banner, native, interstitial, rewarded, rewarded
interstitial, app-open — with preloading, per-unit caching, retry/backoff, GDPR consent, a global
premium kill-switch with runtime purge, nine native templates plus bring-your-own layouts, and a
ready-made splash flow. Everything is tuned for **show rate** and **fill rate**.

> All SDK callbacks are marshalled to the **main thread**, so you can touch UI from any callback. The
> library is Kotlin with full `@JvmStatic` / `@JvmOverloads` annotations, so it's fully usable from
> Java too.

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Complete implementation (preload + show)](#complete-implementation-preload--show)
- [Consent (UMP / GDPR)](#consent-ump--gdpr)
- [Initialization](#initialization)
- [Splash screen (splash interstitial)](#splash-screen-splash-interstitial)
- [Banner ads](#banner-ads)
- [Native ads](#native-ads)
- [Interstitial ads](#interstitial-ads)
- [Rewarded ads](#rewarded-ads)
- [Rewarded interstitial ads](#rewarded-interstitial-ads)
- [App open ads](#app-open-ads)
- [Premium / ad-free users](#premium--ad-free-users)
- [Ad events (analytics & revenue)](#ad-events-analytics--revenue)
- [Show rate & fill rate tips](#show-rate--fill-rate-tips)
- [Test ad unit IDs](#test-ad-unit-ids)
- [ProGuard / R8](#proguard--r8)
- [Troubleshooting](#troubleshooting)
- [API reference](#api-reference)
- [Building the library](#building-the-library)
- [License](#license)

---

## Features

| Format | Entry point | Preload | Cache | Highlights |
| --- | --- | :---: | :---: | --- |
| Banner | `BannerAdHelper` | ✅ | ✅ per unit | Anchored adaptive + **collapsible** (top/bottom) + shimmer |
| Native | `NativeAdHelper` / `BannerNativeView` | ✅ | ✅ per unit | **9 templates** + custom layouts + auto-shimmer |
| Interstitial | `Interstitials` / `SplashAd` | ✅ | ✅ per unit | Frequency cap, counter gating, splash flow |
| Rewarded | `RewardedAds` | ✅ | ✅ per unit | Reward callback |
| Rewarded interstitial | `RewardedInterstitials` | ✅ | ✅ per unit | Reward callback |
| App open | `AppOpenAds` / `AppOpenAdManager` | ✅ | ✅ per unit | 4 h expiry + auto-show on foreground |
| Consent | `ConsentManager` | — | — | UMP GDPR flow |

- **Premium-aware everywhere** — a single gate (`NextGenAds.canShowAds()`) is honoured by every
  helper. Flipping to premium at runtime **purges caches and hides shown ads** (see [Premium](#premium--ad-free-users)).
- **Optimized for show rate** — preload + per-unit cache, exponential-backoff retries, a shared
  request circuit breaker, connectivity recovery, and stale-ad expiry across all formats.
- **Shimmer placeholders** while ads load — hand-tuned for built-ins, **auto-generated** for custom layouts.
- **Main-thread safety** — every ad callback is delivered on the main thread.
- **One analytics hook** — a single `AdEventListener` for all formats, including **paid-revenue** for ROAS.

---

## Requirements

| | |
| --- | --- |
| `minSdk` | 24 |
| `compileSdk` | 37 |
| Ads SDK | `ads-mobile-sdk` 1.2.1 (Next-Gen, beta) |
| UMP | `user-messaging-platform` 4.0.0 |
| Shimmer | `com.facebook.shimmer:shimmer` 0.5.0 |
| Lifecycle | `androidx.lifecycle:lifecycle-process` 2.6.2 |

The Ads SDK, UMP, Shimmer and lifecycle-process are exposed transitively (`api`) — you don't declare
them yourself when consuming the library.

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
    implementation("com.github.Ali-Hassan785:nextgenads:1.0.2")
}
```

**Or as a local module:**

```kotlin
// settings.gradle.kts
include(":app", ":nextgenads")
// app/build.gradle.kts
dependencies { implementation(project(":nextgenads")) }
```

### 2. Declare your AdMob app id in the manifest

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

The whole lifecycle is: **gather consent → initialize → preload → show**.

```kotlin
// 1. Gather consent, then initialize (ideally on a splash screen).
val consent = ConsentManager.getInstance(this)
consent.gatherConsent(this) {
    if (consent.canRequestAds) {
        NextGenAds.initialize(this, APP_ID) {
            // 2. Warm caches for a high show rate.
            NativeAdHelper.preload(NATIVE_UNIT, count = 2)
            Interstitials.preload(INTERSTITIAL_UNIT)
            BannerAdHelper.preload(this, BANNER_UNIT)
        }
    }
}

// 3. Show a native ad (XML view).
findViewById<BannerNativeView>(R.id.adView).load(adUnitId = NATIVE_UNIT)

// 4. Show an interstitial at a transition.
Interstitials.get(INTERSTITIAL_UNIT).show(this) { goToNextScreen() }
```

Prefer a real splash gate? See [Splash screen](#splash-screen-splash-interstitial).

---

## Complete implementation (preload + show)

A single, copy-pasteable Activity that wires up **consent → init → preload → show** for the three
formats you'll use most — **banner, native and interstitial** — plus a **counter-gated interstitial**
that only shows on every Nth action. This mirrors the sample app in `app/`.

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var bannerContainer: FrameLayout
    private lateinit var nativeAdView: BannerNativeView   // drop-in banner/native view from your layout

    /** Clicks on the "counter" action — drives the "1st, then every 4th" interstitial gate. */
    private var counterClicks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bannerContainer = findViewById(R.id.bannerContainer)
        nativeAdView = findViewById(R.id.nativeAdView)

        findViewById<Button>(R.id.btnBanner).setOnClickListener { showBanner() }
        findViewById<Button>(R.id.btnNative).setOnClickListener { showNative() }
        findViewById<Button>(R.id.btnInterstitial).setOnClickListener { showInterstitial() }
        findViewById<Button>(R.id.btnCounter).setOnClickListener { showInterstitialByCounter() }

        // Gather consent, initialize, then preload everything so the first show is instant.
        gatherConsentAndInit()
    }

    // 1. Consent → init → preload -----------------------------------------

    private fun gatherConsentAndInit() {
        val consent = ConsentManager.getInstance(this)
        val start = {
            NextGenAds.initialize(this, APP_ID) { preloadAll() }   // runs once the SDK is ready
        }
        if (consent.canRequestAds) start() else consent.gatherConsent(this) { start() }
    }

    /** Warm every cache right after init — the single biggest lever on show rate. */
    private fun preloadAll() {
        BannerAdHelper.preload(this, BANNER_UNIT, count = 1)
        NativeAdHelper.preload(NATIVE_UNIT, count = 2)
        Interstitials.preload(INTERSTITIAL_UNIT)
    }

    // 2. Banner — attaches a preloaded banner instantly, else loads behind a shimmer ------

    private fun showBanner() {
        BannerAdHelper.loadAdaptiveBanner(
            activity = this,
            container = bannerContainer,
            adUnitId = BANNER_UNIT,
            onLoaded = { /* shown */ },
            onFailed = { /* no fill — the container is collapsed automatically */ },
        )
    }

    // 3. Native — renders into the drop-in view with the chosen template ------------------

    private fun showNative() {
        nativeAdView.load(
            adUnitId = NATIVE_UNIT,
            remoteEnabled = true,                     // your remote-config flag; false hides the view
            nativeTemplate = NativeTemplate.MEDIUM,   // optional override
            onLoaded = { /* shown */ },
            onFailed = { /* failed — the view hides itself */ },
        )
    }

    // 4. Interstitial — show a preloaded ad, or load one on demand -----------------------

    private fun showInterstitial() {
        // Shows the cached ad instantly if ready, otherwise requests one (bounded by the timeout).
        // onDismiss always fires — even when no ad could be shown — so your flow stays uniform.
        Interstitials.get(INTERSTITIAL_UNIT).loadAndShow(this, timeoutMs = 8_000L) {
            goToNextScreen()
        }
    }

    // 5. Counter-gated interstitial — show on click 1, then every 4th (1, 5, 9, 13 …) ----

    private fun showInterstitialByCounter() {
        val helper = Interstitials.get(INTERSTITIAL_UNIT)
        // Warm an ad the moment the counter is first used, so even click #1 is ready.
        if (!helper.isReady) Interstitials.preload(INTERSTITIAL_UNIT)
        counterClicks++

        // showFirstThenEvery tracks the counter for you; onDismiss fires on the shown clicks.
        // forceLoad = true loads on demand (bounded by timeoutMs) if the gate opens with no cached ad.
        val shown = helper.showFirstThenEvery(this, nth = 4, forceLoad = true, timeoutMs = 5_000L) {
            goToNextScreen()
        }
        if (!shown) {
            // A non-show click: warm the next ad so the gated-in click shows without a load wait.
            Interstitials.preload(INTERSTITIAL_UNIT)
        }
    }

    override fun onDestroy() {
        nativeAdView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        private const val BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"
        private const val NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
        private const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"
    }
}
```

The corresponding layout just needs the banner container and the native view:

```xml
<FrameLayout
    android:id="@+id/bannerContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />

<com.alihassan.nextgenads.BannerNativeView
    android:id="@+id/nativeAdView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ngad_ad_type="nativead"
    app:ngad_template="medium" />
```

**Why it's structured this way**

| Step | Why |
| --- | --- |
| Preload in the `initialize` callback | The cache is warm before the user reaches any placement, so the first show is instant instead of showing a shimmer. |
| `loadAndShow` for the plain interstitial | One call handles both cases — show the cached ad, or request+show on demand — and `onDismiss` always fires, so navigation is uniform. |
| `showFirstThenEvery` for the counter | The library tracks the per-unit counter app-wide; you don't keep your own modulo logic. `nth = 4` → shows on 1, 5, 9, 13 … |
| Re-`preload` after a non-show click | Keeps an ad ready for the *next* gated-in click, so it too shows without a visible load. |
| `forceLoad = true` | Safety net: if the gate opens and no ad is cached yet (e.g. click #1, or a splash consumed the unit), it loads on demand within `timeoutMs` instead of silently skipping. |

> Counter variants: use `showEvery(activity, nth = 3) { … }` to show on every 3rd call (3, 6, 9 …)
> instead of first-then-every. Reset the counter for a new session with
> `Interstitials.get(unit).resetTriggerCount()`. See [Counter-gated shows](#counter-gated-shows).

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

// Privacy options entry-point (e.g. from a Settings screen):
if (consent.isPrivacyOptionsRequired) {
    consent.showPrivacyOptionsForm(this) { /* dismissed */ }
}
```

### Testing the consent form outside the EEA

Pass your device's hashed id (printed in Logcat on first run) to `getInstance`. The device is then
treated as a test device and the EEA geography is forced so the form actually appears:

```kotlin
ConsentManager.getInstance(this, "33BE2250B43518CCDA7DE426D04EE231")
    .gatherConsent(this, forceEea = true) { /* form will show */ }
```

> Never ship a test-device hash or `forceEea = true` in a release build — guard them behind
> `BuildConfig.DEBUG`.

| Member | Description |
| --- | --- |
| `canRequestAds: Boolean` | `true` once ads may be requested (consent obtained or not required). |
| `isPrivacyOptionsRequired: Boolean` | `true` when a "Privacy options" entry-point must be shown. |
| `gatherConsent(activity, forceEea, onComplete)` | Updates consent info and shows the form if required. |
| `showPrivacyOptionsForm(activity, onDismissed)` | Presents the privacy options form. |
| `reset()` | Clears all consent state (testing only). |

---

## Initialization

```kotlin
NextGenAds.initialize(
    context = this,
    appId = "ca-app-pub-XXXX~YYYY",
    testDeviceIds = listOf("YOUR_TEST_DEVICE_ID"),   // optional
) {
    // Runs on the main thread once ready — a good place to start preloading.
}
```

Initialization runs off the main thread (as the Next-Gen SDK requires) and the callback is delivered
on the main thread. Calling `initialize` again after it finishes simply runs the callback
immediately. **Any load/preload issued before init completes is queued and replayed** once the SDK is
ready — so you can preload eagerly without racing initialization.

| Member | Description |
| --- | --- |
| `initialize(context, appId, testDeviceIds, onComplete)` | Initializes the SDK once. |
| `isInitialized(): Boolean` | Whether initialization has completed. |
| `enabled: Boolean` | Master kill-switch (default `true`). Setting `false` purges & hides all ads. |
| `loggingEnabled: Boolean` | Verbose Logcat under tag `NextGenAds` (default `true`). |
| `premium`, `premiumProvider`, `canShowAds()`, `clearAllAds()` | See [Premium](#premium--ad-free-users). |

---

## Splash screen (splash interstitial)

`SplashAd` shows an interstitial while your splash is up, held for a **minimum delay** (branding is
always visible) and bounded by a **timeout** (a slow or failed load can never trap the user).
`onComplete` fires exactly once — after the ad is dismissed, or when it's skipped — so you just
navigate onward there. **Exactly one interstitial is requested on open.**

```kotlin
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Gather consent + initialize first, then run the splash ad.
        val consent = ConsentManager.getInstance(this)
        val start = {
            NextGenAds.initialize(this, APP_ID) {
                SplashAd.show(
                    activity = this,
                    adUnitId = INTERSTITIAL_UNIT,
                    minDelayMs = 1_500L,   // keep the splash up at least this long
                    timeoutMs = 8_000L,    // give up waiting for the ad after this (coerced ≥ minDelayMs)
                ) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
        if (consent.canRequestAds) start() else consent.gatherConsent(this) { start() }
    }
}
```

Behaviour, at a glance:

| Situation | What happens |
| --- | --- |
| Ad loads fast | Wait out `minDelayMs`, show it, `onComplete` on dismiss. |
| Ad loads slowly | Shown as soon as it lands (past `minDelayMs`), up to `timeoutMs`. |
| Ad never loads | `onComplete` fires at `timeoutMs`; the in-flight load keeps warming the cache. |
| Ads disabled / premium | `onComplete` fires after `minDelayMs`; no request is made. |

If you also use `AppOpenAdManager`, skip the splash so an app-open ad doesn't compete with the splash
interstitial: `AppOpenAdManager.install(...).skipOn(SplashActivity::class.java)`.

---

## Banner ads

Anchored adaptive banners with a shimmer placeholder and optional preloading. The container is
**collapsed** on no-fill so no empty gap is left behind.

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
| `maxRetries` | `2` | Reload attempts after a failed load (backoff 1s/2s/4s). |
| `preload(activity, adUnitId, count, widthDp, size)` | `count = 1`, `size = ADAPTIVE` | Warms the cache. |
| `loadAdaptiveBanner(activity, container, adUnitId, refill, collapsible, size, onLoaded, onFailed)` | `refill = false`, `size = ADAPTIVE` | Shows a banner. |
| `clearAll()` | — | Destroys the pool and hides banners in populated containers. |

### Banner sizes

By default a banner is a full-width **large anchored adaptive** banner whose height the SDK picks
from the slot width. Pass a `BannerSize` to `preload` / `loadAdaptiveBanner` to request a different
size — adaptive variants that flex to the slot, or the fixed IAB sizes:

| `BannerSize` | Dimensions | Use for |
| --- | --- | --- |
| `ADAPTIVE` *(default)* | full width × adaptive height | Pinned top/bottom banner slots. |
| `ADAPTIVE_INLINE` | full width × taller adaptive height | Banners inside scrolling content / feeds. |
| `BANNER` | 320 × 50 | Fixed standard banner. |
| `LARGE_BANNER` | 320 × 100 | Fixed taller banner. |
| `FULL_BANNER` | 468 × 60 | Tablets. |
| `LEADERBOARD` | 728 × 90 | Tablets. |
| `MEDIUM_RECTANGLE` | 300 × 250 | In-content MREC. |

```kotlin
// Preload and show a 300×250 MREC:
BannerAdHelper.preload(this, BANNER_UNIT, count = 1, size = BannerSize.MEDIUM_RECTANGLE)
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.mrecContainer),
    adUnitId = BANNER_UNIT,
    size = BannerSize.MEDIUM_RECTANGLE,
)
```

The preload cache is keyed by **ad unit *and* size**, so a banner preloaded at one size is never
attached to a request for another. Fixed sizes ignore `widthDp`; adaptive sizes use the container's
content width. Collapsible requests always load fresh regardless of size.

### Collapsible banners

Pass a `BannerCollapsible` to request a **collapsible banner** — it shows as a larger overlay on the
first impression and collapses to the anchored banner (the SDK provides the expand/collapse control).
Anchor it at the edge where the banner actually sits on screen:

```kotlin
BannerAdHelper.loadAdaptiveBanner(
    activity = this,
    container = findViewById(R.id.bannerContainer),   // pin the container to that edge
    adUnitId = BANNER_UNIT,
    collapsible = BannerCollapsible.BOTTOM,            // or BannerCollapsible.TOP
    onLoaded = { /* shown */ },
    onFailed = { error -> /* no fill */ },
)
```

Collapsible requests always load fresh (the preload cache holds standard banners), so `refill` has no
effect for them.

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
| `app:ngad_template` | any template name — `small`, `medium`, `large`, `banner`, `media_left`, `collapsible`, `hero`, `feed`, `spotlight` | `medium` |
| `app:ngad_banner_size` | banner size name (used when `ngad_ad_type="banner"`) — `adaptive`, `adaptive_inline`, `banner`, `large_banner`, `full_banner`, `leaderboard`, `medium_rectangle` | `adaptive` |
| `app:ngad_customLayout` | a `@layout` reference (overrides `ngad_template`) | — |
| `app:ngad_customShimmer` | a `@layout` reference (else auto-generated) | — |

### 2. `NativeAdHelper` + `NativeTemplateView` (lower level)

```kotlin
// Preload into the per-unit cache:
NativeAdHelper.preload(NATIVE_UNIT, count = 2)

// Bind into a template view (shimmer until ready, cache-first). refill re-warms the cache:
NativeAdHelper.populate(templateView, NATIVE_UNIT, refill = true)

// Or get the raw ad:
NativeAdHelper.load(NATIVE_UNIT, onLoaded = { ad -> /* … */ })

// Free cached ads (e.g. on logout / low memory):
NativeAdHelper.clear(NATIVE_UNIT)   // or clear() for all units
```

### Built-in templates

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

Select any by name from XML (`app:ngad_template="hero"`) or in code (`NativeTemplate.HERO`). All
templates use rounded, clipped media, a ripple CTA, an "Ad" attribution badge and Roboto typography.
The three creative templates (`HERO`, `FEED`, `SPOTLIGHT`) ship no shimmer XML — one is
auto-generated.

### Your own custom template

Supply your **own layout** and the same shimmer / cache / tracking pipeline drives it. Two ways,
depending on how much control you want.

**A. ID-contract (no code)** — make the layout's root a `NativeAdView` and give the asset views the
library IDs below. Binding, asset registration and click/impression tracking are then automatic.

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
NativeAdHelper.populate(templateView, NATIVE_UNIT)
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

**Auto-generated shimmer** — you don't have to design a shimmer per template. Omit
`app:ngad_customShimmer` / the `shimmer` argument and `NativeTemplateView` builds one from your
layout (`ShimmerSkeleton`): each content view becomes a rounded grey block and the shimmer sweep is
animated, matching the real layout's shape. Pass `autoShimmer = false` to show no placeholder. You
can also call `ShimmerSkeleton.fromLayout(context, R.layout.my_native)` directly.

**Media scaling** — media fills its `MediaView` via `NativeTemplateView.mediaScaleType` (default
`ImageView.ScaleType.CENTER_CROP`), so a creative whose aspect ratio differs from the slot fills it
instead of letterboxing and exposing the view's background as grey bars. Set `FIT_CENTER` to show
the whole creative (in a custom binder, set `mediaView.imageScaleType` yourself).

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

Custom layouts still get the cache, retry/backoff, expiry and error-collapse behaviour of the
built-in templates.

---

## Interstitial ads

```kotlin
// Preload once (reused across screens via the registry):
Interstitials.preload(INTERSTITIAL_UNIT)

// Show — onDismiss is always called (immediately if no ad was ready):
Interstitials.get(INTERSTITIAL_UNIT).show(this) { goToNextScreen() }

// Load on demand and show as soon as it's ready (bounded by a timeout):
Interstitials.loadAndShow(this, INTERSTITIAL_UNIT, timeoutMs = 5_000L) { goToNextScreen() }

// Tune behaviour:
val helper = Interstitials.get(INTERSTITIAL_UNIT)
helper.maxRetries = 3               // exponential backoff on load failure (1s, 2s, 4s …)
helper.minIntervalMs = 60_000       // frequency cap; 0 disables
helper.adValidityMs = 55 * 60_000L  // cached-ad expiry; stale ads are dropped, never shown
helper.autoReload = true            // request the next ad automatically after each dismissal
helper.loadingOverlayMs = 1_000L    // brief "Loading ad…" interlude before the ad opens; 0 to disable
val ready = helper.isReady          // non-expired ad cached
```

Cached interstitials expire after ~1 hour on AdMob's side; the helper drops a stale ad instead of
burning the show on an "ad expired" failure. Only one full-screen ad (any format) can be on screen at
a time — a `show()` while another is presenting is refused and the ad stays cached (see
`NextGenAds.isFullScreenAdShowing()`).

### Counter-gated shows

Show on every Nth trigger without tracking a counter yourself. The counter is app-wide per unit;
`onDismiss` still fires on the in-between calls so your flow stays uniform.

```kotlin
// Show on the 3rd, 6th, 9th … call:
Interstitials.showEvery(this, INTERSTITIAL_UNIT, nth = 3) { startNextLevel() }

// Show on the 1st call, then every 4th after (1, 5, 9, 13 …):
Interstitials.showFirstThenEvery(this, INTERSTITIAL_UNIT, nth = 4) { startNextLevel() }

// Reset the counter (e.g. on a new session):
Interstitials.get(INTERSTITIAL_UNIT).resetTriggerCount()
```

Both accept `forceLoad = true` to load on demand (bounded by `timeoutMs`) when the gate opens with no
cached ad, instead of skipping.

### Splash interstitial

For the app-startup case, use [`SplashAd`](#splash-screen-splash-interstitial) — it adds the minimum
delay + timeout coordination a splash needs.

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

### Auto-show on foreground (recommended)

`AppOpenAdManager` wires itself to the process lifecycle and shows an ad each time the app returns to
the foreground. Install it once, in `Application.onCreate()`:

```kotlin
AppOpenAdManager.install(this, APP_OPEN_UNIT)
    .skipOn(SplashActivity::class.java)   // never cover these screens
```

On a genuine background→foreground transition a cached (non-expired) ad shows instantly. If none is
cached, one is requested at that moment and shown only when it loads within `loadTimeoutMs`
(default 5 s) while the app is still foregrounded — an ad that arrives later is **never** popped over
app content mid-session; it stays cached so the *next* return shows instantly.

The first foreground after a cold start is skipped by default — set `showOnColdStart = true` to opt
in. Pause auto-showing with `AppOpenAdManager.get()?.enabled = false`. Activities implementing
`HideAppOpenAd` (or registered via `skipOn`) are never covered, and an app-open never stacks on
another full-screen ad.

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

Every helper consults a single gate:
`NextGenAds.canShowAds() == enabled && !premium && !premiumProvider()`.

```kotlin
// Static flag:
NextGenAds.premium = user.hasActiveSubscription

// Or dynamic (evaluated on every ad request):
NextGenAds.premiumProvider = { billingRepository.isPremium() }
NextGenAds.refreshPremiumState()   // apply a premiumProvider change right now

// Or disable all ads entirely:
NextGenAds.enabled = false
```

**Runtime purge.** Setting `premium = true` (or `enabled = false`) doesn't just stop *new* requests —
it immediately **drops every format's cached ad and hides any banner/native already on screen**, so a
mid-session purchase removes ads at once and frees their memory. Nothing is requested again while
premium.

- `BannerNativeView` and `NativeTemplateView` register themselves while attached and hide on purge.
- Banners shown via `BannerAdHelper.loadAdaptiveBanner` are cleared from their containers.
- Because `premiumProvider` is evaluated lazily, call `NextGenAds.refreshPremiumState()` after your
  billing state flips so the purge runs.
- Trigger the purge directly anytime (logout, low memory) with `NextGenAds.clearAllAds()`.

---

## Ad events (analytics & revenue)

Register a single `AdEventListener` once and receive **every** ad lifecycle event from **every**
format — load, show, dismiss, impression, click, paid-revenue and reward — without threading
callbacks through each call site. This is the recommended hook for analytics and ROAS / ad-revenue
measurement. Per-call callbacks (`onDismiss`, `onReward`, …) still fire; events are additive.

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
            responseInfo?.loadedAdSourceResponseInfo?.name?.let {
                putString(FirebaseAnalytics.Param.AD_SOURCE, it)
            }
        })
    }

    override fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) {
        // …grant the reward / track completion
    }
})
```

Every method has a no-op default, so implement only what you need. Call
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

## Show rate & fill rate tips

- **Preload** every format right after `initialize` completes. Pass `refill = true` to
  `populate` / `loadAdaptiveBanner` (or set `autoReload = true` on full-screen helpers) so consuming
  a cached ad automatically warms the next one.
- Cached ads **expire** (~1 h for interstitial/rewarded/native/banner, 4 h for app-open). The helpers
  drop stale inventory instead of failing the show — tune via each helper's `adValidityMs`.
- Keep `BannerNativeView` / `NativeTemplateView` instances around and rebind, rather than recreating.
- Bump `NativeAdHelper.maxCachePerUnit` / `BannerAdHelper.maxCachePerUnit` for high-traffic screens.
- On a flaky connection the shared **circuit breaker** pauses new requests briefly (cached ads still
  show) and auto-resumes; connectivity recovery re-enables requests when the network returns.
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

No extra configuration is needed in your app: the library's public API survives R8 through normal
reference-based keep analysis, and the Next-Gen Ads SDK, UMP and Shimmer bring their own consumer
keep rules.

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| **Consent never gathers / "Ads not allowed"** | Missing `com.google.android.gms.ads.APPLICATION_ID` meta-data in the app manifest, or no GDPR message published in the AdMob console. |
| **Consent form never appears** | Expected outside the EEA. To test, pass your test-device hash to `ConsentManager.getInstance` and `forceEea = true`. |
| **No ads show with test ids** | Make sure `NextGenAds.initialize` finished (`isInitialized()`), and that `premium`/`enabled` aren't suppressing ads. |
| **Native media has grey side/top bars** | The creative's aspect ratio differs from the slot. `mediaScaleType` defaults to `CENTER_CROP` to fill it; keep it, or use `FIT_CENTER` to show the whole creative with bars. |
| **Splash never proceeds** | `SplashAd.onComplete` always fires by `timeoutMs`; ensure you call `initialize` first and that your navigation runs in `onComplete`. |
| **`Animators may only be run on Looper threads`** | Not from this library — all callbacks run on the main thread. Check you aren't touching shimmer off the main thread in your own code. |
| **JitPack build fails** | Bleeding-edge AGP/SDK — try `openjdk21` in `jitpack.yml`, or distribute the AAR directly. |

---

## API reference

```
com.alihassan.nextgenads
├── NextGenAds                          // initialize, enabled, premium, premiumProvider,
│                                       //   canShowAds, refreshPremiumState, clearAllAds,
│                                       //   isInitialized, register/unregisterEventListener
├── BannerNativeView                    // drop-in banner/native View (premium-aware)
├── AdType                              // BANNER, NATIVE
├── events.AdEventListener              // app-wide ad events (load/impression/click/paid/reward)
├── events.AdFormat                     // BANNER, NATIVE, INTERSTITIAL, REWARDED, REWARDED_INTERSTITIAL, APP_OPEN
├── consent.ConsentManager              // UMP consent
├── banner.BannerAdHelper               // adaptive + collapsible banners, clearAll()
├── banner.BannerCollapsible            // TOP, BOTTOM
├── banner.BannerSize                   // ADAPTIVE, ADAPTIVE_INLINE, BANNER, LARGE_BANNER, FULL_BANNER, LEADERBOARD, MEDIUM_RECTANGLE
├── nativead.NativeAdHelper             // native loading + cache, clear()
├── nativead.NativeTemplate             // SMALL, MEDIUM, LARGE, BANNER, MEDIA_LEFT, COLLAPSIBLE, HERO, FEED, SPOTLIGHT
├── nativead.NativeTemplateView         // renders a NativeTemplate or a custom layout (setCustomTemplate)
├── nativead.ShimmerSkeleton            // fromLayout(context, layout) → auto shimmer
├── interstitial.Interstitials          // registry → InterstitialAdHelper (showEvery / showFirstThenEvery)
├── interstitial.SplashAd               // splash interstitial (min delay + timeout)
├── rewarded.RewardedAds                // registry → RewardedAdHelper
├── rewardedinterstitial.RewardedInterstitials  // registry → RewardedInterstitialAdHelper
├── appopen.AppOpenAds                  // registry → AppOpenAdHelper
└── appopen.AppOpenAdManager            // auto-show on foreground (process lifecycle), skipOn(...)
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
