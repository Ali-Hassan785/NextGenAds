# NextGenAds

A lightweight, premium Android ad-helper library wrapping Google's **Next-Gen Mobile Ads SDK**
(`com.google.android.libraries.ads.mobile.sdk`). It provides drop-in helpers for every common ad
format with preloading, caching, and high-quality native templates — tuned for show rate and fill
rate.

## Features

- **Banner** — anchored adaptive banners with preload + instant attach behind a shimmer.
- **Native** — five premium templates (small, medium, large, banner, media-left) with rounded
  media, ripple CTA, and high-quality typography; per-unit caching for instant fill.
- **Interstitial** — preload, frequency capping, and exponential-backoff retries.
- **Rewarded** & **Rewarded interstitial** — preload + show with reward callbacks.
- **Consent (UMP)** — thin wrapper around the User Messaging Platform GDPR flow.
- **Global gating** — `enabled` / `premium` / `premiumProvider` kill-switches honoured by every helper.
- All SDK callbacks are marshalled to the main thread.

## Modules

- `nextgenads/` — the reusable Android library.
- `app/` — a sample app demonstrating consent, banner, native, interstitial, rewarded, and
  rewarded-interstitial ads.

## Getting started

1. Add your AdMob **app id** to the app `AndroidManifest.xml`:
   ```xml
   <meta-data
       android:name="com.google.android.gms.ads.APPLICATION_ID"
       android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
   ```
2. Gather consent, then initialize:
   ```kotlin
   ConsentManager.getInstance(this).gatherConsent(this) {
       if (ConsentManager.getInstance(this).canRequestAds) {
           NextGenAds.initialize(this, APP_ID) { /* preload here */ }
       }
   }
   ```
3. Show ads (see the sample `MainActivity` for every format).

> The sample uses Google's official **test** ad unit IDs. Replace them with your own before release.

## License

MIT
