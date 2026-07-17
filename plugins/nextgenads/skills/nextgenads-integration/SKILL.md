---
name: nextgenads-integration
description: >
  Integrate the NextGenAds Android library (a wrapper over Google's Next-Gen Mobile Ads SDK) into an
  app: Gradle setup, UMP consent, SDK init, and banner, native, interstitial, rewarded,
  rewarded-interstitial, app-open and splash ads, plus Jetpack Compose wrappers, a premium
  kill-switch and fill/show-rate diagnostics. Use when adding, configuring or debugging AdMob ads in
  an Android project, or when the user mentions NextGenAds, AdMob, UMP consent, app-open ads,
  NextGenAdsBootstrap, AppOpenAdManager, SplashAdGate, NativeTemplateView or BannerNativeView.
---

# NextGenAds integration

NextGenAds wraps Google's **Next-Gen Mobile Ads SDK** (`com.google.android.libraries.ads.mobile.sdk`)
and the **User Messaging Platform** (UMP) behind small helpers tuned for show rate and fill rate.
Every SDK callback is marshalled to the **main thread**, so callbacks can touch UI directly. The
whole surface is `@JvmStatic` / `@JvmOverloads`, so it reads naturally from Java as well as Kotlin.

## Version

Verified against **NextGenAds 1.4.0** (commit `c34f529`). Two rules:

- **The workspace wins.** If the project has the library's source or README available, that is the
  truth — this skill is a fallback for when it isn't. The canonical README is
  `https://raw.githubusercontent.com/Ali-Hassan785/NextGenAds/main/README.md`; fetch it if a fact
  here looks wrong.
- **Check the resolved version before trusting a snippet.** `1.4.0` renamed `onDismiss` →
  `onComplete` on every full-screen format, and the `nextgenads-compose` artifact does not exist
  before `1.1.0` — so these snippets do **not** compile against `1.0.x`. Newest tag:
  `git ls-remote --tags https://github.com/Ali-Hassan785/NextGenAds`.

## Golden path

Five steps get ads working end to end. Full detail is in `references/setup.md`.

**1. Gradle** — note the group is `com.github.<user>.<repo>`:

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { … } }
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts
implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads:1.4.0")
```

**2. Manifest** — the App ID (a **tilde** id), read by both the SDK and UMP:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-3940256099942544~3347511713" />
```

**3. `Application.onCreate`** — process-level wiring. Requests nothing:

```kotlin
NextGenAdsBootstrap.configure(
    application = this,
    appOpenUnitId = AdUnits.APP_OPEN,                       // installs foreground-return auto-show
    skipAppOpenOn = listOf(SplashActivity::class.java),     // splash runs its own ad
)
```

**4. First screen** — consent, then init, then ads:

```kotlin
NextGenAdsBootstrap.gatherConsentThenInitialize(
    activity = this,
    testDeviceHashedId = if (BuildConfig.DEBUG) "YOUR_HASH" else null,  // debug only
) {
    SplashAdGate.show(                                       // one splash ad, then onComplete once
        activity = this,
        coldStart = SplashAdGate.consumeColdStart(),
        interstitialUnitId = AdUnits.SPLASH_INTERSTITIAL,
        appOpenUnitId = AdUnits.SPLASH_APP_OPEN,
        onComplete = ::goToMain,
    )
}
```

**5. Show an ad** — e.g. an interstitial that already has inventory:

```kotlin
Interstitials.preload(AdUnits.INTERSTITIAL)                  // warm early, once
Interstitials.loadAndShow(this, AdUnits.INTERSTITIAL) { goNext() }
```

## Rules that are easy to get wrong

Read these before writing any integration code. Most "ads don't show" reports are one of these.

- **Order is consent → `initialize` → request.** A pre-consent request is refused by the
  `consentProvider` gate, so ads silently never request. `gatherConsentThenInitialize` encodes the
  order — use it rather than calling the two yourself.
- **The App ID uses a tilde** (`ca-app-pub-…~…`). Passing an ad *unit* id (a slash, `…/…`) there
  fails later as an opaque `INVALID_REQUEST`.
