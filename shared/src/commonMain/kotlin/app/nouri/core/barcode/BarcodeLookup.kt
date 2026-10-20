package app.nouri.core.barcode

import app.nouri.core.fooddb.FoodReference
import app.nouri.core.ontology.Allergen

/**
 * A packaged product identified by its barcode.
 *
 * [food] is the per-100 g nutrition record, or null when the source lacks energy, protein,
 * carbohydrate or fat; the app then offers to scan the nutrition label instead.
 * [missingNutrients] names every nutrient the source did not provide (see
 * [app.nouri.core.label.NutrientFields]), so partial records are never mistaken for zeros.
 * [allergens] are the allergens the source declares; an empty set means "none listed", not
 * "allergen-free". [attribution] must be shown wherever the data is displayed.
 */
data class BrandedProduct(
    val gtin: String,
    val name: String,
    val brand: String?,
    val packageQuantityText: String?,
    val packageGrams: Double?,
    val servingSizeText: String?,
    val servingGrams: Double?,
    val food: FoodReference?,
    val allergens: Set<Allergen>,
    val missingNutrients: List<String>,
    val attribution: String,
)

/** Outcome of a barcode lookup. */
sealed interface BarcodeLookupResult {
    data class Found(val product: BrandedProduct) : BarcodeLookupResult

    /** The source has no product for this barcode. */
    data object NotFound : BarcodeLookupResult

    /**
     * The lookup could not be completed. [retryable] is true for transient problems (network,
     * server errors, garbled responses) and false when retrying cannot help (invalid barcode).
     */
    data class Failed(val reason: String, val retryable: Boolean) : BarcodeLookupResult
}

/**
 * Looks up a product by GTIN. Implementations normalise the code with [Gtin.normalize] and
 * return [BarcodeLookupResult.Failed] with `retryable = false` for codes that are not valid GTINs.
 * The network implementation lives in the Android app; this module only maps responses.
 */
interface BarcodeLookupService {
    suspend fun lookup(gtin: String): BarcodeLookupResult
}
