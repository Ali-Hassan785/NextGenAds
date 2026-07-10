package com.alihassan.nextgenadscompose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Walks the [ContextWrapper] chain from a Compose `LocalContext` to the hosting [Activity], or
 * `null` if there isn't one. Full-screen ads (interstitial, rewarded, app-open) need the real
 * Activity, which `LocalContext.current` usually is but can be wrapped.
 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The hosting [ComponentActivity], required by the lifecycle-aware managers (in-app update / review
 * and their activity-result plumbing). `null` when the composable isn't hosted by one.
 */
internal fun Context.findComponentActivity(): ComponentActivity? = findActivity() as? ComponentActivity
