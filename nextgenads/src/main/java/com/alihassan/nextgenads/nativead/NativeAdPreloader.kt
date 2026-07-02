package com.alihassan.nextgenads.nativead

import com.alihassan.nextgenads.NextGenAds
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd

/**
 * Tracks the in-flight state of a native ad that is preloaded ahead of the screen that shows it
 * (e.g. warm a Language-screen ad from Splash).
 *
 * [NativeAdHelper] caches ready ads but does not expose whether a load is *currently running*, so a
 * screen that arrives mid-load cannot tell "still loading" from "nothing queued". This holder fills
 * that gap. For any ad unit a consumer can:
 *  - show instantly when a preloaded ad is [State.READY],
 *  - wait — without firing a second request — while a load is [State.LOADING],
 *  - fall back to a fresh load when [State.IDLE] or [State.FAILED].
 *
 * State is tracked per ad unit, so one preloader instance serves every native slot in the app.
 * All methods are safe to call from the main thread; callbacks are delivered on the main thread.
 */
object NativeAdPreloader {

    enum class State { IDLE, LOADING, READY, FAILED }

    private class Slot {
        @Volatile var state: State = State.IDLE
        var ad: NativeAd? = null
        val waiters = ArrayList<(Boolean) -> Unit>()
    }

    private val slots = HashMap<String, Slot>()

    @Synchronized
    private fun slot(adUnitId: String): Slot = slots.getOrPut(adUnitId) { Slot() }

    /** Current state for [adUnitId]. */
    fun stateOf(adUnitId: String): State = slot(adUnitId).state

    /** True only when a preloaded ad is loaded and still held for this unit. */
    fun isReady(adUnitId: String): Boolean = synchronized(this) {
        val s = slots[adUnitId] ?: return false
        s.state == State.READY && s.ad != null
    }

    /**
     * Warms the cache for [adUnitId]. Call from an earlier screen (e.g. Splash). Safe to call
     * repeatedly — it never issues a second request while one is already in flight or ready.
     */
    @JvmStatic
    fun preload(adUnitId: String) {
        if (!NextGenAds.canShowAds()) return

        val slot = slot(adUnitId)
        synchronized(this) {
            if (slot.state == State.LOADING || slot.state == State.READY) return
            slot.state = State.LOADING
        }

        NextGenAds.log("Native preload requesting: $adUnitId")
        NativeAdHelper.load(
            adUnitId = adUnitId,
            onLoaded = { ad ->
                val waiters = synchronized(this) {
                    slot.ad = ad
                    slot.state = State.READY
                    slot.waiters.toList().also { slot.waiters.clear() }
                }
                NextGenAds.log("Native preload ready: $adUnitId (waiters=${waiters.size})")
                waiters.forEach { it(true) }
            },
            onFailed = {
                val waiters = synchronized(this) {
                    slot.state = State.FAILED
                    slot.waiters.toList().also { slot.waiters.clear() }
                }
                NextGenAds.log("Native preload failed: $adUnitId (waiters=${waiters.size})")
                waiters.forEach { it(false) }
            },
        )
    }

    /**
     * Registers interest in [adUnitId] from the screen that will show it. [onResult] fires
     * immediately if the load already settled, otherwise once the in-flight load finishes.
     * NEVER starts a new request — pair it with [preload] on an earlier screen, or with
     * [showInto]'s fallback when nothing was preloaded.
     */
    @JvmStatic
    fun awaitResult(adUnitId: String, onResult: (ready: Boolean) -> Unit) {
        val slot = slot(adUnitId)
        val immediate: Boolean? = synchronized(this) {
            when (slot.state) {
                State.READY -> true
                State.FAILED, State.IDLE -> false
                State.LOADING -> { slot.waiters.add(onResult); null }
            }
        }
        if (immediate != null) NextGenAds.runOnMain { onResult(immediate) }
    }

    /**
     * Destructive read: hands off the held [NativeAd] and resets the slot to [State.IDLE] so the
     * caller owns the ad (and its lifecycle). Returns null when nothing is held.
     */
    @JvmStatic
    fun consume(adUnitId: String): NativeAd? = synchronized(this) {
        val slot = slots[adUnitId] ?: return null
        val ad = slot.ad
        slot.ad = null
        slot.state = State.IDLE
        ad
    }

    /**
     * Convenience: bind the preloaded ad into [templateView] the moment it is ready, waiting on an
     * in-flight preload rather than double-requesting. If nothing was preloaded (IDLE/FAILED) and
     * [fallbackLoad] is true, it triggers a fresh [preload] and binds when that settles.
     */
    @JvmStatic
    @JvmOverloads
    fun showInto(
        templateView: NativeTemplateView,
        adUnitId: String,
        fallbackLoad: Boolean = true,
        onFailed: (() -> Unit)? = null,
    ) {
        templateView.showShimmer()

        fun bindOrFail(ready: Boolean) {
            val ad = if (ready) consume(adUnitId) else null
            if (ad != null) {
                templateView.setNativeAd(ad)
            } else {
                templateView.showError()
                onFailed?.invoke()
            }
        }

        when (stateOf(adUnitId)) {
            State.READY, State.LOADING -> awaitResult(adUnitId) { bindOrFail(it) }
            State.IDLE, State.FAILED -> {
                if (fallbackLoad) {
                    // preload() flips the slot to LOADING synchronously, so the following
                    // awaitResult parks as a waiter instead of firing an immediate failure.
                    preload(adUnitId)
                    awaitResult(adUnitId) { bindOrFail(it) }
                } else {
                    bindOrFail(false)
                }
            }
        }
    }

    /**
     * Drops waiters and destroys any held ad for [adUnitId] (or every unit when null).
     * Call from the showing screen's `onDestroy` to avoid leaking callbacks or ads.
     */
    @JvmStatic
    @JvmOverloads
    fun clear(adUnitId: String? = null) = synchronized(this) {
        val keys = if (adUnitId != null) listOf(adUnitId) else slots.keys.toList()
        keys.forEach { key ->
            slots.remove(key)?.let { slot ->
                slot.ad?.destroy()
                slot.waiters.clear()
            }
        }
    }
}
