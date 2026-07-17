package com.alihassn.nextgenSample

/**
 * Every ad unit id the app uses, in one place. These are Google's official **test** ids — replace
 * each with your own AdMob unit for release. The Application passes what it needs into [AdsBootstrap];
 * each screen reads the unit it shows from here, so there's a single source of truth and no per-screen
 * copies to drift out of sync.
 *
 * **The splash has its own units.** [SPLASH_INTERSTITIAL] / [SPLASH_APP_OPEN] are deliberately kept
 * separate from the in-app [INTERSTITIAL] / [APP_OPEN] ones, because the two placements behave
 * nothing alike: the splash fires once per launch against a cold cache under a hard timeout, while
 * the in-app units are preloaded and shown mid-session. Sharing one id averages those two very
 * different populations into a single AdMob report, so you can't tell which placement is losing
 * money — and you can't tune floors / mediation for one without moving the other. Separate ids also
 * make the per-unit rows in [AdReport] (shake to open) meaningful.
 */
object AdUnits {
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_INT = "ca-app-pub-3940256099942544/5354046379"

    /** Foreground-return app-open (the [com.alihassan.nextgenads.appopen.AppOpenAdManager] unit). */
    const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"

    // ---------------------------------------------------------------------------------------------
    // Splash-only units
    // ---------------------------------------------------------------------------------------------

    /**
     * Interstitial shown on the splash at cold start. Google's *interstitial video* test id — a
     * genuinely different unit from [INTERSTITIAL], so the two show up as separate rows in the
     * report exactly as your real splash / in-app units will.
     */
    const val SPLASH_INTERSTITIAL = "ca-app-pub-3940256099942544/8691691433"

    /**
     * App-open shown on the splash at a warm / hot relaunch.
     *
     * Google publishes only **one** app-open test id, so during development this necessarily points
     * at the same id as [APP_OPEN] — which means the two share a single row in [AdReport] until you
     * swap in your own units. Keep the constants separate anyway: replacing this with your real
     * splash app-open unit is then a one-line change, and the split is what makes the splash and
     * foreground-return numbers separable in AdMob.
     */
    const val SPLASH_APP_OPEN = APP_OPEN
}
