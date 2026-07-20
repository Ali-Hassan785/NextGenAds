# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.7.0] - 2026-07-20

### Added
- **`TITLE_ONLY` native template** — a title-forward card: the "Ad" badge on the left with the
  headline beside it on top, the media below the title, and a full-width CTA at the bottom. No icon,
  body, advertiser or rating. Select with `app:ngad_template="titleonly"` or
  `NativeTemplate.TITLE_ONLY`.
- `NativeTemplate.fromName` is now **separator-insensitive** — `"titleonly"`, `"title_only"` and
  `"title-only"` all resolve (any non-alphanumeric characters are ignored), for every template name.

### Changed
- `HALF_MEDIA` template retuned: tighter padding and margins, bold headline, hidden rating, and
  media/CTA height tweaks.
- Removed a duplicate `:nextgenadscompose` include in `settings.gradle.kts`.

## [1.6.0] - 2026-07-20

### Added
- **`FULLSCREEN` native template** — a full-height card whose media stretches to fill the leftover
  vertical space, with icon + headline/advertiser, rating, body and a bold CTA pinned to the bottom.
  For full-screen slots (a host that gives the `NativeTemplateView` `match_parent` height). Ships its
  own shimmer. Select with `app:ngad_template="fullscreen"` or `NativeTemplate.FULLSCREEN`.
- **App-colour theming for ads** — every ad template's CTA button and "Ad" badge now resolve a
  `ngad_*` palette from a theme overlay applied at inflation, so ads follow the host app's Material3
  colours out of the box. Re-colour **all** ads in one place with
  `NextGenAdsConfig.adThemeOverlay = R.style.MyAdOverlay`, or restore the fixed blue/amber accents
  with the shipped `ThemeOverlay.NextGenAds.Brand`. (Surfaces and body text already followed the
  app theme.)

### Changed
- Sample app: the native section's **Show preloaded native** and **Load & show native on demand**
  buttons now do what their labels say — the first binds a preloaded ad instantly (else loads on
  demand), the second always evicts the cache and loads a fresh ad — instead of both running the
  same handler.

## [1.0.0] - 2026-07-16

Initial release.

### Ad formats
- **Banner** — adaptive, inline-adaptive and fixed sizes, plus collapsible banners, via
  `BannerAdHelper` and the `BannerNativeView` drop-in view. Adaptive banners wait for their
  container's first layout so they size to the real container width instead of the full screen.
- **Native** — six built-in templates plus custom-layout templates, each with an auto-generated
  shimmer placeholder (`NativeAdHelper`, `NativeTemplateView`, `NativeAdPreloader`).
- **Interstitial** — preload/cache, `loadAndShow`, and counter-gated `showEvery` /
  `showFirstThenEvery` (`Interstitials`).
- **Rewarded** and **rewarded-interstitial** (`RewardedAds`, `RewardedInterstitials`).
- **App-open** — an auto-show-on-foreground-return manager plus manual control (`AppOpenAds`,
  `AppOpenAdManager`).
- **Splash gate** — shows an interstitial on a cold start and an app-open on a warm start over your
  splash, then calls back once (`SplashAdGate`).

### Setup & configuration
- **`NextGenAdsBootstrap`** — optional one-call setup: gather UMP consent → initialize (correct
  order, since a pre-consent request is refused), plus connectivity recovery and the app-open
  manager. App-agnostic and Java-friendly.
- **`NextGenAds.initialize`** reads the AdMob App ID from the manifest
  `com.google.android.gms.ads.APPLICATION_ID` meta-data (or an explicit `appId` overload), with a
  clear warning when the id is malformed (e.g. an ad-unit id passed by mistake) and an actionable
  error when the manifest entry is missing.
- **`NextGenAdsConfig`** — app-wide default options (splash timers, on-demand show timeouts, retry
  budget, per-unit frequency cap, and the request circuit breaker), read live.
- **Consent** via `ConsentManager` (UMP). Every ad request is gated on consent and deferred until
  initialization completes.

### Extras
- **Jetpack Compose** wrappers in the separate `nextgenads-compose` artifact.
- **Premium / ad-free** support — `NextGenAds.premium`, `premiumProvider`, `canShowAds`,
  `clearAllAds` (runtime purge of cached and shown ads).
- **Ad events & metrics** — an app-wide `AdEventListener` stream and live fill/show-rate tracking
  (`ShowRateTracker`).
- **Google Play In-App Update and In-App Review** helpers.
