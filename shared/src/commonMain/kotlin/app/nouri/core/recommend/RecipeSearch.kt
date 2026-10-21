package app.nouri.core.recommend

import app.nouri.core.model.FoodPreferences
import app.nouri.core.model.Nutrients
import app.nouri.core.ontology.DietGoal
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.RecipeFamilies
import app.nouri.core.recipes.WorldRegion
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

enum class RecipeSort(val label: String) {
    BEST_MATCH("Best match"),
    TOP_RATED("Your rating"),
    QUICKEST("Quickest"),
    FEWEST_KCAL("Fewest calories"),
    MOST_PROTEIN("Most protein"),
}

/** Favourites, cooking history and the user's own rating for one recipe (kept on the device). */
@Serializable
data class RecipeUsage(val favourite: Boolean = false, val cookedCount: Int = 0, val lastCookedAtEpochMs: Long? = null, val rating: Int? = null)

/** What the user is looking for in Discover. Everything but [query] and [sort] is a filter. */
data class RecipeFilters(
    val query: String = "",
    val sort: RecipeSort = RecipeSort.BEST_MATCH,
    val worldRegion: WorldRegion? = null,
    val cuisineId: String? = null,
    val course: RecipeCourse? = null,
    /** Every chosen diet must be met, as calculated from the ingredients. */
    val diets: Set<DietGoal> = emptySet(),
    val maxMinutes: Int? = null,
    val maxKcal: Int? = null,
    val easyOnly: Boolean = false,
    val usePantry: Boolean = false,
    val onlyAvailable: Boolean = false,
    val highProtein: Boolean = false,
    val favouritesOnly: Boolean = false,
) {
    /** Filters the user can clear in one tap (the search box has its own clear button). */
    val activeCount: Int
        get() = listOfNotNull(worldRegion, cuisineId, course, maxMinutes, maxKcal).size + diets.size +
            listOf(easyOnly, usePantry, highProtein, favouritesOnly).count { it }

    fun cleared() = RecipeFilters(query = query, sort = sort)
}

/**
 * One dish in the results: the best-matching version, plus every version of the dish
 * (regional styles, variations, the user's copies), main recipe first, for the version picker.
 */
data class DishResult(val shown: RankedRecipe, val versions: List<PreparedRecipe>) {
    val versionCount: Int get() = versions.size
}

data class CuisineCount(val id: String, val name: String, val count: Int)

data class RecipeSearchResult(
    val results: List<DishResult>,
    /** Recipes (not dishes) that match, and all recipes before filters. */
    val matchingRecipes: Int,
    val total: Int,
    /** Hidden because of the user's allergies, diets or dislikes (not because of filters they just chose). */
    val hiddenByPreferences: List<Exclusion>,
    /** Hidden because "only what I have" is on. */
    val hiddenMissingIngredients: Int,
    val favourites: Set<String>,
    val pantryCount: Int,
    /** Cuisines that have recipes, grouped by world region, for the cuisine picker. */
    val cuisines: List<Pair<WorldRegion, List<CuisineCount>>>,
)

/**
 * Discover's search: cheap filters first, then explainable ranking of what is left, then
 * sorting and grouping into dishes. Shared so Android and iOS behave identically.
 */
object RecipeSearch {
    /** Diets that can be filtered on; all are calculated from the ingredients. */
    val DIET_FILTERS = listOf(DietGoal.VEGETARIAN, DietGoal.VEGAN, DietGoal.HALAL, DietGoal.GLUTEN_FREE, DietGoal.DAIRY_FREE, DietGoal.NUT_FREE)

    fun search(
        prepared: List<PreparedRecipe>,
        f: RecipeFilters,
        usage: Map<String, RecipeUsage>,
        preferences: FoodPreferences,
        ranker: RecipeRanker,
        pantry: List<PantryItem>,
        today: LocalDate,
    ): RecipeSearchResult {
        val favourites = usage.filterValues { it.favourite }.keys
        val recentlyCooked = usage.entries.filter { it.value.lastCookedAtEpochMs != null }.sortedByDescending { it.value.lastCookedAtEpochMs }.map { it.key }
        val prefs = if (f.highProtein) preferences.copy(rankingGoals = preferences.rankingGoals + DietGoal.HIGHER_PROTEIN) else preferences
        val available = if (f.usePantry) PantryLogic.availableIngredientIds(pantry) else emptySet()
        val expiring = if (f.usePantry) PantryLogic.expiringSoon(pantry, today).mapNotNull { it.ingredientId }.toSet() else emptySet()
        val terms = f.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val candidates = prepared.filter { p -> matchesFilters(p, f, favourites) && terms.all { t -> matchesTerm(p, t) } }
        val ranking = ranker.rankPrepared(
            candidates,
            RecommendationContext(
                preferences = prefs,
                available = available,
                expiringSoon = expiring,
                onlyAvailable = f.usePantry && f.onlyAvailable && available.isNotEmpty(),
                maxMinutes = f.maxMinutes,
                recentlyCookedRecipeIds = recentlyCooked,
                favouriteRecipeIds = favourites,
                ratings = usage.mapNotNull { (id, u) -> u.rating?.let { id to it } }.toMap(),
            ),
        )
        val ranked = ranking.ranked
            .filter { r -> (!f.highProtein || isHighProtein(r.perServing)) && (f.maxKcal == null || r.perServing.energyKcal <= f.maxKcal) }
            // A search for a dish should list that dish first, ahead of recipes that merely contain the words.
            .let { list -> if (terms.isEmpty()) list else list.sortedByDescending { r -> terms.count { r.recipe.name.lowercase().contains(it) } } }
            .let { sortResults(it, f.sort, usage) }
        return RecipeSearchResult(
            results = dishes(ranked, prepared),
            matchingRecipes = ranked.size,
            total = prepared.size,
            hiddenByPreferences = ranking.excluded.filter { !it.reason.startsWith("Needs ") },
            hiddenMissingIngredients = ranking.excluded.count { it.reason.startsWith("Needs ") },
            favourites = favourites,
            pantryCount = available.size,
            cuisines = cuisineGroups(prepared.map { it.recipe }),
        )
    }

