package app.nouri.core.model

import kotlinx.serialization.Serializable

/**
 * Where a nutrition value came from. Ordered roughly from most to least
 * authoritative; [authority] is used when several candidate values compete.
 *
 * A visual model's guess is never treated as more authoritative than a label,
 * a measured weight with database values, or a saved recipe.
 */
@Serializable
enum class NutritionSource(val authority: Int, val label: String) {
    /** Values read from the product's own nutrition facts panel. */
    MANUFACTURER_LABEL(100, "Nutrition label"),

    /** Values the user typed in themselves (e.g. from a package). */
    USER_ENTERED(90, "Entered by you"),

    /** Calculated by the deterministic engine from a recipe the user saved. */
    SAVED_RECIPE(85, "Your saved recipe"),

    /** Structured reference database such as USDA FoodData Central / SR28. */
    VERIFIED_DATABASE(80, "Reference database"),

    /** Crowd-sourced branded product data (e.g. Open Food Facts). */
    BRANDED_DATABASE(70, "Product database"),

    /** Calculated from an editorial reference recipe for a named dish. */
    REFERENCE_RECIPE(60, "Typical recipe"),

    /** AI identified the food and estimated the portion; nutrients from a database. */
    AI_ASSISTED_ESTIMATE(40, "AI-assisted estimate"),

    /** Generic fallback values when nothing better is available. */
    APPROXIMATE_FALLBACK(10, "Approximate"),
}

/** Machine- and human-readable provenance for a value or snapshot. */
@Serializable
data class Provenance(
    val source: NutritionSource,
    /** Stable reference, e.g. "usda-sr28:05064", "off:0737628064502", "dish:chicken_biryani". */
    val referenceId: String? = null,
    /** Free-text detail shown to the user ("USDA SR28 — Chicken breast, roasted"). */
    val detail: String? = null,
    /** Optional dataset version, e.g. "SR28 (2016)". */
    val datasetVersion: String? = null,
)

/** Coarse quality grade of a nutrition reference record. */
@Serializable
enum class DataQuality {
    /** Analytical database values or the product's own label. */
    HIGH,

    /** Calculated from ingredient-level data with documented assumptions. */
    CALCULATED,

    /** Crowd-sourced or partially complete data. */
    MEDIUM,

    /** Editorial approximation; should be replaced with better data. */
    APPROXIMATE,
}
