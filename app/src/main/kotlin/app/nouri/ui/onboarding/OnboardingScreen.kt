package app.nouri.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.ontology.CanonicalIngredient
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.EnergyEstimateOutcome
import app.nouri.core.progress.EnergyEstimator
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.TargetCalculator
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightUnit
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.settings.AboutYouFields
import app.nouri.ui.settings.BodyText
import app.nouri.ui.settings.CuisineFields
import app.nouri.ui.settings.DietAndAllergyFields
import app.nouri.ui.settings.GoalFields
import app.nouri.ui.settings.ProfileForm
import app.nouri.ui.settings.SingleChoiceChips
import app.nouri.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep(val title: String) {
    WELCOME("Welcome to Nouri"),
    GOAL("What would you like help with?"),
    ABOUT("About you"),
    TARGETS("Daily targets"),
    FOOD("What you eat"),
    PRIVACY("AI and your privacy"),
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val form: ProfileForm = ProfileForm(),
    val unit: WeightUnit = WeightUnit.KG,
    val weight: String = "",
    val suggested: NutritionTargets? = null,
    val suggestionNote: String? = null,
    val acceptTargets: Boolean = true,
    val error: String? = null,
    val finished: Boolean = false,
)

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun edit(f: ProfileForm) = _state.update { it.copy(form = f, error = null) }
    fun setUnit(u: WeightUnit) = _state.update { it.copy(unit = u) }
    fun setWeight(w: String) = _state.update { it.copy(weight = w, error = null) }
    fun setAcceptTargets(accept: Boolean) = _state.update { it.copy(acceptTargets = accept) }

    fun next() {
        val s = _state.value
        if (s.step == OnboardingStep.ABOUT) {
            // Validate before moving on so mistakes are fixed where they were made.
            s.form.toProfile(s.unit).exceptionOrNull()?.let { e -> _state.update { it.copy(error = e.message) }; return }
            if (s.weight.isNotBlank() && weightKg(s) == null) { _state.update { it.copy(error = "Enter your weight in ${s.unit.symbol}") }; return }
        }
        val nextStep = OnboardingStep.entries.getOrNull(s.step.ordinal + 1)
        if (nextStep == null) {
            finish()
            return
        }
        if (nextStep == OnboardingStep.TARGETS) computeSuggestion()
        _state.update { it.copy(step = nextStep, error = null) }
    }

    fun back() {
        val s = _state.value
        OnboardingStep.entries.getOrNull(s.step.ordinal - 1)?.let { prev -> _state.update { it.copy(step = prev, error = null) } }
    }

    fun suggestIngredients(q: String): List<CanonicalIngredient> = c.core().normalizer.suggest(q, 6)

    private fun weightKg(s: OnboardingState): Double? = s.weight.toUserDouble()?.let(s.unit::toKg)?.takeIf { it in 25.0..400.0 }

    private fun computeSuggestion() {
        val s = _state.value
        val profile = s.form.toProfile(s.unit).getOrNull() ?: BodyProfile()
        val kg = weightKg(s)
        when (val outcome = EnergyEstimator.estimate(profile, kg, SystemTime.today())) {
            is EnergyEstimateOutcome.Estimated -> _state.update {
                it.copy(suggested = TargetCalculator.suggest(profile, kg!!, outcome.estimate, s.unit), suggestionNote = outcome.estimate.explanation)
            }
            is EnergyEstimateOutcome.Unavailable -> _state.update { it.copy(suggested = null, suggestionNote = outcome.reason) }
        }
    }

    fun finish() = viewModelScope.launch {
        val s = _state.value
        val profile = s.form.toProfile(s.unit).getOrNull()
        val kg = weightKg(s)
        if (kg != null) {
            val today = SystemTime.today()
            c.weights.save(WeightEntry(c.newId(), today, SystemTime.minuteOfDay(), kg))
        }
        c.settings.update {
            it.copy(
                onboardingComplete = true,
                profile = profile ?: it.profile,
                preferences = s.form.applyTo(it.preferences),
                weightUnit = s.unit,
                targets = if (s.acceptTargets && s.suggested != null) s.suggested else it.targets,
            )
        }
        _state.update { it.copy(finished = true) }
    }
}