    /** Same rule the ranker uses for its "High protein" reason: ≥ 20 g and ≥ 25% of energy. */
    fun isHighProtein(perServing: Nutrients): Boolean {
        val kcal = perServing.energyKcal
        return kcal > 0 && perServing.proteinG >= 20 && perServing.proteinG * 4 / kcal >= 0.25
    }

    fun matchesFilters(p: PreparedRecipe, f: RecipeFilters, favourites: Set<String>): Boolean {
        val r = p.recipe
        return (f.cuisineId == null || r.cuisineId == f.cuisineId) &&
            (f.worldRegion == null || CuisineCatalog[r.cuisineId]?.worldRegion == f.worldRegion) &&
            (f.course == null || RecipeCourse.of(r) == f.course) &&
            p.analysis.diets.containsAll(f.diets) &&
            (f.maxMinutes == null || r.totalMinutes <= f.maxMinutes) &&
            (f.maxKcal == null || p.perServing.energyKcal <= f.maxKcal) &&
            (!f.easyOnly || r.difficulty == Difficulty.EASY) &&
            (!f.favouritesOnly || r.id in favourites)
    }

    /**
     * One search word against the name, cuisine, region, course, calculated diets, tags and ingredients.
     * "High protein" is calculated from the nutrition, like the diets, because recipes don't declare it.
     */
    fun matchesTerm(p: PreparedRecipe, term: String): Boolean {
        val r = p.recipe
        val cuisine = CuisineCatalog[r.cuisineId]
        val words = term.replace('-', ' ')
        return r.name.lowercase().contains(term) ||
            p.analysis.diets.any { it.label.lowercase().startsWith(term) } ||
            (("high protein".startsWith(words) || "protein".startsWith(words)) && isHighProtein(p.perServing)) ||
            cuisine?.name?.lowercase()?.contains(term) == true ||
            cuisine?.country?.lowercase()?.contains(term) == true ||
            cuisine?.worldRegion?.label?.lowercase()?.contains(term) == true ||
            r.region?.lowercase()?.contains(term) == true ||
            RecipeCourse.of(r).label.lowercase().contains(term) ||
            r.tags.any { it.contains(term) } ||
            r.ingredients.any { it.displayName.lowercase().contains(term) }
    }

    fun sortResults(list: List<RankedRecipe>, sort: RecipeSort, usage: Map<String, RecipeUsage>): List<RankedRecipe> = when (sort) {
        RecipeSort.BEST_MATCH -> list
        // Rated recipes first, best first; unrated keep their match order.
        RecipeSort.TOP_RATED -> list.sortedByDescending { usage[it.recipe.id]?.rating ?: 0 }
        RecipeSort.QUICKEST -> list.sortedBy { it.recipe.totalMinutes }
        RecipeSort.FEWEST_KCAL -> list.sortedBy { it.perServing.energyKcal }
        RecipeSort.MOST_PROTEIN -> list.sortedByDescending { it.perServing.proteinG }
    }

    /** Groups ranked recipes into dishes, keeping the order of each dish's best version. */
    fun dishes(ranked: List<RankedRecipe>, all: List<PreparedRecipe>): List<DishResult> {
        val byId = all.associateBy { it.recipe.id }
        val keyOf = { r: Recipe -> RecipeFamilies.key(r) { id -> byId[id]?.recipe } }
        val families = all.groupBy { keyOf(it.recipe) }
        val seen = HashSet<String>()
        return ranked.mapNotNull { r ->
            val key = keyOf(r.recipe)
            if (!seen.add(key)) return@mapNotNull null
            DishResult(r, families[key].orEmpty().sortedByDescending { it.recipe.id == key })
        }
    }

    /** Every version of the dish [recipeId] belongs to, main recipe first. */
    fun versionsOf(recipeId: String, all: List<PreparedRecipe>): List<PreparedRecipe> {
        val byId = all.associateBy { it.recipe.id }
        val self = byId[recipeId]?.recipe ?: return emptyList()
        val keyOf = { r: Recipe -> RecipeFamilies.key(r) { id -> byId[id]?.recipe } }
        val key = keyOf(self)
        return all.filter { keyOf(it.recipe) == key }.sortedByDescending { it.recipe.id == key }
    }

    fun cuisineGroups(recipes: List<Recipe>): List<Pair<WorldRegion, List<CuisineCount>>> =
        recipes.groupingBy { it.cuisineId }.eachCount()
            .mapNotNull { (id, n) -> CuisineCatalog[id]?.let { it to n } }
            .groupBy({ it.first.worldRegion }, { (cuisine, n) -> CuisineCount(cuisine.id, cuisine.name, n) })
            .toList()
            .sortedBy { it.first.ordinal }
            .map { (region, list) -> region to list.sortedBy { it.name } }
}
