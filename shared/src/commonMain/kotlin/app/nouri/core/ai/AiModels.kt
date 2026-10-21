package app.nouri.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Structured outputs exchanged with vision/language providers. Providers identify
 * foods and estimate portions; they never supply authoritative nutrition. Calories
 * are calculated afterwards by the deterministic engine from reference data.
 */

/** Qualitative likelihood. Used instead of model-generated percentages, which are not calibrated. */
@Serializable
enum class Likelihood {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

@Serializable
data class GramRange(val low: Double, val typical: Double, val high: Double) {
    fun isValid(): Boolean = low > 0 && low <= typical && typical <= high && high < 5000
}

@Serializable
data class DishCandidate(
    val name: String,
    val likelihood: Likelihood = Likelihood.MEDIUM,
    /** Short visual reason ("orange-tinted layered grains, fried onions"). */
    val reason: String? = null,
)

@Serializable
data class DetectedFoodItem(
    /** Best label for this item as it should appear to the user. */
    val label: String,
    val candidates: List<DishCandidate> = emptyList(),
    val cuisine: String? = null,
    /** Visible components ("rice", "chicken on the bone", "fried onions"). */
    val components: List<String> = emptyList(),
    val cookingMethod: String? = null,
    /** "none" | "light" | "medium" | "heavy" — visible oil/butter/cream. */
    val visibleFat: String? = null,
    val estimatedGrams: GramRange? = null,
    val householdEstimate: String? = null,
    /** How the portion was judged (reference object, plate assumption, packaging). */
    val portionBasis: String? = null,
    val isSauceOrCondiment: Boolean = false,
    val partiallyHidden: Boolean = false,
)

@Serializable
data class ReferenceObject(val name: String, val note: String? = null)

@Serializable
data class ClarifyingQuestion(
    val text: String,
    val options: List<String>,
    /** Index into [MealAnalysis.items] the question refers to, if any. */
    val itemIndex: Int? = null,
)

@Serializable
data class MealAnalysis(
    val items: List<DetectedFoodItem>,
    val referenceObjects: List<ReferenceObject> = emptyList(),
    /** Regions the model could not identify ("dark sauce in a small bowl"). */
    val unidentified: List<String> = emptyList(),
    val suggestedQuestion: ClarifyingQuestion? = null,
    /** "good" | "blurry" | "dark" | "partial" | "not_food". */
    val imageQuality: String = "good",
    val notes: String? = null,
)

@Serializable
enum class IngredientCertainty {
    /** Clearly visible and recognisable. */
    @SerialName("clear") CLEAR,

    /** Probably this item. */
    @SerialName("likely") LIKELY,

    /** Partly hidden or ambiguous; never added to the pantry without confirmation. */
    @SerialName("possible") POSSIBLE,
}

@Serializable
data class DetectedIngredient(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val packaged: Boolean = false,
    val certainty: IngredientCertainty = IngredientCertainty.LIKELY,
    val notes: String? = null,
)

@Serializable
data class IngredientAnalysis(
    val items: List<DetectedIngredient>,
    /** "fridge" | "pantry" | "counter" | "grocery" | "other". */
    val scene: String? = null,
    val notes: String? = null,
)

@Serializable
data class GeneratedIngredient(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val grams: Double? = null,
    val preparation: String? = null,
    val optional: Boolean = false,
    val group: String? = null,
)

@Serializable
data class GeneratedStep(val text: String, val timerMinutes: Double? = null, val temperatureC: Int? = null)

@Serializable
data class GeneratedSubstitution(val ingredient: String, val substitute: String, val note: String? = null)

@Serializable
data class GeneratedRecipe(
    val name: String,
    val cuisine: String,
    val region: String? = null,
    val description: String = "",
    /** True only for an established traditional preparation; variations must say false. */
    val isTraditional: Boolean = false,
    val variantOf: String? = null,
    val servings: Int,
    val prepMinutes: Int,
    val cookMinutes: Int,
    /** "easy" | "medium" | "involved". */
    val difficulty: String = "medium",
    val equipment: List<String> = emptyList(),
    val ingredients: List<GeneratedIngredient>,
    val steps: List<GeneratedStep>,
    val substitutions: List<GeneratedSubstitution> = emptyList(),
    val foodSafety: List<String> = emptyList(),
    val storage: String? = null,
    val reheating: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class GeneratedRecipes(val recipes: List<GeneratedRecipe>, val notes: String? = null)

@Serializable
data class ParsedFoodMention(
    val food: String,
    val amount: Double? = null,
    val unit: String? = null,
    val grams: Double? = null,
    val preparation: String? = null,
    /** e.g. 0.5 when the user says "half of the biryani I made yesterday". */
    val recipeFraction: Double? = null,
    val recipeReference: String? = null,
    /** Brand, shop or restaurant the user named ("Deep" in "2 Deep naans"); [food] stays generic. */
    val brand: String? = null,
)

@Serializable
data class ParsedMealText(val items: List<ParsedFoodMention>, val mealHint: String? = null, val notes: String? = null)

@Serializable
data class LabelReading(
    val productName: String? = null,
    /** Verbatim transcription of the nutrition panel; parsed by the deterministic label parser. */
    val panelText: String,
    val language: String? = null,
)
