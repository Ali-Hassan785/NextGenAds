package com.alihassan.nextgenadscompose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.review.InAppReviewManager

/**
 * Remembers an [InAppReviewManager] bound to the host `ComponentActivity`. Trigger the Play review
 * flow at a positive moment and run your next action on completion — it always fires, whether or not
 * the card was actually shown:
 *
 * ```
 * val review = rememberInAppReviewManager()
 * Button(onClick = { review.launchReview { navigateNext() } }) { Text("Done") }
 * ```
 *
 * @param configure applied once to the newly-created manager (set `onError` etc. here).
 * @throws IllegalStateException if not hosted by a `ComponentActivity`.
 */
@Composable
fun rememberInAppReviewManager(
    configure: InAppReviewManager.() -> Unit = {},
): InAppReviewManager {
    val activity = LocalContext.current.findComponentActivity()
        ?: error("rememberInAppReviewManager must be hosted by a ComponentActivity")
    return remember(activity) {
        InAppReviewManager.with(activity).apply(configure)
    }
}
