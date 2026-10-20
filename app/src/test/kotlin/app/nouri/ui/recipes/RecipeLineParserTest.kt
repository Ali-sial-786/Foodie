package app.nouri.ui.recipes

import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeLineParserTest {
    private fun parse(s: String) = RecipeLineParser.parse(s)!!

    @Test
    fun amountUnitNameAndPreparation() {
        val p = parse("2 cups basmati rice, rinsed")
        assertEquals(2.0, p.amount, 1e-9)
        assertEquals(QuantityUnit.CUP, p.unit)
        assertEquals("basmati rice", p.name)
        assertEquals("rinsed", p.preparation)
        assertFalse(p.optional)
    }

    @Test
    fun countWithoutUnitIsPieces() {
        val p = parse("3 eggs")
        assertEquals(3.0, p.amount, 1e-9)
        assertEquals(QuantityUnit.PIECE, p.unit)
        assertEquals("eggs", p.name)
    }

    @Test
    fun sizeWordsAreKept() {
        val p = parse("1 large onion, thinly sliced")
        assertEquals(PortionSize.LARGE, p.size)
        assertEquals(QuantityUnit.PIECE, p.unit)
        assertEquals("onion", p.name)
        assertEquals("thinly sliced", p.preparation)
    }

    @Test
    fun fractionsMixedNumbersRangesAndUnicode() {
        assertEquals(0.5, parse("1/2 cup yogurt").amount, 1e-9)
        assertEquals(1.5, parse("1 1/2 cups water").amount, 1e-9)
        assertEquals(2.5, parse("2-3 green chillies").amount, 1e-9)
        assertEquals(1.5, parse("1½ tsp cumin seeds").amount, 1e-9)
        assertEquals(0.25, parse("¼ tsp turmeric").amount, 1e-9)
        assertEquals(1.5, parse("1,5 kg lamb").amount, 1e-9)
    }

    @Test
    fun attachedAndTwoWordUnits() {
        val g = parse("200g chicken thigh")
        assertEquals(200.0, g.amount, 1e-9)
        assertEquals(QuantityUnit.GRAM, g.unit)
        assertEquals("chicken thigh", g.name)
        val floz = parse("4 fl oz cream")
        assertEquals(QuantityUnit.FLUID_OUNCE, floz.unit)
        assertEquals("cream", floz.name)
        assertEquals("rice", parse("2 cups of rice").name)
    }

    @Test
    fun toTasteAndUnquantifiedLinesAreNotCounted() {
        val salt = parse("Salt, to taste")
        assertEquals(0.0, salt.amount, 1e-9)
        assertEquals("Salt", salt.name)
        assertEquals("to taste", salt.preparation)
        val pepper = parse("black pepper")
        assertEquals(0.0, pepper.amount, 1e-9)
        assertEquals("black pepper", pepper.name)
    }

    @Test
    fun optionalMarkers() {
        assertTrue(parse("1 tbsp cream (optional)").optional)
        assertTrue(parse("Coriander leaves, optional").optional)
        assertEquals("cream", parse("1 tbsp cream (optional)").name)
    }

    @Test
    fun groupsFromHeadingLines() {
        val lines = RecipeLineParser.parseAll(
            """
            Marinade:
            200 g yogurt
            1 tsp salt

            Tempering (tadka):
            2 tbsp ghee
            """.trimIndent(),
        )
        assertEquals(3, lines.size)
        assertEquals("Marinade", lines[0].group)
        assertEquals("Marinade", lines[1].group)
        assertEquals("Tempering (tadka)", lines[2].group)
    }

    @Test
    fun blankAndBulletLines() {
        assertNull(RecipeLineParser.parse("   "))
        assertEquals("garlic", parse("- 3 cloves garlic").name)
        assertEquals(QuantityUnit.CLOVE, parse("• 3 cloves garlic").unit)
    }

    @Test
    fun ingredientTextRoundTrips() {
        val lines = listOf(
            RecipeIngredient("onion", quantity = Quantity(2.0, QuantityUnit.PIECE, PortionSize.MEDIUM), preparation = "sliced", group = "Base"),
            RecipeIngredient("basmati rice", quantity = Quantity(1.5, QuantityUnit.CUP), group = "Base"),
            RecipeIngredient("Salt", quantity = Quantity(0.0, QuantityUnit.PINCH), preparation = "to taste"),
            RecipeIngredient("cream", quantity = Quantity(2.0, QuantityUnit.TABLESPOON), optional = true),
        )
        val text = RecipeText.ingredientsText(lines)
        val parsed = RecipeLineParser.parseAll(text)
        assertEquals(lines.size, parsed.size)
        lines.zip(parsed).forEach { (orig, p) ->
            assertEquals(orig.displayName, p.name)
            assertEquals(orig.quantity.amount, p.amount, 1e-9)
            if (orig.quantity.amount > 0) assertEquals(orig.quantity.unit, p.unit)
            assertEquals(orig.optional, p.optional)
            assertEquals(orig.group, p.group)
            assertEquals(RecipeText.ingredientLine(orig), p.raw)
        }
    }

    @Test
    fun quantityDisplay() {
        assertEquals("2 cups", RecipeText.quantity(Quantity(2.0, QuantityUnit.CUP)))
        assertEquals("1 cup", RecipeText.quantity(Quantity(1.0, QuantityUnit.CUP)))
        assertEquals("3", RecipeText.quantity(Quantity(3.0, QuantityUnit.PIECE)))
        assertEquals("1 medium", RecipeText.quantity(Quantity(1.0, QuantityUnit.PIECE, PortionSize.MEDIUM)))
        assertEquals("", RecipeText.quantity(Quantity(0.0, QuantityUnit.PINCH)))
        assertEquals("250 g", RecipeText.quantity(Quantity(250.0, QuantityUnit.GRAM)))
    }

    @Test
    fun standaloneQuantities() {
        assertEquals(Quantity(500.0, QuantityUnit.GRAM), RecipeLineParser.parseQuantity("500 g"))
        assertEquals(Quantity(6.0, QuantityUnit.PIECE), RecipeLineParser.parseQuantity("6"))
        assertEquals(Quantity(1.0, QuantityUnit.PACKAGE), RecipeLineParser.parseQuantity("1 bag"))
        assertNull(RecipeLineParser.parseQuantity("lots"))
        assertNull(RecipeLineParser.parseQuantity(""))
    }

    @Test
    fun stepTimers() {
        assertEquals(600, RecipeLineParser.stepTimerSeconds("Simmer for 10–12 minutes until thick"))
        assertEquals(3600, RecipeLineParser.stepTimerSeconds("Bake for 1 hour"))
        assertEquals(1200, RecipeLineParser.stepTimerSeconds("Rest 20 min"))
        assertNull(RecipeLineParser.stepTimerSeconds("Season and serve"))
    }

    @Test
    fun countdownAndMinutes() {
        assertEquals("12:05", RecipeText.countdown(725))
        assertEquals("1:00:00", RecipeText.countdown(3600))
        assertEquals("45 min", RecipeText.minutes(45))
        assertEquals("1 h 15 min", RecipeText.minutes(75))
        assertEquals("2 h", RecipeText.minutes(120))
    }
}
