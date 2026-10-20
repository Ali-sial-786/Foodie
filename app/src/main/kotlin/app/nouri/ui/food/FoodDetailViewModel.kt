package app.nouri.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.dishes.DishNutrition
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.nutrition.IngredientLineResult
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.Precision
import app.nouri.core.scan.MatchMethod
import app.nouri.core.units.ConversionMethod
import app.nouri.core.units.MeasurementSystem
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import app.nouri.core.util.SystemTime
import app.nouri.data.repo.LogResult
import app.nouri.ui.common.logEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** One way to express an amount of this food ("g", "1 cup, chopped", "1 medium"). */
data class UnitOption(val label: String, val unit: QuantityUnit, val size: PortionSize? = null, val gramsHint: Double? = null)

data class FoodDetailState(
    val loading: Boolean = true,
    val food: FoodReference? = null,
    val title: String = "",
    val options: List<UnitOption> = emptyList(),
    val selected: UnitOption? = null,
    val amountText: String = "1",
    val result: IngredientLineResult? = null,
    val meal: MealType = MealType.SNACK,
    val isDish: Boolean = false,
    val variabilityNote: String? = null,
    val energyRange: EstimateRange? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.MODERATE,
    val precision: Precision = Precision.CALCULATED,
    val notFound: Boolean = false,
)

