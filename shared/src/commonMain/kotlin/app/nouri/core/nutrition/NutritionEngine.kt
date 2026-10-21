package app.nouri.core.nutrition

import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.ontology.CanonicalIngredient
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientCategory
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.ontology.MatchKind
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.units.ConversionMethod
import app.nouri.core.units.ConversionOutcome
import app.nouri.core.units.GramConversion
import app.nouri.core.units.MeasurementSystem
import app.nouri.core.units.PortionContext
import app.nouri.core.units.Quantity
import app.nouri.core.units.UnitConverter
import app.nouri.core.units.UnitKind
import app.nouri.core.units.formatAmount

enum class LineStatus {
    /** Identity, quantity and nutrition data are all solid. */
    RESOLVED,

    /** Calculated, but with an assumption (volume density, fuzzy name, approximate data). */
    ESTIMATED,

    /** Unquantified ("salt to taste"); contributes nothing and is not an error. */
    UNQUANTIFIED,

    /** Could not be identified or converted to grams; excluded from totals. */
    UNRESOLVED,
}

data class IngredientLineResult(
    val line: RecipeIngredient,
    val canonical: CanonicalIngredient?,
    val food: FoodReference?,
    /** Grams for the quantity as written (as purchased, before edible/consumed adjustments). */
    val gramsAsWritten: Double?,
    /** Grams actually eaten after removing bone/peel and unconsumed portions. */
    val gramsEaten: Double?,
    val nutrients: Nutrients,
    val conversion: GramConversion?,
    val status: LineStatus,
    val notes: List<String>,
)

data class RecipeNutritionReport(
    val recipeId: String,
    val lines: List<IngredientLineResult>,
    val total: Nutrients,
    val servings: Int,
    val perServing: Nutrients,
    /** Sum of everything that went into the pot (as written), including water. */
    val inputMassGrams: Double,
    val cookedWeightGrams: Double,
    val cookedWeightMeasured: Boolean,
    val per100gCooked: Nutrients,
    /** Share of quantified, non-optional lines that could be calculated (0–1). */
    val completeness: Double,
    val confidence: ConfidenceLevel,
    val warnings: List<String>,
    val sources: List<Provenance>,
) {
    val cookedGramsPerServing: Double get() = cookedWeightGrams / servings

    /** Nutrients for an eaten portion weighed in grams (batch cooking). */
    fun forPortionGrams(grams: Double): Nutrients = per100gCooked * (grams / 100.0)

    /** Nutrients for a fraction of the whole recipe ("I ate half of it"). */
    fun forFraction(fraction: Double): Nutrients {
        require(fraction >= 0) { "Fraction must be non-negative" }
        return total * fraction
    }

    fun forServings(count: Double): Nutrients = perServing * count
}

/**
 * Deterministic nutrition calculator. Language models are never consulted here:
 * every number comes from reference data, explicit quantities and documented
 * assumptions, so results are reproducible and testable offline.
 */
