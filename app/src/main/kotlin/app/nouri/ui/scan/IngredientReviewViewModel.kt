package app.nouri.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiJson
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.IngredientAnalysis
import app.nouri.core.ai.IngredientCertainty
import app.nouri.core.ai.IngredientImageRequest
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.RecipeGenerationRequest
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantrySource
import app.nouri.core.recipes.GeneratedRecipeConverter
import app.nouri.core.recipes.Recipe
import app.nouri.core.recommend.RankedRecipe
import app.nouri.core.recommend.RecommendationContext
import app.nouri.core.scan.IngredientChip
import app.nouri.core.scan.IngredientScanMapper
import app.nouri.core.util.SystemTime
import app.nouri.data.prefs.ImageRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class IngredientReviewState(
    val phase: AnalysisPhase = AnalysisPhase.ANALYSING,
    val error: ErrorUi? = null,
    val photos: List<File> = emptyList(),
    val providerLabel: String = "",
    val simulated: Boolean = false,
    val chips: List<IngredientChip> = emptyList(),
    val includePantry: Boolean = false,
    val onlyAvailable: Boolean = false,
    val ideas: List<RankedRecipe> = emptyList(),
    val generated: List<Recipe> = emptyList(),
    val generating: Boolean = false,
    val generationError: String? = null,
    val canGenerate: Boolean = false,
    val missingCapture: Boolean = false,
    val addedToPantry: Boolean = false,
) {
    val selected: List<IngredientChip> get() = chips.filter { it.selected }
}

