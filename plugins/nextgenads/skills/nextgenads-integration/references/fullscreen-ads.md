# Full-screen ads — interstitial, rewarded, rewarded-interstitial, app-open

All four share one lifecycle: **preload → show → `onComplete`**. Differences: rewarded adds
`onReward`; app-open adds a 4 h expiry and a foreground-return manager.

## Rules for all four

- **`onComplete` fires exactly once** — on dismiss, on failure to show, or on timeout. Navigate
  there. Never navigate after `show()` returns *and* in `onComplete`, or you double-navigate.
- **Only one full-screen ad on screen at a time**, enforced globally. A refused `show()` keeps its ad
  cached and calls `onComplete` **synchronously** — so `onComplete` is not guaranteed async.
- **`autoReload` is `false` by default**: a show does not fetch the next ad. Warm it with `preload()`.
- Ads expire after `NextGenAdsConfig.adValidityMs` (55 min); app-open after **4 h**.

## Interstitial

```kotlin
object Interstitials {
    fun get(adUnitId: String): InterstitialAdHelper
    fun preload(adUnitId: String, remoteEnabled: Boolean = true, onResult: ((Boolean) -> Unit)? = null)
    fun loadAndShow(activity: Activity, adUnitId: String,
                    timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {})
    fun showEvery(activity: Activity, adUnitId: String, nth: Int = 1, forceLoad: Boolean = false,
                  timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {}): Boolean
    fun showFirstThenEvery(activity: Activity, adUnitId: String, nth: Int = 1, forceLoad: Boolean = false,
                           timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {}): Boolean
    fun clearAll()
}
```

The common pattern — warm once, then show at a natural break:

```kotlin
Interstitials.preload(AdUnits.INTERSTITIAL)                 // e.g. from onReady / warm-up

Interstitials.loadAndShow(this, AdUnits.INTERSTITIAL) {     // shows cached, else fetches
    goToNextScreen()                                        // runs once, whatever happened
}
Interstitials.preload(AdUnits.INTERSTITIAL)                 // warm the next (autoReload is off)
```

### Counter-gated shows

Don't show on every trigger — cap the frequency instead:

```kotlin
Interstitials.showEvery(this, AdUnits.INTERSTITIAL, nth = 3) { goNext() }          // calls 3, 6, 9…
Interstitials.showFirstThenEvery(this, AdUnits.INTERSTITIAL, nth = 4) { goNext() } // calls 1, 5, 9…
```

Counters are app-wide per unit. Both return `false` when this call isn't a show-call — `onComplete`
still fires, so callers navigate uniformly. `forceLoad = true` fetches when the cache is empty on a
show-call instead of skipping.

### Per-unit helper

```kotlin
val helper = Interstitials.get(AdUnits.INTERSTITIAL)
helper.isReady                       // cached, non-expired ad available
helper.isShowing
helper.lastLoadMs                    // load time of the last fetch, -1 until one lands
helper.minIntervalMs = 60_000L       // ≥ 60s between shows of THIS unit
helper.loadingText = "Loading ad…"   // else R.string.ngad_ad_loading
helper.resetTriggerCount()           // reset showEvery/showFirstThenEvery counters
helper.clear()
```

Assigning a property **pins** it for that unit; leave it alone to keep following `NextGenAdsConfig`.

## Rewarded and rewarded-interstitial

`RewardedAds` and `RewardedInterstitials` are identical in shape. The only structural difference from
an interstitial is `onReward`.

```kotlin
object RewardedAds {   // and RewardedInterstitials
    fun get(adUnitId: String): RewardedAdHelper
    fun preload(adUnitId: String, remoteEnabled: Boolean = true)
    fun loadAndShow(activity: Activity, adUnitId: String, onReward: (RewardItem) -> Unit,
                    timeoutMs: Long = NextGenAdsConfig.rewardedForceShowTimeoutMs,
                    onComplete: () -> Unit = {})
    fun clearAll()
}
```

```kotlin
var earned = false
RewardedAds.loadAndShow(
    activity = this,
    adUnitId = AdUnits.REWARDED,
    timeoutMs = 10_000L,
    onReward = { reward ->                 // ONLY if the user actually earned it
        earned = true
        credit(reward.amount, reward.type) // grant here
    },
    onComplete = {                         // ALWAYS, when the ad closes
        if (!earned) toast("Watch the full video to earn your reward")
        RewardedAds.preload(AdUnits.REWARDED)
    },
)
```