class FoodDetailViewModel(
    private val c: AppContainer,
    private val foodId: String,
    private val date: LocalDate,
    meal: MealType,
    private val titleOverride: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(FoodDetailState(meal = meal))
    val state: StateFlow<FoodDetailState> = _state.asStateFlow()

    private val _duplicate = MutableStateFlow<LogResult.PossibleDuplicate?>(null)
    val duplicate: StateFlow<LogResult.PossibleDuplicate?> = _duplicate.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var system = MeasurementSystem.METRIC
    private var variability = 0.1

    init {
        viewModelScope.launch {
            system = c.settings.current().preferences.measurementSystem
            val core = c.core(system)
            if (core.foods.get(foodId) == null) runCatching { c.usdaFoods.load() }
            val food = core.foods.get(foodId)
            if (food == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val match = core.matcher.byReferenceId(foodId, MatchMethod.USER_SELECTED)
            val options = unitOptions(food)
            val preferred = options.firstOrNull { it.unit == QuantityUnit.SERVING } ?: options.firstOrNull { it.unit != QuantityUnit.GRAM } ?: options.first()
            val usual = c.settings.current().correctionMemory.usualPortion(food.id)
            val (opt, amount) = if (usual != null) options.first { it.unit == QuantityUnit.GRAM } to formatAmount(usual.lastGrams) else preferred to if (preferred.unit == QuantityUnit.GRAM) formatAmount(food.servingGrams ?: 100.0) else "1"
            _state.update {
                it.copy(
                    loading = false, food = food, title = titleOverride ?: match?.label ?: food.name, options = options, selected = opt, amountText = amount,
                    isDish = food.id.startsWith(DishNutrition.PREFIX), variabilityNote = match?.variabilityNote,
                )
            }
            recalculate(match?.energyVariability ?: 0.1)
        }
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text) }
        recalculate()
    }

    fun setUnit(option: UnitOption) {
        val s = _state.value
        // Keep the same weight when switching units, where possible, so the numbers don't jump.
        val grams = s.result?.gramsAsWritten
        val newAmount = when {
            grams == null -> s.amountText
            option.unit == QuantityUnit.GRAM -> formatAmount(grams)
            option.gramsHint != null -> formatAmount(grams / option.gramsHint)
            else -> "1"
        }
        _state.update { it.copy(selected = option, amountText = newAmount) }
        recalculate()
    }

    fun setMeal(m: MealType) = _state.update { it.copy(meal = m) }

    private fun recalculate(v: Double? = null) {
        if (v != null) variability = v
        val s = _state.value
        val food = s.food ?: return
        val option = s.selected ?: return
        val amount = s.amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        if (amount == null) {
            _state.update { it.copy(result = null) }
            return
        }
        val result = c.core(system).engine.calculateFood(food, Quantity(amount, option.unit, option.size))
        val generic = result.conversion?.method in setOf(ConversionMethod.ASSUMED_WATER_DENSITY, ConversionMethod.GENERIC_PORTION)
        val precision = when {
            s.isDish || generic || food.quality == DataQuality.APPROXIMATE -> Precision.ESTIMATED
            food.source == NutritionSource.MANUFACTURER_LABEL || food.source == NutritionSource.USER_ENTERED -> Precision.MEASURED
            else -> Precision.CALCULATED
        }
        val confidence = when {
            result.status == LineStatus.UNRESOLVED -> ConfidenceLevel.LOW
            food.quality == DataQuality.APPROXIMATE -> ConfidenceLevel.LOW
            s.isDish || generic -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGHER
        }
        val spread = if (precision == Precision.ESTIMATED) variability + if (generic) 0.15 else 0.0 else 0.0
        _state.update {
            it.copy(
                result = result, precision = precision, confidence = confidence,
                energyRange = EstimateRange.around(result.nutrients.energyKcal, spread).takeIf { spread > 0 },
            )
        }
    }

    fun log(force: Boolean = false) = viewModelScope.launch {
        val s = _state.value
        val food = s.food ?: return@launch
        val result = s.result ?: return@launch
        if (result.status == LineStatus.UNRESOLVED) return@launch
        val now = SystemTime.nowEpochMs()
        val entry = DiaryEntry(
            id = c.newId(), date = date, mealType = s.meal, loggedAtEpochMs = now, timeZoneId = SystemTime.zoneId(),
            name = s.title, quantity = result.line.quantity, foodRefId = food.id,
            snapshot = NutritionSnapshot(
                nutrients = result.nutrients, grams = result.gramsEaten, provenance = food.provenance(),
                confidence = s.confidence, precision = s.precision, energyRange = s.energyRange,
                capturedAtEpochMs = now, notes = result.notes + listOfNotNull(result.conversion?.assumption, s.variabilityNote),
            ),
            origin = EntryOrigin.SEARCH,
            idempotencyKey = "food:${food.id}:$date:${s.meal}:${now / 1000}",
        )
        when (val r = c.logEntries(listOf(entry), force)) {
            is LogResult.PossibleDuplicate -> _duplicate.value = r
            else -> _done.value = true
        }
    }

    fun confirmDuplicate() { _duplicate.value = null; log(force = true) }
    fun dismissDuplicate() { _duplicate.value = null }

    private fun unitOptions(food: FoodReference): List<UnitOption> = buildList {
        add(UnitOption("grams", QuantityUnit.GRAM, gramsHint = 1.0))
        if (system == MeasurementSystem.US_CUSTOMARY) add(UnitOption("ounces", QuantityUnit.OUNCE, gramsHint = QuantityUnit.OUNCE.massGrams))
        food.servingGrams?.let { add(UnitOption("serving (${formatAmount(it)} g)", QuantityUnit.SERVING, gramsHint = it)) }
        food.portions.filter { it.unit != QuantityUnit.SERVING || food.servingGrams == null }.forEach { p ->
            val label = p.label.ifBlank { listOfNotNull(p.size?.name?.lowercase(), p.unit.symbol).joinToString(" ") }
            add(UnitOption("$label (${formatAmount(p.grams)} g)", p.unit, p.size, p.grams))
        }
        if (food.densityGPerMl != null || food.portions.any { it.unit == QuantityUnit.CUP }) {
            if (none { it.unit == QuantityUnit.CUP }) add(UnitOption("cup", QuantityUnit.CUP))
            add(UnitOption("tablespoon", QuantityUnit.TABLESPOON))
        }
    }.distinctBy { Triple(it.unit, it.size, it.gramsHint?.let(::formatAmount)) }
}
