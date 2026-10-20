package app.nouri.core.scan

import app.nouri.core.ai.ClarifyingQuestion
import app.nouri.core.ai.DetectedFoodItem
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.Nutrients
import app.nouri.core.units.Quantity

/** How a detected item was linked to nutrition data (Stage 3). */
enum class MatchMethod(val label: String) {
    USER_SELECTED("Chosen by you"),
    USER_CORRECTION_MEMORY("From your past corrections"),
    SAVED_FOOD("Your saved food"),
    DISH_REFERENCE("Typical recipe"),
    INGREDIENT_REFERENCE("Reference database"),
    DATABASE_SEARCH("Closest database match"),
    UNMATCHED("Not matched"),
}

data class FoodMatch(
    val food: FoodReference,
    val method: MatchMethod,
    val dishId: String? = null,
    val ingredientId: String? = null,
    /** Name shown to the user. */
    val label: String = food.name,
    /** Relative spread of energy density for this kind of food (preparation variability). */
    val energyVariability: Double = 0.1,
    val variabilityNote: String? = null,
    val restaurantEnergyFactor: Double = 1.0,
)

/** Where the portion weight came from (Stage 2), ordered by reliability. */
enum class PortionSource(val label: String) {
    USER_WEIGHED("Weighed"),
    LABEL_SERVING("Package serving"),
    USER_ADJUSTED("Adjusted by you"),
    MULTI_ANGLE("Estimated from several photos"),
    REFERENCE_OBJECT("Estimated using a reference object"),
    LEARNED_PREFERENCE("Your usual portion"),
    AI_ESTIMATE("Estimated from the photo"),
    TYPICAL_SERVING("Typical serving"),
}

data class PortionEstimate(
    val grams: Double,
    val lowGrams: Double,
    val highGrams: Double,
    val source: PortionSource,
    /** As entered when the user typed a household quantity ("2 rotis", "1 cup"). */
    val quantity: Quantity? = null,
    val householdText: String? = null,
    val basis: String? = null,
) {
    init {
        require(grams >= 0 && lowGrams <= grams && grams <= highGrams) { "Inconsistent portion: $lowGrams..$grams..$highGrams" }
    }

    companion object {
        fun exact(grams: Double, source: PortionSource, quantity: Quantity? = null) =
            PortionEstimate(grams, grams, grams, source, quantity)
    }
}

enum class PrepContext(val label: String) { UNKNOWN("Not specified"), HOMEMADE("Homemade"), RESTAURANT("Restaurant / takeaway") }

data class AddedFat(val ingredientId: String, val grams: Double, val label: String)

data class Preparation(
    val context: PrepContext = PrepContext.UNKNOWN,
    val addedFats: List<AddedFat> = emptyList(),
    val cookingMethod: String? = null,
)

data class ItemConfidence(
    val identification: ConfidenceLevel,
    val portion: ConfidenceLevel,
    val data: ConfidenceLevel,
) {
    val overall: ConfidenceLevel get() = ConfidenceLevel.min(identification, portion, data)
}

data class ScanItem(
    val id: String,
    /** Raw provider output; null for items the user added. */
    val detected: DetectedFoodItem?,
    val match: FoodMatch?,
    val alternatives: List<FoodMatch>,
    val portion: PortionEstimate,
    val preparation: Preparation,
    val nutrients: Nutrients,
    val energyRange: EstimateRange,
    val confidence: ItemConfidence,
    val included: Boolean = true,
    val notes: List<String> = emptyList(),
    val userEdited: Boolean = false,
    /** The provider's original match, kept to learn from corrections. */
    val originalMatchId: String? = null,
) {
    val displayName: String get() = match?.label ?: detected?.label ?: "Unknown food"
    val isUnmatched: Boolean get() = match == null
}

data class MealScanResult(
    val scanId: String,
    val items: List<ScanItem>,
    val question: ClarifyingQuestion?,
    val total: Nutrients,
    val totalEnergyRange: EstimateRange,
    val overallConfidence: ConfidenceLevel,
    /** Plain-language summary: "Estimated 520–720 kcal. The amount of cooking oil and rice is uncertain." */
    val explanation: String,
    val simulated: Boolean,
    val imageQuality: String,
    val warnings: List<String>,
    val unidentified: List<String>,
) {
    val includedItems: List<ScanItem> get() = items.filter { it.included }
}

/** User corrections (Stage 6). Every edit is followed by deterministic recalculation. */
sealed interface ScanEdit {
    val itemId: String?

    data class ChangeFood(override val itemId: String, val match: FoodMatch) : ScanEdit
    data class SetGrams(override val itemId: String, val grams: Double, val weighed: Boolean = false) : ScanEdit
    data class SetQuantity(override val itemId: String, val quantity: Quantity) : ScanEdit
    data class ScalePortion(override val itemId: String, val factor: Double) : ScanEdit
    data class SetPreparation(override val itemId: String, val context: PrepContext) : ScanEdit
    data class AddFat(override val itemId: String, val fat: AddedFat) : ScanEdit
    data class ClearAddedFats(override val itemId: String) : ScanEdit
    data class SetIncluded(override val itemId: String, val included: Boolean) : ScanEdit
    data class Remove(override val itemId: String) : ScanEdit
    data class AddItem(val match: FoodMatch, val grams: Double, val quantity: Quantity? = null) : ScanEdit {
        override val itemId: String? = null
    }
    /** Answer to [MealScanResult.question]. */
    data class Answer(val option: String) : ScanEdit {
        override val itemId: String? = null
    }
}
