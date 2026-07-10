package com.alihassan.nextgenads.update

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.alihassan.nextgenads.NextGenAds
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/** Which Google Play in-app-update experience to request. */
enum class UpdateType {
    /**
     * Background download while the user keeps using the app; when the download finishes the user is
     * prompted to restart to install. The friendliest option for non-critical updates.
     */
    FLEXIBLE,

    /**
     * Full-screen, blocking Play UI that updates and restarts the app before it can be used again.
     * Reserve it for updates the user must take (e.g. a breaking backend change).
     */
    IMMEDIATE,
}

/**
 * Drop-in wrapper around the Google Play **In-App Updates** API
 * (`com.google.android.play:app-update`). It checks for an available update, launches the Play
 * update flow, and — for [UpdateType.FLEXIBLE] — tracks the background download and prompts the user
 * to restart once it's ready. It is lifecycle-aware: it resumes a stalled [UpdateType.IMMEDIATE]
 * update and re-surfaces a completed flexible download when the app returns to the foreground, and
 * it releases its Play listener / activity-result launcher automatically when the host is destroyed.
 *
 * ### Usage
 * Create it once per screen (typically your launcher activity's `onCreate`) via [with], configure
 * it, then call [checkForUpdate]:
 * ```
 * private val updater = InAppUpdateManager.with(this).apply {
 *     updateType = UpdateType.FLEXIBLE
 *     onUpdateFailed = { code -> Log.w("Update", "failed: $code") }
 * }
 *
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     updater.checkForUpdate()
 * }
 * ```
 * For a flexible update you may also drive the restart from your own UI instead of the built-in
 * snackbar — set [onFlexibleUpdateDownloaded] and call [completeFlexibleUpdate] from your button.
 *
 * Notes:
 * - In-app updates only work with an app installed from Google Play (or internal app sharing). On a
 *   debug/sideloaded build the availability check simply reports "no update" — that's expected.
 * - Everything here runs on the main thread; the Play SDK delivers its callbacks on the main thread
 *   too, so the callbacks below are always invoked there.
 */
