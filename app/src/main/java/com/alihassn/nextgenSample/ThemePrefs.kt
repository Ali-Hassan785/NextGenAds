package com.alihassn.nextgenSample

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists the user's day/night choice and applies it app-wide via
 * [AppCompatDelegate.setDefaultNightMode]. The stored value is one of the `MODE_NIGHT_*` constants
 * (NO = light, YES = dark, FOLLOW_SYSTEM = auto), so it round-trips straight into the delegate.
 *
 * Because the whole app theme is DayNight and the ad templates read the same Material tokens, one
 * call flips both the app chrome and every ad (native cards, shimmer, loading / welcome covers).
 */
object ThemePrefs {

    private const val PREFS = "ngad_sample_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

    /** The saved mode, defaulting to "follow the system" on first launch. */
    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    /**
     * Saves [mode] and applies it immediately. Applying recreates any started activity when the mode
     * actually changes, so the switch is instant; setting the current mode again is a no-op.
     */
    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NIGHT_MODE, mode)
            .apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Applies the saved mode. Call once from `Application.onCreate`, before any activity is shown. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getMode(context))
    }
}
