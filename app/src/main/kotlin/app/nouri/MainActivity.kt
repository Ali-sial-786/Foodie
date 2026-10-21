package app.nouri

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import app.nouri.ui.recipes.SharedRecipeContent
import app.nouri.ui.theme.NouriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NouriApplication).container
        if (savedInstanceState == null) receiveShare(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    /** Text or screenshots shared to Nouri (e.g. from a browser or the gallery) become a recipe import. */
    private fun receiveShare(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val images = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            else -> intent.streamUris()
        }.filter { intent.type?.startsWith("image/") == true || contentResolver.getType(it)?.startsWith("image/") == true }
        if (text.isNullOrBlank() && images.isEmpty()) return
        (application as NouriApplication).container.recipeInbox.offer(SharedRecipeContent(text, images))
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}
