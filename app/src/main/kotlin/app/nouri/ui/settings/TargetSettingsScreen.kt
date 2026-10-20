package app.nouri.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.EnergyEstimateOutcome
import app.nouri.core.progress.EnergyEstimator
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.TargetCalculator
import app.nouri.core.progress.WeightEntry
import app.nouri.core.util.SystemTime
import app.nouri.data.prefs.AppSettings
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A suggestion to preview, or the reason one can't be made yet. */
sealed interface TargetSuggestion {
    data class Ready(val targets: NutritionTargets, val estimateNote: String) : TargetSuggestion
    data class Unavailable(val reason: String) : TargetSuggestion
}

data class TargetScreenState(val settings: AppSettings, val latestWeightKg: Double?)

class TargetSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<TargetScreenState?> = combine(c.settings.settings, c.weights.entries) { s, w ->
        TargetScreenState(s, w.maxWithOrNull(compareBy<WeightEntry>({ it.date }, { it.minuteOfDay ?: 0 }))?.weightKg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _suggestion = MutableStateFlow<TargetSuggestion?>(null)
    val suggestion: StateFlow<TargetSuggestion?> = _suggestion.asStateFlow()

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    fun suggest() {
        val st = state.value ?: return
        val profile = st.settings.profile ?: BodyProfile()
        val today = SystemTime.today()
        _suggestion.value = when (val outcome = EnergyEstimator.estimate(profile, st.latestWeightKg, today)) {
            is EnergyEstimateOutcome.Unavailable -> TargetSuggestion.Unavailable(outcome.reason)
            is EnergyEstimateOutcome.Estimated -> TargetSuggestion.Ready(
                TargetCalculator.suggest(profile, st.latestWeightKg!!, outcome.estimate, st.settings.weightUnit),
                outcome.estimate.explanation,
            )
        }
    }

    fun useSuggestion(targets: NutritionTargets) = viewModelScope.launch {
        c.settings.update { it.copy(targets = targets) }
        _suggestion.value = null
        c.messages.show("Targets saved")
    }

    fun saveManual(energy: Double, protein: Double, carbs: Double, fat: Double, fibre: Double?) = viewModelScope.launch {
        val result = TargetCalculator.manual(energy, protein, carbs, fat, fibre)
        c.settings.update { it.copy(targets = result.targets) }
        _warnings.value = result.warnings
        c.messages.show("Targets saved")
    }

    fun remove() = viewModelScope.launch {
        c.settings.update { it.copy(targets = null) }
        _warnings.value = emptyList()
        c.messages.show("Targets removed")
    }
}

@Composable
fun TargetSettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { TargetSettingsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val suggestion by vm.suggestion.collectAsStateWithLifecycle()
    val warnings by vm.warnings.collectAsStateWithLifecycle()

    Scaffold(topBar = { NouriTopBar("Daily targets", onBack = nav::back) }) { padding ->
        val st = state ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        val current = st.settings.targets
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BodyText("Targets are optional. Nouri tracks what you eat either way, and you can change or remove them whenever you like.")
            NouriCard {
                Text("Current targets", style = MaterialTheme.typography.titleSmall)
                if (current == null) {
                    Text("None set", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${Fmt.kcalUnit(current.energyKcal)} · P ${Fmt.gramsShort(current.proteinG)} · C ${Fmt.gramsShort(current.carbsG)} · F ${Fmt.gramsShort(current.fatG)}" + (current.fiberG?.let { " · fibre ${Fmt.gramsShort(it)}" } ?: ""))
                    BodyText(current.basis)
                    TextButton(onClick = vm::remove) { Text("Remove targets") }
                }
            }

            NouriCard {
                Text("Suggest targets for me", style = MaterialTheme.typography.titleSmall)
                BodyText("Uses your profile and latest weigh-in with a standard energy equation. It's a starting estimate, not a prescription.")
                OutlinedButton(onClick = vm::suggest, modifier = Modifier.padding(top = 8.dp)) { Text("Work out a suggestion") }
                when (val sug = suggestion) {
                    is TargetSuggestion.Unavailable -> {
                        Text(sug.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { nav.to(Route.ProfileSettings) }) { Text("Edit profile") }
                            TextButton(onClick = { nav.toTab(Route.Progress) }) { Text("Log weight") }
                        }
                    }
                    is TargetSuggestion.Ready -> {
                        val t = sug.targets
                        Text("${Fmt.kcalUnit(t.energyKcal)} · P ${Fmt.gramsShort(t.proteinG)} · C ${Fmt.gramsShort(t.carbsG)} · F ${Fmt.gramsShort(t.fatG)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        BodyText(sug.estimateNote)
                        BodyText(t.basis)
                        Button(onClick = { vm.useSuggestion(t) }, modifier = Modifier.padding(top = 8.dp)) { Text("Use these targets") }
                    }
                    null -> Unit
                }
            }

            ManualTargetsCard(current, warnings) { e, p, c, f, fi -> vm.saveManual(e, p, c, f, fi) }
        }
    }
}

@Composable
private fun ManualTargetsCard(current: NutritionTargets?, warnings: List<String>, onSave: (Double, Double, Double, Double, Double?) -> Unit) {
    var energy by rememberSaveable(current) { mutableStateOf(current?.energyKcal?.let { Fmt.decimal(it, 0) } ?: "") }
    var protein by rememberSaveable(current) { mutableStateOf(current?.proteinG?.let { Fmt.decimal(it, 0) } ?: "") }
    var carbs by rememberSaveable(current) { mutableStateOf(current?.carbsG?.let { Fmt.decimal(it, 0) } ?: "") }
    var fat by rememberSaveable(current) { mutableStateOf(current?.fatG?.let { Fmt.decimal(it, 0) } ?: "") }
    var fibre by rememberSaveable(current) { mutableStateOf(current?.fiberG?.let { Fmt.decimal(it, 0) } ?: "") }
    val e = energy.toUserDouble()
    NouriCard {
        Text("Set your own", style = MaterialTheme.typography.titleSmall)
        NumberField(energy, { energy = it }, "Energy", Modifier.fillMaxWidth(), suffix = "kcal", decimal = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(protein, { protein = it }, "Protein", Modifier.weight(1f), suffix = "g")
            NumberField(carbs, { carbs = it }, "Carbs", Modifier.weight(1f), suffix = "g")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(fat, { fat = it }, "Fat", Modifier.weight(1f), suffix = "g")
            NumberField(fibre, { fibre = it }, "Fibre (optional)", Modifier.weight(1f), suffix = "g")
        }
        warnings.forEach { InfoBanner(it, Icons.Outlined.Info, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 8.dp)) }
        Button(
            onClick = { onSave(e!!, protein.toUserDouble() ?: 0.0, carbs.toUserDouble() ?: 0.0, fat.toUserDouble() ?: 0.0, fibre.toUserDouble()) },
            enabled = e != null && e > 0,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Save my targets") }
    }
}
