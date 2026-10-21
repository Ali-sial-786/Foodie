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
import app.nouri.core.barcode.BrandedProduct
import app.nouri.core.recipes.Recipe
import app.nouri.core.scan.MatchMethod
import app.nouri.data.repo.ProductSearchOutcome
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

/** Open Food Facts name search, used from Search and from a described item with a brand. */
data class OnlineSearch(
    val terms: String = "",
    val loading: Boolean = false,
    val products: List<BrandedProduct> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
    /** Index of the described item the user is finding a product for; null in Search. */
    val forDescribedIndex: Int? = null,
)

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

    private val _online = MutableStateFlow(OnlineSearch())
    val online: StateFlow<OnlineSearch> = _online.asStateFlow()

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
        val items = withSavedBrands(withContext(Dispatchers.Default) { resolver().resolve(MealTextParser.parse(text)) })
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
                val items = withSavedBrands(withContext(Dispatchers.Default) { resolver().resolve(r.value) })
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

    /** A branded item ("2 Deep naans") uses that brand's product if the user has used it before. */
    private suspend fun withSavedBrands(items: List<ResolvedTextItem>): List<ResolvedTextItem> {
        if (items.none { it.mention.brand != null }) return items
        val resolver = resolver()
        val core = c.core(c.settings.current().preferences.measurementSystem)
        return items.map { item ->
            val brand = item.mention.brand ?: return@map item
            val noun = item.mention.food.let { if (it.length > 3) it.removeSuffix("s") else it }
            val saved = c.userFoods.search("$brand $noun", 5).firstOrNull { it.name.contains(brand, ignoreCase = true) }
                ?: return@map item
            resolver.resolveWith(item.mention, core.matcher.fromReference(saved, MatchMethod.SAVED_FOOD))
        }
    }

    // ---- Online product search (Open Food Facts)

    fun searchOnline(terms: String, forDescribedIndex: Int? = null) = viewModelScope.launch {
        _online.value = OnlineSearch(terms = terms.trim(), loading = true, forDescribedIndex = forDescribedIndex)
        val outcome = c.barcodes.searchProducts(terms)
        _online.update {
            when (outcome) {
                is ProductSearchOutcome.Results -> it.copy(loading = false, searched = true, products = outcome.products)
                is ProductSearchOutcome.Unavailable -> it.copy(loading = false, searched = true, error = outcome.reason)
            }
        }
    }

    fun clearOnline() { _online.value = OnlineSearch() }

    /** Keeps the product as one of the user's foods and returns its id for the detail screen. */
    suspend fun keepProduct(product: BrandedProduct): String? = c.barcodes.keep(product)?.id

    /** Replaces a described item with the chosen product, keeping the amount the user said. */
    fun useProductForDescribed(index: Int, product: BrandedProduct) = viewModelScope.launch {
        val food = c.barcodes.keep(product) ?: return@launch
        val item = _describe.value.items.getOrNull(index) ?: return@launch
        val core = c.core(c.settings.current().preferences.measurementSystem)
        val resolved = resolver().resolveWith(item.mention, core.matcher.fromReference(food, MatchMethod.USER_SELECTED))
        _describe.update { d -> d.copy(items = d.items.toMutableList().also { it[index] = resolved }) }
        clearOnline()
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
