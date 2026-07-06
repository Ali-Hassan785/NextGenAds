package com.alihassan.nextgenads.consent

import android.app.Activity
import android.content.Context
import com.alihassan.nextgenads.NextGenAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Thin Kotlin wrapper around the User Messaging Platform (UMP) consent flow.
 *
 * Typical usage from a splash screen:
 * ```
 * ConsentManager.getInstance(this).gatherConsent(this) { error ->
 *     if (ConsentManager.getInstance(this).canRequestAds) {
 *         NextGenAds.initialize(this) { /* preload */ }
 *     }
 * }
 * ```
 *
 * To test the consent UI from a non-EEA region, pass your device's hashed id (logged by the SDK)
 * to [getInstance]; that device is registered as a test device and the EEA geography is forced so
 * the form actually appears.
 */
class ConsentManager private constructor(
    context: Context,
    private val testDeviceHashedId: String?,
) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    init {
        // Gate every ad request in the library on UMP consent: once an app uses this manager, no
        // helper can fire a pre-consent request (NextGenAds.canRequest consults this).
        NextGenAds.consentProvider = { consentInformation.canRequestAds() }
    }

    /** `true` once we are allowed to request ads (consent obtained or not required). */
    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    /** `true` when a "Privacy options" entry point must be shown (e.g. in Settings). */
    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Requests an update of the consent information and shows the consent form if required.
     * Safe to call on every app launch; [onComplete] is always invoked (with a non-null
     * [FormError] on failure).
     *
     * When a test-device hash was supplied to [getInstance], that device is registered as a UMP
     * test device. Pass `forceEea = true` (test devices only) to also force the EEA debug
     * geography so the form appears from any region. Both are debug facilities — never enable
     * them for production users.
     *
     * @param forceEea when `true`, forces the EEA debug geography (for testing the form).
     */
    @JvmOverloads
    fun gatherConsent(
        activity: Activity,
        forceEea: Boolean = false,
        onComplete: (FormError?) -> Unit,
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()
        if (testDeviceHashedId != null || forceEea) {
            val debugBuilder = ConsentDebugSettings.Builder(activity)
            testDeviceHashedId?.let { debugBuilder.addTestDeviceHashedId(it) }
            if (forceEea) {
                debugBuilder.setDebugGeography(
                    ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
                )
            }
            paramsBuilder.setConsentDebugSettings(debugBuilder.build())
        }

        consentInformation.requestConsentInfoUpdate(
            activity,
            paramsBuilder.build(),
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    // The gathering screen died during the async update — don't try to present a
                    // form over it. canRequestAds may still be true from a previous session.
                    onComplete(null)
                    return@requestConsentInfoUpdate
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    NextGenAds.log("Consent gathered, error=${formError?.message}")
                    onComplete(formError)
                }
            },
            { requestError ->
                NextGenAds.log("Consent update failed: ${requestError.message}")
                onComplete(requestError)
            },
        )
    }

    /** Presents the privacy options form (call from a Settings entry point). */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (FormError?) -> Unit,
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(
            activity,
            ConsentForm.OnConsentFormDismissedListener { onDismissed(it) },
        )
    }

    /** Clears all consent state (mainly for testing). */
    fun reset() = consentInformation.reset()

    companion object {
        @Volatile
        private var instance: ConsentManager? = null

        /**
         * Returns the shared [ConsentManager].
         *
         * @param testDeviceHashedId optional hashed device id (logged by the SDK as
         *   "Use ... addTestDeviceHashedId(...)"). When set, that device is treated as a test
         *   device so the consent form can be exercised from any region.
         */
        @JvmStatic
        @JvmOverloads
        fun getInstance(context: Context, testDeviceHashedId: String? = null): ConsentManager {
            instance?.let {
                if (testDeviceHashedId != null && testDeviceHashedId != it.testDeviceHashedId) {
                    NextGenAds.log(
                        "ConsentManager.getInstance: ignoring new testDeviceHashedId — the first " +
                            "instance's debug settings are kept for the process lifetime",
                    )
                }
                return it
            }
            return synchronized(this) {
                instance ?: ConsentManager(context, testDeviceHashedId).also { instance = it }
            }
        }
    }
}
