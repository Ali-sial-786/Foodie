package app.nouri.ui.discover

import app.nouri.core.ontology.DietGoal
import app.nouri.core.recipes.RecipeCatalog
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.WorldRegion
import app.nouri.core.recommend.RecipeRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverFiltersTest {
    private val prepared = RecipeCatalog.all.map(RecipeRanker()::prepare)

    private fun matching(f: DiscoverFilters, query: String = "") = prepared.filter { p ->
        DiscoverViewModel.matchesFilters(p, f, favourites = emptySet()) &&
            query.split(" ").filter { it.isNotBlank() }.all { DiscoverViewModel.matchesTerm(p, it) }
    }

    @Test
    fun dietFiltersUseCalculatedDiets() {
        val vegan = matching(DiscoverFilters(diets = setOf(DietGoal.VEGAN)))
        assertTrue(vegan.isNotEmpty())
        assertTrue(vegan.all { DietGoal.VEGAN in it.analysis.diets && DietGoal.VEGETARIAN in it.analysis.diets })
        assertTrue(vegan.none { it.recipe.id == "butter_chicken" })
    }

    @Test
    fun searchCombinesWordsAcrossNameCuisineAndDiet() {
        assertTrue(matching(DiscoverFilters(), "butter chicken").any { it.recipe.id == "butter_chicken" })
        assertTrue(matching(DiscoverFilters(), "indian").any { it.recipe.id == "butter_chicken" })
        val veganCurries = matching(DiscoverFilters(), "vegan curry")
        assertTrue(veganCurries.all { DietGoal.VEGAN in it.analysis.diets })
    }

    @Test
    fun regionCourseAndCalorieFiltersNarrowResults() {
        val asia = matching(DiscoverFilters(worldRegion = WorldRegion.SOUTH_ASIA))
        assertTrue(asia.isNotEmpty() && asia.all { it.recipe.cuisineId in setOf("pakistani", "indian", "bangladeshi", "sri_lankan", "nepali", "maldivian") })
        val light = matching(DiscoverFilters(maxKcal = 450))
        assertTrue(light.all { it.perServing.energyKcal <= 450 })
        val mains = matching(DiscoverFilters(course = RecipeCourse.MAIN))
        assertTrue(mains.all { RecipeCourse.of(it.recipe) == RecipeCourse.MAIN })
    }

    @Test
    fun cuisinePickerCountsEveryRecipeOnce() {
        val groups = DiscoverViewModel.cuisineGroups(RecipeCatalog.all)
        assertEquals(RecipeCatalog.all.size, groups.sumOf { g -> g.second.sumOf { it.count } })
        assertEquals(groups.map { it.first.ordinal }.sorted(), groups.map { it.first.ordinal })
    }

    @Test
    fun clearingKeepsTheSearch() {
        val f = DiscoverFilters(query = "dal", course = RecipeCourse.MAIN, easyOnly = true, diets = setOf(DietGoal.HALAL))
        assertEquals(3, f.activeCount)
        assertEquals(DiscoverFilters(query = "dal"), f.cleared())
    }
}
