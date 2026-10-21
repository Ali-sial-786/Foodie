package app.nouri.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ontology.DietGoal
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.WorldRegion
import app.nouri.core.recommend.Exclusion
import app.nouri.core.recommend.PreparedRecipe
import app.nouri.core.recommend.RankedRecipe
import app.nouri.core.recommend.RecommendationContext
import app.nouri.core.util.SystemTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class DiscoverFilters(
    val query: String = "",
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

    fun cleared() = DiscoverFilters(query = query)
}

data class CuisineCount(val id: String, val name: String, val count: Int)

data class DiscoverState(
    val results: List<RankedRecipe>,
    /** All recipes before filters, for "12 of 480". */
    val total: Int,
    /** Hidden because of the user's allergies, diets or dislikes (not because of filters they just tapped). */
    val hiddenByPreferences: List<Exclusion>,
    /** Hidden because "only what I have" is on. */
    val hiddenMissingIngredients: Int,
    val favourites: Set<String>,
    val pantryCount: Int,
    /** Cuisines that have recipes, grouped by world region, for the cuisine picker. */
    val cuisines: List<Pair<WorldRegion, List<CuisineCount>>>,
)

class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    private val _filters = MutableStateFlow(DiscoverFilters())
    val filters: StateFlow<DiscoverFilters> = _filters.asStateFlow()

    val state: StateFlow<DiscoverState?> = combine(c.settings.settings, c.preparedRecipes, c.recipes.usage, c.pantry.pantry, _filters) { s, prepared, usage, pantry, f ->
        val favourites = usage.filterValues { it.favourite }.keys
        val recentlyCooked = usage.entries.filter { it.value.lastCookedAtEpochMs != null }.sortedByDescending { it.value.lastCookedAtEpochMs }.map { it.key }
        val prefs = if (f.highProtein) s.preferences.copy(rankingGoals = s.preferences.rankingGoals + DietGoal.HIGHER_PROTEIN) else s.preferences
        val available = if (f.usePantry) PantryLogic.availableIngredientIds(pantry) else emptySet()
        val expiring = if (f.usePantry) PantryLogic.expiringSoon(pantry, SystemTime.today()).mapNotNull { it.ingredientId }.toSet() else emptySet()
        // Cheap filters first, so only matching recipes are scored.
        val terms = f.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val candidates = prepared.filter { p -> matchesFilters(p, f, favourites) && terms.all { t -> matchesTerm(p.recipe, t) } }
        val ranking = c.core(s.preferences.measurementSystem).ranker.rankPrepared(
            candidates,
            RecommendationContext(
                preferences = prefs,
                available = available,
                expiringSoon = expiring,
                onlyAvailable = f.usePantry && f.onlyAvailable && available.isNotEmpty(),
                maxMinutes = f.maxMinutes,
                recentlyCookedRecipeIds = recentlyCooked,
                favouriteRecipeIds = favourites,
            ),
        )
        val results = ranking.ranked
            .filter { r -> (!f.highProtein || isHighProtein(r)) && (f.maxKcal == null || r.perServing.energyKcal <= f.maxKcal) }
            // A search for a dish should list that dish first, ahead of recipes that merely contain the words.
            .let { list -> if (terms.isEmpty()) list else list.sortedByDescending { r -> terms.count { r.recipe.name.lowercase().contains(it) } } }
        val preferenceReasons = ranking.excluded.filter { !it.reason.startsWith("Needs ") }
        DiscoverState(
            results = results,
            total = prepared.size,
            hiddenByPreferences = preferenceReasons,
            hiddenMissingIngredients = ranking.excluded.count { it.reason.startsWith("Needs ") },
            favourites = favourites,
            pantryCount = available.size,
            cuisines = cuisineGroups(prepared.map { it.recipe }),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (DiscoverFilters) -> DiscoverFilters) = _filters.update(transform)

    companion object {
        /** Same rule the ranker uses for its "High protein" reason: ≥ 20 g and ≥ 25% of energy. */
        fun isHighProtein(r: RankedRecipe): Boolean {
            val kcal = r.perServing.energyKcal
            return kcal > 0 && r.perServing.proteinG >= 20 && r.perServing.proteinG * 4 / kcal >= 0.25
        }

        /** Diets that can be filtered on; all are calculated from the ingredients. */
        val DIET_FILTERS = listOf(DietGoal.VEGETARIAN, DietGoal.VEGAN, DietGoal.HALAL, DietGoal.GLUTEN_FREE, DietGoal.DAIRY_FREE, DietGoal.NUT_FREE)

        fun matchesFilters(p: PreparedRecipe, f: DiscoverFilters, favourites: Set<String>): Boolean {
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

        /** One search word against the name, cuisine, region, course, tags and ingredients. */
        fun matchesTerm(r: Recipe, term: String): Boolean {
            val cuisine = CuisineCatalog[r.cuisineId]
            return r.name.lowercase().contains(term) ||
                cuisine?.name?.lowercase()?.contains(term) == true ||
                cuisine?.country?.lowercase()?.contains(term) == true ||
                cuisine?.worldRegion?.label?.lowercase()?.contains(term) == true ||
                r.region?.lowercase()?.contains(term) == true ||
                RecipeCourse.of(r).label.lowercase().contains(term) ||
                r.tags.any { it.contains(term) } ||
                r.ingredients.any { it.displayName.lowercase().contains(term) }
        }

        fun cuisineGroups(recipes: List<Recipe>): List<Pair<WorldRegion, List<CuisineCount>>> =
            recipes.groupingBy { it.cuisineId }.eachCount()
                .mapNotNull { (id, n) -> CuisineCatalog[id]?.let { it to n } }
                .groupBy({ it.first.worldRegion }, { (cuisine, n) -> CuisineCount(cuisine.id, cuisine.name, n) })
                .toList()
                .sortedBy { it.first.ordinal }
                .map { (region, list) -> region to list.sortedBy { it.name } }
    }
}
