# Diagnostics — events, fill/show rate, troubleshooting

## Start here: the two numbers that matter

- **fill%** = loaded ÷ requested — *demand-side*. Low means the network had no ad for you (check
  mediation adapters, floors, region).
- **use%** = impressions ÷ loaded — *your integration*. Low means you load ads you never show, i.e.
  wasted inventory. This is the number preloading and retry tuning actually move.

## Ad events

One app-wide stream of every lifecycle event, across all formats. Register once, typically from
`Application.onCreate`. Every method has a no-op default, so implement only what you need, and all
callbacks arrive on the **main thread**.

```kotlin
interface AdEventListener {
    fun onAdRequested(format: AdFormat, adUnitId: String) {}
    fun onAdLoaded(format: AdFormat, adUnitId: String) {}
    fun onAdFailedToLoad(format: AdFormat, adUnitId: String, error: LoadAdError) {}
    fun onAdShown(format: AdFormat, adUnitId: String) {}          // full-screen only
    fun onAdFailedToShow(format: AdFormat, adUnitId: String, error: FullScreenContentError) {}
    fun onAdDismissed(format: AdFormat, adUnitId: String) {}
    fun onAdImpression(format: AdFormat, adUnitId: String) {}     // canonical "seen", every format
    fun onAdClicked(format: AdFormat, adUnitId: String) {}
    fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue, responseInfo: ResponseInfo?) {}
    fun onUserEarnedReward(format: AdFormat, adUnitId: String, reward: RewardItem) {}
}

enum class AdFormat { BANNER, NATIVE, INTERSTITIAL, REWARDED, REWARDED_INTERSTITIAL, APP_OPEN }
```

```kotlin
NextGenAds.registerEventListener(object : AdEventListener {
    override fun onAdPaid(format: AdFormat, adUnitId: String, value: AdValue, responseInfo: ResponseInfo?) {
        analytics.logEvent("ad_impression", bundleOf(
            "value" to value.valueMicros / 1_000_000.0,
            "currency" to value.currencyCode,
            "ad_format" to format.name,
            "ad_unit" to adUnitId,
            "ad_source" to responseInfo?.mediationAdapterClassName,
        ))
    }
})
```

`onAdPaid` is the ROAS hook — forward it to your analytics/attribution pipeline. `onAdImpression` is
the "was it seen" signal for **all** formats; `onAdShown` fires only for full-screen ones.

`onAdRequested` fires per attempt, **including retries** — so `requested` counts attempts, not
placements.

## `ShowRateTracker` — per-format

```kotlin
val tracker = ShowRateTracker(logEachEvent = false)
NextGenAds.registerEventListener(tracker)
// later:
tracker.logReport()          // → logcat under "NextGenAds"
```

```kotlin
class ShowRateTracker(logEachEvent: Boolean = true) : AdEventListener {
    fun report(): String                  // box-drawing table (needs a monospace view)
    fun snapshot(): List<FormatRow>       // build your own UI from this
    fun summaryFor(format: AdFormat): String
    fun logReport()
    fun reset()
    companion object { val COLUMNS: List<String>; val LEFT_ALIGNED: BooleanArray }
}
```

```
┌──────────┬─────┬──────┬──────┬──────┬─────┬──────┬──────┬────────┐
│ FORMAT   │ REQ │ LOAD │ FAIL │ SHOW │ IMP │ FILL │ USE  │ AVG ms │
├──────────┼─────┼──────┼──────┼──────┼─────┼──────┼──────┼────────┤
│ APP_OPEN │   4 │    3 │    1 │    3 │   3 │  75% │ 100% │    412 │
└──────────┴─────┴──────┴──────┴──────┴─────┴──────┴──────┴────────┘
```

**It groups by format, not by unit.** Three interstitial units collapse into one `INTERSTITIAL` row,
so a single unit with no fill is invisible. If you run more than one unit per format, tally per unit
yourself — key a map by `adUnitId` in your own `AdEventListener` (the `format` arg is still passed, so
each row can show both).

## Per-unit request counts

```kotlin
NextGenAds.requestCount(AdUnits.INTERSTITIAL)   // cumulative for this process
NextGenAds.resetRequestCounts()
```

Cheap way to spot duplicate/runaway loads without a full tracker. Each request also logs
`… requesting: <unit> (request #N for this unit)`.

## Ad Inspector

```kotlin
NextGenAds.openAdInspector { error -> if (error != null) toast(error) }
```

On-device UI for live requests and mediation. **The device must be registered as a test device**
(`initialize`'s `testDeviceIds`), or it won't open.

## Mediation adapter health

```kotlin
NextGenAds.initializationStatus?.adapterStatusMap?.forEach { (name, s) ->
    Log.d("Ads", "$name → ${s.initializationState}: ${s.description}")
}
```

A `NOT_READY` / `FAILED` adapter silently forfeits that network's fill — a prime suspect behind low
fill%. The library logs this summary automatically at init when logging is on.

## Request breaker

After `maxRequestFailures` (3) consecutive **network/timeout** failures, new requests pause for
`requestCooldownMs` (3 min), then resume. Cached ads still show. A single success resets it.

```kotlin
NextGenAds.isRequestPaused()
NextGenAds.requestCooldownRemainingMs()
NextGenAds.resetRequestBreaker()
```

`NO_FILL` and configuration errors deliberately do **not** trip it — pausing every format for minutes
because one unit had no demand would cost fill across the whole app.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| **No ads request at all** (no `requesting` in logcat) | Consent. `canRequest()` is false until UMP allows it — check `ConsentManager.canRequestAds` and that consent ran **before** `initialize`. Also check `premium` / `enabled` / `adsLoadEnabled` and the per-format toggle. |
| `INVALID_REQUEST` on the first load | The App ID in the manifest is an ad **unit** id (slash) instead of an App ID (tilde). |
| `NO_FILL` | Real: no ad available for that unit/region/floor. Test ids always fill — if a test id fills and yours doesn't, it's demand, not code. New units can take hours to serve. |
| Ads load but never show | Another full-screen ad holds the global gate; or `minIntervalMs` is capping; or the ad expired (55 min, 4 h for app-open). |
| Second show finds nothing | `autoReload` is off by default — `preload()` the next one. |
| Splash hangs | Consent/init stalled. Keep a watchdog on the splash and cancel it once init completes. |
| Compose artifact won't resolve | `nextgenads-compose` doesn't exist before `1.1.0`. |
| `onDismiss` unresolved | Renamed to `onComplete` in `1.4.0`. |
| Ad cards wrong in dark mode | Host theme is missing the Material3 tokens the templates read (`colorSurface`, `colorOutlineVariant`) — see `references/inline-ads.md`. |
| Ads still show after going premium | `premiumProvider` changed without `refreshPremiumState()`. |
| Everything paused for minutes | The request breaker tripped — `isRequestPaused()`. It auto-resumes; recovery also fires on reconnect. |

Turn on `NextGenAds.loggingEnabled` and filter logcat by `NextGenAds`. Every failure logs
`code=… message=… response=…`, which distinguishes no-fill from invalid-request from network.