`onReward` may fire zero or one time; `onComplete` always fires exactly once, **after**. Use a flag
(as above) to tell "earned" from "closed early". Ask the user to opt in before showing — it's a
rewarded ad, not an interruption.

There is no `minIntervalMs` on rewarded formats: the user explicitly opted in, so no frequency cap.
`rewardedForceShowTimeoutMs` defaults to 10 s (longer than other formats — the user is waiting on
purpose).

## App-open

Two independent uses. Most apps want both:

1. `AppOpenAdManager` — auto-shows when the user **returns from background**. Process-scoped, wired
   once in `Application.onCreate`.
2. `AppOpenAds` / `SplashAppOpenAd` — on-demand, e.g. on a splash (see `references/splash.md`).

### Auto-show on foreground return

```kotlin
// Application.onCreate — NextGenAdsBootstrap.configure installs this for you
val manager = AppOpenAdManager.install(this, AdUnits.APP_OPEN)
    .skipOn(SplashActivity::class.java, PaywallActivity::class.java)

manager.loadTimeoutMs = 5_000L                  // show only if an on-return load lands this fast; 0 = warm cache only
manager.showOnColdStart = false                 // a cold start isn't a "return" — default
manager.coverStyle = AppOpenCoverStyle.WELCOME  // or LOADING for a plain spinner
manager.enabled = false                         // pause auto-show without tearing it down
```

It requests **nothing** at install — only on a genuine background→foreground transition. A cached ad
shows instantly; otherwise it fetches, and shows only if the ad lands within `loadTimeoutMs` while
still foregrounded on an allowed screen. A late ad is never popped over app content mid-session
(policy-safe); it stays cached for the next return.

### Excluding screens

An app-open over a splash, paywall, onboarding or IAP screen hurts UX and can violate policy.

```kotlin
class SplashActivity : AppCompatActivity(), HideAppOpenAd   // for activities you own
AppOpenAdManager.get()?.skipOn(ThirdPartyActivity::class.java)   // for ones you don't
```

Ad activities of the Mobile Ads SDK itself are always skipped, so an app-open can never stack on
another full-screen ad.

### Manual control

```kotlin
object AppOpenAds {
    fun get(adUnitId: String): AppOpenAdHelper
    fun preload(adUnitId: String, remoteEnabled: Boolean = true)
    fun loadAndShow(activity: Activity, adUnitId: String,
                    timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs, onComplete: () -> Unit = {})
    fun clearAll()
}

class AppOpenAdHelper {
    companion object { const val AD_VALIDITY_MS = 4 * 60 * 60 * 1000L }   // fixed by the SDK
    val isReady: Boolean; val isShowing: Boolean
    var welcomeTitle: CharSequence?    // else R.string.ngad_welcome_title
    var loadingText: CharSequence?     // else R.string.ngad_welcome_loading
    var showingText: CharSequence?     // else R.string.ngad_welcome_showing
    fun load(remoteEnabled: Boolean = true, onResult: ((Boolean) -> Unit)? = null)
    fun loadAndShow(activity: Activity, timeoutMs: Long = NextGenAdsConfig.forceShowTimeoutMs,
                    coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME,
                    canShow: () -> Boolean = { true }, onComplete: () -> Unit = {})
    fun show(activity: Activity, onComplete: () -> Unit = {}, preloadedOverlay: View? = null,
             showCover: Boolean = true, coverStyle: AppOpenCoverStyle = AppOpenCoverStyle.WELCOME): Boolean
    fun clear()
}
```

**Covers.** An app-open takes ~0.5–1 s to render, so a full-screen cover bridges the gap.
`AppOpenCoverStyle.WELCOME` is branded (app icon + "Welcome back") and suits a foreground return;
`AppOpenCoverStyle.LOADING` is a plain spinner and suits a splash, where "Welcome back" would be
absurd. Rebrand the copy via `welcomeTitle` / `loadingText` / `showingText`, or by overriding the
`ngad_welcome_*` string resources.

**4 h expiry** is an SDK rule, not a library choice: a stale ad is dropped and refetched rather than
shown, so `isReady` can turn false on its own.
