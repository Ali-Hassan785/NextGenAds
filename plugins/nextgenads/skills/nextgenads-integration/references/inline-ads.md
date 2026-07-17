# Inline ads — banner and native

Inline ads live inside your layout (unlike full-screen formats). `BannerNativeView` serves both and
is the easiest path; the lower-level helpers exist when you need control.

## The quick path — `BannerNativeView`

One drop-in view that renders **either** a banner or a native ad, with an auto-generated shimmer
placeholder while loading.

```xml
<com.alihassan.nextgenads.BannerNativeView
    android:id="@+id/adView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ngad_ad_type="nativead"
    app:ngad_template="medium"
    app:ngad_banner_size="adaptive" />
```

```kotlin
findViewById<BannerNativeView>(R.id.adView).load(AdUnits.NATIVE)
```

```kotlin
class BannerNativeView(context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : FrameLayout {
    var adType: AdType = AdType.NATIVE
    var nativeTemplate: NativeTemplate = NativeTemplate.MEDIUM
    var bannerSize: BannerSize = BannerSize.ADAPTIVE

    fun load(adUnitId: String, remoteEnabled: Boolean = true,
             adType: AdType = this.adType,
             nativeTemplate: NativeTemplate = this.nativeTemplate,
             bannerSize: BannerSize = this.bannerSize,
             onLoaded: (() -> Unit)? = null, onFailed: (() -> Unit)? = null)
    fun destroy()
}
enum class AdType { BANNER, NATIVE }
```

XML attrs: `ngad_ad_type` (`banner` / `nativead`), `ngad_template`, `ngad_banner_size`. Call
`destroy()` in `onDestroy` for banners.

## Banner

```kotlin
object BannerAdHelper {
    var maxCachePerUnit = 2
    var maxRetries = 2
    var adValidityMs = 55 * 60 * 1000L

    fun preload(activity: Activity, adUnitId: String, count: Int = 1,
                widthDp: Int = screenWidthDp(activity), size: BannerSize = BannerSize.ADAPTIVE,
                remoteEnabled: Boolean = true)
    fun loadAdaptiveBanner(activity: Activity, container: ViewGroup, adUnitId: String,
                           refill: Boolean = false, collapsible: BannerCollapsible? = null,
                           size: BannerSize = BannerSize.ADAPTIVE,
                           onLoaded: (() -> Unit)? = null, onFailed: ((LoadAdError) -> Unit)? = null,
                           remoteEnabled: Boolean = true)
    fun containerWidthDp(activity: Activity, container: ViewGroup): Int
    fun clearAll()
}
```

```kotlin
BannerAdHelper.loadAdaptiveBanner(this, findViewById(R.id.bannerContainer), AdUnits.BANNER)
```

Adaptive banners wait for the container's first layout, so they size to the real container width
rather than the full screen. **The preload cache is keyed by unit + `BannerSize`** — preloading
`ADAPTIVE` then requesting `LEADERBOARD` is a cache miss. If the slot has padding, pass
`containerWidthDp(activity, container)` as `widthDp` when preloading so the cached ad matches.

### Sizes

```kotlin
enum class BannerSize { ADAPTIVE, ADAPTIVE_INLINE, BANNER, LARGE_BANNER, FULL_BANNER, LEADERBOARD, MEDIUM_RECTANGLE }
```

| Value | Size |
| --- | --- |
| `ADAPTIVE` (default) | Large anchored adaptive — best revenue for a pinned top/bottom slot |
| `ADAPTIVE_INLINE` | Taller, for banners inside scrolling content |
| `BANNER` | 320×50 |
| `LARGE_BANNER` | 320×100 |
| `FULL_BANNER` | 468×60 (tablet) |
| `LEADERBOARD` | 728×90 (tablet) |
| `MEDIUM_RECTANGLE` | 300×250 |

`BannerSize.fromName(name)` parses a string, defaulting to `ADAPTIVE`.

Three things are called "banner" — the *format* (vs native), the `AdType.BANNER` enum, and the
`BannerSize.BANNER` 320×50 fixed size. `NativeTemplate.BANNER` is a fourth: a **native** ad drawn in
a banner-shaped template.

### Collapsible

```kotlin
BannerAdHelper.loadAdaptiveBanner(this, container, AdUnits.BANNER, collapsible = BannerCollapsible.BOTTOM)
```

Shows expanded, then collapses to a smaller anchored bar. `TOP` for a banner pinned to the top,
`BOTTOM` for one at the bottom. Collapsible banners always load fresh (never from cache), since the
collapse behaviour is baked into the response.

## Native

### `NativeAdHelper` + `NativeTemplateView`

```kotlin
object NativeAdHelper {
    var maxCachePerUnit = 3
    var maxRetries = 3
    var adValidityMs = 55 * 60 * 1000L

    fun load(adUnitId: String, onLoaded: (NativeAd) -> Unit,
             onFailed: ((LoadAdError?) -> Unit)? = null, remoteEnabled: Boolean = true)
    fun preload(adUnitId: String, count: Int = maxCachePerUnit, remoteEnabled: Boolean = true)
    fun populate(templateView: NativeTemplateView, adUnitId: String, refill: Boolean = false,
                 onLoaded: (() -> Unit)? = null, onFailed: ((LoadAdError) -> Unit)? = null,
                 remoteEnabled: Boolean = true)
    fun clear(adUnitId: String? = null)
}
```

