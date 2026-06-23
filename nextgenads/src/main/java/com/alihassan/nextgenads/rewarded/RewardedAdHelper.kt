package com.alihassan.nextgenads.rewarded

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.alihassan.nextgenads.NextGenAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and shows a single rewarded ad unit (Next-Gen SDK) with automatic preloading and
 * exponential-backoff retries. A fresh ad is requested immediately after each dismissal so the
 * next reward is ready to show.
 *
 * Prefer obtaining instances through [RewardedAds.get] so the same cached ad is reused across
 * screens. Requires [NextGenAds.initialize] to have completed first. SDK callbacks are marshalled
 * to the main thread, so [load]/[show] callbacks are always delivered there.
 */
class RewardedAdHelper(private val adUnitId: String) {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardedAd: RewardedAd? = null
    private var loading = false
    private var retryCount = 0

    /** Maximum number of automatic reload attempts after a failed load. */
    var maxRetries = 3

    val isReady: Boolean
        get() = rewardedAd != null

    /** Preloads the ad if not already available / in flight. */
    @JvmOverloads
    fun load(onResult: ((Boolean) -> Unit)? = null) {
        if (!NextGenAds.canShowAds()) {
            onResult?.invoke(false)
            return
        }
        if (rewardedAd != null) {
            onResult?.invoke(true)
            return
        }
        if (loading) return
        loading = true

        RewardedAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    NextGenAds.runOnMain {
                        rewardedAd = ad
                        loading = false
                        retryCount = 0
                        NextGenAds.log("Rewarded loaded: $adUnitId")
                        onResult?.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    NextGenAds.runOnMain {
                        rewardedAd = null
                        loading = false
                        NextGenAds.log("Rewarded failed ($adUnitId): $adError")
                        if (retryCount < maxRetries) {
                            val delayMs = 1000L shl retryCount // 1s, 2s, 4s …
                            retryCount++
                            handler.postDelayed({ load() }, delayMs)
                        }
                        onResult?.invoke(false)
                    }
                }
            },
        )
    }

    /**
     * Shows the ad if one is ready, then preloads the next one.
     *
     * @param onReward invoked with the earned [RewardItem] when the user completes the ad.
     * @param onDismiss invoked when the ad is closed (whether or not a reward was earned). If no ad
     *   is ready it is called immediately and the method returns `false`.
     * @return `true` if the ad is being shown.
     */
    @JvmOverloads
    fun show(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        onDismiss: () -> Unit = {},
    ): Boolean {
        if (!NextGenAds.canShowAds()) {
            onDismiss()
            return false
        }
        val ad = rewardedAd
        if (ad == null) {
            onDismiss()
            load() // make sure the next attempt has an ad ready
            return false
        }

        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                NextGenAds.log("Rewarded shown: $adUnitId")
            }

            override fun onAdDismissedFullScreenContent() {
                NextGenAds.runOnMain {
                    rewardedAd = null
                    load()
                    onDismiss()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                NextGenAds.runOnMain {
                    rewardedAd = null
                    NextGenAds.log("Rewarded show failed ($adUnitId): $fullScreenContentError")
                    load()
                    onDismiss()
                }
            }

            override fun onAdImpression() {}

            override fun onAdClicked() {}
        }

        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    NextGenAds.runOnMain { onReward(rewardItem) }
                }
            },
        )
        return true
    }
}

/** Registry that keeps one [RewardedAdHelper] per ad unit alive for reuse across screens. */
object RewardedAds {

    private val helpers = ConcurrentHashMap<String, RewardedAdHelper>()

    @JvmStatic
    fun get(adUnitId: String): RewardedAdHelper =
        helpers.getOrPut(adUnitId) { RewardedAdHelper(adUnitId) }

    /** Convenience: preload an ad unit. */
    @JvmStatic
    fun preload(adUnitId: String) = get(adUnitId).load()
}
