package app.nouri.core.recipes

import app.nouri.core.ai.AiValidator
import app.nouri.core.ai.GeneratedRecipe
import app.nouri.core.ai.ImportedRecipe
import app.nouri.core.nutrition.NutritionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeImportTest {
    private val pasted = """
        Easy Butter Chicken
        By Sara K

        I first made this on a rainy evening and it has been a family favourite ever since, so here it is for you all to enjoy.

        Serves 4
        Prep time: 15 mins
        Cook time: 30 mins

        Ingredients
        For the marinade:
        • 600 g boneless chicken thighs, cut into chunks
        • 150 g plain yogurt
        • 1 tsp garam masala
        For the sauce:
        • 2 tbsp butter
        • 1 onion, finely chopped
        • 2–3 cloves garlic, minced
        • 1 (400 g) can chopped tomatoes
        • ½ cup heavy cream
        • Salt to taste

        Method
        1. Mix the chicken with the yogurt and garam masala and leave for 15 minutes.
        2. Melt the butter and cook the onion until soft and golden,
        stirring often.
        3. Add the garlic, then the tomatoes, and simmer for 10 minutes.
        4. Add the chicken and cook for 15 minutes until cooked through. Stir in the cream and season.

        Notes
        Leftovers keep for 3 days.
    """.trimIndent()

    @Test
    fun pastedRecipeIsStructuredWithoutInventingAnything() {
        val imported = RecipeTextImporter.parse(pasted)
        assertTrue(imported.isRecipe)
        val r = assertNotNull(imported.recipe)
        assertEquals("Easy Butter Chicken", r.name)
        assertEquals("Sara K", imported.sourceName)
        assertEquals(4, r.servings)
        assertEquals(15, r.prepMinutes)
        assertEquals(30, r.cookMinutes)
        assertEquals(9, r.ingredients.size, r.ingredients.joinToString { it.name })

        val chicken = r.ingredients.first()
        assertEquals("boneless chicken thighs", chicken.name)
        assertEquals(600.0, chicken.amount)
        assertEquals("g", chicken.unit)
        assertEquals("cut into chunks", chicken.preparation)
        assertEquals("marinade", chicken.group)

        val garlic = r.ingredients.first { "garlic" in it.name }
        assertEquals(2.5, garlic.amount)
        assertEquals("clove", garlic.unit)
        val tomatoes = r.ingredients.first { "tomatoes" in it.name }
        assertEquals(400.0, tomatoes.grams)
        assertEquals(0.5, r.ingredients.first { "cream" in it.name }.amount)
        val salt = r.ingredients.first { it.name.equals("Salt", ignoreCase = true) }
        assertNull(salt.amount)
        assertEquals("to taste", salt.preparation)
        assertEquals("sauce", salt.group)

        assertEquals(4, r.steps.size)
        assertTrue(r.steps[1].text.endsWith("stirring often."), "wrapped lines continue the same step")
        assertEquals(15.0, r.steps[3].timerMinutes)
        assertTrue(r.steps.none { "rainy evening" in it.text }, "the introduction is not a step")
        assertTrue(r.steps.none { "Leftovers" in it.text }, "notes are not steps")
        assertEquals("indian", r.cuisine)
    }

    @Test
    fun importedRecipeConvertsAndCalculates() {
        val imported = RecipeTextImporter.parse(pasted)
        val recipe = GeneratedRecipeConverter.convert(
            imported.recipe!!, "user:imported-1", 0L, origin = RecipeOrigin.IMPORTED,
            attribution = imported.sourceName?.let { "From $it" }, extraNotes = imported.notes,
        )
        assertEquals(RecipeOrigin.IMPORTED, recipe.origin)
        assertEquals("From Sara K", recipe.attribution)
        assertTrue("imported" in recipe.tags && "ai-generated" !in recipe.tags)
        val report = NutritionEngine().calculateRecipe(recipe)
        assertTrue(report.perServing.energyKcal in 250.0..900.0, "per serving ${report.perServing.energyKcal}")
    }

    @Test
    fun textWithoutARecipeIsRejectedWithAReason() {
        val r = RecipeTextImporter.parse("Had a great time at the market today. The weather was lovely!")
        assertFalse(r.isRecipe)
        assertTrue(r.notes.single().contains("ingredient"))
    }

    @Test
    fun missingDetailsBecomeNotesNotGuesses() {
        val r = RecipeTextImporter.parse(
            """
            Ingredients
            2 eggs
            butter
            Method
            Whisk the eggs and cook gently in butter, stirring, until just set.
            """.trimIndent(),
        )
        assertTrue(r.isRecipe)
        assertEquals(4, r.recipe!!.servings)
        assertTrue(r.notes.any { "Servings" in it })
        assertTrue(r.notes.any { "title" in it })
        assertTrue(r.notes.any { "No amount for: butter" in it })
        assertNull(r.sourceName)
    }

    @Test
    fun aiImportValidationClampsAndKeepsSource() {
        val raw = ImportedRecipe(
            isRecipe = true,
            recipe = GeneratedRecipe(name = "Dal", cuisine = "indian", servings = 0, prepMinutes = -5, cookMinutes = 40,
                ingredients = listOf(app.nouri.core.ai.GeneratedIngredient("red lentils", 1.0, "cup")), steps = listOf(app.nouri.core.ai.GeneratedStep("Boil the lentils."))),
            sourceName = "  Example Kitchen  ",
        )
        val v = AiValidator.importedRecipe(raw).getOrThrow().value
        assertEquals(1, v.recipe!!.servings)
        assertEquals(0, v.recipe!!.prepMinutes)
        assertEquals("Example Kitchen", v.sourceName)
        val notRecipe = AiValidator.importedRecipe(ImportedRecipe(isRecipe = false, recipe = raw.recipe)).getOrThrow().value
        assertNull(notRecipe.recipe)
        assertTrue(AiValidator.importedRecipe(raw.copy(recipe = raw.recipe!!.copy(steps = emptyList()))).isFailure)
    }
}
