package app.nouri.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.BuildConfig
import app.nouri.core.barcode.OpenFoodFactsMapper
import app.nouri.core.progress.WeightUnit
import app.nouri.core.units.MeasurementSystem
import app.nouri.data.prefs.ThemeMode
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route

private val DayStartOptions = listOf(0 to "Midnight", 120 to "2:00", 240 to "4:00")

@Composable
fun SettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(expiryReminders = granted) }
        if (!granted) container.messages.show("Notifications are off for Nouri, so reminders can't be shown")
    }

    Scaffold(topBar = { NouriTopBar("Settings", onBack = nav::back) }) { padding ->
        val s = settings ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SettingsHeader("You")
            NavRow("Profile & food preferences", "Body details, diets, allergies, cuisines", Icons.Outlined.Person) { nav.to(Route.ProfileSettings) }
            NavRow("Daily targets", s.targets?.let { "${it.energyKcal.toInt()} kcal" + if (it.manual) " · set by you" else " · suggested" } ?: "Not set — optional", Icons.Outlined.Flag) { nav.to(Route.TargetSettings) }

            SettingsHeader("AI and privacy")
            NavRow("AI provider", s.ai.selected.label, Icons.Outlined.AutoAwesome) { nav.to(Route.AiSettings) }
            NavRow("Privacy", "Photos: ${s.privacy.imageRetention.label.lowercase()}", Icons.Outlined.Lock) { nav.to(Route.PrivacySettings) }
            NavRow("Your data", "Export or delete everything", Icons.Outlined.Folder) { nav.to(Route.DataSettings) }

            SettingsHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                RadioRow(mode.label, selected = s.themeMode == mode) { vm.update { it.copy(themeMode = mode) } }
            }

            SettingsHeader("Units")
            Text("Body weight", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceChips(WeightUnit.entries, s.weightUnit, { it.symbol }, { u -> vm.update { it.copy(weightUnit = u) } })
            Text("Cups and spoons", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            RadioRow(
                "Metric", "1 cup = 250 ml, 1 tbsp = 15 ml (Canada, UK, Australia, most of South Asia)",
                selected = s.preferences.measurementSystem == MeasurementSystem.METRIC,
            ) { vm.update { it.copy(preferences = it.preferences.copy(measurementSystem = MeasurementSystem.METRIC)) } }
            RadioRow(
                "US customary", "1 cup ≈ 237 ml, 1 tbsp ≈ 14.8 ml; ounces for food weights",
                selected = s.preferences.measurementSystem == MeasurementSystem.US_CUSTOMARY,
            ) { vm.update { it.copy(preferences = it.preferences.copy(measurementSystem = MeasurementSystem.US_CUSTOMARY)) } }

            SettingsHeader("Diary")
            Text("A new diary day starts at", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceChips(DayStartOptions.map { it.first }, s.dayStartMinute, { m -> DayStartOptions.first { it.first == m }.second }, { m -> vm.update { it.copy(dayStartMinute = m) } })
            BodyText("Useful for night shifts or suhoor: food eaten before this time counts towards the previous day.")

            SettingsHeader("Kitchen")
            SwitchRow("Keep the screen on in cook mode", checked = s.keepScreenOnInCookMode) { on -> vm.update { it.copy(keepScreenOnInCookMode = on) } }
            SwitchRow("Pantry expiry reminders", "A daily notification for items that expire tomorrow", checked = s.expiryReminders) { on ->
                val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.update { it.copy(expiryReminders = on) }
            }

            SettingsHeader("About")
            NouriCard {
                Text("Nouri ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                BodyText("Nouri gives nutrition estimates to help you understand what you eat. It is not medical advice; talk to a doctor or registered dietitian about medical or dietary needs.")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Data sources", style = MaterialTheme.typography.titleSmall)
                BodyText("Reference nutrition: USDA National Nutrient Database for Standard Reference, Release 28 (U.S. Department of Agriculture, public domain).")
                BodyText("Packaged products: ${OpenFoodFactsMapper.ATTRIBUTION}. Product data is crowd-sourced; check it against the package.")
                BodyText("Typical dish recipes are Nouri's own reference compositions, calculated from USDA ingredient data.")
            }
        }
    }
}
