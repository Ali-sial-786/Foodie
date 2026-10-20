package app.nouri.core.recipes

import app.nouri.core.ai.GeneratedIngredient
import app.nouri.core.ai.GeneratedRecipe
import app.nouri.core.ai.GeneratedStep
import app.nouri.core.model.FoodPreferences
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recommend.RecipeRanker
import app.nouri.core.recommend.RecommendationContext
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeEngineTest {
    private val engine = NutritionEngine()

    @Test
    fun catalogRecipesAreCompleteAndCalculable() {
        val ids = RecipeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (r in RecipeCatalog.all) {
            for (line in r.ingredients) assertNotNull(line.ingredientId?.let(IngredientCatalog::get), "${r.id}: unknown ${line.ingredientId}")
            val report = engine.calculateRecipe(r)
            val unresolved = report.lines.filter { it.status == LineStatus.UNRESOLVED }
            assertTrue(unresolved.isEmpty(), "${r.id}: ${unresolved.map { it.line.displayName to it.notes }}")
            assertTrue(report.perServing.energyKcal in 80.0..1200.0, "${r.id}: ${report.perServing.energyKcal} kcal/serving")
            assertTrue(r.steps.isNotEmpty())
            if (r.origin == RecipeOrigin.EDITORIAL_VARIATION) {
                assertNotNull(r.variantOf, "${r.id} must link its traditional version")
                assertNotNull(r.variantSummary)
            }
            if (r.ingredients.any { IngredientCatalog[it.ingredientId!!]?.category in setOf(app.nouri.core.ontology.IngredientCategory.POULTRY) }) {
                assertTrue(r.foodSafety.any { "74 °C" in it }, "${r.id} needs a poultry safety note")
            }
        }
        val variations = RecipeCatalog.all.filter { it.variantOf != null }.mapNotNull { it.variantOf }
        variations.forEach { assertNotNull(RecipeCatalog[it], "variation base $it missing") }
    }

    @Test
    fun traditionalAndLighterKarahiAreSeparateAndLighterHasLessEnergy() {
        val traditional = RecipeCatalog["chicken_karahi"]!!
        val lighter = RecipeCatalog["chicken_karahi_lighter"]!!
        assertEquals(RecipeOrigin.EDITORIAL_TRADITIONAL, traditional.origin)
        assertEquals("chicken_karahi", lighter.variantOf)
        assertTrue(engine.calculateRecipe(lighter).perServing.energyKcal < engine.calculateRecipe(traditional).perServing.energyKcal)
    }

    @Test
    fun analysisIsIngredientLevel() {
        val dal = RecipeAnalyzer.analyze(RecipeCatalog["masoor_dal_tadka"]!!)
        assertTrue(DietGoal.VEGETARIAN in dal.diets)
        assertFalse(DietGoal.VEGAN in dal.diets, "ghee makes it non-vegan")
        assertEquals(HalalStatus.PERMISSIBLE, dal.halal)
        val karahi = RecipeAnalyzer.analyze(RecipeCatalog["chicken_karahi"]!!)
        assertEquals(HalalStatus.REQUIRES_HALAL_SOURCE, karahi.halal)
        assertTrue(karahi.halalNotes.any { "halal-certified" in it })
        assertTrue(DietGoal.GLUTEN_FREE in karahi.diets)
        val pasta = RecipeAnalyzer.analyze(RecipeCatalog["pasta_pomodoro"]!!)
        assertTrue(Allergen.GLUTEN in pasta.allergens)
        assertTrue(Allergen.MILK in pasta.optionalAllergens)
        assertTrue(DietGoal.VEGAN in pasta.diets, "parmesan is optional")
        assertEquals(HalalStatus.REQUIRES_HALAL_SOURCE, RecipeAnalyzer.analyze(RecipeCatalog["chicken_adobo"]!!).halal)
    }

    @Test
    fun scalingIsNotNaiveMultiplication() {
        val biryani = RecipeCatalog["chicken_biryani"]!!
        val doubled = RecipeScaler.scale(biryani, 12)
        assertEquals(2.0, doubled.factor)
        val rice = doubled.recipe.ingredients.first { it.ingredientId == "rice_white" }
        assertEquals(1000.0, rice.quantity.amount)
        val masala = doubled.recipe.ingredients.first { it.ingredientId == "garam_masala" }
        assertTrue(masala.quantity.amount < 4.0, "spices scale less than 2×")
        assertTrue(doubled.notes.any { "larger pot" in it })
        assertEquals(biryani.steps, doubled.recipe.steps, "steps/times are not multiplied")
        val a = engine.calculateRecipe(biryani).perServing.energyKcal
        val b = engine.calculateRecipe(doubled.recipe).perServing.energyKcal
        assertTrue(abs(a - b) / a < 0.02, "per-serving energy stays ~constant ($a vs $b)")

        val shakshuka = RecipeScaler.scale(RecipeCatalog["shakshuka"]!!, 3)
        val eggs = shakshuka.recipe.ingredients.first { it.ingredientId == "egg" }
        assertEquals(6.0, eggs.quantity.amount, "4 eggs × 1.5 = 6")
        val halved = RecipeScaler.scale(RecipeCatalog["frittata_spinach_cheese"]!!, 1)
        assertEquals(2.0, halved.recipe.ingredients.first { it.ingredientId == "egg" }.quantity.amount)
        assertTrue(halved.notes.any { "smaller pan" in it })
    }

    private fun abs(x: Double) = kotlin.math.abs(x)

    @Test
    fun vegetarianVariationSwapsMeatAndKeepsOriginal() {
        val original = RecipeCatalog["aloo_murgh"]!!
        val v = RecipeTransformer.transform(original, RecipeAdjustment.VEGETARIAN)
        assertTrue(v.changedAnything)
        assertEquals("aloo_murgh", v.recipe.variantOf)
        assertTrue(v.recipe.name.contains("vegetarian variation"))
        assertTrue(DietGoal.VEGETARIAN in RecipeAnalyzer.analyze(v.recipe).diets)
        assertTrue(v.recipe.steps.any { "tofu" in it.text.lowercase() }, "steps mention the substitute")
        assertEquals("chicken_breast", original.ingredients.first().ingredientId, "original untouched")
    }

    @Test
    fun lowerCalorieVariationReducesEnergy() {
        val original = RecipeCatalog["chicken_biryani"]!!
        val v = RecipeTransformer.transform(original, RecipeAdjustment.LOWER_CALORIE)
        assertTrue(engine.calculateRecipe(v.recipe).perServing.energyKcal < engine.calculateRecipe(original).perServing.energyKcal)
        assertTrue(v.changes.any { "Half the oil" in it || "half the oil" in it.lowercase() }, v.changes.toString())
    }

    @Test
    fun halalVariationReplacesWineAndFlagsMeat() {
        val r = Recipe(
            id = "test_ragu", name = "Test ragù", cuisineId = "italian", servings = 4,
            ingredients = listOf(
                RecipeIngredient("Minced beef", "beef_ground", Quantity(500.0, QuantityUnit.GRAM)),
                RecipeIngredient("Red wine", "white_wine", Quantity(100.0, QuantityUnit.MILLILITRE)),
                RecipeIngredient("Tomatoes", "tomato_canned", Quantity(1.0, QuantityUnit.CAN)),
            ),
            steps = listOf(RecipeStep("Brown the minced beef, add the red wine and tomatoes.")),
            prepMinutes = 10, cookMinutes = 60, origin = RecipeOrigin.USER,
        )
        val v = RecipeTransformer.transform(r, RecipeAdjustment.HALAL)
        assertTrue(v.recipe.ingredients.none { it.ingredientId == "white_wine" })
        assertTrue(v.warnings.any { "halal-certified" in it })
        assertTrue(RecipeAnalyzer.analyze(v.recipe).halal != HalalStatus.NOT_PERMISSIBLE)
    }

    @Test
    fun milderLeavesOutGreenChillies() {
        val v = RecipeTransformer.transform(RecipeCatalog["chicken_karahi"]!!, RecipeAdjustment.MILDER)
        assertTrue(v.recipe.ingredients.none { it.ingredientId == "green_chili" })
        assertTrue(v.recipe.ingredients.first { it.ingredientId == "chili_powder" }.quantity.amount < 1.0)
    }

    @Test
    fun pantryMatchingAndShoppingList() {
        val recipe = RecipeCatalog["aloo_murgh"]!!
        val have = setOf("chicken_thigh", "potato", "onion", "tomato", "yogurt", "garlic", "rice_white")
        val match = PantryMatcher.match(recipe, have)
        assertEquals(Availability.SUBSTITUTE_AVAILABLE, match.items.first { it.ingredientId == "chicken_breast" }.status)
        assertEquals(listOf("ginger"), match.missing.map { it.ingredientId })
        assertTrue(match.coverage > 0.8)
        var n = 0
        val list = ShoppingListBuilder.fromMissing(recipe, match, emptyList(), 0) { "g${n++}" }
        assertEquals("Ginger", list.single().name)
        val again = ShoppingListBuilder.fromMissing(recipe, match, list, 0) { "g${n++}" }
        assertEquals(1, again.size, "duplicates merge")
        assertEquals(2.0, again.single().quantity!!.amount)
    }

    @Test
    fun rankerFiltersAllergiesAndExplainsChoices() {
        val ranker = RecipeRanker(engine)
        val ctx = RecommendationContext(
            preferences = FoodPreferences(favouriteCuisines = listOf("pakistani"), allergies = setOf(Allergen.GLUTEN), requiredDiets = setOf(DietGoal.HALAL)),
            available = setOf("chicken_breast", "potato", "onion", "tomato", "yogurt", "garlic", "rice_white", "ginger"),
            remainingKcal = 650.0,
        )
        val result = ranker.rank(RecipeCatalog.all, ctx)
        assertTrue(result.ranked.none { Allergen.GLUTEN in it.analysis.allergens })
        assertTrue(result.excluded.any { it.recipe.id == "pasta_pomodoro" })
        val top = result.ranked.take(3).map { it.recipe.id }
        assertTrue(top.any { it in setOf("aloo_murgh", "chicken_karahi_lighter", "chicken_tikka_bowl", "chicken_karahi") }, top.toString())
        val first = result.ranked.first()
        assertTrue(first.reasons.any { "ingredients you have" in it })
        assertTrue(first.reasons.any { "favourite" in it })
    }

    @Test
    fun onlyAvailableRequiresEverything() {
        val ranker = RecipeRanker(engine)
        val ctx = RecommendationContext(available = setOf("egg", "spinach", "cheddar", "rice_white", "onion", "garlic"), onlyAvailable = true)
        val ids = ranker.rank(RecipeCatalog.all, ctx).ranked.map { it.recipe.id }
        assertTrue("spinach_egg_cheese_rice" in ids, ids.toString())
        assertTrue("frittata_spinach_cheese" in ids, ids.toString())
        assertTrue("chicken_biryani" !in ids)
    }

    @Test
    fun generatedRecipesBecomeLabelledRecipes() {
        val g = GeneratedRecipe(
            name = "Quick spinach dal", cuisine = "Pakistani", servings = 2, prepMinutes = 5, cookMinutes = 20,
            ingredients = listOf(
                GeneratedIngredient("red lentils", 150.0, "g"),
                GeneratedIngredient("spinach", 2.0, "cups"),
                GeneratedIngredient("salt", null, null),
            ),
            steps = listOf(GeneratedStep("Simmer lentils.", 15.0)),
            tags = listOf("vegetarian"),
        )
        val r = GeneratedRecipeConverter.convert(g, "ai1", 0)
        assertEquals(RecipeOrigin.AI_GENERATED, r.origin)
        assertEquals("pakistani", r.cuisineId)
        assertEquals("lentils_red", r.ingredients[0].ingredientId)
        assertEquals(QuantityUnit.CUP, r.ingredients[1].quantity.unit)
        assertEquals("to taste", r.ingredients[2].preparation)
        assertEquals(900, r.steps.single().timerSeconds)
        assertTrue(DietGoal.VEGETARIAN in r.declaredDiets)
        assertTrue(engine.calculateRecipe(r).completeness == 1.0)
    }

    @Test
    fun pantryExpiryAndDeductionNeedConfirmation() {
        val today = LocalDate(2026, 10, 20)
        val expiry = PantryLogic.defaultExpiry("spinach", StorageLocation.FRIDGE, today)
        assertEquals(LocalDate(2026, 10, 25), expiry)
        val pantry = listOf(
            PantryItem("p1", "Potatoes", "potato", Quantity(5.0, QuantityUnit.PIECE), addedOn = today),
            PantryItem("p2", "Chicken", "chicken_breast", Quantity(1000.0, QuantityUnit.GRAM), addedOn = today, expiresOn = today),
            PantryItem("p3", "Salt", "salt", addedOn = today),
        )
        assertEquals(listOf("p2"), PantryLogic.expiringSoon(pantry, today).map { it.id })
        val proposals = PantryLogic.proposeDeduction(RecipeCatalog["aloo_murgh"]!!, pantry)
        assertEquals(setOf("p1", "p2"), proposals.map { it.pantryItemId }.toSet(), "staples like salt are not deducted")
        val chicken = proposals.first { it.pantryItemId == "p2" }
        assertEquals(Quantity(400.0, QuantityUnit.GRAM), chicken.remaining)
        val updated = PantryLogic.applyDeductions(pantry, proposals.filter { it.pantryItemId == "p1" })
        assertEquals(2.0, updated.first { it.id == "p1" }.quantity!!.amount)
        assertEquals(1000.0, updated.first { it.id == "p2" }.quantity!!.amount, "unaccepted proposals change nothing")
    }
}