@Composable
fun OnboardingScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { OnboardingViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.finished) { if (s.finished) nav.finishOnboarding() }
    BackHandler(enabled = s.step != OnboardingStep.WELCOME) { vm.back() }

    val steps = OnboardingStep.entries
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        LinearProgressIndicator(
            progress = { (s.step.ordinal + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.step == OnboardingStep.WELCOME) {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Text(s.step.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            when (s.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.GOAL -> {
                    BodyText("Optional. You can track food without any goal.")
                    GoalFields(s.form, s.unit, vm::edit)
                }
                OnboardingStep.ABOUT -> {
                    BodyText("Optional, and stored only on this phone. It's used to estimate your energy needs.")
                    Text("Weight unit", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceChips(WeightUnit.entries, s.unit, { it.symbol }, vm::setUnit)
                    NumberField(s.weight, vm::setWeight, "Current weight (optional)", Modifier.fillMaxWidth(), suffix = s.unit.symbol)
                    AboutYouFields(s.form, s.unit, vm::edit)
                }
                OnboardingStep.TARGETS -> TargetsStep(s, vm::setAcceptTargets)
                OnboardingStep.FOOD -> {
                    DietAndAllergyFields(s.form, vm::edit)
                    CuisineFields(s.form, vm::edit)
                }
                OnboardingStep.PRIVACY -> PrivacyStep()
            }
            s.error?.let { ErrorBanner(it) }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.step != OnboardingStep.WELCOME) TextButton(onClick = vm::back) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (s.step != OnboardingStep.WELCOME && s.step != OnboardingStep.PRIVACY) {
                TextButton(onClick = { skip(vm, s) }) { Text("Skip") }
            }
            Button(onClick = vm::next) {
                Text(
                    when (s.step) {
                        OnboardingStep.WELCOME -> "Get started"
                        OnboardingStep.PRIVACY -> "Start using Nouri"
                        else -> "Next"
                    },
                )
            }
        }
    }
}

/** Skipping a step discards what was typed on it, so it can't block moving on. */
private fun skip(vm: OnboardingViewModel, s: OnboardingState) {
    when (s.step) {
        OnboardingStep.GOAL -> vm.edit(s.form.copy(goal = ProfileForm().goal, weeklyRate = "", targetWeight = ""))
        OnboardingStep.ABOUT -> {
            vm.edit(s.form.copy(sex = ProfileForm().sex, birthYear = "", heightCm = "", bodyFat = "", activity = ProfileForm().activity))
            vm.setWeight("")
        }
        OnboardingStep.TARGETS -> vm.setAcceptTargets(false)
        OnboardingStep.FOOD -> vm.edit(s.form.copy(diets = emptySet(), allergies = emptySet(), cuisines = emptySet()))
        else -> Unit
    }
    vm.next()
}

@Composable
private fun WelcomeStep() {
    Text("Track what you eat with a photo, a barcode or a few words — and cook from what you have.", style = MaterialTheme.typography.bodyLarge)
    NouriCard {
        BodyText("• Point your camera at a meal for an editable estimate with a calorie range.")
        BodyText("• Scan ingredients to find recipes from many cuisines, including regional dishes.")
        BodyText("• Nutrition is calculated from reference data like USDA, with sources shown for every number.")
    }
    BodyText("Nouri gives estimates to help you understand your eating. It isn't medical advice.")
}

@Composable
private fun TargetsStep(s: OnboardingState, onAccept: (Boolean) -> Unit) {
    val t = s.suggested
    if (t == null) {
        BodyText(s.suggestionNote ?: "Add your details to get a suggestion.")
        BodyText("No problem — you can set targets later in Settings, or never. Nouri works without them.")
        return
    }
    NouriCard {
        Text("${Fmt.kcalUnit(t.energyKcal)} a day", style = MaterialTheme.typography.titleLarge)
        Text("Protein ${Fmt.gramsShort(t.proteinG)} · Carbs ${Fmt.gramsShort(t.carbsG)} · Fat ${Fmt.gramsShort(t.fatG)}", style = MaterialTheme.typography.bodyMedium)
        s.suggestionNote?.let { BodyText(it, Modifier.padding(top = 8.dp)) }
        BodyText(t.basis, Modifier.padding(top = 4.dp))
    }
    SwitchRow("Use these as my daily targets", "You can change them at any time", checked = s.acceptTargets, onCheckedChange = onAccept)
}

@Composable
private fun PrivacyStep() {
    NouriCard {
        Text("Demo mode is on", style = MaterialTheme.typography.titleSmall)
        BodyText("Until you connect an AI provider, photo results are simulated and clearly labelled. Search, barcodes, recipes and your diary work fully without AI.")
    }
    NouriCard {
        Text("Your photos, your choice", style = MaterialTheme.typography.titleSmall)
        BodyText("A photo is only analysed when you ask, by the provider you choose, after you agree. It's resized and stripped of location data first.")
        BodyText("You can connect ChatGPT later in Settings. Requests then use your ChatGPT plan's limits; Nouri never switches to paid usage on its own.")
    }
    NouriCard {
        Text("Stored on this phone", style = MaterialTheme.typography.titleSmall)
        BodyText("Your diary and details stay on this device. No cloud sync, no backups, no analytics. Export or delete everything from Settings.")
    }
}
