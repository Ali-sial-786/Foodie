package app.nouri.core.recommend

import app.nouri.core.model.FoodPreferences
import app.nouri.core.ontology.DietGoal
import app.nouri.core.recipes.RecipeCatalog
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.WorldRegion
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeSearchTest {
    private val ranker = RecipeRanker()
    private val prepared = RecipeCatalog.all.map(ranker::prepare)
    private val today = LocalDate(2026, 10, 21)

    private fun matching(f: RecipeFilters, query: String = "") = prepared.filter { p ->
        RecipeSearch.matchesFilters(p, f, favourites = emptySet()) &&
            query.split(" ").filter { it.isNotBlank() }.all { RecipeSearch.matchesTerm(p, it) }
    }

    private fun search(f: RecipeFilters, usage: Map<String, RecipeUsage> = emptyMap()) =
        RecipeSearch.search(prepared, f, usage, FoodPreferences(), ranker, emptyList(), today)

    @Test
    fun dietFiltersUseCalculatedDiets() {
        val vegan = matching(RecipeFilters(diets = setOf(DietGoal.VEGAN)))
        assertTrue(vegan.isNotEmpty())
        assertTrue(vegan.all { DietGoal.VEGAN in it.analysis.diets && DietGoal.VEGETARIAN in it.analysis.diets })
        assertTrue(vegan.none { it.recipe.id == "butter_chicken" })
    }

    @Test
    fun searchCombinesWordsAcrossNameCuisineAndDiet() {
        assertTrue(matching(RecipeFilters(), "butter chicken").any { it.recipe.id == "butter_chicken" })
        assertTrue(matching(RecipeFilters(), "indian").any { it.recipe.id == "butter_chicken" })
        assertTrue(matching(RecipeFilters(), "vegan curry").all { DietGoal.VEGAN in it.analysis.diets })
        assertTrue(matching(RecipeFilters(), "high protein").all { RecipeSearch.isHighProtein(it.perServing) })
    }

    @Test
    fun regionCourseAndCalorieFiltersNarrowResults() {
        val asia = matching(RecipeFilters(worldRegion = WorldRegion.SOUTH_ASIA))
        assertTrue(asia.isNotEmpty() && asia.all { it.recipe.cuisineId in setOf("pakistani", "indian", "bangladeshi", "sri_lankan", "nepali", "maldivian") })
        assertTrue(matching(RecipeFilters(maxKcal = 450)).all { it.perServing.energyKcal <= 450 })
        assertTrue(matching(RecipeFilters(course = RecipeCourse.SOUP)).all { RecipeCourse.of(it.recipe) == RecipeCourse.SOUP })
    }

    @Test
    fun versionsAreGroupedUnderOneDish() {
        val result = search(RecipeFilters(query = "butter chicken"))
        val dish = result.results.first { d -> d.versions.any { it.recipe.id == "butter_chicken" } }
        assertEquals(setOf("butter_chicken", "butter_chicken_lighter"), dish.versions.map { it.recipe.id }.toSet())
        assertEquals("butter_chicken", dish.versions.first().recipe.id)
        assertEquals(1, result.results.count { d -> d.versions.any { it.recipe.id == "butter_chicken_lighter" } })
    }

    @Test
    fun yourRatingSortPutsRatedRecipesFirst() {
        val usage = mapOf("kheer" to RecipeUsage(rating = 5), "hummus" to RecipeUsage(rating = 3))
        val ids = search(RecipeFilters(sort = RecipeSort.TOP_RATED), usage).results.map { it.shown.recipe.id }
        assertEquals(listOf("kheer", "hummus"), ids.take(2))
    }

    @Test
    fun cuisinePickerCountsEveryRecipeOnce() {
        val groups = RecipeSearch.cuisineGroups(RecipeCatalog.all)
        assertEquals(RecipeCatalog.all.size, groups.sumOf { g -> g.second.sumOf { it.count } })
        assertEquals(groups.map { it.first.ordinal }.sorted(), groups.map { it.first.ordinal })
    }

    @Test
    fun clearingKeepsTheSearchAndSort() {
        val f = RecipeFilters(query = "dal", sort = RecipeSort.QUICKEST, course = RecipeCourse.MAIN, easyOnly = true, diets = setOf(DietGoal.HALAL))
        assertEquals(3, f.activeCount)
        assertEquals(RecipeFilters(query = "dal", sort = RecipeSort.QUICKEST), f.cleared())
    }
}
