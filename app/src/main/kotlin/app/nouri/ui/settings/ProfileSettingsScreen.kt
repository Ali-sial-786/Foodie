package app.nouri.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.ontology.CanonicalIngredient
import app.nouri.core.progress.WeightUnit
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileSettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val _form = MutableStateFlow<ProfileForm?>(null)
    val form: StateFlow<ProfileForm?> = _form.asStateFlow()
    private val _unit = MutableStateFlow(WeightUnit.KG)
    val unit: StateFlow<WeightUnit> = _unit.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            _unit.value = s.weightUnit
            _form.value = ProfileForm.from(s.profile, s.preferences, s.weightUnit)
        }
    }

    fun edit(f: ProfileForm) {
        _form.value = f
        _error.value = null
    }

    fun suggestIngredients(q: String): List<CanonicalIngredient> = c.core().normalizer.suggest(q, 6)

    fun save() = viewModelScope.launch {
        val f = _form.value ?: return@launch
        val profile = f.toProfile(_unit.value).getOrElse {
            _error.value = it.message ?: "Please check your details"
            return@launch
        }
        c.settings.update { it.copy(profile = profile, preferences = f.applyTo(it.preferences)) }
        c.messages.show("Profile saved")
        _saved.value = true
    }
}

@Composable
fun ProfileSettingsScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm = viewModel { ProfileSettingsViewModel(container) }
    val form by vm.form.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) nav.back() }

    Scaffold(topBar = { NouriTopBar("Profile & preferences", onBack = nav::back) }) { padding ->
        val f = form ?: return@Scaffold LoadingBox(Modifier.padding(padding))
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BodyText("Everything here is optional and stays on this phone. It's used for energy estimates and recipe suggestions.")
            SettingsHeader("Goal")
            NouriCard { GoalFields(f, unit, vm::edit) }
            SettingsHeader("About you")
            NouriCard { AboutYouFields(f, unit, vm::edit) }
            SettingsHeader("Food")
            NouriCard { DietAndAllergyFields(f, vm::edit) }
            NouriCard { CuisineFields(f, vm::edit) }
            NouriCard { DislikeFields(f, vm::suggestIngredients, vm::edit) }
            NouriCard { CookingFields(f, vm::edit) }
            error?.let { ErrorBanner(it) }
            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
