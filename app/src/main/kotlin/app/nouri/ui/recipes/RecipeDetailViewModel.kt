package app.nouri.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.nutrition.RecipeNutritionReport
import app.nouri.core.pantry.DeductionProposal
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recipes.PantryMatch
import app.nouri.core.recipes.PantryMatcher
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeAdjustment
import app.nouri.core.recipes.RecipeAnalysis
import app.nouri.core.recipes.RecipeAnalyzer
import app.nouri.core.recipes.RecipeFamilies
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeScaler
import app.nouri.core.recipes.RecipeTransformer
import app.nouri.core.recipes.ShoppingListBuilder
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import app.nouri.core.util.SystemTime
import app.nouri.data.repo.LogResult
import app.nouri.ui.common.logEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the user describes the amount they ate from a recipe. */
enum class PortionMode(val label: String) { SERVINGS("Servings"), GRAMS("Grams"), FRACTION("Share of the pot") }

data class RecipeDetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val base: Recipe? = null,
    /** The recipe at the chosen number of servings (what the user is cooking). */
    val scaled: Recipe? = null,
    val scaleNotes: List<String> = emptyList(),
    val servings: Int = 1,
    val report: RecipeNutritionReport? = null,
    val analysis: RecipeAnalysis? = null,
    val pantry: PantryMatch? = null,
    val pantryEmpty: Boolean = true,
    val favourite: Boolean = false,
    /** The user's own 1–5 stars; null until rated. */
    val rating: Int? = null,
    val cookedCount: Int = 0,
    /** Every version of this dish (this one included); one entry means it stands alone. */
    val versions: List<RecipeVersion> = emptyList(),
    /** Saved in the user's own collection (can be edited or deleted). */
    val saved: Boolean = false,
    val originalName: String? = null,
    val cookedJustNow: Boolean = false,
    val deductions: List<DeductionProposal>? = null,
)

sealed interface RecipeEvent {
    data class Open(val recipeId: String) : RecipeEvent
    data object Closed : RecipeEvent
}

class RecipeDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(RecipeDetailState())
    val state: StateFlow<RecipeDetailState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecipeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RecipeEvent> = _events

    private val _duplicate = MutableStateFlow<LogResult.PossibleDuplicate?>(null)
    val duplicate: StateFlow<LogResult.PossibleDuplicate?> = _duplicate.asStateFlow()

    init {
        viewModelScope.launch {
            val recipe = c.recipes.get(id)
            if (recipe == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val saved = c.recipes.saved.first().any { it.id == id }
            val original = recipe.variantOf?.let { c.recipes.get(it)?.name }
            _state.update { it.copy(base = recipe, servings = recipe.servings, saved = saved, originalName = original) }
            recompute()
        }
        viewModelScope.launch {
            c.recipes.usage.collect { usage ->
                val u = usage[id]
                _state.update { it.copy(favourite = u?.favourite == true, rating = u?.rating, cookedCount = u?.cookedCount ?: 0) }
            }
        }
        viewModelScope.launch {
            combine(c.preparedRecipes, c.recipes.usage) { prepared, usage ->
                val byId = prepared.associateBy { it.recipe.id }
                val self = byId[id]?.recipe ?: return@combine emptyList()
                val key = RecipeFamilies.key(self) { other -> byId[other]?.recipe }
                prepared.filter { RecipeFamilies.key(it.recipe) { other -> byId[other]?.recipe } == key }
                    .sortedByDescending { it.recipe.id == key }
                    .map { p -> RecipeVersion(p.recipe, p.perServing.energyKcal, usage[p.recipe.id]?.rating, usage[p.recipe.id]?.cookedCount ?: 0) }
            }.flowOn(Dispatchers.Default).collect { versions -> _state.update { it.copy(versions = versions) } }
        }
        viewModelScope.launch {
            c.pantry.pantry.collect { recompute() }
        }
    }

    fun setServings(n: Int) {
        if (n !in 1..100) return
        _state.update { it.copy(servings = n) }
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val base = _state.value.base ?: return
        val servings = _state.value.servings
        val system = c.settings.current().preferences.measurementSystem
        val pantryItems = c.pantry.pantryNow()
        val result = withContext(Dispatchers.Default) {
            val scaled = RecipeScaler.scale(base, servings)
            val report = c.core(system).engine.calculateRecipe(scaled.recipe)
            val available = PantryLogic.availableIngredientIds(pantryItems)
            Triple(scaled, report, PantryMatcher.match(scaled.recipe, available) to RecipeAnalyzer.analyze(base))
        }
        val (scaled, report, rest) = result
        _state.update {
            it.copy(
                loading = false, scaled = scaled.recipe, scaleNotes = scaled.notes, report = report,
                pantry = rest.first, analysis = rest.second, pantryEmpty = pantryItems.isEmpty(),
            )
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.recipes.setFavourite(id, !_state.value.favourite) }

    fun rate(stars: Int?) = viewModelScope.launch { c.recipes.setRating(id, stars) }

    /** Explicit action: adds only missing, non-staple ingredients, merging with what's already on the list. */
    fun addMissingToGrocery() = viewModelScope.launch {
        val s = _state.value
        val recipe = s.scaled ?: return@launch
        val match = s.pantry ?: return@launch
        if (match.missing.isEmpty()) {
            c.messages.show("Nothing missing")
            return@launch
        }
        val existing = c.pantry.groceryNow()
        val merged = ShoppingListBuilder.fromMissing(recipe, match, existing, SystemTime.nowEpochMs(), c::newId)
        val changed = merged.filter { m -> existing.none { it == m } }
        c.pantry.saveGrocery(changed)
        val addedIds = changed.filter { m -> existing.none { it.id == m.id } }.map { it.id }
        c.messages.show("Added ${match.missing.size} to your grocery list", if (addedIds.isNotEmpty()) "Undo" else null) {
            c.pantry.deleteGrocery(addedIds)
            c.pantry.saveGrocery(existing.filter { e -> changed.any { it.id == e.id } })
        }
    }

    /** Variations are saved as separate recipes; the original is never changed. */
    fun createVariation(adjustment: RecipeAdjustment) = viewModelScope.launch {
        val base = _state.value.base ?: return@launch
        val variation = withContext(Dispatchers.Default) { RecipeTransformer.transform(base, adjustment) }
        if (!variation.changedAnything && variation.warnings.isEmpty()) {
            c.messages.show("This recipe is already ${adjustment.label.lowercase()} — no changes needed")
            return@launch
        }
        c.recipes.save(variation.recipe.copy(createdAtEpochMs = SystemTime.nowEpochMs()))
        if (variation.warnings.isNotEmpty()) c.messages.show(variation.warnings.first())
        _events.tryEmit(RecipeEvent.Open(variation.recipe.id))
    }

    fun markCooked() = viewModelScope.launch {
        c.recipes.markCooked(id)
        _state.update { it.copy(cookedJustNow = true) }
        c.messages.show("Marked as cooked")
    }

    /** Proposes pantry changes for the batch just cooked; nothing changes until [applyDeductions]. */
    fun proposePantryUpdate() = viewModelScope.launch {
        val recipe = _state.value.scaled ?: return@launch
        val proposals = PantryLogic.proposeDeduction(recipe, c.pantry.pantryNow())
        if (proposals.isEmpty()) c.messages.show("None of this recipe's ingredients are tracked in your pantry")
        else _state.update { it.copy(deductions = proposals) }
    }

    fun dismissDeductions() = _state.update { it.copy(deductions = null) }

    fun applyDeductions(accepted: List<DeductionProposal>) = viewModelScope.launch {
        _state.update { it.copy(deductions = null) }
        if (accepted.isEmpty()) return@launch
        val before = c.pantry.pantryNow()
        val after = PantryLogic.applyDeductions(before, accepted)
        c.pantry.replacePantry(before, after)
        c.messages.show("Pantry updated", "Undo") { c.pantry.replacePantry(after, before) }
    }

    fun delete() = viewModelScope.launch {
        if (!_state.value.saved) return@launch
        val recipe = _state.value.base ?: return@launch
        c.recipes.delete(id)
        c.messages.show("Deleted ${recipe.name}", "Undo") { c.recipes.save(recipe) }
        _events.tryEmit(RecipeEvent.Closed)
    }

    /**
     * Logs what was eaten from the batch at the current servings. [amount] is a
     * number of servings, grams of the cooked dish, or a fraction (0–1) of the pot.
     */
    fun logPortion(mode: PortionMode, amount: Double, meal: MealType, force: Boolean = false) = viewModelScope.launch {
        val entry = buildEntry(mode, amount, meal) ?: return@launch
        when (val r = c.logEntries(listOf(entry), force)) {
            is LogResult.PossibleDuplicate -> _duplicate.value = r
            else -> Unit
        }
    }

    fun confirmDuplicate() {
        val pending = _duplicate.value ?: return
        _duplicate.value = null
        viewModelScope.launch { c.logEntries(pending.pending, force = true) }
    }

    fun dismissDuplicate() { _duplicate.value = null }

    private suspend fun buildEntry(mode: PortionMode, amount: Double, meal: MealType): DiaryEntry? {
        val s = _state.value
        val recipe = s.scaled ?: return null
        val report = s.report ?: return null
        if (amount <= 0) return null
        val batchGrams = report.cookedWeightGrams.takeIf { it > 0 }
        val fraction = when (mode) {
            PortionMode.SERVINGS -> amount / recipe.servings
            PortionMode.GRAMS -> batchGrams?.let { amount / it } ?: return null
            PortionMode.FRACTION -> amount.coerceIn(0.0, 1.0)
        }
        val nutrients: Nutrients = when (mode) {
            PortionMode.SERVINGS -> report.forServings(amount)
            PortionMode.GRAMS -> report.forPortionGrams(amount)
            PortionMode.FRACTION -> report.forFraction(fraction)
        }
        val grams = when (mode) {
            PortionMode.GRAMS -> amount
            else -> batchGrams?.times(fraction)
        }
        // Weighing a portion only helps when the whole pot was weighed too.
        val estimated = report.confidence == ConfidenceLevel.LOW || (mode == PortionMode.GRAMS && !report.cookedWeightMeasured)
        val catalog = !s.saved && c.recipes.isReference(recipe.id)
        val now = SystemTime.nowEpochMs()
        val settings = c.settings.current()
        val notes = buildList {
            addAll(report.warnings)
            if (!report.cookedWeightMeasured) add("Cooked weight estimated (${formatAmount(report.cookedWeightGrams)} g for the pot); weighing the pot makes portions more accurate.")
            if (recipe.origin == RecipeOrigin.AI_GENERATED) add("AI-generated recipe; nutrition calculated by Nouri from its ingredient list.")
        }
        return DiaryEntry(
            id = c.newId(),
            date = SystemTime.diaryToday(settings.dayStartMinute),
            mealType = meal,
            loggedAtEpochMs = now,
            timeZoneId = SystemTime.zoneId(),
            name = recipe.name,
            quantity = when (mode) {
                PortionMode.SERVINGS -> Quantity(amount, QuantityUnit.SERVING)
                PortionMode.GRAMS -> Quantity.grams(amount)
                PortionMode.FRACTION -> Quantity(fraction * recipe.servings, QuantityUnit.SERVING)
            },
            recipeId = recipe.id,
            recipeFraction = fraction,
            snapshot = NutritionSnapshot(
                nutrients = nutrients,
                grams = grams,
                provenance = Provenance(
                    source = if (catalog) NutritionSource.REFERENCE_RECIPE else NutritionSource.SAVED_RECIPE,
                    referenceId = "recipe:${recipe.id}",
                    detail = "Calculated from ${recipe.ingredients.size} ingredients at ${recipe.servings} servings" +
                        if (report.cookedWeightMeasured) ", weighed cooked pot" else "",
                ),
                confidence = report.confidence,
                precision = if (estimated) Precision.ESTIMATED else Precision.CALCULATED,
                energyRange = if (estimated) EstimateRange.around(nutrients.energyKcal, 0.2) else null,
                capturedAtEpochMs = now,
                notes = notes,
            ),
            origin = EntryOrigin.RECIPE,
            idempotencyKey = "recipe:${recipe.id}:${mode.name}:${formatAmount(amount)}:${meal.name}:${now / 1000}",
        )
    }
}
