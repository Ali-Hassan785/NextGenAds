package com.alihassn.nextgenSample

/**
 * In-app on/off switches controlling **whether each ad may load** — one boolean per ad.
 *
 * Every ad call site passes the matching flag into the library as its `remoteEnabled` argument (or
 * gates its show with it): `true` lets that ad be requested/loaded, `false` stops the request so no
 * ad of that kind loads. Flip these from wherever you like (a debug menu, or your own remote config
 * later) — they default to `true` so every ad loads out of the box.
 */
object AdsConfig {
    var banner: Boolean = true
    var native: Boolean = true
    var interstitial: Boolean = true
    var rewarded: Boolean = true
    var rewardedInterstitial: Boolean = true
    var appOpen: Boolean = true
}