class IngredientReviewViewModel(private val c: AppContainer, private val captureId: String) : ViewModel() {
    private val _state = MutableStateFlow(IngredientReviewState())
    val state: StateFlow<IngredientReviewState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        val capture = c.scanSessions[captureId]
        if (capture == null) _state.update { it.copy(missingCapture = true, phase = AnalysisPhase.FAILED) } else {
            _state.update { it.copy(photos = capture.images.map { img -> img.file }) }
            analyse()
        }
    }

    fun analyse() {
        val capture = c.scanSessions[captureId] ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val settings = c.settings.current()
            _state.update { it.copy(phase = AnalysisPhase.ANALYSING, error = null, providerLabel = settings.ai.selected.label) }
            c.scans.save(captureId, "ingredients", settings.ai.selected.name, settings.ai.selected == ProviderKind.MOCK, "", capture.images.map { it.file.path })
            when (val r = c.ai.identifyIngredients(IngredientImageRequest(capture.images.map { it.payload }, capture.demoScenario))) {
                is AiResult.Failure -> _state.update { it.copy(phase = AnalysisPhase.FAILED, error = r.error.toUi()) }
                is AiResult.Success -> {
                    val chips = IngredientScanMapper.chips(r.value, c.core().normalizer, c::newId)
                    c.scans.save(captureId, "ingredients", r.meta.provider.name, r.meta.simulated, AiJson.json.encodeToString(IngredientAnalysis.serializer(), r.value), capture.images.map { it.file.path })
                    val status = c.ai.activeStatus()
                    _state.update {
                        it.copy(
                            phase = AnalysisPhase.DONE, chips = chips, simulated = r.meta.simulated, providerLabel = r.meta.provider.label,
                            canGenerate = status != null && AiCapability.RECIPE_GENERATION in status.capabilities && !status.isSimulated,
                        )
                    }
                    refreshIdeas()
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = AnalysisPhase.FAILED, error = ErrorUi("Cancelled", "Analysis was cancelled.", listOf(ErrorAction.RETRY))) }
    }

    fun toggle(id: String) {
        _state.update { s -> s.copy(chips = s.chips.map { if (it.id == id) it.copy(selected = !it.selected) else it }) }
        refreshIdeas()
    }

    fun remove(id: String) {
        _state.update { s -> s.copy(chips = s.chips.filterNot { it.id == id }) }
        refreshIdeas()
    }

    /** Adds or renames a chip; the name is resolved to a canonical ingredient on-device. */
    fun upsert(id: String?, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        val match = c.core().normalizer.resolve(clean)
        val chip = IngredientChip(
            id = id ?: c.newId(),
            name = match?.ingredient?.name ?: clean.replaceFirstChar { it.uppercase() },
            ingredientId = match?.ingredient?.id,
            quantity = null,
            certainty = IngredientCertainty.CLEAR,
            packaged = false,
            selected = true,
        )
        _state.update { s -> s.copy(chips = if (id == null) s.chips + chip else s.chips.map { if (it.id == id) chip else it }) }
        refreshIdeas()
    }

    fun setIncludePantry(v: Boolean) { _state.update { it.copy(includePantry = v) }; refreshIdeas() }
    fun setOnlyAvailable(v: Boolean) { _state.update { it.copy(onlyAvailable = v) }; refreshIdeas() }

    private fun refreshIdeas() = viewModelScope.launch {
        val s = _state.value
        val settings = c.settings.current()
        val pantryIds = if (s.includePantry) c.pantry.pantryNow().mapNotNull { it.ingredientId }.toSet() else emptySet()
        val available = s.selected.mapNotNull { it.ingredientId }.toSet() + pantryIds
        if (available.isEmpty()) {
            _state.update { it.copy(ideas = emptyList()) }
            return@launch
        }
        val recipes = c.recipes.all.first()
        val ranked = withContext(Dispatchers.Default) {
            c.core(settings.preferences.measurementSystem).ranker.rank(
                recipes,
                RecommendationContext(preferences = settings.preferences, available = available, onlyAvailable = s.onlyAvailable),
            ).ranked.take(8)
        }
        _state.update { it.copy(ideas = ranked) }
    }

    /** Explicit user action only: the pantry is never changed silently. */
    fun addSelectedToPantry() = viewModelScope.launch {
        val today = SystemTime.today()
        val existing = c.pantry.pantryNow()
        val items = _state.value.selected
            .filter { chip -> existing.none { e -> (e.ingredientId != null && e.ingredientId == chip.ingredientId) || e.name.equals(chip.name, ignoreCase = true) } }
            .map { PantryItem(id = c.newId(), name = it.name, ingredientId = it.ingredientId, quantity = it.quantity, addedOn = today, source = PantrySource.PHOTO_SCAN) }
        c.pantry.savePantry(items)
        _state.update { it.copy(addedToPantry = true) }
        val ids = items.map { it.id }
        c.messages.show(if (items.isEmpty()) "Already in your pantry" else "Added ${items.size} to your pantry", if (items.isEmpty()) null else "Undo") { c.pantry.deletePantry(ids) }
    }

    /** Asks the connected provider for new recipes; results are labelled AI-generated. */
    fun generate() = viewModelScope.launch {
        val s = _state.value
        val settings = c.settings.current()
        _state.update { it.copy(generating = true, generationError = null) }
        val request = RecipeGenerationRequest(
            availableIngredients = s.selected.map { it.name },
            cuisines = settings.preferences.favouriteCuisines,
            dietaryRequirements = settings.preferences.requiredDiets.map { it.label },
            allergiesToAvoid = settings.preferences.allergies.map { it.label },
            dislikes = settings.preferences.dislikedIngredients.toList(),
            maxMinutes = settings.preferences.usualMaxMinutes,
            servings = settings.preferences.householdSize.coerceAtLeast(1),
            onlyAvailableIngredients = s.onlyAvailable,
        )
        when (val r = c.ai.generateRecipes(request)) {
            is AiResult.Success -> {
                val now = SystemTime.nowEpochMs()
                val recipes = r.value.recipes.map { GeneratedRecipeConverter.convert(it, "ai:${c.newId()}", now, c.core().normalizer) }
                _state.update { it.copy(generating = false, generated = recipes) }
            }
            is AiResult.Failure -> _state.update { it.copy(generating = false, generationError = r.error.message) }
        }
    }

    /** Saves an AI-generated recipe so it can be opened, scaled and cooked. */
    suspend fun saveGenerated(recipe: Recipe): String {
        c.recipes.save(recipe)
        return recipe.id
    }

    override fun onCleared() {
        // Photos stay visible while this screen is open, then follow the retention setting.
        val capture = c.scanSessions.remove(captureId)
        if (capture != null) {
            c.appScope.launch {
                if (c.settings.current().privacy.imageRetention == ImageRetention.DELETE_AFTER_ANALYSIS) {
                    c.images.delete(capture.images.map { it.file.path })
                    c.scans.clearImages(listOf(captureId))
                }
            }
        }
        super.onCleared()
    }
}
