package app.nouri.core.parse

import app.nouri.core.dishes.DishFoodRepository
import app.nouri.core.fooddb.CompositeFoodRepository
import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.Nutrients
import app.nouri.core.scan.FoodMatcher
import app.nouri.core.scan.PortionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealTextParserTest {
    private val foods = CompositeFoodRepository(listOf(CoreFoods.repository, DishFoodRepository()))
    private val resolver = MealTextResolver(
        FoodMatcher(foods),
        recipes = { name -> if ("biryani" in name) SavedRecipeNutrition("r1", "Mum's chicken biryani", Nutrients(4000.0, 200.0, 450.0, 160.0), 8, 1800.0) else null },
    )

    @Test
    fun rotiCurryRice() {
        val parsed = MealTextParser.parse("2 rotis with chicken curry and half a cup of rice")
        assertEquals(listOf("rotis", "chicken curry", "rice"), parsed.items.map { it.food })
        assertEquals(2.0, parsed.items[0].amount)
        assertEquals("piece", parsed.items[0].unit)
        assertNull(parsed.items[1].amount)
        assertEquals(0.5, parsed.items[2].amount)
        assertEquals("cup", parsed.items[2].unit)

        val resolved = resolver.resolve(parsed)
        assertEquals("chapati", resolved[0].match?.dishId)
        assertEquals(80.0, resolved[0].grams!!, 1e-9)
        assertEquals("chicken_curry", resolved[1].match?.dishId)
        assertEquals(PortionSource.TYPICAL_SERVING, resolved[1].portionSource)
        assertEquals("plain_rice", resolved[2].match?.dishId)
        assertTrue(resolved.all { it.isResolved })
    }

    @Test
    fun coffeeWithSplashOfMilk() {
        val parsed = MealTextParser.parse("Coffee with a splash of milk")
        assertEquals(listOf("coffee", "milk"), parsed.items.map { it.food })
        assertEquals(2.0, parsed.items[1].amount)
        assertEquals("tbsp", parsed.items[1].unit)
        val resolved = resolver.resolve(parsed)
        assertEquals("coffee", resolved[0].match?.ingredientId)
        assertEquals(237.0, resolved[0].grams!!, 1e-9)
        assertEquals("milk_whole", resolved[1].match?.ingredientId)
        assertTrue(resolved[1].nutrients.energyKcal in 15.0..25.0)
    }

    @Test
    fun fractionOfSavedRecipe() {
        val parsed = MealTextParser.parse("I ate half of the biryani recipe I made yesterday")
        val mention = parsed.items.single()
        assertEquals(0.5, mention.recipeFraction)
        assertEquals("biryani", mention.recipeReference)
        val resolved = resolver.resolve(parsed).single()
        assertNotNull(resolved.recipe)
        assertEquals(2000.0, resolved.nutrients.energyKcal, 1e-9)
        assertEquals(900.0, resolved.grams!!, 1e-9)
        assertEquals(ConfidenceLevel.HIGHER, resolved.confidence)
    }

    @Test
    fun gramsAndUnicodeFractions() {
        val p = MealTextParser.parse("150g chicken breast, ½ cup yogurt and 1 1/2 tbsp olive oil")
        assertEquals(150.0, p.items[0].grams)
        assertEquals(0.5, p.items[1].amount)
        assertEquals(1.5, p.items[2].amount)
        val r = resolver.resolve(p)
        assertEquals(PortionSource.USER_WEIGHED, r[0].portionSource)
        assertEquals(150 * 1.65, r[0].nutrients.energyKcal, 1e-6) // cooked chicken breast
    }

    @Test
    fun compoundDishNamesStayWhole() {
        val p = MealTextParser.parse("rice and peas with jerk chicken")
        assertEquals(listOf("rice and peas", "jerk chicken"), p.items.map { it.food })
    }

    @Test
    fun mealHintAndNoise() {
        val p = MealTextParser.parse("For lunch I had 3 eggs and a large banana")
        assertEquals("lunch", p.mealHint)
        assertEquals(listOf("eggs", "large banana"), p.items.map { it.food })
        val r = resolver.resolve(p)
        assertEquals(3 * 44.0, r[0].grams!!, 1e-9)
        assertEquals(136.0, r[1].grams!!, 1e-9)
    }

    @Test
    fun unknownFoodIsReportedNotInvented() {
        val r = resolver.resolve(MealTextParser.parse("2 flurbles")).single()
        assertNull(r.match)
        assertEquals(0.0, r.nutrients.energyKcal)
        assertTrue(r.notes.single().contains("Couldn't find"))
    }

    @Test
    fun countOfDishMeansServings() {
        val r = resolver.resolve(MealTextParser.parse("2 chicken karahi")).single()
        assertTrue(r.notes.any { "typical serving" in it }, r.notes.toString())
    }

    @Test
    fun brandIsSeparatedFromTheFood() {
        val m = MealTextParser.parse("2 deep brand naans").items.single()
        assertEquals("deep", m.brand)
        assertEquals("naans", m.food)
        assertEquals(2.0, m.amount)
        val r = resolver.resolve(MealTextParser.parse("2 deep brand naans")).single()
        assertEquals(2.0, r.quantity!!.amount)
        assertTrue(r.notes.any { "not deep's own" in it }, r.notes.toString())
    }

    @Test
    fun countWithAnUnrecognisedUnitIsNotDropped() {
        // The AI path can return the food noun as the unit ("2" + "naans").
        val r = resolver.resolveMention(app.nouri.core.ai.ParsedFoodMention(food = "naan", amount = 2.0, unit = "naans", brand = "Deep"))
        assertNotNull(r.match)
        assertEquals(2.0, r.quantity!!.amount)
        assertEquals(app.nouri.core.units.QuantityUnit.PIECE, r.quantity!!.unit)
        val single = resolver.resolve(MealTextParser.parse("1 naan")).single()
        assertEquals(2 * single.grams!!, r.grams!!, 1e-9)
        assertTrue(r.notes.any { "as a count" in it }, r.notes.toString())
    }

    @Test
    fun resolveWithUsesTheChosenProduct() {
        val product = app.nouri.core.fooddb.FoodReference(
            id = "off:0001", name = "Garlic Naan (Deep)", per100g = Nutrients(300.0, 9.0, 50.0, 7.0),
            source = app.nouri.core.model.NutritionSource.BRANDED_DATABASE, quality = app.nouri.core.model.DataQuality.MEDIUM,
            portions = listOf(app.nouri.core.units.FoodPortion(app.nouri.core.units.QuantityUnit.PIECE, 85.0, label = "1 naan (85 g)")),
            servingGrams = 85.0,
        )
        val mention = MealTextParser.parse("2 deep brand naans").items.single()
        val r = resolver.resolveWith(mention, FoodMatcher(foods).fromReference(product))
        assertEquals(170.0, r.grams!!, 1e-9)
        assertEquals(510.0, r.nutrients.energyKcal, 1e-9)
        assertTrue(r.notes.none { "Generic values" in it }, r.notes.toString())
    }
}
