package app.nouri.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.recommend.RecipeFilters
import app.nouri.core.recommend.RecipeSearch
import app.nouri.core.recommend.RecipeSearchResult
import app.nouri.core.recommend.RecipeUsage
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

/** Discover's filters are the shared [RecipeFilters]; the search itself is [RecipeSearch]. */
typealias DiscoverFilters = RecipeFilters

data class DiscoverState(val search: RecipeSearchResult, val usage: Map<String, RecipeUsage>)

class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    private val _filters = MutableStateFlow(DiscoverFilters())
    val filters: StateFlow<DiscoverFilters> = _filters.asStateFlow()

    val state: StateFlow<DiscoverState?> = combine(c.settings.settings, c.preparedRecipes, c.recipes.usage, c.pantry.pantry, _filters) { s, prepared, usage, pantry, f ->
        val search = RecipeSearch.search(
            prepared, f, usage, s.preferences, c.core(s.preferences.measurementSystem).ranker, pantry, SystemTime.today(),
        )
        DiscoverState(search, usage)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (DiscoverFilters) -> DiscoverFilters) = _filters.update(transform)
}
