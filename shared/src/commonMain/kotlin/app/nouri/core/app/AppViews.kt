package app.nouri.core.app

/*
 * Plain view models for platforms that render with their own UI toolkit (the iOS app).
 * Fields are strings, numbers, booleans and lists so they bridge cleanly to Swift;
 * numbers are also given pre-formatted so every platform shows them the same way.
 * Enum values cross the boundary as their `name` strings.
 */

class OptionView(val id: String, val label: String)

class NutritionView(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val kcalText: String,
    /** "P 32 g · C 45 g · F 12 g" */
    val macrosText: String,
)

class EntryView(
    val id: String,
    val name: String,
    /** Amount and source, e.g. "150 g · USDA reference". */
    val detail: String,
    val nutrition: NutritionView,
    /** True for estimates (photo scans, typical portions); [rangeText] then gives the range. */
    val isEstimate: Boolean,
    val rangeText: String?,
    val confidenceLabel: String,
)

class MealView(val mealId: String, val label: String, val entries: List<EntryView>, val total: NutritionView)

class DayView(
    val dateIso: String,
    /** "Today", "Yesterday" or "Tue 21 Oct". */
    val title: String,
    val consumed: NutritionView,
    val targetKcal: Double?,
    val remainingKcal: Double?,
    /** 0–1+ share of the energy target eaten, or null without a target. */
    val progress: Double?,
    val meals: List<MealView>,
    val containsEstimates: Boolean,
)

class FoodHitView(val token: String, val title: String, val subtitle: String, val kcalPer100gText: String)

class PortionOptionView(val index: Int, val label: String, val defaultAmount: Double)

class FoodPreviewView(
    val nutrition: NutritionView,
    val gramsText: String?,
    val isEstimate: Boolean,
    val rangeText: String?,
    val notes: List<String>,
    val canLog: Boolean,
)

class DescribedItemView(
    val name: String,
    val amountText: String,
    val nutrition: NutritionView,
    val resolved: Boolean,
    val notes: List<String>,
)

class DescribeResultView(
    val items: List<DescribedItemView>,
    val total: NutritionView,
    val usedAi: Boolean,
    val simulated: Boolean,
    val error: String?,
)

/** Result of a log action: logged, a possible duplicate the user should confirm, or an error. */
class LogOutcomeView(val logged: Boolean, val duplicateMessage: String?, val error: String?, val count: Int)

class RecipeCardView(
    val id: String,
    val name: String,
    /** "Main · Indian · Punjabi" */
    val subtitle: String,
    val minutes: Int,
    val nutrition: NutritionView,
    val difficulty: String,
    /** 1–3 */
    val difficultyLevel: Int,
    val rating: Int?,
    val cookedCount: Int,
    val favourite: Boolean,
    val dietLabel: String?,
    val originLabel: String,
    val isTraditional: Boolean,
    val reasons: List<String>,
    val versionCount: Int,
    /** Versions of this dish, main recipe first (empty when it stands alone). */
    val versions: List<RecipeVersionView>,
)

class RecipeVersionView(
    val id: String,
    val name: String,
    val label: String,
    val minutes: Int,
    val kcalText: String,
    val difficulty: String,
    val difficultyLevel: Int,
    val rating: Int?,
)

class CuisineOptionView(val id: String, val name: String, val count: Int)

class RegionGroupView(val regionId: String, val label: String, val count: Int, val cuisines: List<CuisineOptionView>)

class RecipeSearchView(
    val dishes: List<RecipeCardView>,
    val matchingRecipes: Int,
    val total: Int,
    val hiddenByPreferences: Int,
    val regions: List<RegionGroupView>,
)

class IngredientLineView(val text: String, val group: String?, val optional: Boolean)

class StepView(val number: Int, val text: String, val timerText: String?)

class RecipeDetailView(
    val id: String,
    val name: String,
    val subtitle: String,
    /** The recipe's description (named so it doesn't clash with Objective-C's `description`). */
    val about: String,
    val originLabel: String,
    val attribution: String?,
    val variantSummary: String?,
    val servings: Int,
    val baseServings: Int,
    val minutesText: String,
    val difficulty: String,
    val difficultyLevel: Int,
    val perServing: NutritionView,
    val completenessText: String,
    val confidenceLabel: String,
    val diets: List<String>,
    val allergens: List<String>,
    val halalNotes: List<String>,
    val ingredients: List<IngredientLineView>,
    val steps: List<StepView>,
    val equipment: List<String>,
    val safety: List<String>,
    val notes: List<String>,
    val scaleNotes: List<String>,
    val rating: Int?,
    val favourite: Boolean,
    val cookedCount: Int,
    val versions: List<RecipeVersionView>,
)

class WeightPointView(val dateIso: String, val scale: Double, val trend: Double)

class WeightView(
    val unit: String,
    val entries: List<WeightEntryView>,
    val points: List<WeightPointView>,
    val latestText: String?,
    /** "Trend: −0.4 kg per week" */
    val rateText: String?,
)

class WeightEntryView(val id: String, val dateIso: String, val valueText: String)

class DayTotalView(val dateIso: String, val label: String, val kcal: Double, val targetKcal: Double?)

class ProviderView(
    val id: String,
    val label: String,
    val selected: Boolean,
    val connected: Boolean,
    val accountLabel: String?,
    val billing: String,
    val simulated: Boolean,
    val restrictions: List<String>,
)

class SettingsView(
    val onboarded: Boolean,
    val sex: String,
    val birthYear: Int?,
    val heightCm: Double?,
    val activity: String,
    val goal: String,
    val weightUnit: String,
    val measurementSystem: String,
    val targetKcal: Double?,
    val targetProteinG: Double?,
    val targetCarbsG: Double?,
    val targetFatG: Double?,
    val targetsBasis: String?,
    val targetsManual: Boolean,
    val provider: String,
    val photoConsent: Boolean,
    val allowApiKeyFallback: Boolean,
    val hasApiKey: Boolean,
    val onlineProductLookup: Boolean,
    val dayStartMinute: Int,
    val requiredDiets: List<String>,
    val allergies: List<String>,
)

class ScanItemView(
    val id: String,
    val name: String,
    val grams: Double,
    val portionText: String,
    val nutrition: NutritionView,
    val rangeText: String,
    val confidenceLabel: String,
    val included: Boolean,
    val notes: List<String>,
)

class ScanResultView(
    val items: List<ScanItemView>,
    val total: NutritionView,
    val rangeText: String,
    val explanation: String,
    val confidenceLabel: String,
    val simulated: Boolean,
    val warnings: List<String>,
    val error: String?,
)

class ProductView(val token: String?, val title: String, val subtitle: String, val kcalPer100gText: String?, val error: String?)