class InAppUpdateManager private constructor(
    private var host: ComponentActivity?,
) : DefaultLifecycleObserver, InstallStateUpdatedListener {

    private val appUpdateManager: AppUpdateManager =
        AppUpdateManagerFactory.create(requireHost().applicationContext)

    /** Launches the Play update dialog and routes its result back into [onUpdateResult]. */
    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
        requireHost().activityResultRegistry.register(
            LAUNCHER_KEY,
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> onUpdateResult(result.resultCode) }

    /** Guards against launching a second flow while one Play dialog is already up. */
    private var flowInProgress = false

    /** Whether our flexible install-state listener is currently registered (so we unregister once). */
    private var listenerRegistered = false

    /** The type actually launched, so the result / resume handling knows how to react. */
    private var activeType: UpdateType? = null

    // --- Configuration -------------------------------------------------------------------------

    /** The update experience to request. Defaults to [UpdateType.FLEXIBLE]. */
    var updateType: UpdateType = UpdateType.FLEXIBLE

    /**
     * A flexible update whose Play Console **priority** is at least this value is escalated to an
     * [UpdateType.IMMEDIATE] (blocking) flow — the standard way to force-push a critical release
     * without changing code. Priorities run 0–5; the default `5` only escalates the top priority.
     * Set it above `5` to never escalate. Ignored when [updateType] is already [UpdateType.IMMEDIATE].
     */
    var immediatePriorityThreshold: Int = 5

    /**
     * Minimum number of days an update must have been available before a **flexible** prompt is
     * shown (Play reports this as staleness). `null` (default) prompts as soon as an update exists.
     * A high-priority escalation to immediate ([immediatePriorityThreshold]) ignores this.
     */
    var minFlexibleStalenessDays: Int? = null

    /** Allow Play to delete asset-pack files if storage is tight during the update. */
    var allowAssetPackDeletion: Boolean = false

    /**
     * When a flexible update finishes downloading and no [onFlexibleUpdateDownloaded] handler is
     * set, show a built-in indefinite snackbar prompting the user to restart. Set `false` to
     * suppress it (e.g. you render your own prompt). Defaults to `true`.
     */
    var showDefaultDownloadedPrompt: Boolean = true

    /** Message on the built-in "update downloaded" snackbar. */
    var downloadedMessage: CharSequence = "An update has just been downloaded."

    /** Action label on the built-in "update downloaded" snackbar. */
    var restartActionText: CharSequence = "RESTART"

    // --- Callbacks (all delivered on the main thread) ------------------------------------------

    /** An update is available and its flow is about to be launched. */
    var onUpdateAvailable: ((AppUpdateInfo) -> Unit)? = null

    /** No update is available (or none that satisfies the type / staleness / priority gates). */
    var onNoUpdateAvailable: (() -> Unit)? = null

    /** The user accepted the Play dialog (flexible: download started; immediate: install starting). */
    var onUpdateAccepted: (() -> Unit)? = null

    /** The user dismissed the Play dialog, or a flexible download was cancelled. */
    var onUpdateCanceled: (() -> Unit)? = null

    /**
     * A flexible download is complete and ready to install. If you set this you own the restart
     * prompt — call [completeFlexibleUpdate] when the user opts in; the built-in snackbar is not
     * shown. Leave it `null` to use the built-in snackbar (see [showDefaultDownloadedPrompt]).
     */
    var onFlexibleUpdateDownloaded: (() -> Unit)? = null

    /** Flexible download progress: `(bytesDownloaded, totalBytesToDownload)`. */
    var onDownloadProgress: ((Long, Long) -> Unit)? = null

    /** The update flow failed. Carries the Play result / install error code. */
    var onUpdateFailed: ((Int) -> Unit)? = null

    /** The availability check itself failed (e.g. Play services unavailable). */
    var onError: ((Throwable) -> Unit)? = null

    // --- Public API ----------------------------------------------------------------------------

    /**
     * Queries Play for an available update and, if one qualifies, launches the configured flow.
     * Safe to call more than once; a call while a Play dialog is already showing is ignored. Call it
     * once your UI is ready (e.g. from `onCreate`).
     */
    fun checkForUpdate() {
        if (flowInProgress) return
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info -> onInfoForCheck(info) }
            .addOnFailureListener { error ->
                NextGenAds.log("In-app update check failed", error)
                onError?.invoke(error)
            }
    }

    /**
     * Installs a flexible update that has finished downloading. This **restarts the app**, so call
     * it from an explicit user action (the built-in snackbar's action does this for you). No-op if
     * nothing has been downloaded yet.
     */
    fun completeFlexibleUpdate() {
        runCatching { appUpdateManager.completeUpdate() }
            .onFailure { NextGenAds.log("completeUpdate failed", it) }
    }

    // --- Availability handling -----------------------------------------------------------------

    private fun onInfoForCheck(info: AppUpdateInfo) {
        when (info.updateAvailability()) {
            UpdateAvailability.UPDATE_AVAILABLE -> {
                val type = resolveType(info)
                if (type == null || !info.isUpdateTypeAllowed(playType(type))) {
                    NextGenAds.log("In-app update available but not eligible for $updateType — skipping")
                    onNoUpdateAvailable?.invoke()
                    return
                }
                onUpdateAvailable?.invoke(info)
                startFlow(info, type)
            }

            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                // An immediate update was already in progress (e.g. app was killed mid-update);
                // resume it so the user isn't stranded on a half-finished update.
                NextGenAds.log("Resuming in-progress immediate update")
                startFlow(info, UpdateType.IMMEDIATE)
            }

            else -> onNoUpdateAvailable?.invoke()
        }
    }

    /**
     * Picks the effective type for an available update, applying the priority escalation and (for a
     * plain flexible request) the staleness gate. Returns `null` when a flexible update doesn't yet
     * meet the staleness requirement, so nothing is launched this time.
     */
    private fun resolveType(info: AppUpdateInfo): UpdateType? {
        if (updateType == UpdateType.IMMEDIATE) return UpdateType.IMMEDIATE

        // High-priority release: escalate to a blocking immediate flow (bypasses staleness).
        if (info.updatePriority() >= immediatePriorityThreshold &&
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        ) {
            return UpdateType.IMMEDIATE
        }

        // Plain flexible: honour the staleness gate if one is set.
        val requiredDays = minFlexibleStalenessDays
        if (requiredDays != null) {
            val staleFor = info.clientVersionStalenessDays() ?: 0
            if (staleFor < requiredDays) {
                NextGenAds.log("Flexible update available but stale for $staleFor/$requiredDays days — waiting")
                return null
            }
        }
        return UpdateType.FLEXIBLE
    }

    private fun startFlow(info: AppUpdateInfo, type: UpdateType) {
        val activity = host
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        // Track flexible download state before the flow starts, so no state update is missed.
        if (type == UpdateType.FLEXIBLE) registerInstallListener()

        activeType = type
        flowInProgress = true
        val options = AppUpdateOptions.newBuilder(playType(type))
            .setAllowAssetPackDeletion(allowAssetPackDeletion)
            .build()
        try {
            appUpdateManager.startUpdateFlowForResult(info, updateLauncher, options)
            NextGenAds.log("Launched $type in-app update flow")
        } catch (t: Throwable) {
            // Couldn't hand off to Play — undo the bookkeeping so a later retry can start cleanly.
            NextGenAds.log("Failed to start in-app update flow", t)
            flowInProgress = false
            activeType = null
            if (type == UpdateType.FLEXIBLE) unregisterInstallListener()
            onUpdateFailed?.invoke(ActivityResult.RESULT_IN_APP_UPDATE_FAILED)
        }
    }

    /** Result from the Play update dialog. Result codes are those the Play SDK documents. */
    private fun onUpdateResult(resultCode: Int) {
        flowInProgress = false
        when (resultCode) {
            Activity.RESULT_OK -> {
                NextGenAds.log("In-app update accepted")
                onUpdateAccepted?.invoke()
            }

            Activity.RESULT_CANCELED -> {
                NextGenAds.log("In-app update cancelled by user")
                // The flexible download never started; nothing left to listen for.
                if (activeType == UpdateType.FLEXIBLE) unregisterInstallListener()
                activeType = null
                onUpdateCanceled?.invoke()
            }

            ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
                NextGenAds.log("In-app update flow failed (code $resultCode)")
                if (activeType == UpdateType.FLEXIBLE) unregisterInstallListener()
                activeType = null
                onUpdateFailed?.invoke(resultCode)
            }
        }
    }

    // --- Flexible download tracking ------------------------------------------------------------

    override fun onStateUpdate(state: InstallState) {
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING ->
                onDownloadProgress?.invoke(state.bytesDownloaded(), state.totalBytesToDownload())

            InstallStatus.DOWNLOADED -> {
                NextGenAds.log("Flexible update downloaded")
                notifyDownloaded()
            }

            InstallStatus.INSTALLED -> unregisterInstallListener()

            InstallStatus.FAILED -> {
                NextGenAds.log("Flexible update failed (error ${state.installErrorCode()})")
                unregisterInstallListener()
                onUpdateFailed?.invoke(state.installErrorCode())
            }

            InstallStatus.CANCELED -> {
                unregisterInstallListener()
                onUpdateCanceled?.invoke()
            }

            else -> Unit // PENDING / UNKNOWN — nothing to do
        }
    }

    private fun notifyDownloaded() {
        val handler = onFlexibleUpdateDownloaded
        if (handler != null) {
            handler()
            return
        }
        if (showDefaultDownloadedPrompt) showRestartSnackbar()
    }

    private fun showRestartSnackbar() {
        val activity = host ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val root: View = activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(root, downloadedMessage, Snackbar.LENGTH_INDEFINITE)
            .setAction(restartActionText) { completeFlexibleUpdate() }
            .show()
    }

    private fun registerInstallListener() {
        if (listenerRegistered) return
        appUpdateManager.registerListener(this)
        listenerRegistered = true
    }

    private fun unregisterInstallListener() {
        if (!listenerRegistered) return
        appUpdateManager.unregisterListener(this)
        listenerRegistered = false
    }

    // --- Lifecycle -----------------------------------------------------------------------------

    override fun onResume(owner: LifecycleOwner) {
        // Resume anything left in flight: a stalled immediate update, or a flexible download that
        // finished while the app was in the background (its listener callback may have been missed).
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.updateAvailability() ==
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    startFlow(info, UpdateType.IMMEDIATE)

                info.installStatus() == InstallStatus.DOWNLOADED -> notifyDownloaded()
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterInstallListener()
        updateLauncher.unregister()
        owner.lifecycle.removeObserver(this)
        host = null
    }

    private fun requireHost(): ComponentActivity =
        host ?: error("InAppUpdateManager used after its host activity was destroyed")

    private fun playType(type: UpdateType): Int = when (type) {
        UpdateType.FLEXIBLE -> AppUpdateType.FLEXIBLE
        UpdateType.IMMEDIATE -> AppUpdateType.IMMEDIATE
    }

    companion object {
        private const val LAUNCHER_KEY = "ngad_in_app_update"

        /**
         * Creates a manager bound to [activity] and wires it to the activity's lifecycle (so it
         * auto-resumes in-progress updates and cleans itself up on destroy). Call from the main
         * thread — typically in the activity's `onCreate`.
         */
        @JvmStatic
        fun with(activity: ComponentActivity): InAppUpdateManager =
            InAppUpdateManager(activity).also { activity.lifecycle.addObserver(it) }
    }
}
