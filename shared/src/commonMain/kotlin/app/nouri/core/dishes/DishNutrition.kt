package app.nouri.core.dishes

import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.fooddb.FoodState
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.nutrition.RecipeNutritionReport
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeStep
import app.nouri.core.units.Quantity

/** Calculates and caches nutrition for reference dishes from their compositions. */
class DishNutrition(
    private val engine: NutritionEngine = NutritionEngine(foods = CoreFoods.repository),
    private val dishes: List<DishDefinition> = DishCatalog.all,
) {
    private val reports = HashMap<String, RecipeNutritionReport>()
    private val references = HashMap<String, FoodReference>()

    fun report(dishId: String): RecipeNutritionReport? {
        reports[dishId]?.let { return it }
        val dish = dishes.firstOrNull { it.id == dishId } ?: return null
        val report = engine.calculateRecipe(asRecipe(dish))
        reports[dishId] = report
        return report
    }

    fun reference(dishId: String): FoodReference? {
        references[dishId]?.let { return it }
        val dish = dishes.firstOrNull { it.id == dishId } ?: return null
        val report = report(dishId) ?: return null
        val ref = FoodReference(
            id = idFor(dish.id),
            name = dish.name,
            per100g = report.total * (100.0 / dish.batchCookedGrams),
            source = NutritionSource.REFERENCE_RECIPE,
            quality = DataQuality.CALCULATED,
            state = FoodState.PREPARED,
            portions = dish.portions,
            servingGrams = dish.servingGrams,
            foodGroup = dish.category.name,
            sourceDetail = "Typical ${dish.name.lowercase()} — calculated from Nouri's reference recipe (USDA SR28 ingredients)",
            datasetVersion = "dishes-v1",
        )
        references[dishId] = ref
        return ref
    }

    /** Ingredient-level halal assessment for a dish's reference recipe. */
    fun halalStatus(dishId: String): HalalStatus {
        val dish = dishes.firstOrNull { it.id == dishId } ?: return HalalStatus.VERIFY
        val statuses = dish.composition.mapNotNull { c -> c.ingredientId?.let { IngredientCatalog[it]?.halal } }
        return when {
            HalalStatus.NOT_PERMISSIBLE in statuses -> HalalStatus.NOT_PERMISSIBLE
            dish.halalCaution != null || HalalStatus.VERIFY in statuses || dish.composition.any { it.foodRefId != null } -> HalalStatus.VERIFY
            HalalStatus.REQUIRES_HALAL_SOURCE in statuses -> HalalStatus.REQUIRES_HALAL_SOURCE
            else -> HalalStatus.PERMISSIBLE
        }
    }

    companion object {
        const val PREFIX = "dish:"
        fun idFor(dishId: String) = "$PREFIX$dishId"

        val shared: DishNutrition by lazy { DishNutrition() }

        fun asRecipe(dish: DishDefinition): Recipe = Recipe(
            id = idFor(dish.id),
            name = dish.name,
            cuisineId = dish.cuisineId,
            region = dish.region,
            servings = maxOf(1, (dish.batchCookedGrams / dish.servingGrams).toInt()),
            ingredients = dish.composition.map { c ->
                RecipeIngredient(
                    displayName = c.ingredientId ?: c.foodRefId!!,
                    ingredientId = c.ingredientId,
                    foodRefId = c.foodRefId,
                    quantity = Quantity.grams(c.grams),
                    consumedFraction = c.consumedFraction,
                    measuredCooked = c.cooked,
                )
            },
            steps = listOf(RecipeStep("Reference composition for nutrition estimates.")),
            prepMinutes = 0,
            cookMinutes = 0,
            origin = RecipeOrigin.EDITORIAL_TRADITIONAL,
            cookedYieldGrams = dish.batchCookedGrams,
        )
    }
}

/** Exposes reference dishes as "dish:<id>" food records. */
class DishFoodRepository(
    private val nutrition: DishNutrition = DishNutrition.shared,
    private val matcher: DishMatcher = DishMatcher.shared,
) : FoodRepository {
    override fun get(id: String): FoodReference? =
        if (id.startsWith(DishNutrition.PREFIX)) nutrition.reference(id.removePrefix(DishNutrition.PREFIX)) else null

    override fun search(query: String, limit: Int): List<FoodReference> =
        matcher.search(query, limit).mapNotNull { nutrition.reference(it.id) }
}

data class DishMatch(val dish: DishDefinition, val alias: String, val exact: Boolean)

/** Resolves dish names in many spellings ("biriyani", "Chicken Karhai", "phở") to reference dishes. */
class DishMatcher(private val dishes: List<DishDefinition> = DishCatalog.all) {
    private val index: List<Pair<String, DishDefinition>> = dishes
        .flatMap { d -> (d.aliases + d.name + d.id.replace('_', ' ')).map { IngredientNormalizer.normalize(stripDiacritics(it)) to d } }
        .filter { it.first.isNotBlank() }
        .distinctBy { it.first }
        .sortedByDescending { it.first.length }

    fun match(name: String): DishMatch? {
        val n = IngredientNormalizer.normalize(stripDiacritics(name))
        if (n.isBlank()) return null
        index.firstOrNull { it.first == n }?.let { return DishMatch(it.second, it.first, exact = true) }
        val padded = " $n "
        index.firstOrNull { it.first.length >= 4 && padded.contains(" ${it.first} ") }?.let { return DishMatch(it.second, it.first, exact = false) }
        // Tolerate small spelling differences in single-word names ("biriani", "nehari").
        val token = n.split(' ').maxByOrNull { it.length } ?: return null
        if (token.length < 5) return null
        return index.asSequence()
            .filter { !it.first.contains(' ') && kotlin.math.abs(it.first.length - token.length) <= 2 }
            .map { it to IngredientNormalizer.levenshtein(token, it.first, 2) }
            .filter { it.second <= (if (token.length >= 8) 2 else 1) }
            .minByOrNull { it.second }
            ?.let { DishMatch(it.first.second, it.first.first, exact = false) }
    }

    fun search(query: String, limit: Int = 10): List<DishDefinition> {
        val q = IngredientNormalizer.normalize(stripDiacritics(query))
        if (q.isBlank()) return emptyList()
        return index.asSequence()
            .filter { (alias, _) -> alias.startsWith(q) || alias.contains(" $q") || q.contains(alias) }
            .map { it.second }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    companion object {
        val shared: DishMatcher by lazy { DishMatcher() }

        private val diacritics = mapOf(
            'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
            'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
            'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ñ' to 'n', 'ç' to 'c', 'ş' to 's', 'ğ' to 'g', 'ı' to 'i',
            'ở' to 'o', 'ơ' to 'o', 'ư' to 'u', 'đ' to 'd', 'ạ' to 'a', 'ả' to 'a', 'ế' to 'e', 'ọ' to 'o', 'ộ' to 'o',
        )

        fun stripDiacritics(text: String): String = buildString(text.length) {
            for (ch in text) {
                val lower = ch.lowercaseChar()
                val mapped = diacritics[lower]
                append(if (mapped != null) (if (ch.isUpperCase()) mapped.uppercaseChar() else mapped) else ch)
            }
        }
    }
}
