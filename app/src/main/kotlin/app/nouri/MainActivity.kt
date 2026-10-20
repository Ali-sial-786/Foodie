package app.nouri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nouri.data.prefs.AppSettings
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.navigation.NouriApp
import app.nouri.ui.theme.NouriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NouriApplication).container
        setContent {
            // null until the first DataStore read completes, so returning users never see onboarding flash.
            val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val current = settings ?: return@setContent
            CompositionLocalProvider(LocalAppContainer provides container) {
                NouriTheme(mode = current.themeMode) {
                    NouriApp(onboardingComplete = current.onboardingComplete)
                }
            }
        }
    }
}
