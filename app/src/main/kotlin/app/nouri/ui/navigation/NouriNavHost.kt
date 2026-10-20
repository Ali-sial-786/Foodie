package app.nouri.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun NouriNavHost(navController: NavHostController, startDestination: Route, contentPadding: PaddingValues) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Onboarding> { PlaceholderScreen("Welcome to Nouri", contentPadding) }
        composable<Route.Home> { PlaceholderScreen("Nouri", contentPadding) }
        composable<Route.Discover> { PlaceholderScreen("Discover", contentPadding) }
        composable<Route.Diary> { PlaceholderScreen("Diary", contentPadding) }
        composable<Route.Progress> { PlaceholderScreen("Progress", contentPadding) }
        composable<Route.Camera> { PlaceholderScreen("Camera", contentPadding) }
    }
}
