package app.nouri.core.parse

import app.nouri.core.ai.ParsedFoodMention
import app.nouri.core.ai.ParsedMealText
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.dishes.DishCatalog
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientCategory
import app.nouri.core.scan.FoodMatch
import app.nouri.core.scan.FoodMatcher
import app.nouri.core.scan.PortionSource
import app.nouri.core.units.ConversionMethod
import app.nouri.core.units.ConversionOutcome
import app.nouri.core.units.MeasurementSystem
import app.nouri.core.units.PortionContext
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitConverter
import app.nouri.core.units.formatAmount
import kotlinx.datetime.LocalDate

/** Nutrition of a recipe the user saved, used for "half of the biryani I made". */
data class SavedRecipeNutrition(
    val recipeId: String,
    val name: String,
    val total: Nutrients,
    val servings: Int,
    val cookedWeightGrams: Double?,
)

data class ResolvedTextItem(
    val mention: ParsedFoodMention,
    val match: FoodMatch?,
    val recipe: SavedRecipeNutrition?,
    val quantity: Quantity?,
    val grams: Double?,
    val nutrients: Nutrients,
    val portionSource: PortionSource,
    val confidence: ConfidenceLevel,
    val notes: List<String>,
) {
    val displayName: String get() = recipe?.name ?: match?.label ?: mention.food
    val isResolved: Boolean get() = match != null || recipe != null
}

class MealTextResolver(
    private val matcher: FoodMatcher,
    private val recipes: (String) -> SavedRecipeNutrition? = { null },
    private val system: MeasurementSystem = MeasurementSystem.METRIC,
) {
    fun resolve(parsed: ParsedMealText): List<ResolvedTextItem> = parsed.items.map(::resolveMention)

    fun resolveMention(m: ParsedFoodMention): ResolvedTextItem {
        m.recipeFraction?.let { fraction ->
            val recipe = recipes(m.recipeReference ?: m.food)
            if (recipe != null) {
                return ResolvedTextItem(
                    mention = m, match = null, recipe = recipe, quantity = null,
                    grams = recipe.cookedWeightGrams?.times(fraction),
                    nutrients = recipe.total * fraction,
                    portionSource = PortionSource.USER_ADJUSTED,
                    confidence = ConfidenceLevel.HIGHER,
                    notes = listOf("${formatAmount(fraction * 100)}% of your ${recipe.name} recipe"),
                )
            }
        }

        val (size, name) = extractSize(m.food)
        val match = matcher.match(name)
            ?: return ResolvedTextItem(m, null, null, null, null, Nutrients.ZERO, PortionSource.TYPICAL_SERVING, ConfidenceLevel.LOW, listOf("Couldn't find \"${m.food}\"; search or enter it manually"))
        val notes = mutableListOf<String>()
        if (m.recipeFraction != null) notes += "No saved recipe called \"${m.recipeReference ?: m.food}\"; used a typical ${match.label.lowercase()}"

        val unit = m.unit?.let(QuantityUnit::parse)
        val quantity: Quantity
        val grams: Double
        val source: PortionSource
        when {
            m.grams != null -> {
                grams = m.grams
                quantity = Quantity.grams(m.grams)
                source = PortionSource.USER_WEIGHED
            }
            m.amount != null && unit != null -> {
                quantity = Quantity(m.amount, unit, size)
                val conversion = UnitConverter.toGrams(quantity, contextFor(match), system)
                when (conversion) {
                    is ConversionOutcome.Success -> {
                        grams = conversion.conversion.grams
                        conversion.conversion.assumption?.let { notes += it }
                        source = if (conversion.conversion.method == ConversionMethod.GENERIC_PORTION || conversion.conversion.method == ConversionMethod.ASSUMED_WATER_DENSITY) PortionSource.TYPICAL_SERVING else PortionSource.USER_ADJUSTED
                    }
                    is ConversionOutcome.Failure -> {
                        // e.g. "2 chicken curry" — treat a count of a dish as servings.
                        val serving = match.food.servingGrams ?: 100.0
                        grams = m.amount * serving
                        notes += "Counted as ${formatAmount(m.amount)} typical serving${if (m.amount == 1.0) "" else "s"} (${formatAmount(serving)} g each)"
                        source = PortionSource.TYPICAL_SERVING
                    }
                }
            }
            else -> {
                val (q, g, note) = defaultPortion(match)
                quantity = q
                grams = g
                notes += note
                source = PortionSource.TYPICAL_SERVING
            }
        }
        val nutrients = match.food.nutrientsFor(grams)
        val confidence = when (source) {
            PortionSource.USER_WEIGHED -> if (match.dishId == null) ConfidenceLevel.HIGHER else ConfidenceLevel.MODERATE
            PortionSource.USER_ADJUSTED -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.LOW
        }
        return ResolvedTextItem(m, match, null, quantity, grams, nutrients, source, confidence, notes)
    }

    private fun contextFor(match: FoodMatch): PortionContext {
        val base = match.food.portionContext()
        val ing = match.ingredientId?.let(IngredientCatalog::get) ?: return base
        return base.copy(densityGPerMl = ing.densityGPerMl ?: base.densityGPerMl, portions = ing.portions + base.portions)
    }

    private fun defaultPortion(match: FoodMatch): Triple<Quantity, Double, String> {
        val dish = match.dishId?.let(DishCatalog::get)
        if (dish != null) {
            val piece = dish.portions.firstOrNull { it.unit == QuantityUnit.PIECE }
            if (piece != null) return Triple(Quantity(1.0, QuantityUnit.PIECE), piece.grams, "Assumed ${piece.label}")
            return Triple(Quantity(1.0, QuantityUnit.SERVING), dish.servingGrams, "Assumed one typical serving (${formatAmount(dish.servingGrams)} g)")
        }
        val ing = match.ingredientId?.let(IngredientCatalog::get)
        ing?.portions?.firstOrNull { it.unit == QuantityUnit.PIECE && (it.size == PortionSize.MEDIUM || it.size == null) }?.let {
            return Triple(Quantity(1.0, QuantityUnit.PIECE), it.grams, "Assumed ${it.label}")
        }
        if (ing?.category == IngredientCategory.BEVERAGE) {
            val cup = ing.portions.firstOrNull { it.unit == QuantityUnit.CUP }?.grams ?: 240.0
            return Triple(Quantity(1.0, QuantityUnit.CUP), cup, "Assumed one cup")
        }
        val serving = match.food.servingGrams ?: 100.0
        return Triple(Quantity.grams(serving), serving, "Assumed ${formatAmount(serving)} g")
    }

    private fun extractSize(food: String): Pair<PortionSize?, String> {
        val words = food.split(' ')
        val size = when {
            "small" in words -> PortionSize.SMALL
            "large" in words || "big" in words -> PortionSize.LARGE
            "medium" in words -> PortionSize.MEDIUM
            else -> null
        }
        val name = words.filter { it !in setOf("small", "large", "big", "medium") }.joinToString(" ").ifBlank { food }
        return size to name
    }
}

