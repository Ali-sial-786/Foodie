package app.nouri.core.recipes

import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Guards the whole built-in catalogue, so a new recipe cannot silently ship with missing or implausible nutrition. */
class RecipeCatalogCoverageTest {
    private val engine = NutritionEngine()

    @Test
    fun idsAreUniqueAndCuisinesExist() {
        val ids = RecipeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        for (r in RecipeCatalog.all) {
            assertNotNull(CuisineCatalog[r.cuisineId], "${r.id}: unknown cuisine ${r.cuisineId}")
        }
    }

    @Test
    fun everyRecipeIsFullyCalculableWithPlausibleEnergy() {
        for (r in RecipeCatalog.all) {
            for (line in r.ingredients) {
                assertNotNull(line.ingredientId?.let(IngredientCatalog::get), "${r.id}: unknown ingredient ${line.ingredientId}")
            }
            val report = engine.calculateRecipe(r)
            assertTrue(report.completeness >= 0.9, "${r.id}: completeness ${report.completeness}")
            val dropped = report.warnings.filter { it.startsWith("Not included in totals") }
            assertTrue(dropped.isEmpty(), "${r.id}: $dropped")
            assertTrue(report.lines.none { it.status == LineStatus.UNRESOLVED }, "${r.id}: unresolved lines")
            val kcal = report.perServing.energyKcal
            assertTrue(kcal in 80.0..1500.0, "${r.id}: $kcal kcal/serving")
        }
    }

    @Test
    fun recipesAreCookableAndVariationsAreLinked() {
        for (r in RecipeCatalog.all) {
            assertTrue(r.steps.isNotEmpty(), "${r.id}: no steps")
            assertTrue(r.equipment.isNotEmpty(), "${r.id}: no equipment")
            assertTrue(r.description.isNotBlank(), "${r.id}: no description")
            when (r.origin) {
                RecipeOrigin.EDITORIAL_VARIATION -> {
                    val base = assertNotNull(r.variantOf, "${r.id} must name its traditional version")
                    assertNotNull(RecipeCatalog[base], "${r.id}: variation base $base missing")
                    assertTrue(!r.variantSummary.isNullOrBlank(), "${r.id}: variation needs a summary")
                }
                else -> assertEquals(null, r.variantOf, "${r.id}: only variations link a base recipe")
            }
        }
    }

    @Test
    fun popularDishesArePresent() {
        val expected = listOf(
            "butter_chicken", "chicken_tikka_masala", "pad_thai", "spaghetti_bolognese", "lasagne",
            "hummus", "falafel", "chicken_shawarma", "guacamole", "jerk_chicken", "pho_ga", "beef_nihari",
        )
        expected.forEach { assertNotNull(RecipeCatalog[it], "missing $it") }
    }

    @Test
    fun nonHalalIngredientsAreOnlyOptional() {
        // Mirin and wine stay optional so halal and alcohol-free cooks can make every built-in recipe as written.
        for (r in RecipeCatalog.all) {
            for (line in r.ingredients) {
                if (IngredientCatalog[line.ingredientId!!]?.halal == HalalStatus.NOT_PERMISSIBLE) {
                    assertTrue(line.optional, "${r.id}: ${line.displayName} should be optional")
                }
            }
        }
    }
}
