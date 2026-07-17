# Configuration — defaults, premium, remote toggles

## `NextGenAdsConfig` — app-wide defaults

Every value is read **live**, so set only what you want to change, typically once from
`Application.onCreate`. Assigning a value that already equals the default just re-asserts it.

```kotlin
NextGenAdsConfig.minIntervalMs = 60_000L    // ≥ 60s between interstitials, app-wide
NextGenAdsConfig.splashTimeoutMs = 6_000L   // leave the splash sooner
```

| Property | Default | Meaning |
| --- | --- | --- |
| `maxRequestFailures` | `3` | Consecutive network/timeout failures before the request breaker trips |
| `requestCooldownMs` | `3 * 60 * 1000` | How long new requests pause once it trips |
| `splashMinDelayMs` | `1_500` | Minimum splash time before an ad may show |
| `splashTimeoutMs` | `8_000` | Hard ceiling on the splash; `0` = no timeout |
| `splashRetryOnFailure` | `false` | A splash load is a single attempt |
| `forceShowTimeoutMs` | `8_000` | Caps on-demand `loadAndShow` fetches; `0` = wait for the load |
| `rewardedForceShowTimeoutMs` | `10_000` | Same, for rewarded (longer — the user opted in) |
| `maxRetries` | `3` | Reload attempts after a failed load (exponential backoff: 1s, 2s, 4s…) |
| `adValidityMs` | `55 * 60 * 1000` | Cached-ad lifetime (app-open is fixed at 4 h, not this) |
| `autoReload` | `false` | Whether a show automatically requests the next ad |
| `minIntervalMs` | `0` | Minimum gap between full-screen shows; `0` = no cap |
| `loadingOverlayMs` | `0` | Artificial dwell on a "Showing ad…" cover before a ready ad opens |
| `minLoadingCoverMs` | `500` | Minimum time a loading cover stays up (anti-flicker) |
| `appOpenLoadTimeoutMs` | `5_000` | Window for a foreground-return app-open to land and still show |

Banner and native tuning is **not** here — it lives on the helpers (`BannerAdHelper.maxCachePerUnit`,
`NativeAdHelper.maxRetries`, …).

### Per-unit overrides

Helper properties follow `NextGenAdsConfig` **until you assign one**, which pins it for that unit:

```kotlin
Interstitials.get(AdUnits.INTERSTITIAL).minIntervalMs = 90_000L   // this unit only; others follow the config
```

### Why `autoReload` is off

With `autoReload = true`, a single `loadAndShow` costs **two** requests (the show, plus an automatic
refetch) even if the user never sees another ad — which wrecks your request:impression ratio. Off by
default: one show = one request, and you warm the next deliberately.

```kotlin
Interstitials.loadAndShow(this, unit) { goNext() }
Interstitials.preload(unit)   // explicit, at a moment you choose
```

## Premium / ad-free users

```kotlin
NextGenAds.premium = true                       // static: user bought IAP
NextGenAds.premiumProvider = { billing.isPro }  // dynamic: evaluated on every request
NextGenAds.refreshPremiumState()                // apply a premiumProvider change immediately
```

While premium, **nothing is requested or shown**. Setting `premium = true` (or calling
`refreshPremiumState()` after a dynamic flip) doesn't only stop new requests — it **immediately**
drops every cached ad across all formats and hides inline ads already on screen, freeing their memory.

`premiumProvider` is evaluated lazily, so changing what it returns does **not** auto-purge — call
`refreshPremiumState()` when your billing state flips, or the user keeps seeing ads until the next
request.

## Kill switches

Three independent gates, deliberately separate so a server toggle never fights a purchase:

```kotlin
NextGenAds.enabled = false          // local master kill-switch
NextGenAds.adsLoadEnabled = false   // remote-config master switch
NextGenAds.premium = true           // per-user IAP
```

```kotlin
NextGenAds.canShowAds(): Boolean               // enabled && adsLoadEnabled && !premium
NextGenAds.canShowAds(format): Boolean         // …and that format's toggle is on
NextGenAds.canRequest(): Boolean               // canShowAds && consent allows && breaker not paused
NextGenAds.canRequest(format): Boolean
```

Use `canShowAds` to gate *showing* a loaded ad, `canRequest` to gate a *new request*.

## Per-format toggles

Wire each to its own remote-config key. All default to on.

```kotlin
NextGenAds.bannerAdsEnabled = rc.getBoolean("banner_ads")
NextGenAds.nativeAdsEnabled = …
NextGenAds.interstitialAdsEnabled = …
NextGenAds.rewardedAdsEnabled = …
NextGenAds.rewardedInterstitialAdsEnabled = …
NextGenAds.appOpenAdsEnabled = …

NextGenAds.setFormatEnabled(AdFormat.NATIVE, false)
NextGenAds.isFormatEnabled(AdFormat.NATIVE)
```

Turning a format off purges that format's cache and hides its on-screen inline ads. Turning it back
on lets it load again on the next trigger.

## Purging

```kotlin
NextGenAds.clearAllAds()              // every format's cache + hide live inline slots
NextGenAds.clearFormat(AdFormat.NATIVE)
```

Safe any time (logout, low memory), not only for premium.

## Warm-up and connectivity recovery

The biggest lever on show rate is preloading early and re-warming after a failure window.

```kotlin
NextGenAds.registerWarmUp { NativeAdHelper.preload(AdUnits.NATIVE) }
NextGenAds.registerWarmUp { Interstitials.preload(AdUnits.INTERSTITIAL) }
NextGenAds.enableConnectivityRecovery(this)   // NextGenAdsBootstrap.configure does this for you
```

Registered tasks run when the SDK finishes initialising, and again on every `warmUp()` — including
automatically when the network comes back, which also clears the request breaker's cooldown. So ads
that failed on a dead connection are re-requested the moment it recovers.

## Ad audio

Mirror your app's own volume/mute so an ad never blasts over your audio. Both are remembered and
applied once initialised, so they're safe to call at any time.

```kotlin
NextGenAds.setAppVolume(0.5f)   // fraction of device volume, 0f..1f (clamped)
NextGenAds.setAppMuted(true)
```

## Logging

```kotlin
NextGenAds.loggingEnabled = BuildConfig.DEBUG   // logcat tag: "NextGenAds"
```
