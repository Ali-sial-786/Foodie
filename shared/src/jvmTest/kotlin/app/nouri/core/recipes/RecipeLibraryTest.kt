package app.nouri.core.recipes

import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientCategory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards every recipe in the JSON files in `data/recipes/` (the bundled library): it must decode, use
 * known cuisines and ingredients, calculate fully, and have a method detailed enough to
 * cook from. `scripts/build_recipe_library.py` applies the same rules before bundling.
 */
class RecipeLibraryTest {
    private val engine = NutritionEngine()

    private val files: List<File> by lazy {
        val root = System.getProperty("nouri.repoRoot")?.let(::File) ?: File(System.getProperty("user.dir")).absoluteFile.parentFile
        val dir = File(root, "data/recipes").takeIf { it.isDirectory } ?: fail("data/recipes not found under $root")
        dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    }

    private val library: List<Pair<String, Recipe>> by lazy {
        files.flatMap { f ->
            val recipes = runCatching { RecipeLibrary.decode(f.readText()) }.getOrElse { fail("${f.name}: ${it.message}") }
            recipes.map { f.name to it }
        }
    }

    @Test
    fun idsAndNamesAreUniqueAcrossLibraryAndCatalogue() {
        val all = RecipeCatalog.all.map { "catalogue" to it } + library
        val byId = all.groupBy { it.second.id }.filterValues { it.size > 1 }
        assertTrue(byId.isEmpty(), "duplicate ids: ${byId.mapValues { v -> v.value.map { it.first } }}")
        val byName = all.groupBy { normalizedName(it.second.name) }.filterValues { it.size > 1 }
        assertTrue(byName.isEmpty(), "duplicate dishes: ${byName.mapValues { v -> v.value.map { "${it.first}:${it.second.id}" } }}")
    }

    @Test
    fun everyRecipeIsWellFormed() {
        val problems = library.flatMap { (file, r) -> structuralProblems(r).map { "$file ${r.id}: $it" } }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyRecipeCalculatesFullyWithPlausibleEnergy() {
        val problems = library.flatMap { (file, r) ->
            val report = engine.calculateRecipe(r)
            buildList {
                report.lines.filter { it.status == LineStatus.UNRESOLVED }.forEach { add("${it.line.displayName} (${it.line.ingredientId}) not calculated: ${it.notes}") }
                if (report.completeness < 0.95) add("completeness ${report.completeness}")
                val kcal = report.perServing.energyKcal
                val range = kcalRange(RecipeCourse.of(r))
                if (kcal !in range) add("${kcal.toInt()} kcal per serving is outside $range for ${RecipeCourse.of(r).label}")
            }.map { "$file ${r.id}: $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun variationsPointAtExistingRecipes() {
        val ids = RecipeCatalog.all.map { it.id }.toSet() + library.map { it.second.id }
        for ((file, r) in library) {
            r.variantOf?.let {
                assertTrue(it in ids, "$file ${r.id}: variation of unknown recipe $it")
                assertTrue(!r.variantSummary.isNullOrBlank(), "$file ${r.id}: variation needs a summary")
            }
        }
    }

    companion object {
        /** Diet labels are calculated from the ingredients, never declared. */
        val COMPUTED_TAGS = setOf("vegetarian", "vegan", "gluten-free", "dairy-free", "nut-free", "halal", "high-protein", "lower-calorie")

        /** Already cooked, cured or canned: no doneness advice needed. */
        val READY_TO_EAT = setOf("tuna_canned", "sardines", "anchovies", "smoked_salmon", "dried_shrimp", "ham")

        fun normalizedName(name: String): String =
            name.lowercase().replace(Regex("\\([^)]*\\)"), "").replace(Regex("[^a-z0-9]"), "")

        fun detailed(course: RecipeCourse) = course in setOf(RecipeCourse.MAIN, RecipeCourse.SOUP, RecipeCourse.BREAKFAST, RecipeCourse.BREAD, RecipeCourse.DESSERT)

        fun kcalRange(course: RecipeCourse): ClosedFloatingPointRange<Double> = when (course) {
            RecipeCourse.MAIN -> 200.0..1400.0
            RecipeCourse.BREAKFAST -> 150.0..1000.0
            RecipeCourse.SOUP -> 60.0..900.0
            RecipeCourse.SALAD -> 40.0..900.0
            RecipeCourse.SIDE -> 30.0..800.0
            RecipeCourse.SNACK -> 40.0..800.0
            RecipeCourse.DESSERT -> 80.0..900.0
            RecipeCourse.BREAD -> 80.0..800.0
            RecipeCourse.DRINK, RecipeCourse.SAUCE -> 5.0..600.0
        }

        fun structuralProblems(r: Recipe): List<String> = buildList {
            val course = RecipeCourse.of(r)
            if (CuisineCatalog[r.cuisineId] == null) add("unknown cuisine ${r.cuisineId}")
            if (r.tags.count { RecipeCourse.fromTag(it) != null } != 1) add("needs exactly one course")
            (r.tags intersect COMPUTED_TAGS).takeIf { it.isNotEmpty() }?.let { add("tags $it are calculated, not declared") }
            r.tags.filterNot { it.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")) }.takeIf { it.isNotEmpty() }?.let { add("tags must be lowercase-kebab: $it") }
            if (r.servings !in 1..24) add("servings ${r.servings}")
            if (r.prepMinutes !in 0..240 || r.cookMinutes !in 0..720 || r.totalMinutes < 5) add("times ${r.prepMinutes}+${r.cookMinutes}")
            if (r.description.length !in 40..400) add("description should be 40–400 characters")
            if (r.equipment.isEmpty()) add("no equipment")
            if (r.storage.isNullOrBlank()) add("no storage advice")
            val minSteps = if (detailed(course)) 6 else 4
            val minChars = if (detailed(course)) 700 else 350
            if (r.steps.size < minSteps) add("${r.steps.size} steps; at least $minSteps for ${course.label}")
            r.steps.forEachIndexed { i, s -> if (s.text.length !in 40..600) add("step ${i + 1} should be 40–600 characters") }
            val method = r.steps.sumOf { it.text.length }
            if (method < minChars) add("method is $method characters; at least $minChars for ${course.label}")
            if (r.ingredients.isEmpty()) add("no ingredients")
            var needsSafety = false
            for (line in r.ingredients) {
                val ing = IngredientCatalog[line.ingredientId ?: ""]
                if (ing == null) { add("unknown ingredient ${line.ingredientId}"); continue }
                if (ing.halal == HalalStatus.NOT_PERMISSIBLE && !line.optional) add("${line.displayName} must be optional (halal / alcohol-free cooks)")
                if (!line.optional && !line.measuredCooked && line.ingredientId !in READY_TO_EAT &&
                    ing.category in setOf(IngredientCategory.POULTRY, IngredientCategory.RED_MEAT, IngredientCategory.PORK, IngredientCategory.SEAFOOD)
                ) needsSafety = true
            }
            if (needsSafety && r.foodSafety.isEmpty()) add("raw meat, poultry or seafood needs a food-safety note")
        }
    }
}
