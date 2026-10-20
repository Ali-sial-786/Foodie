package app.nouri.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.nouri.ui.diary.DiaryScreen
import app.nouri.ui.food.AddFoodScreen
import app.nouri.ui.food.EntryDetailScreen
import app.nouri.ui.food.FoodDetailScreen
import app.nouri.ui.home.HomeScreen
import app.nouri.ui.progress.ProgressScreen

@Composable
fun NouriNavHost(navController: NavHostController, startDestination: Route, contentPadding: PaddingValues) {
    val nav = remember(navController) { AppNavigator(navController) }
    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Onboarding> { PlaceholderScreen("Welcome to Nouri", contentPadding) }
        composable<Route.Home> { HomeScreen(nav, contentPadding) }
        composable<Route.Discover> { PlaceholderScreen("Discover", contentPadding) }
        composable<Route.Diary> { DiaryScreen(nav, contentPadding) }
        composable<Route.Progress> { ProgressScreen(nav, contentPadding) }
        composable<Route.Camera> { PlaceholderScreen("Camera", contentPadding) }
        composable<Route.AddFood> { AddFoodScreen(nav, it.toRoute()) }
        composable<Route.FoodDetail> { FoodDetailScreen(nav, it.toRoute()) }
        composable<Route.EntryDetail> { EntryDetailScreen(nav, it.toRoute()) }
    }
}
