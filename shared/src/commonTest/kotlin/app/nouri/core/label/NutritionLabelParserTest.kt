package app.nouri.core.label

import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.units.QuantityUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NutritionLabelParserTest {

    private fun assertNutrients(expected: Nutrients, actual: Nutrients?, tolerance: Double = 1e-6) {
        assertNotNull(actual, "expected nutrients")
        assertEquals(expected.energyKcal, actual.energyKcal, tolerance, "energy")
        assertEquals(expected.proteinG, actual.proteinG, tolerance, "protein")
        assertEquals(expected.carbsG, actual.carbsG, tolerance, "carbs")
        assertEquals(expected.fatG, actual.fatG, tolerance, "fat")
        assertNullableEquals(expected.fiberG, actual.fiberG, tolerance, "fibre")
        assertNullableEquals(expected.sugarG, actual.sugarG, tolerance, "sugars")
        assertNullableEquals(expected.sodiumMg, actual.sodiumMg, tolerance, "sodium")
        assertNullableEquals(expected.saturatedFatG, actual.saturatedFatG, tolerance, "saturated fat")
    }

    private fun assertNullableEquals(expected: Double?, actual: Double?, tolerance: Double, what: String) {
        if (expected == null) assertNull(actual, what) else assertEquals(expected, assertNotNull(actual, what), tolerance, what)
    }

    // --- US -------------------------------------------------------------------------------

    private val us2016 = """
        Nutrition Facts
        8 servings per container
        Serving size 2/3 cup (55g)
        Amount per serving
        Calories 230
        % Daily Value*
        Total Fat 8g 10%
        Saturated Fat 1g 5%
        Trans Fat 0g
        Cholesterol 0mg 0%
        Sodium 160mg 7%
        Total Carbohydrate 37g 13%
        Dietary Fiber 4g 14%
        Total Sugars 12g
        Includes 10g Added Sugars 20%
        Protein 3g
        Vitamin D 2mcg 10%
        Calcium 260mg 20%
        Iron 8mg 45%
        Potassium 240mg 6%
        * The % Daily Value (DV) tells you how much a nutrient in
        a serving of food contributes to a daily diet. 2,000 calories
        a day is used for general nutrition advice.
    """.trimIndent()

    @Test
    fun parsesUs2016Label() {
        val r = NutritionLabelParser.parse(us2016)
        assertEquals(LabelRegion.US, r.region)
        assertEquals(ServingSize("2/3 cup (55g)", 55.0, null, "2/3 cup"), r.servingSize)
        assertEquals(8.0, r.servingsPerContainer)
        assertNutrients(
            Nutrients(230.0, 3.0, 37.0, 8.0, fiberG = 4.0, sugarG = 12.0, sodiumMg = 160.0, saturatedFatG = 1.0),
            r.perServing,
        )
        assertNull(r.per100g)
        assertEquals(emptyList(), r.missingFields)
        assertEquals(emptyList(), r.warnings)
        assertEquals(ConfidenceLevel.HIGHER, r.confidence)
    }

    @Test
    fun usLabelBecomesPer100gFoodReference() {
        val food = assertNotNull(NutritionLabelParser.parse(us2016).toFoodReference("label:test", "Test cereal"))
        assertEquals(NutritionSource.MANUFACTURER_LABEL, food.source)
        assertEquals(DataQuality.HIGH, food.quality)
        assertEquals(55.0, food.servingGrams)
        assertEquals(230.0 * 100 / 55, food.per100g.energyKcal, 1e-9)
        assertEquals(37.0 * 100 / 55, food.per100g.carbsG, 1e-9)
        assertEquals(160.0 * 100 / 55, food.per100g.sodiumMg!!, 1e-9)
        val serving = food.portions.single { it.unit == QuantityUnit.SERVING }
        assertEquals(55.0, serving.grams)
        // 2/3 cup weighs 55 g, so a cup is 82.5 g.
        assertEquals(82.5, food.portions.single { it.unit == QuantityUnit.CUP }.grams, 1e-9)
        // Back to one serving gives the printed values.
        assertEquals(230.0, food.nutrientsFor(55.0).energyKcal, 1e-9)
    }

    @Test
    fun olderUsFormatIgnoresCaloriesFromFatAndFootnoteTable() {
        val text = """
            Nutrition Facts
            Serving Size 1 cup (228g)
            Servings Per Container 2
            Amount Per Serving
            Calories 250 Calories from Fat 110
            % Daily Value*
            Total Fat 12g 18%
            Saturated Fat 3g 15%
            Trans Fat 3g
            Cholesterol 30mg 10%
            Sodium 470mg 20%
            Total Carbohydrate 31g 10%
            Dietary Fiber 0g 0%
            Sugars 5g
            Protein 5g
            Vitamin A 4%
            Vitamin C 2%
            Calcium 20%
            Iron 4%
            * Percent Daily Values are based on a 2,000 calorie diet.
            Your Daily Values may be higher or lower depending on your calorie needs:
            Calories: 2,000 2,500
            Total Fat Less than 65g 80g
            Sat Fat Less than 20g 25g
            Cholesterol Less than 300mg 300mg
            Sodium Less than 2,400mg 2,400mg
            Total Carbohydrate 300g 375g
            Dietary Fiber 25g 30g
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.US, r.region)
        assertEquals(228.0, r.servingSize?.grams)
        assertEquals(2.0, r.servingsPerContainer)
        assertNutrients(
            Nutrients(250.0, 5.0, 31.0, 12.0, fiberG = 0.0, sugarG = 5.0, sodiumMg = 470.0, saturatedFatG = 3.0),
            r.perServing,
        )
        assertEquals(ConfidenceLevel.HIGHER, r.confidence)
    }

    @Test
    fun valuesOnTheLineAfterTheirName() {
        val text = """
            Nutrition Facts
            Serving size
            1 cup (30g)
            Calories
            120
            Total Fat
            2g
            Protein
            3g
            Total Carbohydrate 22g
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(30.0, r.servingSize?.grams)
        assertEquals("1 cup", r.servingSize?.householdText)
        val n = assertNotNull(r.perServing)
        assertEquals(120.0, n.energyKcal)
        assertEquals(2.0, n.fatG)
        assertEquals(3.0, n.proteinG)
        assertEquals(22.0, n.carbsG)
    }

    // --- Canada ---------------------------------------------------------------------------

    private val canadianMilk = """
        Nutrition Facts
        Valeur nutritive
        Per 1 cup (250 mL) / pour 1 tasse (250 mL)
        Calories 110 % Daily Value*
        % valeur quotidienne*
        Fat / Lipides 2 g 3 %
        Saturated / saturés 1 g
        + Trans / trans 0 g 5 %
        Carbohydrate / Glucides 13 g
        Fibre / Fibres 0 g 0 %
        Sugars / Sucres 12 g 12 %
        Protein / Protéines 9 g
        Cholesterol / Cholestérol 10 mg
        Sodium 115 mg 5 %
        Potassium 400 mg 9 %
        Calcium 300 mg 23 %
        Iron / Fer 0 mg 0 %
        *5% or less is a little, 15% or more is a lot
        *5 % ou moins c'est peu, 15 % ou plus c'est beaucoup
    """.trimIndent()

    @Test
    fun parsesCanadianBilingualLabelIgnoringDailyValues() {
        val r = NutritionLabelParser.parse(canadianMilk)
        assertEquals(LabelRegion.CANADA, r.region)
        assertEquals(ServingSize("1 cup (250 mL)", null, 250.0, "1 cup"), r.servingSize)
        assertNutrients(
            Nutrients(110.0, 9.0, 13.0, 2.0, fiberG = 0.0, sugarG = 12.0, sodiumMg = 115.0, saturatedFatG = 1.0),
            r.perServing,
        )
        assertNull(r.per100g)
    }

    @Test
    fun volumeOnlyServingIsNotConvertedWithAnAssumedDensity() {
        val r = NutritionLabelParser.parse(canadianMilk)
        assertEquals(listOf(NutrientFields.SERVING_GRAMS), r.missingFields)
        assertTrue(r.warnings.any { "volume" in it }, r.warnings.toString())
        assertEquals(ConfidenceLevel.MODERATE, r.confidence)
        assertNull(r.toFoodReference("label:milk", "Milk"))
    }

    @Test
    fun readsFrenchOnlyCanadianLines() {
        val text = """
            Valeur nutritive
            pour 1 tasse (250 mL)
            Calories 110
            Lipides 2 g 3 %
            saturés 1 g
            Glucides 13 g
            Sucres 12 g
            Protéines 9 g
            Sodium 115 mg 5 %
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.CANADA, r.region)
        assertEquals("1 tasse (250 mL)", r.servingSize?.text)
        assertEquals(250.0, r.servingSize?.millilitres)
        val n = assertNotNull(r.perServing)
        assertEquals(110.0, n.energyKcal)
        assertEquals(2.0, n.fatG)
        assertEquals(1.0, n.saturatedFatG)
        assertEquals(13.0, n.carbsG)
        assertEquals(12.0, n.sugarG)
        assertEquals(9.0, n.proteinG)
        assertEquals(115.0, n.sodiumMg)
    }

    // --- EU / UK --------------------------------------------------------------------------

    private val euPer100 = Nutrients(
        energyKcal = 370.0, proteinG = 13.0, carbsG = 69.0, fatG = 8.5,
        fiberG = 9.0, sugarG = 1.1, sodiumMg = 8.0, saturatedFatG = 1.2,
    )
    private val euPerServing = Nutrients(
        energyKcal = 148.0, proteinG = 5.2, carbsG = 27.6, fatG = 3.4,
        fiberG = 3.6, sugarG = 0.4, sodiumMg = 4.0, saturatedFatG = 0.5,
    )

    @Test
    fun parsesEuTableWithTwoColumnsCommaDecimalsAndSalt() {
        val text = """
            NUTRITION INFORMATION
            Typical values Per 100g Per serving (40g)
            Energy 1550kJ / 370kcal 620kJ / 148kcal
            Fat 8.5g 3.4g
            of which saturates 1.2g 0.5g
            Carbohydrate 60g 24g
            of which sugars 1,1g 0,4g
            Fibre 9g 3.6g
            Protein 13g 5.2g
            Salt 0.02g 0.01g
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.EU, r.region)
        assertEquals(40.0, r.servingSize?.grams)
        assertEquals("40g", r.servingSize?.text)
        // Carbohydrate is total (60 g available + 9 g fibre); sodium = salt × 400.
        assertNutrients(euPer100, r.per100g, 1e-9)
        assertNutrients(euPerServing, r.perServing, 1e-9)
        assertEquals(emptyList(), r.warnings)
        assertEquals(ConfidenceLevel.HIGHER, r.confidence)

        val food = assertNotNull(r.toFoodReference("label:muesli", "Muesli"))
        assertEquals(370.0, food.per100g.energyKcal)
        assertEquals(40.0, food.portions.single { it.unit == QuantityUnit.SERVING }.grams)
    }

    @Test
    fun readsEuTableWhenOcrReturnsNamesAndValueColumnsAsSeparateBlocks() {
        val text = """
            Nutrition Information
            Typical values
            Energy
            Fat
            of which saturates
            Carbohydrate
            of which sugars
            Fibre
            Protein
            Salt
            Per 100g
            1550kJ/370kcal
            8.5g
            1.2g
            60g
            1,1g
            9g
            13g
            0.02g
            Per serving (40g)
            620kJ/148kcal
            3.4g
            0.5g
            24g
            0,4g
            3.6g
            5.2g
            0.01g
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.EU, r.region)
        assertNutrients(euPer100, r.per100g, 1e-9)
        assertNutrients(euPerServing, r.perServing, 1e-9)
    }

    @Test
    fun ukEnergyOnTwoLinesAndKilojoulesOnly() {
        val twoLines = NutritionLabelParser.parse(
            """
                Nutrition
                Typical values per 100g
                Energy 1550kJ
                370kcal
                Fat 8.5g
                Carbohydrate 60g
                Protein 13g
            """.trimIndent(),
        )
        assertEquals(370.0, twoLines.per100g?.energyKcal)
        assertEquals(8.5, twoLines.per100g?.fatG)

        val kjOnly = NutritionLabelParser.parse("Per 100g\nEnergy 1674 kJ\nFat 10g\nCarbohydrate 50g\nProtein 20g")
        assertEquals(LabelRegion.EU, kjOnly.region)
        assertEquals(1674 / 4.184, kjOnly.per100g!!.energyKcal, 1e-9)
    }

    @Test
    fun drinkLabelPer100mlIsKeptAsAVolumeServing() {
        val r = NutritionLabelParser.parse(
            """
                Nutrition information
                Typical values per 100ml
                Energy 180kJ / 43kcal
                Fat 0g
                of which saturates 0g
                Carbohydrate 10.6g
                of which sugars 10.6g
                Protein 0g
                Salt 0g
            """.trimIndent(),
        )
        assertNull(r.per100g)
        assertEquals(43.0, r.perServing?.energyKcal)
        assertEquals(100.0, r.servingSize?.millilitres)
        assertNull(r.servingSize?.grams)
        assertNull(r.toFoodReference("label:juice", "Juice"))
    }

    // --- OCR noise ------------------------------------------------------------------------

    @Test
    fun toleratesOcrNoise() {
        val text = """
            NUTRITION FACTS
            Serving size 1 bar (4Og)
            Calories 19O
            TotalFat8g 10%
            Saturated Fat 3g 15%
            Trans Fat Og
            Sodium l20mg 5%
            Total Carb. 25g 9%
            Dietary Fiber <1g 2%
            Total Sugars 1Og
            Protein12g
        """.trimIndent()
        val r = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.US, r.region)
        assertEquals(40.0, r.servingSize?.grams)
        assertEquals("1 bar", r.servingSize?.householdText)
        assertNutrients(
            Nutrients(190.0, 12.0, 25.0, 8.0, fiberG = 0.5, sugarG = 10.0, sodiumMg = 120.0, saturatedFatG = 3.0),
            r.perServing,
        )
        assertTrue(r.warnings.any { "Fibre" in it && "0.5" in it }, r.warnings.toString())
        assertEquals(ConfidenceLevel.MODERATE, r.confidence)
    }

    @Test
    fun lessThanWordingAndSodiumInGrams() {
        val r = NutritionLabelParser.parse(
            "Nutrition Facts\nServing size 1 piece (15g)\nCalories 60\nTotal Fat 0g\nSodium 0.05g\n" +
                "Total Carbohydrate 15g\nTotal Sugars 11g\nProtein less than 1g",
        )
        val n = assertNotNull(r.perServing)
        assertEquals(0.5, n.proteinG)
        assertEquals(50.0, n.sodiumMg!!, 1e-9)
        assertTrue(r.warnings.any { it.startsWith("Protein") })
    }

    @Test
    fun splitCalorieDigitsAreJoinedOnlyWhenUnambiguous() {
        val split = NutritionLabelParser.parse("Nutrition Facts\nServing size 1 cup (55g)\nCalories 2 30\nTotal Fat 8g\nTotal Carbohydrate 37g\nProtein 3g")
        assertEquals(230.0, split.perServing?.energyKcal)
        assertTrue(split.warnings.any { "2 30" in it })

        // "5 30" could be two columns; it is not joined.
        val twoColumns = NutritionLabelParser.parse("Nutrition Facts\nCalories 5 30\nTotal Fat 0g")
        assertEquals(5.0, twoColumns.perServing?.energyKcal)
    }

    @Test
    fun digitRepairLeavesWordsAndLitresAlone() {
        assertEquals("10 g", LabelAmounts.fixOcrDigits("1O g"))
        assertEquals("0g", LabelAmounts.fixOcrDigits("Og"))
        assertEquals("160mg", LabelAmounts.fixOcrDigits("l60mg"))
        assertEquals("Iron Oats oil", LabelAmounts.fixOcrDigits("Iron Oats oil"))
        assertEquals("1l", LabelAmounts.fixOcrDigits("1l"))
    }

    // --- Validation and confidence --------------------------------------------------------

    @Test
    fun caloriesThatDisagreeWithMacrosAreFlagged() {
        val r = NutritionLabelParser.parse(
            "Nutrition Facts\nServing size 1 cup (240g)\nCalories 450\nTotal Fat 2g\nTotal Carbohydrate 20g\nProtein 5g",
        )
        assertEquals(450.0, r.perServing?.energyKcal)
        assertTrue(r.warnings.any { "does not match" in it }, r.warnings.toString())
        assertEquals(ConfidenceLevel.MODERATE, r.confidence)
    }

    @Test
    fun implausibleValuesAreFlagged() {
        val r = NutritionLabelParser.parse(
            "Nutrition Facts\nServing size 2 tbsp (30g)\nCalories 90\nTotal Fat 2g\nSaturated Fat 5g\n" +
                "Total Carbohydrate 12g\nTotal Sugars 15g\nProtein 40g",
        )
        val warnings = r.warnings.joinToString("\n")
        assertTrue("Sugars" in warnings, warnings)
        assertTrue("Saturated fat" in warnings, warnings)
        assertTrue("Protein above 100 g per 100 g" in warnings, warnings)
        assertTrue(r.confidence != ConfidenceLevel.HIGHER)
    }

    @Test
    fun missingCoreValuesAreReportedNotInvented() {
        val r = NutritionLabelParser.parse("Nutrition Facts\nServing size 1 bar (40g)\nCalories 190\nTotal Fat 8g\nTotal Carbohydrate 25g")
        assertEquals(listOf(NutrientFields.PROTEIN_G, NutrientFields.FIBER_G, NutrientFields.SUGAR_G, NutrientFields.SODIUM_MG, NutrientFields.SATURATED_FAT_G), r.missingFields)
        assertEquals(0.0, r.perServing?.proteinG)
        assertNull(r.perServing?.fiberG)
        assertEquals(ConfidenceLevel.MODERATE, r.confidence)
        assertEquals(DataQuality.MEDIUM, r.toFoodReference("label:bar", "Bar")?.quality)

        // Without energy there is no food reference at all.
        val noEnergy = NutritionLabelParser.parse("Nutrition Facts\nServing size 1 bar (40g)\nTotal Fat 8g\nProtein 3g")
        assertNull(noEnergy.toFoodReference("label:bar", "Bar"))
    }

    @Test
    fun hintIsUsedOnlyWhenTheFormatIsNotRecognised() {
        val text = "Calories 200\nFat 10 g\nCarbohydrate 20 g\nProtein 8 g\nSodium 300 mg"
        val unknown = NutritionLabelParser.parse(text)
        assertEquals(LabelRegion.UNKNOWN, unknown.region)
        assertEquals(ConfidenceLevel.LOW, unknown.confidence)
        assertEquals(200.0, unknown.perServing?.energyKcal)

        val hinted = NutritionLabelParser.parse(text, hint = LabelRegion.CANADA)
        assertEquals(LabelRegion.CANADA, hinted.region)
        assertEquals(ConfidenceLevel.MODERATE, hinted.confidence)

        // A recognised label ignores a conflicting hint.
        assertEquals(LabelRegion.US, NutritionLabelParser.parse(us2016, hint = LabelRegion.EU).region)
    }

    @Test
    fun emptyTextYieldsNothing() {
        val r = NutritionLabelParser.parse("   \n\n")
        assertNull(r.perServing)
        assertNull(r.per100g)
        assertEquals(ConfidenceLevel.LOW, r.confidence)
        assertTrue(NutrientFields.CORE.all { it in r.missingFields })
        assertNull(r.toFoodReference("label:none", "None"))
    }

    @Test
    fun productNameIsTakenFromAboveTheHeading() {
        val r = NutritionLabelParser.parse("Example Honey Granola\nNutrition Facts\nServing size 1/2 cup (50g)\nCalories 220")
        assertEquals("Example Honey Granola", r.productName)
        assertNull(NutritionLabelParser.parse(us2016).productName)
    }
}