- **Use the multi-module JitPack coordinate** `com.github.Ali-Hassan785.NextGenAds:nextgenads`. The
  flat `com.github.Ali-Hassan785:nextgenads` also resolves, but on a multi-module repo JitPack turns
  it into an aggregate that drags all of Jetpack Compose into an XML-only app.
- **`autoReload` defaults to `false`** everywhere: one show = one request. Warm the next ad
  explicitly with `preload()` / `load()`, or the second show finds an empty cache.
- **Only one full-screen ad may be on screen at a time**, enforced globally (stacking violates
  AdMob policy). A refused `show()` keeps its ad cached and fires `onComplete` synchronously.
- **Every `show*` / `loadAndShow` fires `onComplete` exactly once** — on dismiss, failure, or
  timeout. Navigate there. Never *also* navigate on your own timer, or you will double-navigate.
- **App-open ads expire after 4 h**; every other format follows `NextGenAdsConfig.adValidityMs`
  (55 min). A stale ad is dropped and refetched, so `isReady` can flip to false on its own.
- **Exclude splash / paywall / onboarding from the auto app-open**: implement the `HideAppOpenAd`
  marker on activities you own, or `AppOpenAdManager.skipOn(...)` for ones you don't.
- **Give each placement its own ad unit** (splash interstitial ≠ in-app interstitial). One id shared
  across two placements averages two unlike populations, so you cannot tell which one is losing
  money, and you cannot tune floors for one without moving the other.
- **Templates read the host app's Material3 theme attrs** (`?attr/colorSurface`,
  `?attr/colorOutlineVariant`) — the library ships no `values-night` qualifier. If ad cards look
  wrong in dark mode, the host theme is missing those tokens, not the library.
- **`premium = true` purges immediately** — cached ads are dropped and on-screen inline ads hidden.
  For dynamic billing use `premiumProvider` plus `refreshPremiumState()`.

## Reference map

Read **only** the file the task needs — each is self-contained.

| Task | File |
| --- | --- |
| Add the dependency, consent, init, test ids, ProGuard | `references/setup.md` |
| Banner or native ads, templates, custom layouts, theming | `references/inline-ads.md` |
| Interstitial, rewarded, rewarded-interstitial, app-open | `references/fullscreen-ads.md` |
| Splash ad flow (cold vs warm start) | `references/splash.md` |
| Jetpack Compose wrappers | `references/compose.md` |
| Tuning defaults, premium, remote toggles | `references/config.md` |
| Ad events, fill/show-rate reports, troubleshooting | `references/diagnostics.md` |

## Working in a project that already uses NextGenAds

Before adding anything, check what exists — these apps usually centralise ads already:

1. Look for an `Application` subclass calling `NextGenAdsBootstrap.configure` and an ad-unit holder
   (often `AdUnits.kt`). Add new units there, not inline at the call site.
2. Grep for `registerEventListener` to find existing analytics/tracking before adding your own.
3. Check whether the screen is XML or Compose and use the matching API — do not mix a
   `NativeTemplateView` into a Compose screen when `NativeAd(...)` exists.

---

## Maintainers of NextGenAds only

This skill restates API facts that live in the library source, so it can drift. It was last verified
at `c34f529` (1.4.0); to find what changed since, diff the sources each file mirrors:

| Reference | Mirrors |
| --- | --- |
| `setup.md` | `NextGenAdsBootstrap.kt`, `NextGenAds.kt` (initialize), `consent/ConsentManager.kt`, both `build.gradle.kts` (version, artifactIds) |
| `inline-ads.md` | `banner/`, `nativead/` (esp. `NativeTemplate.kt`, `BannerNativeView.kt`) |
| `fullscreen-ads.md` | `interstitial/`, `rewarded/`, `rewardedinterstitial/`, `appopen/` |
| `splash.md` | `splash/SplashAdGate.kt`, `interstitial/SplashAd.kt`, `appopen/SplashAppOpenAd.kt` |
| `compose.md` | `nextgenadscompose/src/main/` |
| `config.md` | `NextGenAdsConfig.kt`, `NextGenAds.kt` (toggles/premium) |
| `diagnostics.md` | `events/AdEvents.kt`, `events/ShowRateTracker.kt` |

`git diff c34f529..HEAD -- nextgenads/src/main nextgenadscompose/src/main`. When the public API
changes, update the affected reference **and** the version stamp above.
