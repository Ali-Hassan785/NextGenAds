package com.alihassan.nextgenads.review

import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Drop-in wrapper around the Google Play **In-App Review** API
 * (`com.google.android.play:review`). It requests a review flow, launches the Play rating card, and
 * — however the flow ends — runs a caller-supplied **next action** so your app's flow is never
 * blocked on the review.
 *
 * ### The key contract
 * Play's review flow completes **whether or not** the card was actually shown and **whether or not**
 * the user rated. By design the API never tells you the outcome (this stops apps from gating rewards
 * on a review, which Play policy forbids). Play also enforces its own quota — the card appears at
 * most a few times per user per year — so most calls complete without showing anything. Because of
 * that, treat [launchReview] as "*maybe* show the card, then continue": the `onComplete` you pass is
 * always invoked exactly once, so put your next step (navigate, dismiss, resume a flow) there.
 *
 * ### Usage
 * Create one per screen via [with], then call [launchReview] at a natural, positive moment (e.g.
 * after the user finishes a task) with the action to run afterwards:
 * ```
 * private val review = InAppReviewManager.with(this)
 *
 * private fun onLevelComplete() {
 *     review.launchReview {
 *         // Runs whether or not the card was shown — always safe to continue here.
 *         goToNextLevel()
 *     }
 * }
 * ```
 * Optionally call [preload] a few seconds earlier (e.g. on screen entry) so the card can appear with
 * no perceptible delay when you later call [launchReview].
 *
 * Notes:
 * - **Don't** ask "would you like to review?" first, and **don't** reward the user for reviewing —
 *   both violate Play policy. Just trigger the flow at a good moment.
 * - The card only appears for an app installed from Google Play. On a debug/sideloaded build the
 *   flow completes without showing anything (so `onComplete` still runs) — test it via the internal
 *   test track or internal app sharing.
 * - All callbacks run on the main thread.
 */
class InAppReviewManager private constructor(
    private var host: ComponentActivity?,
) : DefaultLifecycleObserver {

    private val reviewManager: ReviewManager =
        ReviewManagerFactory.create(requireHost().applicationContext)

    /** A [ReviewInfo] fetched ahead of time by [preload], consumed by the next [launchReview]. */
    private var cachedReviewInfo: ReviewInfo? = null

    /** Guards against launching a second review flow while one is already being requested/shown. */
    private var flowInProgress = false

    /**
     * Invoked once the Play flow has finished (right before the `onComplete` next action). Handy for
     * recording "we asked this session" so you don't trigger it again too soon. Remember Play may
     * not have actually shown anything.
     */
    var onReviewFlowCompleted: (() -> Unit)? = null

    /** Invoked if requesting/launching the flow errored. The next action still runs regardless. */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * Optionally fetches the [ReviewInfo] ahead of time so a later [launchReview] can show the card
     * without a network round-trip. Safe to call more than once; a fetch already cached or in flight
     * is a no-op. Never required — [launchReview] fetches on demand when nothing is cached.
     */
    fun preload() {
        if (cachedReviewInfo != null || flowInProgress) return
        reviewManager.requestReviewFlow()
            .addOnSuccessListener { info -> cachedReviewInfo = info }
            .addOnFailureListener { error ->
                NextGenAds.log("In-app review preload failed", error)
                onError?.invoke(error)
            }
    }

    /**
     * Requests (if needed) and launches the Play in-app review flow, then runs [onComplete].
     *
     * [onComplete] is invoked **exactly once**, on the main thread, no matter how the flow ends —
     * card shown, card silently skipped by Play's quota, activity gone, or an error — so it's the
     * safe place to continue your app's flow. It defaults to a no-op when you only want to surface
     * the card.
     */
    @JvmOverloads
    fun launchReview(onComplete: () -> Unit = {}) {
        val proceed = once(onComplete)
        val activity = host
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            proceed()
            return
        }
        // A flow is already running — don't stack a second card; let this caller continue.
        if (flowInProgress) {
            proceed()
            return
        }
        flowInProgress = true

        val cached = cachedReviewInfo
        if (cached != null) {
            cachedReviewInfo = null
            launchFlow(activity, cached, proceed)
            return
        }
        reviewManager.requestReviewFlow()
            .addOnSuccessListener { info ->
                // The activity may have gone away during the async request.
                val current = host
                if (current == null || current.isFinishing || current.isDestroyed) {
                    flowInProgress = false
                    proceed()
                    return@addOnSuccessListener
                }
                launchFlow(current, info, proceed)
            }
            .addOnFailureListener { error ->
                // Never block the user on a failed review request — log, report, and continue.
                NextGenAds.log("In-app review request failed", error)
                flowInProgress = false
                onError?.invoke(error)
                proceed()
            }
    }

    private fun launchFlow(activity: ComponentActivity, info: ReviewInfo, proceed: () -> Unit) {
        reviewManager.launchReviewFlow(activity, info)
            .addOnCompleteListener { task ->
                flowInProgress = false
                // The task succeeds even when Play chose not to show the card; a failure just means
                // the flow errored. Either way we continue — the outcome is intentionally opaque.
                if (!task.isSuccessful) {
                    task.exception?.let {
                        NextGenAds.log("In-app review flow error", it)
                        onError?.invoke(it)
                    }
                }
                onReviewFlowCompleted?.invoke()
                proceed()
            }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        host = null
        cachedReviewInfo = null
    }

    private fun requireHost(): ComponentActivity =
        host ?: error("InAppReviewManager used after its host activity was destroyed")

    /** Wraps [action] so it can be invoked from multiple code paths but only ever runs once. */
    private fun once(action: () -> Unit): () -> Unit {
        var ran = false
        return {
            if (!ran) {
                ran = true
                action()
            }
        }
    }

    companion object {
        /**
         * Creates a review manager bound to [activity] and wires it to the activity's lifecycle (so
         * it releases its activity reference on destroy). Call from the main thread — typically in
         * the activity's `onCreate`.
         */
        @JvmStatic
        fun with(activity: ComponentActivity): InAppReviewManager =
            InAppReviewManager(activity).also { activity.lifecycle.addObserver(it) }
    }
}
