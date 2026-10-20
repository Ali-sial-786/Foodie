package app.nouri.core.scan

import app.nouri.core.ai.Likelihood
import app.nouri.core.dishes.DishCatalog
import app.nouri.core.dishes.DishMatcher
import app.nouri.core.dishes.DishNutrition
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.ontology.IngredientCategory
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.ontology.MatchKind
import kotlinx.serialization.Serializable

/**
 * Personal, on-device learning from corrections. Nothing here trains a model: it
 * is context the app applies to future estimates and (minimally) to prompts.
 */
@Serializable
data class CorrectionMemory(
    /** Normalised provider label → food reference id the user chose instead. */
    val renames: Map<String, RenameRecord> = emptyMap(),
    /** Food reference id → the portions the user actually logged. */
    val portions: Map<String, PortionRecord> = emptyMap(),
) {
    @Serializable
    data class RenameRecord(val foodRefId: String, val label: String, val count: Int)

    @Serializable
    data class PortionRecord(val meanGrams: Double, val count: Int, val lastGrams: Double)

    fun renameFor(providerLabel: String): RenameRecord? = renames[key(providerLabel)]?.takeIf { it.count >= 1 }

    fun usualPortion(foodRefId: String): PortionRecord? = portions[foodRefId]?.takeIf { it.count >= 2 }

    /** Records a logged scan item. Renames are learned only when the user changed the food. */
    fun learn(item: ScanItem): CorrectionMemory {
        val match = item.match ?: return this
        var next = this
        val providerLabel = item.detected?.label
        if (providerLabel != null && item.originalMatchId != null && item.originalMatchId != match.food.id) {
            val k = key(providerLabel)
            val prev = renames[k]
            val count = if (prev?.foodRefId == match.food.id) prev.count + 1 else 1
            next = next.copy(renames = renames + (k to RenameRecord(match.food.id, match.label, count)))
        }
        val prevPortion = portions[match.food.id]
        val grams = item.portion.grams
        val updated = if (prevPortion == null) PortionRecord(grams, 1, grams)
        else {
            // Exponential average so habits can change; capped history weight.
            val weight = minOf(prevPortion.count, 9).toDouble()
            PortionRecord((prevPortion.meanGrams * weight + grams) / (weight + 1), prevPortion.count + 1, grams)
        }
        return next.copy(portions = next.portions + (match.food.id to updated))
    }

    /** Dish names the user has confirmed, for prompt context. */
    fun knownLabels(limit: Int = 15): List<String> =
        renames.values.sortedByDescending { it.count }.map { it.label }.distinct().take(limit)

    companion object {
        fun key(label: String): String = IngredientNormalizer.normalize(label)
    }
}

/**
 * Stage 3: links an identified food name to nutrition data. Identification and
 * nutrition are separate: names come from the provider or the user; numbers come
 * only from reference data.
 */
class FoodMatcher(
    private val foods: FoodRepository,
    private val dishMatcher: DishMatcher = DishMatcher.shared,
    private val dishNutrition: DishNutrition = DishNutrition.shared,
    private val normalizer: IngredientNormalizer = IngredientNormalizer(),
    /** Extra searchable repository (e.g. the full USDA table on device). */
    private val searchRepository: FoodRepository? = null,
) {
    /** Best match for a name; [preferCooked] selects cooked-state references for prepared food. */
    fun match(name: String, preferCooked: Boolean = true): FoodMatch? =
        dishMatch(name) ?: ingredientMatch(name, preferCooked) ?: searchMatch(name)

    fun dishMatch(name: String): FoodMatch? {
        val m = dishMatcher.match(name) ?: return null
        val ref = dishNutrition.reference(m.dish.id) ?: return null
        return FoodMatch(
            food = ref,
            method = MatchMethod.DISH_REFERENCE,
            dishId = m.dish.id,
            label = m.dish.name,
            energyVariability = m.dish.energyVariability,
            variabilityNote = m.dish.variabilityNote,
            restaurantEnergyFactor = m.dish.restaurantEnergyFactor,
        )
    }

    fun ingredientMatch(name: String, preferCooked: Boolean = true): FoodMatch? {
        val m = normalizer.resolve(name) ?: return null
        if (m.kind == MatchKind.FUZZY && m.ingredient.name.length < 4) return null
        val ing = m.ingredient
        val cookedByDefault = preferCooked && ing.category in COOKED_WHEN_SERVED
        val ref = (if (cookedByDefault) ing.cookedRef?.let(foods::get) else null)
            ?: ing.primaryRef?.let(foods::get)
            ?: ing.cookedRef?.let(foods::get)
            ?: return null
        val label = if (cookedByDefault && ref.id == ing.cookedRef) "${ing.name} (cooked)" else ing.name
        return FoodMatch(
            food = ref,
            method = MatchMethod.INGREDIENT_REFERENCE,
            ingredientId = ing.id,
            label = label,
            energyVariability = if (ref.quality == DataQuality.APPROXIMATE) 0.25 else 0.08,
        )
    }

    fun searchMatch(name: String): FoodMatch? {
        val repo = searchRepository ?: return null
        val hit = repo.search(name, 1).firstOrNull() ?: return null
        return FoodMatch(food = hit, method = MatchMethod.DATABASE_SEARCH, label = hit.name, energyVariability = 0.3)
    }

    /** Candidate matches for a provider item, best first, de-duplicated by reference. */
    fun candidates(names: List<Pair<String, Likelihood>>, preferCooked: Boolean = true): List<Pair<FoodMatch, Likelihood>> =
        names.mapNotNull { (n, l) -> match(n, preferCooked)?.let { it to l } }
            .distinctBy { it.first.food.id }

    /** Visually similar dishes, offered as one-tap alternatives. */
    fun lookalikes(dishId: String): List<FoodMatch> =
        DishCatalog[dishId]?.lookalikes.orEmpty().mapNotNull { id ->
            DishCatalog[id]?.let { dishMatch(it.name) }
        }

    /** Rebuilds a full match (including dish metadata) from a stored reference id. */
    fun byReferenceId(id: String, method: MatchMethod): FoodMatch? {
        if (id.startsWith(DishNutrition.PREFIX)) {
            val dish = DishCatalog[id.removePrefix(DishNutrition.PREFIX)] ?: return null
            return dishMatch(dish.name)?.copy(method = method)
        }
        return foods.get(id)?.let { fromReference(it, method) }
    }

    fun fromReference(food: FoodReference, method: MatchMethod = MatchMethod.USER_SELECTED): FoodMatch = FoodMatch(
        food = food,
        method = method,
        label = food.name,
        energyVariability = when (food.source) {
            NutritionSource.MANUFACTURER_LABEL, NutritionSource.USER_ENTERED -> 0.03
            NutritionSource.VERIFIED_DATABASE, NutritionSource.BRANDED_DATABASE, NutritionSource.SAVED_RECIPE -> 0.08
            else -> 0.25
        },
    )

    companion object {
        private val COOKED_WHEN_SERVED = setOf(
            IngredientCategory.GRAIN, IngredientCategory.PASTA_NOODLE, IngredientCategory.POULTRY, IngredientCategory.RED_MEAT,
            IngredientCategory.PORK, IngredientCategory.SEAFOOD, IngredientCategory.LEGUME,
        )
    }
}
