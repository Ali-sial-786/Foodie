package app.nouri.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.nouri.ui.LocalAppContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.nouri.ui.scan.ScanMode
import kotlin.reflect.KClass

private data class Tab(val route: Route, val routeClass: KClass<out Route>, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab(Route.Home, Route.Home::class, "Home", Icons.Outlined.Home),
    Tab(Route.Discover, Route.Discover::class, "Discover", Icons.Outlined.Explore),
    Tab(Route.Diary, Route.Diary::class, "Diary", Icons.Outlined.Book),
    Tab(Route.Progress, Route.Progress::class, "Progress", Icons.AutoMirrored.Outlined.ShowChart),
)

/** Root of the UI: bottom navigation with a central Scan action, and the navigation graph. */
@Composable
fun NouriApp(onboardingComplete: Boolean, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = destination != null && Tabs.any { destination.hasRoute(it.routeClass) }
    // Fixed for the life of the graph; finishing onboarding navigates to Home itself.
    val start = remember { if (onboardingComplete) Route.Home else Route.Onboarding }

    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(container) {
        container.messages.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.onAction?.let { action -> scope.launch { action() } }
        }
    }

    Scaffold(
        bottomBar = { if (showBar) NouriBottomBar(destination, navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NouriNavHost(
            navController = navController,
            startDestination = start,
            contentPadding = if (showBar) padding else PaddingValues(),
        )
    }
}

@Composable
private fun NouriBottomBar(destination: NavDestination?, navController: NavHostController) {
    NavigationBar {
        Tabs.forEachIndexed { index, tab ->
            if (index == 2) {
                // The camera is the app's primary action, so it sits in the middle and stands out.
                Box(Modifier.weight(1f).padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    FilledIconButton(
                        onClick = { navController.navigate(Route.Camera(ScanMode.MEAL.name)) },
                        modifier = Modifier.size(56.dp).semantics { contentDescription = "Scan food" },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
            }
            val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/** Temporary body for destinations whose screens are not built yet. */
@Composable
internal fun PlaceholderScreen(title: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