object TextLogging {
    fun toDiaryEntries(
        items: List<ResolvedTextItem>,
        date: LocalDate,
        meal: MealType,
        nowEpochMs: Long,
        timeZoneId: String,
        requestKey: String,
        newId: () -> String,
    ): List<DiaryEntry> = items.filter { it.isResolved }.mapIndexed { index, item ->
        val provenance = when {
            item.recipe != null -> Provenance(NutritionSource.SAVED_RECIPE, "recipe:${item.recipe.recipeId}", item.notes.firstOrNull())
            else -> item.match!!.food.provenance()
        }
        val precision = when (item.portionSource) {
            PortionSource.USER_WEIGHED -> if (item.match?.dishId == null) Precision.MEASURED else Precision.CALCULATED
            PortionSource.TYPICAL_SERVING -> Precision.ESTIMATED
            else -> Precision.CALCULATED
        }
        DiaryEntry(
            id = newId(),
            date = date,
            mealType = meal,
            loggedAtEpochMs = nowEpochMs,
            timeZoneId = timeZoneId,
            name = item.displayName,
            quantity = item.quantity,
            foodRefId = item.match?.food?.id,
            recipeId = item.recipe?.recipeId,
            recipeFraction = item.mention.recipeFraction?.takeIf { item.recipe != null },
            snapshot = NutritionSnapshot(
                nutrients = item.nutrients,
                grams = item.grams,
                provenance = provenance,
                confidence = item.confidence,
                precision = precision,
                energyRange = if (precision == Precision.ESTIMATED) EstimateRange.around(item.nutrients.energyKcal, item.match?.energyVariability?.plus(0.25) ?: 0.3) else null,
                capturedAtEpochMs = nowEpochMs,
                notes = item.notes,
            ),
            origin = EntryOrigin.NATURAL_LANGUAGE,
            idempotencyKey = "text:$requestKey:$index",
        )
    }
}
