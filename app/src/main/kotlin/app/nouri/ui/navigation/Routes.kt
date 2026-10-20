package app.nouri.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe destinations. Arguments are small strings; large state travels through repositories. */
sealed interface Route {
    @Serializable data object Onboarding : Route

    // Bottom-bar tabs
    @Serializable data object Home : Route
    @Serializable data object Discover : Route
    @Serializable data object Diary : Route
    @Serializable data object Progress : Route

    // Scanning
    @Serializable data class Camera(val mode: String, val barcode: String? = null) : Route
    @Serializable data class MealReview(val captureId: String) : Route
    @Serializable data class IngredientReview(val captureId: String) : Route
    @Serializable data class LabelReview(val captureId: String) : Route

    // Logging
    @Serializable data class AddFood(val date: String, val meal: String, val mode: String = "SEARCH") : Route
    @Serializable data class FoodDetail(val foodId: String, val date: String, val meal: String, val title: String? = null) : Route
    @Serializable data class EntryDetail(val entryId: String) : Route

    // Recipes and kitchen
    @Serializable data class RecipeDetail(val recipeId: String) : Route
    @Serializable data class CookMode(val recipeId: String, val servings: Double) : Route
    @Serializable data class RecipeEditor(val recipeId: String? = null) : Route
    @Serializable data object Pantry : Route
    @Serializable data object Grocery : Route

    // Settings
    @Serializable data object Settings : Route
    @Serializable data object AiSettings : Route
    @Serializable data object PrivacySettings : Route
    @Serializable data object TargetSettings : Route
    @Serializable data object ProfileSettings : Route
    @Serializable data object DataSettings : Route
}
