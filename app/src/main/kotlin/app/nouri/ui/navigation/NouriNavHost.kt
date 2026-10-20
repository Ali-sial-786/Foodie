package app.nouri.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.nouri.ui.diary.DiaryScreen
import app.nouri.ui.discover.DiscoverScreen
import app.nouri.ui.food.AddFoodScreen
import app.nouri.ui.food.EntryDetailScreen
import app.nouri.ui.food.FoodDetailScreen
import app.nouri.ui.home.HomeScreen
import app.nouri.ui.onboarding.OnboardingScreen
import app.nouri.ui.pantry.GroceryScreen
import app.nouri.ui.pantry.PantryScreen
import app.nouri.ui.progress.ProgressScreen
import app.nouri.ui.recipes.CookModeScreen
import app.nouri.ui.recipes.RecipeDetailScreen
import app.nouri.ui.recipes.RecipeEditorScreen
import app.nouri.ui.scan.CameraScreen
import app.nouri.ui.scan.IngredientReviewScreen
import app.nouri.ui.scan.LabelReviewScreen
import app.nouri.ui.scan.MealReviewScreen
import app.nouri.ui.settings.AiSettingsScreen
import app.nouri.ui.settings.DataSettingsScreen
import app.nouri.ui.settings.PrivacySettingsScreen
import app.nouri.ui.settings.ProfileSettingsScreen
import app.nouri.ui.settings.SettingsScreen
import app.nouri.ui.settings.TargetSettingsScreen

@Composable
fun NouriNavHost(navController: NavHostController, startDestination: Route, contentPadding: PaddingValues) {
    val nav = remember(navController) { AppNavigator(navController) }
    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Onboarding> { OnboardingScreen(nav) }

        composable<Route.Home> { HomeScreen(nav, contentPadding) }
        composable<Route.Discover> { DiscoverScreen(nav, contentPadding) }
        composable<Route.Diary> { DiaryScreen(nav, contentPadding) }
        composable<Route.Progress> { ProgressScreen(nav, contentPadding) }

        composable<Route.Camera> { CameraScreen(nav, it.toRoute()) }
        composable<Route.MealReview> { MealReviewScreen(nav, it.toRoute()) }
        composable<Route.IngredientReview> { IngredientReviewScreen(nav, it.toRoute()) }
        composable<Route.LabelReview> { LabelReviewScreen(nav, it.toRoute()) }

        composable<Route.AddFood> { AddFoodScreen(nav, it.toRoute()) }
        composable<Route.FoodDetail> { FoodDetailScreen(nav, it.toRoute()) }
        composable<Route.EntryDetail> { EntryDetailScreen(nav, it.toRoute()) }

        composable<Route.RecipeDetail> { RecipeDetailScreen(nav, it.toRoute()) }
        composable<Route.CookMode> { CookModeScreen(nav, it.toRoute()) }
        composable<Route.RecipeEditor> { RecipeEditorScreen(nav, it.toRoute()) }
        composable<Route.Pantry> { PantryScreen(nav) }
        composable<Route.Grocery> { GroceryScreen(nav) }

        composable<Route.Settings> { SettingsScreen(nav) }
        composable<Route.AiSettings> { AiSettingsScreen(nav) }
        composable<Route.PrivacySettings> { PrivacySettingsScreen(nav) }
        composable<Route.DataSettings> { DataSettingsScreen(nav) }
        composable<Route.TargetSettings> { TargetSettingsScreen(nav) }
        composable<Route.ProfileSettings> { ProfileSettingsScreen(nav) }
    }
}
