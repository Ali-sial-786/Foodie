package app.nouri.core

import app.nouri.core.dishes.DishCatalog
import app.nouri.core.dishes.DishFoodRepository
import app.nouri.core.dishes.DishMatcher
import app.nouri.core.dishes.DishNutrition
import app.nouri.core.fooddb.CompositeFoodRepository
import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.parse.MealTextResolver
import app.nouri.core.parse.SavedRecipeNutrition
import app.nouri.core.recommend.RecipeRanker
import app.nouri.core.scan.FoodMatch
import app.nouri.core.scan.FoodMatcher
import app.nouri.core.scan.MatchMethod
import app.nouri.core.scan.MealEstimator
import app.nouri.core.units.MeasurementSystem

/** A search hit with a friendly title (USDA names are long) and a ready-to-use match. */
data class FoodSearchResult(
    val match: FoodMatch,
    val title: String,
    val subtitle: String,
) {
    val food: FoodReference get() = match.food
}

/**
 * Wires the shared engine together. Platforms supply [userFoods] (custom foods,
 * label scans, cached barcode products) and [extendedFoods] (e.g. the full USDA
 * table bundled as an asset); everything else is built in.
 */
class NouriCore(
    private val userFoods: FoodRepository? = null,
    private val extendedFoods: FoodRepository? = null,
    private val measurementSystem: MeasurementSystem = MeasurementSystem.METRIC,
    newId: () -> String,
) {
    val normalizer = IngredientNormalizer()
    val dishNutrition = DishNutrition.shared
    val dishMatcher = DishMatcher.shared
    val dishFoods = DishFoodRepository(dishNutrition, dishMatcher)
    val foods: FoodRepository = CompositeFoodRepository(listOfNotNull(userFoods, CoreFoods.repository, dishFoods, extendedFoods))
    val engine = NutritionEngine(foods, normalizer, measurementSystem)
    val matcher = FoodMatcher(foods, dishMatcher, dishNutrition, normalizer, searchRepository = extendedFoods)
    val estimator = MealEstimator(matcher, foods, engine, newId)
    val ranker = RecipeRanker(engine)

    fun textResolver(recipes: (String) -> SavedRecipeNutrition?) = MealTextResolver(matcher, recipes, measurementSystem)

    /** Unified search: your foods, dishes, canonical ingredients, then the extended database. */
    fun search(query: String, limit: Int = 25): List<FoodSearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val results = mutableListOf<FoodSearchResult>()

        userFoods?.search(q, 8)?.forEach { results += FoodSearchResult(matcher.fromReference(it, MatchMethod.SAVED_FOOD), it.name, it.provenance().source.label) }

        dishMatcher.search(q, 8).forEach { dish ->
            matcher.dishMatch(dish.name)?.let { m ->
                val cuisine = app.nouri.core.recipes.CuisineCatalog[dish.cuisineId]?.name
                results += FoodSearchResult(m, dish.name, listOfNotNull(cuisine, "typical recipe").joinToString(" · "))
            }
        }

        normalizer.suggest(q, 8).forEach { ing ->
            ing.cookedRef?.let(foods::get)?.let { cooked ->
                results += FoodSearchResult(
                    FoodMatch(cooked, MatchMethod.INGREDIENT_REFERENCE, ingredientId = ing.id, label = "${ing.name} (cooked)", energyVariability = 0.08),
                    "${ing.name}, cooked", "USDA reference",
                )
            }
            ing.primaryRef?.let(foods::get)?.let { raw ->
                val state = if (ing.cookedRef != null) "raw" else null
                results += FoodSearchResult(
                    FoodMatch(raw, MatchMethod.INGREDIENT_REFERENCE, ingredientId = ing.id, label = ing.name, energyVariability = 0.08),
                    listOfNotNull(ing.name, state).joinToString(", "), if (raw.id.startsWith("usda:")) "USDA reference" else raw.provenance().source.label,
                )
            }
        }

        extendedFoods?.search(q, limit)?.forEach { results += FoodSearchResult(matcher.fromReference(it, MatchMethod.DATABASE_SEARCH), it.name, "USDA SR28") }

        return results.distinctBy { it.food.id }.take(limit)
    }

    companion object {
        val ingredientCount: Int get() = IngredientCatalog.all.size
        val dishCount: Int get() = DishCatalog.all.size
    }
}
