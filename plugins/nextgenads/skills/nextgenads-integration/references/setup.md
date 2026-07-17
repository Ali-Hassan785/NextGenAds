# Setup — dependency, manifest, consent, init

Everything needed to get from an empty project to "the SDK is initialised and ads may request".

## Requirements

| | |
| --- | --- |
| minSdk | 24 |
| compileSdk | 35+ |
| Java / Kotlin | Java 11 compatibility; Kotlin 2.x |
| AdMob account | an App ID and at least one ad unit id |

## 1. Dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()          // transitive: Next-Gen Ads SDK + UMP
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads:1.4.0")

    // Only for Jetpack Compose apps — see references/compose.md
    implementation("com.github.Ali-Hassan785.NextGenAds:nextgenads-compose:1.4.0")
}
```

**Version.** `1.4.0` is the newest published tag. Do not drop below it casually: `1.4.0` renamed
`onDismiss` → `onComplete` on every full-screen format (so the snippets here fail to compile on
`1.0.x`), and `nextgenads-compose` was not published before `1.1.0`. Newest tag:
`git ls-remote --tags https://github.com/Ali-Hassan785/NextGenAds`.

**Use the multi-module group.** The repo publishes two artifacts (`nextgenads`, `nextgenads-compose`),
so each is addressed as `com.github.<user>.<repo>` → `com.github.Ali-Hassan785.NextGenAds`. The flat
`com.github.Ali-Hassan785:nextgenads:1.0.0` also resolves, but on a multi-module repo JitPack turns
it into an **aggregate** that pulls in *both* modules — dragging all of Jetpack Compose into an
XML-only app.

**If resolution fails**, JitPack builds on demand from git tags — the first request for a new version
triggers a build, so it can 404 briefly. Check the build log at
`https://jitpack.io/#Ali-Hassan785/NextGenAds`. Published tags: `1.0.0`, `1.0.1`, `1.0.2`, `1.1.0`,
`1.2.0`, `1.3.0`, `1.4.0`. A branch snapshot (`main-SNAPSHOT`) or a commit SHA also works as a
coordinate if you need unreleased code.

**As a local module instead** (vendoring the source):

```kotlin
// settings.gradle.kts
include(":app", ":nextgenads")
// app/build.gradle.kts
dependencies { implementation(project(":nextgenads")) }
```

## 2. Manifest — the App ID

```xml
<application ...>
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-3940256099942544~3347511713" />
</application>
```

`NextGenAds.initialize` reads the App ID from here, and UMP requires the same entry — one source of
truth, no second copy in code to drift.

The App ID contains a **tilde** (`ca-app-pub-################~##########`). The most common mistake is
pasting an ad *unit* id (which uses a **slash**) here; that fails only later, at request time, with a
cryptic `INVALID_REQUEST`. The library logs a warning if the id is malformed.

No permissions are needed — the library's manifest already declares `INTERNET` and
`ACCESS_NETWORK_STATE`.

## 3. Consent, then init

