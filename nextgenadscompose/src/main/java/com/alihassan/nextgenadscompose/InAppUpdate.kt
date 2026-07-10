package com.alihassan.nextgenadscompose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alihassan.nextgenads.update.InAppUpdateManager
import com.alihassan.nextgenads.update.UpdateType

/**
 * Remembers an [InAppUpdateManager] bound to the host `ComponentActivity` and, by default, checks
 * Google Play for an update on first composition. The manager is lifecycle-aware (it resumes a
 * stalled immediate update and re-prompts a completed flexible download), so nothing else is needed.
 *
 * ```
 * val updater = rememberInAppUpdateManager(updateType = UpdateType.FLEXIBLE) {
 *     onNoUpdateAvailable = { /* up to date */ }
 *     onUpdateFailed = { code -> /* log */ }
 * }
 * ```
 *
 * @param updateType flexible (default) or immediate.
 * @param autoCheck run [InAppUpdateManager.checkForUpdate] once when first composed.
 * @param configure applied once to the newly-created manager (set thresholds / callbacks here).
 * @throws IllegalStateException if not hosted by a `ComponentActivity`.
 */
@Composable
fun rememberInAppUpdateManager(
    updateType: UpdateType = UpdateType.FLEXIBLE,
    autoCheck: Boolean = true,
    configure: InAppUpdateManager.() -> Unit = {},
): InAppUpdateManager {
    val activity = LocalContext.current.findComponentActivity()
        ?: error("rememberInAppUpdateManager must be hosted by a ComponentActivity")
    val manager = remember(activity) {
        InAppUpdateManager.with(activity).apply {
            this.updateType = updateType
            configure()
        }
    }
    LaunchedEffect(activity, autoCheck) {
        if (autoCheck) manager.checkForUpdate()
    }
    return manager
}
