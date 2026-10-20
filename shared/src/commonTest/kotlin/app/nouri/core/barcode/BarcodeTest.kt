package app.nouri.core.barcode

import app.nouri.core.label.NutrientFields
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.ontology.Allergen
import app.nouri.core.units.QuantityUnit
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeTest {

    // --- GTIN -----------------------------------------------------------------------------

    @Test
    fun validCheckDigits() {
        assertTrue(Gtin.isValid("4006381333931")) // EAN-13
        assertTrue(Gtin.isValid("036000291452")) // UPC-A
        assertTrue(Gtin.isValid("96385074")) // EAN-8
        assertTrue(Gtin.isValid("10036000291459")) // GTIN-14
        assertEquals(1, Gtin.checkDigit("400638133393"))
    }

    @Test
    fun invalidCodes() {
        assertFalse(Gtin.isValid("4006381333932"))
        assertFalse(Gtin.isValid("036000291453"))
        assertFalse(Gtin.isValid("96385075"))
        assertFalse(Gtin.isValid("10036000291458"))
        assertFalse(Gtin.isValid("12345"))
        assertFalse(Gtin.isValid(""))
        assertFalse(Gtin.isValid("40063813339a1"))
        assertFalse(Gtin.isValid("4006 381333931"))
    }

    @Test
    fun normalizeProducesCanonicalForms() {
        assertEquals("0036000291452", Gtin.normalize("036000291452"))
        assertEquals("4006381333931", Gtin.normalize("4006-3813-33931"))
        assertEquals("4006381333931", Gtin.normalize(" 4006 3813 33931 "))
        assertEquals("96385074", Gtin.normalize("96385074"))
        assertEquals("10036000291459", Gtin.normalize("10036000291459"))
        // "0" + EAN-13 as GTIN-14 is the same item.
        assertEquals("0036000291452", Gtin.normalize("00036000291452"))
        // An 8-digit UPC-E (not a valid EAN-8) is expanded.
        assertEquals("0042100005264", Gtin.normalize("04252614"))
        assertNull(Gtin.normalize("4006381333932"))
        assertNull(Gtin.normalize("ABC"))
        assertNull(Gtin.normalize("123456789"))
    }

    @Test
    fun expandsUpcE() {
        assertEquals("042100005264", Gtin.expandUpcE("04252614"))
        assertEquals("042100005264", Gtin.expandUpcE("0425261"))
        assertEquals("042100005264", Gtin.expandUpcE("425261"))
        assertEquals("012345000065", Gtin.expandUpcE("01234565")) // last digit 5–9
        assertEquals("012300000451", Gtin.expandUpcE("123453")) // last digit 3
        assertEquals("012340000053", Gtin.expandUpcE("123454")) // last digit 4
        assertEquals("012000000003", Gtin.expandUpcE("120000")) // last digit 0–2
        assertTrue(Gtin.isValid(Gtin.expandUpcE("123453")!!))
        assertNull(Gtin.expandUpcE("04252615")) // wrong check digit
        assertNull(Gtin.expandUpcE("24252614")) // number system must be 0 or 1
        assertNull(Gtin.expandUpcE("12345"))
    }

    // --- Open Food Facts ------------------------------------------------------------------

    private fun found(json: String): BrandedProduct {
        val result = OpenFoodFactsMapper.map(json)
        assertIs<BarcodeLookupResult.Found>(result)
        return result.product
    }

    private val fullProduct = """
        {
          "code": "2000000000077",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "product_name": "Céréales test",
            "product_name_en": "Example Multigrain Crackers",
            "brands": "Example Mill, Example Group",
            "quantity": "2 x 150 g",
            "serving_size": "30 g",
            "serving_quantity": 30,
            "nutrition_data_per": "100g",
            "ingredients_text_en": "Whole WHEAT flour, sesame seeds, sunflower oil, salt",
            "allergens_tags": ["en:gluten", "en:sesame-seeds", "en:milk", "en:celery"],
            "traces_tags": ["en:peanuts"],
            "nutriments": {
              "energy-kcal_100g": 452,
              "energy-kj_100g": 1891,
              "energy_100g": 1891,
              "proteins_100g": 11.2,
              "carbohydrates_100g": 64.5,
              "fat_100g": 15.8,
              "saturated-fat_100g": 1.9,
              "sugars_100g": 3.1,
              "fiber_100g": 7.4,
              "sodium_100g": 0.48,
              "salt_100g": 1.2
            }
          }
        }
    """.trimIndent()

    @Test
    fun mapsCompleteProduct() {
        val p = found(fullProduct)
        assertEquals("2000000000077", p.gtin)
        assertEquals("Example Multigrain Crackers", p.name)
        assertEquals("Example Mill", p.brand)
        assertEquals("2 x 150 g", p.packageQuantityText)
        assertEquals(300.0, p.packageGrams)
        assertEquals("30 g", p.servingSizeText)
        assertEquals(30.0, p.servingGrams)
        assertEquals(OpenFoodFactsMapper.ATTRIBUTION, p.attribution)
        assertEquals(emptyList(), p.missingNutrients)

        val food = assertNotNull(p.food)
        assertEquals("off:2000000000077", food.id)
        assertEquals(NutritionSource.BRANDED_DATABASE, food.source)
        assertEquals(DataQuality.MEDIUM, food.quality)
        assertEquals(OpenFoodFactsMapper.ATTRIBUTION, food.sourceDetail)
        assertEquals(452.0, food.per100g.energyKcal)
        assertEquals(11.2, food.per100g.proteinG)
        assertEquals(64.5, food.per100g.carbsG) // no country information: passed through
        assertEquals(15.8, food.per100g.fatG)
        assertEquals(480.0, food.per100g.sodiumMg!!, 1e-9) // sodium_100g is in grams
        assertEquals(1.9, food.per100g.saturatedFatG)
        assertEquals(30.0, food.portions.single { it.unit == QuantityUnit.SERVING }.grams)
        assertEquals(30.0, food.servingGrams)
    }

    @Test
    fun mapsAllergenTags() {
        val p = found(fullProduct)
        // "may contain" traces are not allergens of the product; unknown tags are skipped.
        assertEquals(setOf(Allergen.MILK, Allergen.WHEAT, Allergen.GLUTEN, Allergen.SESAME), p.allergens)

        val all = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","allergens_tags":[
                "en:milk","en:eggs","en:peanuts","en:nuts","en:soybeans","en:gluten","en:fish","en:crustaceans",
                "en:molluscs","en:sesame-seeds","en:mustard","en:sulphur-dioxide-and-sulphites"],
                "ingredients_text":"buckwheat, oats"}}""",
        )
        // Buckwheat is not wheat, so only GLUTEN is added for the gluten tag.
        assertEquals(Allergen.entries.toSet() - Allergen.WHEAT, all.allergens)
    }

    @Test
    fun energyFromKilojoulesWhenKcalIsMissing() {
        val kjOnly = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","nutriments":{
                "energy-kj_100g":1674,"proteins_100g":10,"carbohydrates_100g":60,"fat_100g":10}}}""",
        )
        assertEquals(1674 / 4.184, kjOnly.food!!.per100g.energyKcal, 1e-9)

        val genericEnergy = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","nutriments":{
                "energy_100g":1674,"proteins_100g":10,"carbohydrates_100g":60,"fat_100g":10}}}""",
        )
        assertEquals(1674 / 4.184, genericEnergy.food!!.per100g.energyKcal, 1e-9)
    }

    @Test
    fun sodiumFromSaltWhenSodiumIsMissing() {
        val p = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","nutriments":{
                "energy-kcal_100g":250,"proteins_100g":8,"carbohydrates_100g":40,"fat_100g":6,"salt_100g":1.25}}}""",
        )
        assertEquals(500.0, p.food!!.per100g.sodiumMg!!, 1e-9)
        assertEquals(
            listOf(NutrientFields.FIBER_G, NutrientFields.SUGAR_G, NutrientFields.SATURATED_FAT_G),
            p.missingNutrients,
        )
    }

    @Test
    fun acceptsNumbersEncodedAsStrings() {
        val p = found(
            """{"code":"036000291452","status":"1","product":{"product_name":"X","serving_quantity":"40",
                "serving_size":"40 g","nutriments":{"energy-kcal_100g":"380","proteins_100g":"12.5",
                "carbohydrates_100g":"61","fat_100g":"3,2","sugars_100g":"","fiber_100g":null}}}""",
        )
        assertEquals("0036000291452", p.gtin)
        assertEquals(40.0, p.servingGrams)
        val n = p.food!!.per100g
        assertEquals(380.0, n.energyKcal)
        assertEquals(12.5, n.proteinG)
        assertEquals(3.2, n.fatG)
        assertNull(n.sugarG)
        assertNull(n.fiberG)
    }

    @Test
    fun statusZeroIsNotFound() {
        assertEquals(
            BarcodeLookupResult.NotFound,
            OpenFoodFactsMapper.map("""{"code":"4006381333931","status":0,"status_verbose":"product not found"}"""),
        )
    }

    @Test
    fun missingNutrimentsStillFindTheProduct() {
        val p = found("""{"code":"4006381333931","status":1,"product":{"product_name":"Mystery Bar","brands":"Example"}}""")
        assertEquals("Mystery Bar", p.name)
        assertNull(p.food)
        assertEquals(NutrientFields.ALL_NUTRIENTS, p.missingNutrients)

        // Energy alone is not enough for a food record.
        val partial = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","nutriments":{"energy-kcal_100g":120}}}""",
        )
        assertNull(partial.food)
        assertEquals(
            listOf(NutrientFields.PROTEIN_G, NutrientFields.CARBS_G, NutrientFields.FAT_G),
            partial.missingNutrients.filter { it in NutrientFields.CORE },
        )
    }

    @Test
    fun impossibleValuesAreTreatedAsMissing() {
        val p = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","nutriments":{
                "energy-kcal_100g":250,"proteins_100g":8,"carbohydrates_100g":40,"fat_100g":160}}}""",
        )
        assertNull(p.food)
        assertTrue(NutrientFields.FAT_G in p.missingNutrients)
    }

    @Test
    fun volumeServingHasNoGramWeight() {
        val p = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"Juice","quantity":"1 l",
                "serving_size":"250 ml","serving_quantity":250,"nutriments":{
                "energy-kcal_100g":45,"proteins_100g":0.5,"carbohydrates_100g":10,"fat_100g":0}}}""",
        )
        assertNull(p.servingGrams)
        assertNull(p.packageGrams)
        assertTrue(p.food!!.portions.isEmpty())
    }

    @Test
    fun euProductsGetFibreAddedToCarbohydrate() {
        fun carbs(countries: String) = found(
            """{"code":"4006381333931","status":1,"product":{"product_name":"X","countries_tags":$countries,"nutriments":{
                "energy-kcal_100g":360,"proteins_100g":12,"carbohydrates_100g":60,"fiber_100g":8,"fat_100g":6}}}""",
        ).food!!.per100g.carbsG
        assertEquals(68.0, carbs("""["en:france","en:germany"]"""))
        assertEquals(60.0, carbs("""["en:united-states"]"""))
        assertEquals(60.0, carbs("""["en:france","en:canada"]"""))
    }

    @Test
    fun malformedResponsesFailRetryably() {
        val result = OpenFoodFactsMapper.map("<html>502 Bad Gateway</html>")
        assertIs<BarcodeLookupResult.Failed>(result)
        assertTrue(result.retryable)
        assertIs<BarcodeLookupResult.Failed>(OpenFoodFactsMapper.map("[1,2]"))
    }

    @Test
    fun parsesPackageQuantities() {
        assertEquals(400.0, QuantityText.packageGrams("400 g"))
        assertEquals(400.0, QuantityText.packageGrams("400g"))
        assertEquals(1000.0, QuantityText.packageGrams("1 kg"))
        assertEquals(1500.0, QuantityText.packageGrams("1,5 kg"))
        assertEquals(300.0, QuantityText.packageGrams("2 x 150 g"))
        assertEquals(500.0, QuantityText.packageGrams("4×125g"))
        assertEquals(340.0, QuantityText.packageGrams("12 oz (340 g)"))
        assertEquals(453.59237, QuantityText.packageGrams("1 lb")!!, 1e-9)
        assertNull(QuantityText.packageGrams("500 ml"))
        assertNull(QuantityText.packageGrams("12 fl oz (355 mL)"))
        assertNull(QuantityText.packageGrams("6 x 330 ml"))
        assertNull(QuantityText.packageGrams(null))
    }

    // --- Fake service ---------------------------------------------------------------------

    @Test
    fun fixturesUseValidInStoreGtinsAndPlausibleValues() {
        val fixtures = FakeBarcodeLookupService.fixtures
        assertTrue(fixtures.size in 4..6)
        for ((gtin, product) in fixtures) {
            assertTrue(Gtin.isValid(gtin), gtin)
            assertTrue(gtin.startsWith("2"), "fixtures must use the restricted-circulation range")
            assertEquals(gtin, product.gtin)
            assertTrue(product.name.startsWith("Example"))
            val food = product.food ?: continue
            assertEquals("off:$gtin", food.id)
            val n = food.per100g
            assertTrue(abs(n.energyKcal - n.atwaterEnergyKcal) <= 0.2 * n.energyKcal, product.name)
        }
        assertNull(fixtures.getValue(FakeBarcodeLookupService.INCOMPLETE_GRANOLA_BAR).food)
    }

    @Test
    fun fakeServiceLooksUpNormalisedCodes() = runTest {
        val service = FakeBarcodeLookupService()
        val oats = service.lookup("2 000000 000015")
        assertIs<BarcodeLookupResult.Found>(oats)
        assertEquals("Example Rolled Oats", oats.product.name)
        assertEquals(BarcodeLookupResult.NotFound, service.lookup("4006381333931"))
        val invalid = service.lookup("4006381333932")
        assertIs<BarcodeLookupResult.Failed>(invalid)
        assertFalse(invalid.retryable)

        val custom = FakeBarcodeLookupService(mapOf("036000291452" to oats.product))
        assertIs<BarcodeLookupResult.Found>(custom.lookup("0036000291452"))
    }
}
