package app.nouri.core.barcode

import app.nouri.core.fooddb.FoodReference
import app.nouri.core.label.NutrientFields
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.ontology.Allergen
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit

/**
 * Offline [BarcodeLookupService] for mock mode, previews and tests. Codes are normalised with
 * [Gtin.normalize]; invalid codes fail (not retryable), unknown valid codes are not found.
 *
 * The default [fixtures] are fictional products with generic names and plausible label values.
 * Their GTINs use GS1 prefix 20 (restricted in-store circulation), so they can never collide with
 * a real product.
 */
class FakeBarcodeLookupService(
    products: Map<String, BrandedProduct> = FakeBarcodeLookupService.fixtures,
) : BarcodeLookupService {

    private val products: Map<String, BrandedProduct> = products.mapKeys { (code, _) -> Gtin.normalize(code) ?: code }

    override suspend fun lookup(gtin: String): BarcodeLookupResult {
        val code = Gtin.normalize(gtin)
            ?: return BarcodeLookupResult.Failed("\"$gtin\" is not a valid barcode", retryable = false)
        return products[code]?.let { BarcodeLookupResult.Found(it) } ?: BarcodeLookupResult.NotFound
    }

    companion object {
        const val FIXTURE_ATTRIBUTION = "Sample product (fictional data for mock mode)"

        const val ROLLED_OATS = "2000000000015"
        const val GREEK_YOGURT = "2000000000022"
        const val WHOLE_WHEAT_BREAD = "2000000000039"
        const val PEANUT_BUTTER = "2000000000046"
        const val ALMOND_DRINK = "2000000000053"

        /** Has no nutrition data, to exercise the "scan the label instead" path. */
        const val INCOMPLETE_GRANOLA_BAR = "2000000000060"

        val fixtures: Map<String, BrandedProduct> by lazy {
            listOf(
                fixture(
                    ROLLED_OATS, "Example Rolled Oats", "1 kg", 1000.0, "40 g", 40.0,
                    Nutrients(379.0, 13.2, 67.7, 6.5, fiberG = 10.1, sugarG = 1.0, sodiumMg = 6.0, saturatedFatG = 1.1),
                    setOf(Allergen.GLUTEN),
                ),
                fixture(
                    GREEK_YOGURT, "Example Greek Yogurt 0%", "500 g", 500.0, "170 g", 170.0,
                    Nutrients(59.0, 10.3, 3.6, 0.4, fiberG = 0.0, sugarG = 3.2, sodiumMg = 36.0, saturatedFatG = 0.1),
                    setOf(Allergen.MILK),
                ),
                fixture(
                    WHOLE_WHEAT_BREAD, "Example Whole Wheat Bread", "675 g", 675.0, "1 slice (38 g)", 38.0,
                    Nutrients(252.0, 12.4, 43.1, 3.5, fiberG = 6.0, sugarG = 4.4, sodiumMg = 450.0, saturatedFatG = 0.7),
                    setOf(Allergen.WHEAT, Allergen.GLUTEN),
                ),
                fixture(
                    PEANUT_BUTTER, "Example Smooth Peanut Butter", "500 g", 500.0, "2 tbsp (32 g)", 32.0,
                    Nutrients(588.0, 25.0, 20.0, 50.0, fiberG = 6.0, sugarG = 9.0, sodiumMg = 430.0, saturatedFatG = 10.0),
                    setOf(Allergen.PEANUT),
                ),
                // A drink: package and serving are volumes, so there is no gram weight for either.
                fixture(
                    ALMOND_DRINK, "Example Unsweetened Almond Drink", "1 l", null, "1 cup (240 ml)", null,
                    Nutrients(15.0, 0.4, 0.6, 1.1, fiberG = 0.2, sugarG = 0.0, sodiumMg = 63.0, saturatedFatG = 0.1),
                    setOf(Allergen.TREE_NUT),
                ),
                fixture(
                    INCOMPLETE_GRANOLA_BAR, "Example Granola Bar", "6 x 35 g", 210.0, "1 bar (35 g)", 35.0,
                    per100g = null,
                    allergens = setOf(Allergen.GLUTEN, Allergen.TREE_NUT),
                ),
            ).associateBy { it.gtin }
        }

        private fun fixture(
            gtin: String,
            name: String,
            packageText: String,
            packageGrams: Double?,
            servingText: String,
            servingGrams: Double?,
            per100g: Nutrients?,
            allergens: Set<Allergen>,
        ): BrandedProduct = BrandedProduct(
            gtin = gtin,
            name = name,
            brand = "Example Foods",
            packageQuantityText = packageText,
            packageGrams = packageGrams,
            servingSizeText = servingText,
            servingGrams = servingGrams,
            food = per100g?.let {
                FoodReference(
                    id = "off:$gtin",
                    name = name,
                    per100g = it,
                    source = NutritionSource.BRANDED_DATABASE,
                    quality = DataQuality.MEDIUM,
                    portions = listOfNotNull(servingGrams?.let { g -> FoodPortion(QuantityUnit.SERVING, g, label = servingText) }),
                    servingGrams = servingGrams,
                    sourceDetail = FIXTURE_ATTRIBUTION,
                )
            },
            allergens = allergens,
            missingNutrients = if (per100g == null) NutrientFields.ALL_NUTRIENTS else emptyList(),
            attribution = FIXTURE_ATTRIBUTION,
        )
    }
}
