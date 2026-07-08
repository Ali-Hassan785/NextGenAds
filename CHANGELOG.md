# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.2] - 2026-07-08

### Added
- **Banner sizes** — `BannerSize` (`ADAPTIVE`, `ADAPTIVE_INLINE`, `BANNER` 320×50, `LARGE_BANNER`
  320×100, `FULL_BANNER` 468×60, `LEADERBOARD` 728×90, `MEDIUM_RECTANGLE` 300×250) can be passed to
  `BannerAdHelper.preload` / `loadAdaptiveBanner`, to `BannerNativeView.load(bannerSize = …)`, or via
  the `app:ngad_banner_size` XML attribute. The preload cache is keyed by ad unit **and** size, so a
  banner warmed at one size is never attached to a request for another. Fixed sizes are now centered
  in their container, and the loading shimmer matches a fixed size's exact footprint.

### Added (earlier, since 1.0.0)
- **Runtime premium purge** — setting `NextGenAds.premium = true` / `enabled = false` (or calling
  `NextGenAds.refreshPremiumState()` for a dynamic `premiumProvider`) now immediately drops every
  format's cached ad and hides any banner/native already on screen, via new `clearAll()` methods on
  each registry, `NextGenAds.clearAllAds()`, and a `PremiumAware` slot registry that
  `BannerNativeView` / `NativeTemplateView` / populated banner containers join. No ad is requested
  while premium (unchanged). Demo gains a Premium toggle.
- **Splash interstitial** — `SplashAd.show(activity, adUnitId, minDelayMs, timeoutMs, onComplete)`
  drives a splash-screen interstitial: loads while the splash is up, shows it only after a minimum
  delay (branding always visible) and never past a timeout (a slow/failed load can't trap the user),
  then fires `onComplete` once so the caller navigates on. Demo `SplashActivity` shows the pattern.
- **Collapsible banners** — `BannerAdHelper.loadAdaptiveBanner(..., collapsible = BannerCollapsible.BOTTOM)`
  (or `TOP`) requests a collapsible banner via the SDK's `"collapsible"` extra. Collapsible requests
  always load fresh (the preload cache holds standard banners).
- **Creative native templates** — `NativeTemplate.HERO`, `.FEED`, `.SPOTLIGHT` join the six built-ins
  (also selectable via `app:ngad_template="hero"` etc.). They ship no shimmer XML — one is
  auto-generated from the layout — and use the library's themeable `ngad_*` tokens.
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
- `Interstitials.showEvery` / `InterstitialAdHelper.showEvery` + `resetTriggerCount` — counter-gated
  interstitials that show on every Nth call.
- **Custom native templates** — `NativeTemplateView.setCustomTemplate(layout, shimmer, autoShimmer, binder)`
  plus `app:ngad_customLayout` / `app:ngad_customShimmer` XML attributes let callers supply their own
  native layout instead of the six built-ins. Layouts using the `ngad_*` asset IDs bind and track
  automatically; an optional `binder` gives full control for arbitrary-ID layouts. The cache,
  retry/backoff, expiry and error-collapse pipeline drives custom layouts unchanged.
- **Auto-generated shimmer** — `ShimmerSkeleton.fromLayout(context, layout)` builds a shimmer
  placeholder from any ad layout, so a custom template needs no hand-designed shimmer. When
  `app:ngad_customShimmer` / the `shimmer` argument is omitted, `NativeTemplateView` auto-generates
  one (each content view becomes a rounded grey block); pass `autoShimmer = false` to opt out. Leaf
  views — including the surface-backed `MediaView` — are replaced with plain blocks so the
  placeholder hides correctly and doesn't duplicate the ad view's IDs.
- **`NativeTemplateView.mediaScaleType`** — controls how native media fills its `MediaView`
  (default `CENTER_CROP`). The media now fills the slot regardless of aspect ratio instead of
  letterboxing and exposing the view's background as grey bars; set `FIT_CENTER` to show the whole
  creative.

### Fixed
- Native ads no longer shimmer forever when a load fails — the slot now collapses (`showError`).
- `BannerNativeView` now destroys its bound native ad in `hide()` and when switching to a banner,
  preventing a `NativeAd` leak.
- `AppOpenAdManager` holds the current `Activity` via a `WeakReference` (fixes a `StaticFieldLeak`).
- Banner `preload` builds its `AdView` only after the SDK is initialized.
- **Consumer R8 keep rules** — the shipped `consumer-rules.keep` (previously empty) now keeps the
  XML-inflated `NativeAdView` / `MediaView` and the library's `BannerNativeView` /
  `NativeTemplateView`, so an R8-minified host app can't strip them and break native inflation in
  release (banners, created in code, were unaffected).

### Changed
- Ad **load failures now log the reason centrally** — `code` / `message` / `responseInfo` for every
  format (e.g. `code=NO_FILL`), under tag `NextGenAds`, making no-fill vs invalid-request easy to tell.
- `app:ngad_template` is now resolved **by name** — the attribute is a plain string matched against
  the `NativeTemplate` enum names (`NativeTemplate.fromName`), replacing the brittle integer-index
  `fromAttr`. Existing XML using names (`app:ngad_template="medium"`) is unaffected.
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

[1.0.2]: https://github.com/Ali-Hassan785/NextGenAds/releases/tag/1.0.2
[1.0.0]: https://github.com/Ali-Hassan785/NextGenAds/releases/tag/1.0.0