```kotlin
NativeAdHelper.populate(findViewById<NativeTemplateView>(R.id.nativeAd), AdUnits.NATIVE)
```

`populate` handles the shimmer, the load and the binding. `refill = true` replaces an ad already in
the view.

```kotlin
class NativeTemplateView(context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : FrameLayout {
    var template: NativeTemplate   // private set
    val isCustomTemplate: Boolean
    var mediaScaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP

    fun setTemplate(template: NativeTemplate)
    fun setCustomTemplate(@LayoutRes layout: Int, @LayoutRes shimmer: Int = 0,
                          autoShimmer: Boolean = true,
                          binder: ((NativeAdView, NativeAd) -> Unit)? = null)
    fun setNativeAd(ad: NativeAd)
    fun showShimmer()
    fun showError()
    fun destroy()
}
```

### The 12 built-in templates

```kotlin
enum class NativeTemplate { SMALL, MEDIUM, LARGE, BANNER, MEDIA_LEFT, COLLAPSIBLE,
                            HERO, FEED, SPOTLIGHT, ACTION_TOP, HALF_MEDIA, STACKED }
```

`MEDIUM` is the default. `NativeTemplate.fromName(name)` parses a string, defaulting to `MEDIUM`.
Each ships an auto-generated shimmer placeholder. Pick by slot shape: `SMALL`/`BANNER` for list rows,
`MEDIUM`/`LARGE`/`HERO` for content breaks, `MEDIA_LEFT`/`HALF_MEDIA` for compact media,
`COLLAPSIBLE` where the user should be able to dismiss it.

### Your own template

```kotlin
templateView.setCustomTemplate(
    layout = R.layout.my_native_ad,
    shimmer = R.layout.my_native_ad_shimmer,   // 0 → auto-generated from the layout
)
NativeAdHelper.populate(templateView, AdUnits.NATIVE)
```

The layout's **root must be a `NativeAdView`**, and views are wired by id — supply what you use:

| Id | View |
| --- | --- |
| `ngad_headline` | `TextView` (required by policy) |
| `ngad_body` | `TextView` |
| `ngad_cta` | `Button` / `TextView` |
| `ngad_icon` | `ImageView` |
| `ngad_advertiser` | `TextView` |
| `ngad_stars` | `RatingBar` |
| `ngad_media` | `MediaView` |
| `ngad_collapse` | `View` (dismiss affordance) |

Pass a `binder` to `setCustomTemplate` for fields the contract doesn't cover.

### Preloading natives

```kotlin
object NativeAdPreloader {
    enum class State { IDLE, LOADING, READY, FAILED }
    fun stateOf(adUnitId: String): State
    fun isReady(adUnitId: String): Boolean
    fun preload(adUnitId: String, remoteEnabled: Boolean = true)
    fun awaitResult(adUnitId: String, onResult: (ready: Boolean) -> Unit)   // never starts a request
    fun consume(adUnitId: String): NativeAd?                                // destructive read
    fun showInto(templateView: NativeTemplateView, adUnitId: String,
                 fallbackLoad: Boolean = true, onFailed: (() -> Unit)? = null)
    fun clear(adUnitId: String? = null)
}
```

Use `showInto` for a preloaded native in a screen that must render instantly. `awaitResult` waits on
an in-flight preload without starting one — handy on a screen that must not trigger a fetch itself.

## Theming

Templates take their colours from the **host app's Material3 theme attributes** — there is no
`values-night` qualifier in the library, so ad cards follow your Light/Dark choice automatically
*provided your theme defines the tokens*:

| Token | Used for |
| --- | --- |
| `?attr/colorSurface` | Card background |
| `?attr/colorSurfaceVariant` | Media, placeholder and control backgrounds |
| `?attr/colorOutlineVariant` | **Card border** (1 dp stroke) |
| `?attr/colorOnSurface` | Headline text |
| `?attr/colorOnSurfaceVariant` | Body / advertiser text |

If ad cards look wrong (e.g. a border that doesn't match your dividers), the host theme is missing
the token — `colorOutlineVariant` is the usual culprit, since apps often set `colorOutline` and
assume it covers borders. It doesn't. Fix it in your theme, not the library:

```xml
<item name="colorOutlineVariant">@color/my_border</item>
```

### The CTA is not themed

The call-to-action button is **not** a theme attribute — it is a fixed library colour, the same in
light and dark:

| Resource | Default |
| --- | --- |
| `ngad_cta` | `#2563EB` (blue) |
| `ngad_cta_text` | `#FFFFFF` |
| `ngad_cta_ripple` | `#52FFFFFF` |

To match your brand, redeclare the colour in **your app's** `res/values/colors.xml` — an app
resource overrides a library one of the same name, and you can add a `values-night` variant that the
library itself doesn't ship:

```xml
<!-- app/src/main/res/values/colors.xml -->
<color name="ngad_cta">#FF6D28D9</color>
```

## Premium and lifecycle

Inline slots register themselves so that going premium at runtime (`NextGenAds.premium = true`) hides
them immediately. Call `destroy()` on `BannerNativeView` / `NativeTemplateView` in `onDestroy` to
release the ad.
