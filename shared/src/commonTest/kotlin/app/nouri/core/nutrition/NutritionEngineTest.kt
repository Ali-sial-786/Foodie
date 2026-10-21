package app.nouri.core.nutrition

import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.Nutrients
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeStep
import app.nouri.core.units.MeasurementSystem
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NutritionEngineTest {
    private val engine = NutritionEngine()

    private fun line(name: String, amount: Double, unit: QuantityUnit, id: String? = null, consumed: Double = 1.0, optional: Boolean = false, cooked: Boolean = false) =
        RecipeIngredient(displayName = name, ingredientId = id, quantity = Quantity(amount, unit), consumedFraction = consumed, optional = optional, measuredCooked = cooked)

    private fun recipe(vararg lines: RecipeIngredient, servings: Int = 1, cookedYield: Double? = null) = Recipe(
        id = "test", name = "Test", cuisineId = "international", servings = servings,
        ingredients = lines.toList(), steps = listOf(RecipeStep("Cook.")), prepMinutes = 5, cookMinutes = 10,
        origin = RecipeOrigin.USER, cookedYieldGrams = cookedYield,
    )

    @Test
    fun usdaValuesComeThroughExactly() {
        val rice = CoreFoods.repository.get("usda:20045")!!
        assertEquals(130.0, rice.per100g.energyKcal)
        assertEquals(2.69, rice.per100g.proteinG)
        assertEquals(28.17, rice.per100g.carbsG)
        val egg = CoreFoods.repository.get("usda:01123")!!
        assertEquals(0.12, egg.refuseFraction, 1e-9)
    }

    @Test
    fun gramsScaleLinearly() {
        val r = engine.calculateLine(line("chicken breast", 200.0, QuantityUnit.GRAM, cooked = true))
        assertEquals(LineStatus.RESOLVED, r.status)
        assertEquals(330.0, r.nutrients.energyKcal, 1e-9) // 165 kcal/100 g roasted
        assertEquals(62.04, r.nutrients.proteinG, 1e-9)
    }

    @Test
    fun eggsUseLargeEggWeight() {
        val r = engine.calculateLine(line("eggs", 2.0, QuantityUnit.PIECE))
        assertEquals(88.0, r.gramsEaten!!, 1e-9) // 2 medium eggs at 44 g edible
        assertEquals(143.0 * 0.88, r.nutrients.energyKcal, 1e-9)
    }

    @Test
    fun boneInChickenExcludesBone() {
        val r = engine.calculateLine(line("chicken", 1000.0, QuantityUnit.GRAM))
        assertEquals(1000.0, r.gramsAsWritten)
        assertEquals(720.0, r.gramsEaten!!, 1e-9)
        assertTrue(r.notes.any { "bone" in it })
    }

    @Test
    fun fryingOilCountsOnlyAbsorbedPart() {
        val r = engine.calculateLine(line("oil", 500.0, QuantityUnit.MILLILITRE, consumed = 0.1))
        assertEquals(500 * 0.92 * 0.1, r.gramsEaten!!, 1e-9)
        assertEquals(500 * 0.92 * 0.1 * 8.84, r.nutrients.energyKcal, 1e-6)
    }

    @Test
    fun spoonsOfOilUseIngredientMeasure() {
        val r = engine.calculateLine(line("olive oil", 1.0, QuantityUnit.TABLESPOON))
        // Ontology records 13.5 g per US tablespoon; metric tablespoons are 15 ml.
        assertEquals(13.5 * 15.0 / 14.78676478125, r.gramsEaten!!, 1e-6)
        assertEquals(LineStatus.RESOLVED, r.status)
    }

    @Test
    fun unknownIngredientIsReportedNotGuessed() {
        val report = engine.calculateRecipe(
            recipe(line("rice", 200.0, QuantityUnit.GRAM), line("zorblax powder", 50.0, QuantityUnit.GRAM)),
        )
        assertEquals(LineStatus.UNRESOLVED, report.lines[1].status)
        assertEquals(0.5, report.completeness)
        assertEquals(ConfidenceLevel.LOW, report.confidence)
        assertTrue(report.warnings.any { "zorblax" in it })
        assertEquals(730.0, report.total.energyKcal, 1e-9)
    }

    @Test
    fun saltToTasteIsUnquantifiedNotAnError() {
        val report = engine.calculateRecipe(recipe(line("rice", 100.0, QuantityUnit.GRAM), line("salt", 0.0, QuantityUnit.PINCH)))
        assertEquals(LineStatus.UNQUANTIFIED, report.lines[1].status)
        assertEquals(1.0, report.completeness)
    }

    @Test
    fun perServingDividesTotals() {
        val report = engine.calculateRecipe(recipe(line("rice", 400.0, QuantityUnit.GRAM), servings = 4))
        assertEquals(report.total.energyKcal / 4, report.perServing.energyKcal, 1e-9)
    }

    @Test
    fun measuredBatchWeightDrivesPortionNutrition() {
        // Batch-cooking example: pot yields 1800 g, user eats 325 g.
        val report = engine.calculateRecipe(
            recipe(
                line("basmati rice", 500.0, QuantityUnit.GRAM),
                line("chicken", 1000.0, QuantityUnit.GRAM),
                line("ghee", 60.0, QuantityUnit.GRAM),
                line("water", 900.0, QuantityUnit.MILLILITRE),
                servings = 6, cookedYield = 1800.0,
            ),
        )
        assertTrue(report.cookedWeightMeasured)
        val portion = report.forPortionGrams(325.0)
        assertEquals(report.total.energyKcal * 325.0 / 1800.0, portion.energyKcal, 1e-9)
        assertEquals(report.total.energyKcal * 0.5, report.forFraction(0.5).energyKcal, 1e-9)
    }

    @Test
    fun estimatedPotWeightCountsWaterAbsorbedByDryPulses() {
        // Dried chickpeas boiled in unlisted water, then blended: the pot weighs far more than went in.
        val hummus = engine.calculateRecipe(
            recipe(line("dried chickpeas", 250.0, QuantityUnit.GRAM, id = "chickpeas_dry"), line("olive oil", 30.0, QuantityUnit.GRAM, id = "olive_oil")),
        )
        assertEquals(250.0 * 2.2 + 30.0 * 0.85, hummus.cookedWeightGrams, 1e-9)
        assertTrue(!hummus.cookedWeightMeasured)

        // Listed cooking water is what the rice absorbs, so it isn't counted twice.
        val rice = engine.calculateRecipe(
            recipe(line("rice", 200.0, QuantityUnit.GRAM, id = "rice_white"), line("water", 400.0, QuantityUnit.MILLILITRE, id = "water")),
        )
        assertEquals(200.0 + 400.0, rice.cookedWeightGrams, 1e-9)

        // Plenty of stock: the leftover liquid, after evaporation, sets the weight instead.
        val soup = engine.calculateRecipe(
            recipe(line("red lentils", 100.0, QuantityUnit.GRAM, id = "lentils_red"), line("water", 1000.0, QuantityUnit.MILLILITRE, id = "water")),
        )
        assertEquals(100.0 + 1000.0 * 0.85, soup.cookedWeightGrams, 1e-9)
    }

    @Test
    fun evaporationDoesNotRemoveCalories() {
        val base = recipe(line("lentils", 200.0, QuantityUnit.GRAM), line("water", 1000.0, QuantityUnit.MILLILITRE))
        val a = engine.calculateRecipe(base.copy(cookedYieldGrams = 1100.0))
        val b = engine.calculateRecipe(base.copy(cookedYieldGrams = 700.0))
        assertEquals(a.total.energyKcal, b.total.energyKcal, 1e-9)
        assertTrue(b.per100gCooked.energyKcal > a.per100gCooked.energyKcal)
    }

    @Test
    fun usCustomaryCupsGiveSmallerWeights() {
        val metric = NutritionEngine(system = MeasurementSystem.METRIC).calculateLine(line("milk", 1.0, QuantityUnit.CUP))
        val us = NutritionEngine(system = MeasurementSystem.US_CUSTOMARY).calculateLine(line("milk", 1.0, QuantityUnit.CUP))
        assertTrue(metric.gramsEaten!! > us.gramsEaten!!)
        // USDA's own household measure (1 cup = 244 g) takes precedence over density.
        assertEquals(244.0, us.gramsEaten!!, 1e-9)
        assertEquals(244.0 * 250.0 / 236.5882365, metric.gramsEaten!!, 1e-9)
    }

    @Test
    fun approximateDataLowersStatus() {
        val r = engine.calculateLine(line("paneer", 100.0, QuantityUnit.GRAM))
        assertEquals(LineStatus.ESTIMATED, r.status)
        assertNotNull(r.food)
    }

    @Test
    fun optionalIngredientsExcludedByDefault() {
        val rec = recipe(line("rice", 100.0, QuantityUnit.GRAM), line("cashews", 30.0, QuantityUnit.GRAM, optional = true))
        assertEquals(1, engine.calculateRecipe(rec).lines.size)
        assertEquals(2, engine.calculateRecipe(rec, includeOptional = true).lines.size)
    }

    @Test
    fun nutrientArithmeticHandlesUnknowns() {
        val a = Nutrients(100.0, 10.0, 5.0, 2.0, fiberG = null)
        val b = Nutrients(50.0, 1.0, 1.0, 1.0, fiberG = 3.0)
        assertEquals(3.0, (a + b).fiberG)
        assertEquals(null, (a + a).fiberG)
        assertEquals(75.0, ((a + b) / 2.0).energyKcal)
    }

    @Test
    fun roundingPolicies() {
        assertEquals(613, NutritionRounding.energy(612.6, Precision.MEASURED))
        assertEquals(615, NutritionRounding.energy(612.6, Precision.CALCULATED))
        assertEquals(610, NutritionRounding.energy(612.6, Precision.ESTIMATED))
        assertEquals(4.5, NutritionRounding.grams(4.46, Precision.MEASURED), 1e-9)
        assertEquals(4.0, NutritionRounding.grams(4.46, Precision.CALCULATED), 1e-9)
        assertEquals(120.0, NutritionRounding.UsLabel.calories(123.0))
        assertEquals(45.0, NutritionRounding.UsLabel.calories(47.0))
        assertEquals(2.5, NutritionRounding.UsLabel.fat(2.3))
        assertEquals(0.5, NutritionRounding.UsLabel.carbLike(0.7))
    }
}