**Order matters**: consent → `initialize` → request. An ad requested before consent is refused
(`NextGenAds.consentProvider` is auto-wired to UMP's `canRequestAds`), so ads silently never request.

The one-call path encodes the correct order:

```kotlin
NextGenAdsBootstrap.gatherConsentThenInitialize(
    activity = this,
    testDeviceHashedId = if (BuildConfig.DEBUG) "YOUR_DEVICE_HASH" else null,
    testDeviceIds = listOf("YOUR_DEVICE_HASH"),
    onReady = {
        // Consent gathered and SDK ready. Preload here.
        Interstitials.preload(AdUnits.INTERSTITIAL)
    },
)
```

`onReady` always runs (even if consent errored, so you can navigate on uniformly), on the main thread.

### Signatures

```kotlin
object NextGenAdsBootstrap {
    fun configure(
        application: Application,
        appOpenUnitId: String? = null,
        skipAppOpenOn: List<Class<out Activity>> = emptyList(),
        connectivityRecovery: Boolean = true,
    ): AppOpenAdManager?

    fun gatherConsentThenInitialize(
        activity: Activity,
        testDeviceHashedId: String? = null,
        testDeviceIds: List<String> = emptyList(),
        onReady: Runnable,
    )
}
```

`configure` is safe in `Application.onCreate` — it requests no ads. It enables connectivity recovery
and, given an `appOpenUnitId`, installs the foreground-return `AppOpenAdManager` (returned for
tuning). Registering event listeners and tuning `NextGenAdsConfig` are left to you, around this call.

### Wiring consent yourself

Only if you need the App ID in code rather than the manifest, or a custom order:

```kotlin
ConsentManager.getInstance(activity, testDeviceHashedId)
    .gatherConsent(activity, forceEea = testDeviceHashedId != null) {
        NextGenAds.initialize(activity, testDeviceIds) { /* ready */ }
    }
```

```kotlin
class ConsentManager {
    companion object { fun getInstance(context: Context, testDeviceHashedId: String? = null): ConsentManager }
    val canRequestAds: Boolean
    val isPrivacyOptionsRequired: Boolean
    fun gatherConsent(activity: Activity, forceEea: Boolean = false, onComplete: (FormError?) -> Unit)
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (FormError?) -> Unit)
    fun reset()
}
```

`isPrivacyOptionsRequired` drives whether to show a "Privacy options" entry in your settings screen;
`showPrivacyOptionsForm` opens it. `reset()` clears consent state (debug only).

### Testing the consent form outside the EEA

`testDeviceHashedId` + `forceEea = true` makes the form appear from any region. **Debug builds only** —
a non-null hash in release would force the form on real users. Find your hash in logcat on the first
consent request (UMP logs it), or in the Mobile Ads SDK's "Use RequestConfiguration.Builder()
.setTestDeviceIds(...)" line — the same value serves both.

### `NextGenAds.initialize` overloads

```kotlin
fun initialize(context: Context, testDeviceIds: List<String> = emptyList(), onComplete: Runnable? = null)
fun initialize(context: Context, appId: String, testDeviceIds: List<String> = emptyList(), onComplete: Runnable? = null)
fun isInitialized(): Boolean
fun whenInitialized(action: Runnable)   // runs now if ready, else queued and replayed in order
```

Init runs on a background thread (as the Next-Gen SDK requires); `onComplete` is delivered on the
main thread. Load requests issued before init completes are **queued**, not dropped — so preloading
early is safe.

## 4. Ad units

Declare every unit in one place so there is a single source of truth:

```kotlin
object AdUnits {
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_INT = "ca-app-pub-3940256099942544/5354046379"
    const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"

    // Splash gets its own units — see "one unit per placement" below.
    const val SPLASH_INTERSTITIAL = "ca-app-pub-3940256099942544/8691691433"
    const val SPLASH_APP_OPEN = APP_OPEN
}
```

**One unit per placement.** A splash interstitial fires once per launch against a cold cache under a
hard timeout; an in-app interstitial is preloaded and shown mid-session. Sharing one id averages those
two unlike populations into a single AdMob report — you cannot tell which placement is losing money,
and cannot tune floors or mediation for one without moving the other.

### Google test ids

Safe during development; replace before release.

| Format | Id |
| --- | --- |
| App id | `ca-app-pub-3940256099942544~3347511713` |
| Adaptive banner | `ca-app-pub-3940256099942544/9214589741` |
| Native | `ca-app-pub-3940256099942544/2247696110` |
| Interstitial | `ca-app-pub-3940256099942544/1033173712` |
| Interstitial video | `ca-app-pub-3940256099942544/8691691433` |
| Rewarded | `ca-app-pub-3940256099942544/5224354917` |
| Rewarded interstitial | `ca-app-pub-3940256099942544/5354046379` |
| App open | `ca-app-pub-3940256099942544/9257395921` |

Only **one** app-open test id exists, so two app-open placements cannot be split with test ids — they
share a row in any per-unit report until real ids replace them.

## 5. ProGuard / R8

Nothing to add. The library's public API survives R8 through normal reference-based keep analysis,
and it ships a `consumer-rules.keep` for the XML-inflated `NativeAdView`.