class NutritionEngine(
    private val foods: FoodRepository = CoreFoods.repository,
    private val normalizer: IngredientNormalizer = defaultNormalizer,
    private val system: MeasurementSystem = MeasurementSystem.METRIC,
    /** Fraction of input mass assumed to remain after cooking when the batch was not weighed. */
    private val defaultCookingRetention: Double = 0.85,
) {

    fun calculateLine(line: RecipeIngredient): IngredientLineResult {
        val notes = mutableListOf<String>()
        val match = if (line.ingredientId == null) normalizer.resolve(line.displayName) else null
        val canonical = line.ingredientId?.let(IngredientCatalog::get) ?: match?.ingredient
        if (match?.kind == MatchKind.FUZZY) notes += "Interpreted \"${line.displayName}\" as ${match.ingredient.name}"

        val food = resolveFood(line, canonical, notes)
        if (line.quantity.amount == 0.0 && line.gramsOverride == null) {
            return IngredientLineResult(line, canonical, food, 0.0, 0.0, Nutrients.ZERO, null, LineStatus.UNQUANTIFIED, notes)
        }
        if (food == null) {
            notes += "No nutrition data for \"${line.displayName}\""
            return unresolved(line, canonical, null, notes)
        }

        val conversion: GramConversion = line.gramsOverride?.let { GramConversion(it, ConversionMethod.DIRECT_MASS) }
            ?: when (val outcome = UnitConverter.toGrams(line.quantity, portionContext(canonical, food), system)) {
                is ConversionOutcome.Success -> outcome.conversion
                is ConversionOutcome.Failure -> {
                    notes += outcome.reason
                    return unresolved(line, canonical, food, notes)
                }
            }
        conversion.assumption?.let { notes += it }

        var eaten = conversion.grams
        val edible = canonical?.edibleFraction ?: 1.0
        if (edible < 1.0 && !line.measuredCooked && line.gramsOverride == null && line.foodRefId == null) {
            eaten *= edible
            notes += "Excludes inedible parts (~${formatAmount((1 - edible) * 100)}% bone/peel)"
        }
        if (line.consumedFraction < 1.0) {
            eaten *= line.consumedFraction
            notes += "Counts ~${formatAmount(line.consumedFraction * 100)}% of this ingredient as eaten"
        }

        val estimated = conversion.method in setOf(ConversionMethod.ASSUMED_WATER_DENSITY, ConversionMethod.GENERIC_PORTION) ||
            match?.kind == MatchKind.FUZZY ||
            food.quality == DataQuality.APPROXIMATE
        if (food.quality == DataQuality.APPROXIMATE) notes += "${food.name}: approximate reference values"

        return IngredientLineResult(
            line = line,
            canonical = canonical,
            food = food,
            gramsAsWritten = conversion.grams,
            gramsEaten = eaten,
            nutrients = food.nutrientsFor(eaten),
            conversion = conversion,
            status = if (estimated) LineStatus.ESTIMATED else LineStatus.RESOLVED,
            notes = notes,
        )
    }

    fun calculateRecipe(recipe: Recipe, includeOptional: Boolean = false): RecipeNutritionReport {
        val lines = recipe.ingredients
            .filter { includeOptional || !it.optional }
            .map(::calculateLine)

        val total = Nutrients.sum(lines.map { it.nutrients })
        val inputMass = lines.sumOf { it.gramsAsWritten ?: 0.0 }
        val measured = recipe.cookedYieldGrams?.takeIf { it > 0 }
        val cookedWeight = measured ?: estimateCookedWeight(lines)
        val per100 = if (cookedWeight > 0) total * (100.0 / cookedWeight) else Nutrients.ZERO

        val quantified = lines.filter { it.status != LineStatus.UNQUANTIFIED }
        val calculable = quantified.count { it.status == LineStatus.RESOLVED || it.status == LineStatus.ESTIMATED }
        val completeness = if (quantified.isEmpty()) 1.0 else calculable.toDouble() / quantified.size

        val warnings = buildList {
            lines.filter { it.status == LineStatus.UNRESOLVED }.forEach {
                add("Not included in totals: ${it.line.displayName}")
            }
            if (measured == null) add("Cooked weight estimated (${formatAmount(cookedWeight)} g); weigh the pot for accurate per-gram portions")
        }

        val unresolvedEnergyDense = lines.any { it.status == LineStatus.UNRESOLVED && it.canonical?.category.isEnergyDense() }
        val confidence = when {
            unresolvedEnergyDense || completeness < 0.75 -> ConfidenceLevel.LOW
            lines.any { it.status == LineStatus.ESTIMATED || it.status == LineStatus.UNRESOLVED } -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGHER
        }

        return RecipeNutritionReport(
            recipeId = recipe.id,
            lines = lines,
            total = total,
            servings = recipe.servings,
            perServing = total / recipe.servings.toDouble(),
            inputMassGrams = inputMass,
            cookedWeightGrams = cookedWeight,
            cookedWeightMeasured = measured != null,
            per100gCooked = per100,
            completeness = completeness,
            confidence = confidence,
            warnings = warnings,
            sources = lines.mapNotNull { it.food?.provenance() }.distinctBy { it.referenceId },
        )
    }

    /** Nutrients for a single food and quantity (diary quick-add, search results). */
    fun calculateFood(food: FoodReference, quantity: Quantity, canonical: CanonicalIngredient? = null): IngredientLineResult =
        calculateLine(
            RecipeIngredient(
                displayName = food.name,
                ingredientId = canonical?.id,
                quantity = quantity,
                foodRefId = food.id,
            ),
        )

    /**
     * Pot weight when the cook didn't weigh it. Most lines keep [defaultCookingRetention] of
     * their mass; meat uses its own [CanonicalIngredient.cookedYield]. Dry rice, pasta and
     * pulses swell by absorbing water, which is often not written down (pasta boiled and
     * drained, soaked chickpeas), so the pot gets whichever is larger: the water those
     * ingredients absorb, or the listed water and stock after evaporation.
     */
    private fun estimateCookedWeight(lines: List<IngredientLineResult>): Double {
        var solids = 0.0
        var absorbed = 0.0
        var liquid = 0.0
        for (r in lines) {
            val grams = r.gramsAsWritten ?: continue
            val yieldFactor = r.canonical?.cookedYield?.takeIf { !r.line.measuredCooked }
            when {
                r.canonical?.id in COOKING_LIQUIDS -> liquid += grams
                yieldFactor == null -> solids += grams * defaultCookingRetention
                yieldFactor > 1.0 -> { solids += grams; absorbed += grams * (yieldFactor - 1.0) }
                else -> solids += grams * yieldFactor
            }
        }
        return solids + maxOf(absorbed, liquid * defaultCookingRetention)
    }

    private fun resolveFood(line: RecipeIngredient, canonical: CanonicalIngredient?, notes: MutableList<String>): FoodReference? {
        line.foodRefId?.let { id -> foods.get(id)?.let { return it } }
        if (canonical == null) return null
        if (line.measuredCooked) {
            canonical.cookedRef?.let { ref -> foods.get(ref)?.let { return it } }
            notes += "No cooked-weight data for ${canonical.name}; used raw values"
        }
        return canonical.primaryRef?.let(foods::get) ?: canonical.cookedRef?.let(foods::get)
    }

    private fun portionContext(canonical: CanonicalIngredient?, food: FoodReference): PortionContext {
        val base = food.portionContext()
        if (canonical == null) return base
        return PortionContext(
            densityGPerMl = canonical.densityGPerMl ?: base.densityGPerMl,
            portions = canonical.portions + base.portions,
            servingGrams = base.servingGrams,
            foodName = canonical.name.lowercase(),
        )
    }

    private fun unresolved(line: RecipeIngredient, canonical: CanonicalIngredient?, food: FoodReference?, notes: List<String>) =
        IngredientLineResult(line, canonical, food, null, null, Nutrients.ZERO, null, LineStatus.UNRESOLVED, notes)

    private fun IngredientCategory?.isEnergyDense(): Boolean = this == null || this in setOf(
        IngredientCategory.FAT_OIL, IngredientCategory.GRAIN, IngredientCategory.PASTA_NOODLE, IngredientCategory.BREAD,
        IngredientCategory.RED_MEAT, IngredientCategory.PORK, IngredientCategory.POULTRY, IngredientCategory.NUT_SEED,
        IngredientCategory.SWEETENER, IngredientCategory.DAIRY, IngredientCategory.LEGUME,
    )

    companion object {
        val defaultNormalizer: IngredientNormalizer by lazy { IngredientNormalizer() }

        /** Listed liquids that dry grains and pulses can soak up while cooking. */
        private val COOKING_LIQUIDS = setOf("water", "chicken_broth", "beef_broth", "fish_stock", "vegetable_broth")

        /** True for quantities expressed by mass, which never need a density assumption. */
        fun isMass(quantity: Quantity): Boolean = quantity.unit.kind == UnitKind.MASS
    }
}
