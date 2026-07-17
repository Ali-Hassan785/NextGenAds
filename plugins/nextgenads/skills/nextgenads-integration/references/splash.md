# Splash ad flow

One ad while the splash is up, then a single callback so you navigate on. The library handles the
part apps get wrong: a **minimum** delay (branding stays visible) bounded by a **timeout** (a slow or
failed load can never trap the user).

## Which helper

| Use | Helper |
| --- | --- |
| Interstitial on cold start, app-open on warm relaunch (recommended) | `SplashAdGate` |
| Always an interstitial | `SplashAd` |
| Always an app-open | `SplashAppOpenAd` |

`SplashAdGate` just picks between the other two — cold vs warm is the interesting decision, so start
there.

## SplashAdGate

```kotlin
object SplashAdGate {
    fun show(
        activity: Activity,
        coldStart: Boolean,
        interstitialUnitId: String,
        appOpenUnitId: String,
        coldStartAdType: SplashAdType = SplashAdType.INTERSTITIAL,
        warmStartAdType: SplashAdType = SplashAdType.APP_OPEN,
        minDelayMs: Long = NextGenAdsConfig.splashMinDelayMs,     // 1_500
        timeoutMs: Long = NextGenAdsConfig.splashTimeoutMs,       // 8_000; 0 = no timeout
        retryOnFailure: Boolean = NextGenAdsConfig.splashRetryOnFailure,  // false
        onComplete: () -> Unit,
    )
    fun consumeColdStart(): Boolean   // true exactly once per process
}

enum class SplashAdType { INTERSTITIAL, APP_OPEN }
```

### Complete splash activity

```kotlin
class SplashActivity : AppCompatActivity(), HideAppOpenAd {   // marker: never auto-cover this screen

    private val handler = Handler(Looper.getMainLooper())
    private var coldStart = true
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Resolve cold vs warm once, and keep it stable across a config-change recreation.
        coldStart = savedInstanceState?.getBoolean(KEY_COLD) ?: SplashAdGate.consumeColdStart()

        // Watchdog: never trap the user here if consent/init hangs (e.g. no network).
        handler.postDelayed(::goToMain, 10_000L)

        if (savedInstanceState == null) startAdFlow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COLD, coldStart)
    }

    private fun startAdFlow() {
        NextGenAdsBootstrap.gatherConsentThenInitialize(this) {
            handler.removeCallbacksAndMessages(null)   // init done: the ad flow owns completion now
            SplashAdGate.show(
                activity = this,
                coldStart = coldStart,
                interstitialUnitId = AdUnits.SPLASH_INTERSTITIAL,   // splash-only units
                appOpenUnitId = AdUnits.SPLASH_APP_OPEN,
                onComplete = ::goToMain,
            )
        }
    }

    private fun goToMain() {
        if (navigated) return          // onComplete and the watchdog must not both navigate
        navigated = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded before navigating: drop the watchdog so it can't start Main from the background.
        if (!navigated) handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object { const val KEY_COLD = "ngad_splash_cold_start" }
}
```

Points worth keeping when adapting this:

- **The `navigated` guard.** `onComplete` and the watchdog can race. Guard, or you double-navigate.
- **The watchdog is the safety net for consent/init**, not for the ad — `SplashAdGate`'s own
  `timeoutMs` already bounds the ad. Cancel the watchdog once init completes, or it will fire mid-ad.
- **`HideAppOpenAd` + `skipAppOpenOn`.** The splash runs its own ad; without the exclusion, the
  foreground-return manager can show a *second* app-open over it on a warm relaunch.
- **`consumeColdStart()` returns true once per process**, so store it across recreation.

## Timeline

| Case | Behaviour |
| --- | --- |
| Ad loads fast | Wait out `minDelayMs`, show, `onComplete` on dismiss |
| Ad loads slow | Shown as soon as it lands (past `minDelayMs`), up to `timeoutMs` |
| Ad fails | `onComplete` after `minDelayMs` |
| Ad never loads | `onComplete` at `timeoutMs`; the in-flight load keeps warming the cache for later |
| Ads disabled / premium | `onComplete` after `minDelayMs`, no request |

`timeoutMs` is coerced to be ≥ `minDelayMs`, so the timeout can never cut branding short.

**`retryOnFailure` is `false` by default**, and should usually stay that way: a splash has one shot
under a timeout, so retry/backoff just burns the window. Set `true` only to keep the load running to
warm the cache for a later opportunity.

## Cold vs warm, and why

`consumeColdStart()` is true on the first splash of a fresh process. The default mapping —
**interstitial on cold, app-open on warm** — exists because a cold start has an empty cache and needs
a real fetch, which an interstitial handles well; a warm relaunch often has an app-open already
cached from the last session, so it shows instantly. Both use the plain "Loading ad…" cover on the
splash — the branded "Welcome back" cover is never raised here.

## Splash-only ad units

Give the splash its own units:

```kotlin
const val INTERSTITIAL = "…/1033173712"          // in-app
const val SPLASH_INTERSTITIAL = "…/8691691433"   // splash only
```

A splash ad fires once per launch against a cold cache under a hard timeout; an in-app ad is
preloaded and shown mid-session. Sharing one id averages two unlike populations into one AdMob
report, so you cannot see which placement is losing money, and cannot tune floors for one without
moving the other.

## Single-format splash

```kotlin
SplashAd.show(activity, adUnitId, minDelayMs, timeoutMs, retryOnFailure, onComplete)         // interstitial
SplashAppOpenAd.show(activity, adUnitId, minDelayMs, timeoutMs, retryOnFailure, onComplete)  // app-open
```

Same timeline and defaults as `SplashAdGate`, which delegates to these.
