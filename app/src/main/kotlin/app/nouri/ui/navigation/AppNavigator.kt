package app.nouri.ui.navigation

import androidx.navigation.NavHostController

/** Thin wrapper so screens navigate by [Route] without depending on NavController details. */
class AppNavigator(private val nav: NavHostController) {
    fun to(route: Route) = nav.navigate(route) { launchSingleTop = true }

    /** Navigates and removes the current screen, e.g. camera → review. */
    fun replace(route: Route) {
        val current = nav.currentBackStackEntry?.destination?.id
        nav.navigate(route) {
            launchSingleTop = true
            if (current != null) popUpTo(current) { inclusive = true }
        }
    }

    fun back() {
        if (!nav.popBackStack()) nav.navigate(Route.Home)
    }

    /** Returns to a bottom-bar tab, clearing anything stacked above it. */
    fun toTab(route: Route) = nav.navigate(route) {
        popUpTo(Route.Home) { saveState = false }
        launchSingleTop = true
    }

    fun finishOnboarding() = nav.navigate(Route.Home) {
        popUpTo(Route.Onboarding) { inclusive = true }
    }
}
