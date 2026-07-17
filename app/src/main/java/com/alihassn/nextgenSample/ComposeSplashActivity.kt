package com.alihassn.nextgenSample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alihassan.nextgenads.splash.SplashAdGate
import com.alihassan.nextgenads.splash.SplashAdType

/**
 * Jetpack Compose splash — the Compose counterpart to [SplashActivity]: gather consent → initialize
 * the SDK → show one splash ad → go to [ComposeAdsActivity].
 *
 * The splash-ad choice lives entirely in [SplashAdGate]: an **interstitial** on a cold start (fresh
 * process) and an **app-open** on a warm / hot relaunch (process still alive). A watchdog guarantees
 * we always leave the splash even if consent/init stalls. The Compose UI here is just the branded
 * splash background; [SplashAdGate] shows the ad over it.
 */
class ComposeSplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var coldStart = true
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                SplashScreen()
            }
        }

        // Resolve cold vs warm once; keep it stable across a config-change recreation.
        coldStart = savedInstanceState?.getBoolean(KEY_COLD) ?: SplashAdGate.consumeColdStart()

        // Safety net: never trap the user on the splash if consent/init hangs (e.g. no network).
        handler.postDelayed(::goToNext, WATCHDOG_MS)

        if (savedInstanceState == null) startAdFlow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COLD, coldStart)
    }

    /** Consent → init → splash ad (cold = interstitial, warm/hot = app-open) → next screen. */
    private fun startAdFlow() {
        AdsBootstrap.gatherConsentThenInitialize(this) {
            handler.removeCallbacksAndMessages(null) // init done: the ad flow now owns completion
            SplashAdGate.show(
                activity = this,
                coldStart = coldStart,
                // Splash-only units, separate from the in-app ones — see AdUnits.
                interstitialUnitId = AdUnits.SPLASH_INTERSTITIAL,
                appOpenUnitId = AdUnits.SPLASH_APP_OPEN,
                // Cold start (fresh process) → interstitial; warm / hot relaunch → app-open.
                coldStartAdType = SplashAdType.INTERSTITIAL,
                warmStartAdType = SplashAdType.APP_OPEN,
                onComplete = ::goToNext,
            )
        }
    }

    private fun goToNext() {
        if (navigated) return
        navigated = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, ComposeAdsActivity::class.java))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded before navigating: drop the watchdog so it can't start the next screen from
        // the background.
        if (!navigated) handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val KEY_COLD = "ngad_compose_splash_cold_start"
        const val WATCHDOG_MS = 10_000L
    }
}

@Composable
private fun SplashScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            CircularProgressIndicator()
        }
    }
}
