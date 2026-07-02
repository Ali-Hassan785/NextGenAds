# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Ad events** — `AdEventListener` + `AdFormat` with `NextGenAds.registerEventListener` /
  `unregisterEventListener`. A single app-wide hook for every ad lifecycle event across all formats:
  load, failed-to-load, show, dismiss, failed-to-show, impression, click, **paid-revenue**
  (`onAdPaid` / `AdValue`, for ROAS / analytics), and reward. Callbacks are delivered on the main
  thread and isolated so one listener's exception can't suppress the others. Banner and native ads
  now attach an event callback so their impression/click/paid events are surfaced too.
- `AppOpenAds` / `AppOpenAdHelper` — app-open ads with preloading, exponential-backoff retries,
  frequency capping, and 4-hour expiry handling (stale ads are refetched, never shown).
- `AppOpenAdManager` — opt-in manager that auto-shows an app-open ad when the app returns to the
  foreground, driven by `ProcessLifecycleOwner`.
- `Interstitials.showOnCount` / `InterstitialAdHelper.showOnCount` + `resetCounter` — counter-gated
  interstitials that show on every Nth call.

### Fixed
- Native ads no longer shimmer forever when a load fails — the slot now collapses (`showError`).
- `BannerNativeView` now destroys its bound native ad in `hide()` and when switching to a banner,
  preventing a `NativeAd` leak.
- `AppOpenAdManager` holds the current `Activity` via a `WeakReference` (fixes a `StaticFieldLeak`).
- Banner `preload` builds its `AdView` only after the SDK is initialized.

### Changed
- Load/preload requests issued before `NextGenAds.initialize()` completes are now queued (via the
  new `NextGenAds.whenInitialized`) and replayed once the SDK is ready, instead of failing against
  an uninitialized SDK and wasting the retry budget. Affects every ad format.

### Dependencies
- Added `androidx.lifecycle:lifecycle-process:2.6.2` (for `AppOpenAdManager`).

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
