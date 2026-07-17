# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
