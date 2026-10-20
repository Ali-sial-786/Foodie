package app.nouri.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.FoodSearchResult
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.MealTextRequest
import app.nouri.core.ai.ProviderKind
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.parse.MealTextParser
import app.nouri.core.parse.ResolvedTextItem
import app.nouri.core.parse.SavedRecipeNutrition
import app.nouri.core.parse.TextLogging
import app.nouri.core.recipes.Recipe
import app.nouri.core.util.SystemTime
import app.nouri.data.db.NameCount
import app.nouri.data.repo.LogResult
import app.nouri.ui.common.logEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

enum class AddMode(val label: String) { SEARCH("Search"), DESCRIBE("Describe"), QUICK("Quick add") }

data class DescribeState(
    val text: String = "",
    val items: List<ResolvedTextItem> = emptyList(),
    val excluded: Set<Int> = emptySet(),
    val parsedWithAi: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val aiAvailable: Boolean = false,
    val aiProviderLabel: String? = null,
) {
    val included: List<ResolvedTextItem> get() = items.filterIndexed { i, it -> i !in excluded && it.isResolved }
    val total: Nutrients get() = Nutrients.sum(included.map { it.nutrients })
}

data class QuickAddState(
    val name: String = "",
    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
)

class AddFoodViewModel(
    private val c: AppContainer,
    val date: LocalDate,
    initialMeal: MealType,
    initialMode: AddMode,
) : ViewModel() {
    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<AddMode> = _mode.asStateFlow()

    private val _meal = MutableStateFlow(initialMeal)
    val meal: StateFlow<MealType> = _meal.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class)
    val results: StateFlow<List<FoodSearchResult>> = combine(_query.debounce(180).distinctUntilChanged(), c.usdaFoods.isLoaded, c.settings.settings) { q, _, s ->
        c.core(s.preferences.measurementSystem).search(q, limit = 30)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<DiaryEntry>> = c.diary.observeRecent(30)
        .map { list -> list.distinctBy { it.name.lowercase() }.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val frequent: StateFlow<List<NameCount>> = c.diary.observeFrequent(8).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _describe = MutableStateFlow(DescribeState())
    val describe: StateFlow<DescribeState> = _describe.asStateFlow()

    private val _quick = MutableStateFlow(QuickAddState())
    val quick: StateFlow<QuickAddState> = _quick.asStateFlow()

    private val _duplicate = MutableStateFlow<LogResult.PossibleDuplicate?>(null)
    val duplicate: StateFlow<LogResult.PossibleDuplicate?> = _duplicate.asStateFlow()

    /** Emits once after a successful log so the screen can close. */
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            val status = c.ai.activeStatus()
            val ok = s.ai.selected != ProviderKind.MOCK && status != null && status.connected && AiCapability.MEAL_TEXT_PARSING in status.capabilities
            _describe.update { it.copy(aiAvailable = ok, aiProviderLabel = status?.kind?.label) }
        }
    }

    fun setMode(m: AddMode) { _mode.value = m }
    fun setMeal(m: MealType) { _meal.value = m }
    fun setQuery(q: String) { _query.value = q }

    // ---- Describe (natural language)

    fun setDescribeText(t: String) = _describe.update { it.copy(text = t, error = null) }

    /** Offline, deterministic interpretation; nothing leaves the device. */
    fun interpretOffline() = viewModelScope.launch {
        val text = _describe.value.text
        if (text.isBlank()) return@launch
        _describe.update { it.copy(working = true, error = null) }
        val items = withContext(Dispatchers.Default) { resolver().resolve(MealTextParser.parse(text)) }
        _describe.update { it.copy(items = items, excluded = emptySet(), parsedWithAi = false, working = false, error = if (items.isEmpty()) "Couldn't find any foods in that description" else null) }
    }

    /** Sends only the typed text to the connected provider; nutrition is still calculated on-device. */
    fun interpretWithAi() = viewModelScope.launch {
        val text = _describe.value.text
        if (text.isBlank()) return@launch
        _describe.update { it.copy(working = true, error = null) }
        val recentNames = c.recipes.savedRecipes().map { it.name }.take(20)
        when (val r = c.ai.parseMealText(MealTextRequest(text, recentNames))) {
            is AiResult.Success -> {
                val items = withContext(Dispatchers.Default) { resolver().resolve(r.value) }
                _describe.update { it.copy(items = items, excluded = emptySet(), parsedWithAi = true, working = false) }
            }
            is AiResult.Failure -> _describe.update { it.copy(working = false, error = r.error.message + " — you can still use the offline interpreter.") }
        }
    }

    fun toggleDescribed(index: Int) = _describe.update {
        it.copy(excluded = if (index in it.excluded) it.excluded - index else it.excluded + index)
    }

    fun logDescribed(force: Boolean = false) = viewModelScope.launch {
        val d = _describe.value
        val entries = TextLogging.toDiaryEntries(
            d.included, date, _meal.value, SystemTime.nowEpochMs(), SystemTime.zoneId(),
            requestKey = "${d.text.hashCode()}:${date}:${_meal.value}:${SystemTime.nowEpochMs() / 60_000}", newId = c::newId,
        )
        log(entries, force)
    }

    private suspend fun resolver() = c.settings.current().let { s ->
        val recipes = c.recipes.all.first()
        val core = c.core(s.preferences.measurementSystem)
        core.textResolver { reference -> findRecipe(reference, recipes)?.let { r -> recipeNutrition(r, core) } }
    }

    private fun findRecipe(reference: String, recipes: List<Recipe>): Recipe? {
        val ref = reference.lowercase().trim()
        return recipes.firstOrNull { it.name.lowercase() == ref }
            ?: recipes.firstOrNull { it.name.lowercase().contains(ref) || ref.contains(it.name.lowercase()) }
    }

    private fun recipeNutrition(r: Recipe, core: app.nouri.core.NouriCore): SavedRecipeNutrition {
        val report = core.engine.calculateRecipe(r)
        return SavedRecipeNutrition(r.id, r.name, report.total, r.servings, report.cookedWeightGrams.takeIf { report.cookedWeightMeasured })
    }

    // ---- Quick add

    fun updateQuick(transform: (QuickAddState) -> QuickAddState) = _quick.update(transform)

    fun logQuick() = viewModelScope.launch {
        val q = _quick.value
        val kcal = q.kcal.replace(',', '.').toDoubleOrNull() ?: return@launch
        val now = SystemTime.nowEpochMs()
        val nutrients = Nutrients(
            energyKcal = kcal,
            proteinG = q.protein.replace(',', '.').toDoubleOrNull() ?: 0.0,
            carbsG = q.carbs.replace(',', '.').toDoubleOrNull() ?: 0.0,
            fatG = q.fat.replace(',', '.').toDoubleOrNull() ?: 0.0,
        )
        val entry = DiaryEntry(
            id = c.newId(), date = date, mealType = _meal.value, loggedAtEpochMs = now, timeZoneId = SystemTime.zoneId(),
            name = q.name.ifBlank { "Quick add" },
            snapshot = NutritionSnapshot(nutrients, null, Provenance(NutritionSource.USER_ENTERED, detail = "Entered by you"), ConfidenceLevel.HIGHER, Precision.MEASURED, capturedAtEpochMs = now),
            origin = EntryOrigin.QUICK_ADD,
            idempotencyKey = "quick:${now / 1000}:${q.name}:${q.kcal}",
        )
        log(listOf(entry), force = false)
    }

    // ---- Recent

    fun relog(entry: DiaryEntry) = viewModelScope.launch {
        val now = SystemTime.nowEpochMs()
        val copy = app.nouri.core.diary.DiaryOperations.copyEntries(listOf(entry), date, _meal.value, now, SystemTime.zoneId(), c::newId)
            .map { it.copy(origin = EntryOrigin.FAVOURITE, idempotencyKey = "relog:${entry.id}:${now / 1000}") }
        log(copy, force = false)
    }

    fun confirmDuplicate() {
        val pending = _duplicate.value ?: return
        _duplicate.value = null
        viewModelScope.launch { log(pending.pending, force = true) }
    }

    fun dismissDuplicate() { _duplicate.value = null }

    private suspend fun log(entries: List<DiaryEntry>, force: Boolean) {
        if (entries.isEmpty()) return
        when (val r = c.logEntries(entries, force)) {
            is LogResult.PossibleDuplicate -> _duplicate.value = r
            else -> _done.value = true
        }
    }
}
