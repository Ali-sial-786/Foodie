package app.nouri.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.data.prefs.ImageRetention
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivacySettingsViewModel(c: AppContainer) : SettingsViewModel(c) {
    fun deleteAllPhotos() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            c.images.deleteAll()
            c.scans.clearImages(c.scans.all().map { it.id })
        }
        c.messages.show("All food photos deleted")
    }
}

private fun ImageRetention.explanation(): String = when (this) {
    ImageRetention.DELETE_AFTER_ANALYSIS -> "Photos are removed within a few hours of analysis. Your logged entries stay."
    ImageRetention.KEEP_30_DAYS -> "Keep photos for a month so you can look back at what you scanned."
    ImageRetention.KEEP_UNTIL_DELETED -> "Photos stay on this phone until you delete them here or delete your data."
}

@Composable
fun PrivacySettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { PrivacySettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { NouriTopBar("Privacy", onBack = nav::back) }) { padding ->
        val s = settings ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            NouriCard {
                Text("Where your data lives", style = MaterialTheme.typography.titleSmall)
                BodyText("• Your diary, weights, recipes and pantry are stored only on this phone, in Nouri's private database.")
                BodyText("• Sign-in tokens and API keys are encrypted with Android Keystore.")
                BodyText("• Nothing is backed up to the cloud or synced, and Nouri has no analytics or advertising trackers.")
                BodyText("• Photos and text go to an AI provider only when you ask for an analysis, and only to the provider you chose.")
            }

            SettingsHeader("Food photos")
            ImageRetention.entries.forEach { r ->
                RadioRow(r.label, r.explanation(), selected = s.privacy.imageRetention == r) {
                    vm.update { it.copy(privacy = it.privacy.copy(imageRetention = r)) }
                }
            }
            BodyText("Photos are resized and stripped of location and device details before analysis; the full-size original is never uploaded.")
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete all food photos now") }

            SettingsHeader("AI requests")
            SwitchRow(
                "Personalise AI prompts",
                "Includes your favourite cuisines and dishes you've confirmed, to tell similar dishes apart. Stays off if you'd rather share less.",
                checked = s.privacy.personaliseAiPrompts,
            ) { on -> vm.update { it.copy(privacy = it.privacy.copy(personaliseAiPrompts = on)) } }
            SwitchRow(
                "Photo analysis permission",
                if (s.ai.photoConsent) "Allowed. Turn off to be asked again before the next photo is sent." else "Nouri will ask before sending a photo to a real AI provider.",
                checked = s.ai.photoConsent,
                enabled = s.ai.photoConsent,
            ) { on -> if (!on) vm.update { it.copy(ai = it.ai.copy(photoConsent = false)) } }

            SettingsHeader("Product lookups")
            SwitchRow(
                "Look up products online",
                "Barcode scans send only the barcode number, and product searches send only the words you search for, to Open Food Facts. Products you've used before are found offline.",
                checked = s.privacy.barcodeOnlineLookup,
            ) { on -> vm.update { it.copy(privacy = it.privacy.copy(barcodeOnlineLookup = on)) } }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all food photos?") },
            text = { Text("Your diary entries are kept; only the saved photos are removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllPhotos(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
