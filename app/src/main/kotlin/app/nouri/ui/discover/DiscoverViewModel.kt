package app.nouri.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ontology.DietGoal
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recommend.Exclusion
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
    val cuisineId: String? = null,
    val maxMinutes: Int? = null,
    val usePantry: Boolean = false,
    val onlyAvailable: Boolean = false,
    val highProtein: Boolean = false,
    val favouritesOnly: Boolean = false,
)

data class DiscoverState(
    val results: List<RankedRecipe>,
    /** Hidden because of the user's allergies, diets or dislikes (not because of filters they just tapped). */
    val hiddenByPreferences: List<Exclusion>,
    /** Hidden because "only what I have" is on. */
    val hiddenMissingIngredients: Int,
    val favourites: Set<String>,
    val pantryCount: Int,
    /** Cuisines that actually have recipes, for the filter chips. */
    val cuisines: List<Pair<String, String>>,
)

class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    private val _filters = MutableStateFlow(DiscoverFilters())
    val filters: StateFlow<DiscoverFilters> = _filters.asStateFlow()

    val state: StateFlow<DiscoverState?> = combine(c.settings.settings, c.recipes.all, c.recipes.usage, c.pantry.pantry, _filters) { s, recipes, usage, pantry, f ->
        val favourites = usage.filterValues { it.favourite }.keys
        val recentlyCooked = usage.entries.filter { it.value.lastCookedAtEpochMs != null }.sortedByDescending { it.value.lastCookedAtEpochMs }.map { it.key }
        val prefs = if (f.highProtein) s.preferences.copy(rankingGoals = s.preferences.rankingGoals + DietGoal.HIGHER_PROTEIN) else s.preferences
        val available = if (f.usePantry) PantryLogic.availableIngredientIds(pantry) else emptySet()
        val expiring = if (f.usePantry) PantryLogic.expiringSoon(pantry, SystemTime.today()).mapNotNull { it.ingredientId }.toSet() else emptySet()
        val ranking = c.core(s.preferences.measurementSystem).ranker.rank(
            recipes,
            RecommendationContext(
                preferences = prefs,
                available = available,
                expiringSoon = expiring,
                onlyAvailable = f.usePantry && f.onlyAvailable && available.isNotEmpty(),
                maxMinutes = f.maxMinutes,
                cuisineFilter = f.cuisineId,
                recentlyCookedRecipeIds = recentlyCooked,
                favouriteRecipeIds = favourites,
            ),
        )
        val q = f.query.trim().lowercase()
        val results = ranking.ranked.filter { r ->
            val recipe = r.recipe
            val cuisine = CuisineCatalog[recipe.cuisineId]
            (q.isEmpty() || recipe.name.lowercase().contains(q) || cuisine?.name?.lowercase()?.contains(q) == true ||
                recipe.region?.lowercase()?.contains(q) == true || recipe.tags.any { it.lowercase().contains(q) } ||
                recipe.ingredients.any { it.displayName.lowercase().contains(q) }) &&
                (f.maxMinutes == null || recipe.totalMinutes <= f.maxMinutes) &&
                (!f.favouritesOnly || recipe.id in favourites) &&
                (!f.highProtein || isHighProtein(r))
        }
        val preferenceReasons = ranking.excluded.filter { it.reason != "Different cuisine" && !it.reason.startsWith("Needs ") }
        DiscoverState(
            results = results,
            hiddenByPreferences = preferenceReasons,
            hiddenMissingIngredients = ranking.excluded.count { it.reason.startsWith("Needs ") },
            favourites = favourites,
            pantryCount = available.size,
            cuisines = recipes.map { it.cuisineId }.distinct().mapNotNull { id -> CuisineCatalog[id]?.let { id to it.name } }.sortedBy { it.second },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (DiscoverFilters) -> DiscoverFilters) = _filters.update(transform)

    companion object {
        /** Same rule the ranker uses for its "High protein" reason: ≥ 20 g and ≥ 25% of energy. */
        fun isHighProtein(r: RankedRecipe): Boolean {
            val kcal = r.perServing.energyKcal
            return kcal > 0 && r.perServing.proteinG >= 20 && r.perServing.proteinG * 4 / kcal >= 0.25
        }
    }
}
