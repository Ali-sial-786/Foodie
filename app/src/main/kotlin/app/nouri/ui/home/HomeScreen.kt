package app.nouri.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.MealType
import app.nouri.core.recommend.RankedRecipe
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.CalorieRing
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.MacroRow
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.SectionTitle
import app.nouri.ui.components.SimulatedBanner
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.scan.ScanMode

@Composable
fun HomeScreen(nav: AppNavigator, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return LoadingBox(Modifier.padding(contentPadding))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(Fmt.dayLabel(s.today, s.today) + " · " + Fmt.shortDate(s.today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { nav.to(Route.Settings) }) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
            }
        }
        if (s.demoMode) {
            item {
                SimulatedBanner(text = "AI is in demo mode: photo results are simulated. Connect an AI provider in Settings for real analysis.")
            }
        }
        item { TodayCard(s, onOpenDiary = { nav.toTab(Route.Diary) }, onSetTargets = { nav.to(Route.TargetSettings) }) }
        item {
            QuickActions(
                onScan = { mode -> nav.to(Route.Camera(mode.name)) },
                onSearch = { nav.to(Route.AddFood(s.today.toString(), s.suggestedMeal.name)) },
                onDescribe = { nav.to(Route.AddFood(s.today.toString(), s.suggestedMeal.name, mode = "DESCRIBE")) },
            )
        }
        if (s.expiringSoon.isNotEmpty()) {
            item {
                InfoBanner(
                    text = "Use soon: ${s.expiringSoon.joinToString()}",
                    icon = Icons.Outlined.Kitchen,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    action = { TextButton(onClick = { nav.to(Route.Pantry) }) { Text("Pantry") } },
                )
            }
        }
        if (s.recent.isNotEmpty()) {
            item { SectionTitle("Log again") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.recent.forEach { e ->
                        AssistChip(
                            onClick = { vm.relog(e) },
                            label = { Text("${e.name} · ${Fmt.kcal(e.nutrients.energyKcal, e.snapshot.precision)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        if (s.ideas.isNotEmpty()) {
            item {
                SectionTitle("Ideas for ${s.suggestedMeal.label.lowercase()}") {
                    TextButton(onClick = { nav.toTab(Route.Discover) }) { Text("See all") }
                }
            }
            items(s.ideas, key = { it.recipe.id }) { r -> RecipeIdea(r) { nav.to(Route.RecipeDetail(r.recipe.id)) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.to(Route.Pantry) }, label = { Text("Pantry") }, leadingIcon = { Icon(Icons.Outlined.Kitchen, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.Grocery) }, label = { Text("Grocery list") }, leadingIcon = { Icon(Icons.Outlined.ShoppingBasket, null, Modifier.size(18.dp)) })
            }
        }
    }
}

private fun greeting(): String = when (SystemTime.minuteOfDay() / 60) {
    in 4..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun TodayCard(s: HomeState, onOpenDiary: () -> Unit, onSetTargets: () -> Unit) {
    NouriCard(onClick = onOpenDiary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalorieRing(s.summary.consumed.energyKcal, s.summary.targets?.energyKcal, size = 132.dp, approximate = s.summary.containsEstimates)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Eaten today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.kcalUnit(s.summary.consumed.energyKcal), style = MaterialTheme.typography.titleLarge)
                s.summary.targets?.let { Text("Target ${Fmt.kcalUnit(it.energyKcal)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                MealType.entries.forEach { meal ->
                    val total = s.summary.meals.first { it.mealType == meal }.total.energyKcal
                    Text("${meal.label}: ${if (total > 0) Fmt.kcal(total) else "–"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MacroRow(s.summary.consumed, s.summary.targets)
        if (!s.hasTargets) {
            TextButton(onClick = onSetTargets) { Text("Set daily targets (optional)") }
        }
    }
}

@Composable
private fun QuickActions(onScan: (ScanMode) -> Unit, onSearch: () -> Unit, onDescribe: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("Meal photo", Icons.Outlined.Restaurant, Modifier.weight(1f)) { onScan(ScanMode.MEAL) }
        QuickAction("Ingredients", Icons.Outlined.Kitchen, Modifier.weight(1f)) { onScan(ScanMode.INGREDIENTS) }
        QuickAction("Barcode", Icons.Outlined.QrCodeScanner, Modifier.weight(1f)) { onScan(ScanMode.LABEL) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("Search food", Icons.Outlined.Search, Modifier.weight(1f), onSearch)
        QuickAction("Describe a meal", Icons.Outlined.Edit, Modifier.weight(1f), onDescribe)
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RecipeIdea(r: RankedRecipe, onClick: () -> Unit) {
    NouriCard(onClick = onClick) {
        Text(r.recipe.name, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(
                "${r.recipe.totalMinutes} min · ${Fmt.kcal(r.perServing.energyKcal)} kcal · ${Fmt.gramsShort(r.perServing.proteinG)} protein per serving",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (r.reasons.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Why: " + r.reasons.take(2).joinToString("; "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
