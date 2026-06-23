# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0]

Initial release.

### Added
- `NextGenAds` entry point: background-thread initialization, `enabled` / `premium` /
  `premiumProvider` gating, and `canShowAds()`.
- `ConsentManager` — User Messaging Platform (UMP) GDPR flow with test-device support.
- `BannerAdHelper` — anchored adaptive banners with preloading, per-unit caching, and shimmer.
- `BannerNativeView` — drop-in view that renders a banner or native ad from XML.
- `NativeAdHelper` + `NativeTemplateView` — native loading with per-unit cache and five premium
  templates: `SMALL`, `MEDIUM`, `LARGE`, `BANNER`, `MEDIA_LEFT`.
- `Interstitials` — preload, frequency capping, and exponential-backoff retries.
- `RewardedAds` and `RewardedInterstitials` — preload + show with reward callbacks.
- Premium-themed native templates: rounded clipped media, ripple CTA, "Ad" badge, Roboto typography,
  and an app-overridable color palette.
- All SDK callbacks marshalled to the main thread.
- `maven-publish` + JitPack configuration for distribution.

### Dependencies
- `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.2.1`
- `com.google.android.ump:user-messaging-platform:4.0.0`
- `com.facebook.shimmer:shimmer:0.5.0`

[1.0.0]: https://github.com/Ali-Hassan785/NextGenAds/releases/tag/1.0.0
