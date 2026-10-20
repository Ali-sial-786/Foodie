package app.nouri.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnitConverterTest {

    private fun grams(q: Quantity, ctx: PortionContext = PortionContext(), system: MeasurementSystem = MeasurementSystem.METRIC): GramConversion {
        val outcome = UnitConverter.toGrams(q, ctx, system)
        assertIs<ConversionOutcome.Success>(outcome)
        return outcome.conversion
    }

    @Test
    fun massUnitsConvertExactly() {
        assertEquals(1000.0, grams(Quantity(1.0, QuantityUnit.KILOGRAM)).grams)
        assertEquals(28.349523125, grams(Quantity(1.0, QuantityUnit.OUNCE)).grams, 1e-9)
        assertEquals(453.59237, grams(Quantity(1.0, QuantityUnit.POUND)).grams, 1e-9)
        assertEquals(ConversionMethod.DIRECT_MASS, grams(Quantity(5.0, QuantityUnit.GRAM)).method)
    }

    @Test
    fun cupsDependOnMeasurementSystem() {
        val water = PortionContext(densityGPerMl = 1.0)
        assertEquals(250.0, grams(Quantity(1.0, QuantityUnit.CUP), water, MeasurementSystem.METRIC).grams, 1e-9)
        assertEquals(236.5882365, grams(Quantity(1.0, QuantityUnit.CUP), water, MeasurementSystem.US_CUSTOMARY).grams, 1e-9)
        assertEquals(15.0, grams(Quantity(1.0, QuantityUnit.TABLESPOON), water).grams, 1e-9)
    }

    @Test
    fun densityIsUsedForVolumes() {
        val flour = PortionContext(densityGPerMl = 0.53)
        val c = grams(Quantity(2.0, QuantityUnit.CUP), flour, MeasurementSystem.US_CUSTOMARY)
        assertEquals(2 * 236.5882365 * 0.53, c.grams, 1e-6)
        assertEquals(ConversionMethod.FOOD_DENSITY, c.method)
    }

    @Test
    fun foodSpecificCupMeasureBeatsDensityAndScalesForMetricCups() {
        val rice = PortionContext(densityGPerMl = 0.5, portions = listOf(FoodPortion(QuantityUnit.CUP, 185.0, label = "1 cup")))
        assertEquals(185.0, grams(Quantity(1.0, QuantityUnit.CUP), rice, MeasurementSystem.US_CUSTOMARY).grams, 1e-9)
        assertEquals(185.0 * 250.0 / 236.5882365, grams(Quantity(1.0, QuantityUnit.CUP), rice, MeasurementSystem.METRIC).grams, 1e-9)
    }

    @Test
    fun unknownDensityAssumesWaterAndSaysSo() {
        val c = grams(Quantity(100.0, QuantityUnit.MILLILITRE), PortionContext(foodName = "sauce"))
        assertEquals(100.0, c.grams)
        assertEquals(ConversionMethod.ASSUMED_WATER_DENSITY, c.method)
        assertNotNull(c.assumption)
    }

    @Test
    fun countUnitsUseSizedPortions() {
        val egg = PortionContext(
            portions = listOf(
                FoodPortion(QuantityUnit.PIECE, 50.0, PortionSize.LARGE, "large"),
                FoodPortion(QuantityUnit.PIECE, 44.0, PortionSize.MEDIUM, "medium"),
            ),
        )
        assertEquals(100.0, grams(Quantity(2.0, QuantityUnit.PIECE, PortionSize.LARGE), egg).grams)
        assertEquals(88.0, grams(Quantity(2.0, QuantityUnit.PIECE), egg).grams)
    }

    @Test
    fun countWithoutPortionFailsWithReason() {
        val outcome = UnitConverter.toGrams(Quantity(1.0, QuantityUnit.SLICE), PortionContext(foodName = "mystery"))
        assertIs<ConversionOutcome.Failure>(outcome)
        assertTrue(outcome.reason.contains("grams"))
    }

    @Test
    fun genericBowlIsLabelledAsGeneric() {
        val c = grams(Quantity(1.0, QuantityUnit.BOWL))
        assertEquals(ConversionMethod.GENERIC_PORTION, c.method)
        assertNotNull(c.assumption)
    }

    @Test
    fun unitParsingHandlesAliasesAndCase() {
        assertEquals(QuantityUnit.TABLESPOON, QuantityUnit.parse("T"))
        assertEquals(QuantityUnit.TEASPOON, QuantityUnit.parse("t"))
        assertEquals(QuantityUnit.TABLESPOON, QuantityUnit.parse("tbsp."))
        assertEquals(QuantityUnit.GRAM, QuantityUnit.parse("grams"))
        assertEquals(QuantityUnit.CUP, QuantityUnit.parse("katori"))
        assertEquals(QuantityUnit.PIECE, QuantityUnit.parse("pcs"))
        assertEquals(null, QuantityUnit.parse("bogus"))
    }

    @Test
    fun weightConversionsRoundTrip() {
        assertEquals(80.0, UnitConverter.lbToKg(UnitConverter.kgToLb(80.0)), 1e-9)
        assertEquals(176.37, UnitConverter.kgToLb(80.0), 0.01)
    }

    @Test
    fun formatAmountIsCompact() {
        assertEquals("2", formatAmount(2.0))
        assertEquals("2.5", formatAmount(2.5))
        assertEquals("0.33", formatAmount(1.0 / 3.0))
        assertEquals("150", formatAmount(149.6))
    }
}
