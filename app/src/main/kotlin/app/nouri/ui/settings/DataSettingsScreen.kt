package app.nouri.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.export.DiaryCsvExporter
import app.nouri.core.export.WeightCsvExporter
import app.nouri.core.util.SystemTime
import app.nouri.data.export.BackupExporter
import app.nouri.data.export.ExportMapper
import app.nouri.data.export.NouriBackup
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataSettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working.asStateFlow()

    /** Set once everything has been erased; the screen then restarts the app fresh. */
    private val _erased = MutableStateFlow(false)
    val erased: StateFlow<Boolean> = _erased.asStateFlow()

    fun exportDiary(context: Context, uri: Uri) = export(context, uri, "diary") {
        DiaryCsvExporter.export(ExportMapper.diaryRows(c.diary.all()))
    }

    fun exportWeights(context: Context, uri: Uri) = export(context, uri, "weights") {
        WeightCsvExporter.export(c.weights.all(), c.weights.allMeasurements(), c.settings.current().weightUnit)
    }

    fun exportBackup(context: Context, uri: Uri) = export(context, uri, "backup") {
        BackupExporter.encode(
            NouriBackup(
                exportedAtEpochMs = SystemTime.nowEpochMs(),
                settings = c.settings.current(),
                diary = c.diary.all(),
                weights = c.weights.all(),
                measurements = c.weights.allMeasurements(),
                recipes = c.recipes.savedRecipes(),
                pantry = c.pantry.pantryNow(),
                grocery = c.pantry.groceryNow(),
            ),
        )
    }

    private fun export(context: Context, uri: Uri, what: String, build: suspend () -> String) = viewModelScope.launch {
        _working.value = "Exporting $what…"
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val text = build()
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Couldn't open the file")
            }.isSuccess
        }
        _working.value = null
        c.messages.show(if (ok) "Exported your $what" else "Export failed — try another location")
    }

    /** Erases everything on this device: tokens, key, database, photos and settings. */
    fun deleteEverything() = viewModelScope.launch {
        _working.value = "Deleting your data…"
        withContext(Dispatchers.IO) {
            runCatching { c.siwc.signOut() }
            runCatching { c.apiKeys.clear() }
            runCatching { c.database.clearAllTables() }
            runCatching { c.images.deleteAll() }
        }
        c.forgetDiscoveredModels()
        c.settings.reset()
        // Make sure the reset is visible before restarting.
        c.settings.settings.first()
        _working.value = null
        _erased.value = true
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DataSettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { DataSettingsViewModel(container) }
    val working by vm.working.collectAsStateWithLifecycle()
    val erased by vm.erased.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }

    val today = SystemTime.today()
    val diaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.exportDiary(context, it) } }
    val weightLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.exportWeights(context, it) } }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportBackup(context, it) } }

    LaunchedEffect(erased) {
        if (erased) {
            // Start a fresh task so no screen or saved state from before survives.
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (launch != null) context.startActivity(launch)
            context.findActivity()?.finish()
        }
    }

    Scaffold(topBar = { NouriTopBar("Your data", onBack = nav::back) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            working?.let { LoadingBox(label = it) }
            NouriCard {
                Text("Export", style = MaterialTheme.typography.titleSmall)
                BodyText("Files are saved where you choose. Values are exported exactly as logged, including whether they were estimates.")
                OutlinedButton(onClick = { diaryLauncher.launch("nouri-diary-$today.csv") }, enabled = working == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Food diary (CSV)") }
                OutlinedButton(onClick = { weightLauncher.launch("nouri-weights-$today.csv") }, enabled = working == null, modifier = Modifier.fillMaxWidth()) { Text("Weight and measurements (CSV)") }
                OutlinedButton(onClick = { backupLauncher.launch("nouri-backup-$today.json") }, enabled = working == null, modifier = Modifier.fillMaxWidth()) { Text("Everything (JSON)") }
                BodyText("The JSON file contains your diary, weights, recipes, pantry, grocery list and preferences. It never contains sign-in tokens or API keys.")
            }
            NouriCard {
                Text("Delete all my data", style = MaterialTheme.typography.titleSmall)
                BodyText("Signs out of ChatGPT, removes your API key, and erases your diary, weights, recipes, pantry, photos and settings from this phone. This can't be undone.")
                Button(
                    onClick = { confirm = true },
                    enabled = working == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Delete everything") }
            }
        }
    }

    if (confirm) {
        var typed by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete everything?") },
            text = {
                Column {
                    Text("Consider exporting first. Type DELETE to confirm.")
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteEverything() }, enabled = typed.trim() == "DELETE") { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
