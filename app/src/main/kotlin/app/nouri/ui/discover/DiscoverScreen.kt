package app.nouri.ui.discover

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
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recommend.RankedRecipe
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.recipes.OriginBadge
import app.nouri.ui.recipes.RecipeText

@Composable
fun DiscoverScreen(nav: AppNavigator, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm = viewModel { DiscoverViewModel(container) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var showHidden by rememberSaveable { mutableStateOf(false) }

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
                Text("Discover", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = { nav.to(Route.RecipeImport) }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Import a recipe from text or screenshots") }
                IconButton(onClick = { nav.to(Route.RecipeEditor()) }) { Icon(Icons.Outlined.Add, contentDescription = "New recipe") }
            }
        }
        item {
            OutlinedTextField(
                value = filters.query,
                onValueChange = { q -> vm.update { it.copy(query = q) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search recipes, cuisines, ingredients") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (filters.query.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear search") }
                },
                singleLine = true,
            )
        }
        item { FilterRow(filters, state?.cuisines.orEmpty(), vm) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.to(Route.RecipeImport) }, label = { Text("Import recipe") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.Pantry) }, label = { Text("Pantry") }, leadingIcon = { Icon(Icons.Outlined.Kitchen, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.Grocery) }, label = { Text("Grocery list") }, leadingIcon = { Icon(Icons.Outlined.ShoppingBasket, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.RecipeEditor()) }, label = { Text("New recipe") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
            }
        }
        val s = state
        if (s == null) {
            item { LoadingBox(label = "Ranking recipes…") }
        } else {
            if (filters.usePantry && s.pantryCount == 0) {
                item {
                    Text(
                        "Your pantry is empty, so recipes aren't matched to ingredients yet. Add what you have in Pantry, or scan your ingredients.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.results.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.RestaurantMenu, "No recipes match",
                        "Try removing a filter or searching for something else.",
                    )
                }
            }
            items(s.results, key = { it.recipe.id }) { r ->
                RecipeCard(r, favourite = r.recipe.id in s.favourites) { nav.to(Route.RecipeDetail(r.recipe.id)) }
            }
            if (s.hiddenMissingIngredients > 0) {
                item {
                    Text(
                        "${s.hiddenMissingIngredients} more need ingredients you don't have. Turn off “Only what I have” to see them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.hiddenByPreferences.isNotEmpty()) {
                item {
                    NouriCard(onClick = { showHidden = !showHidden }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${s.hiddenByPreferences.size} hidden by your food preferences",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            Text(if (showHidden) "Hide" else "Why?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (showHidden) {
                            Spacer(Modifier.height(8.dp))
                            s.hiddenByPreferences.forEach { e ->
                                Text("• ${e.recipe.name}: ${e.reason}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Allergies, required diets and dislikes are strict filters. Change them in Settings › Food preferences.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(f: DiscoverFilters, cuisines: List<Pair<String, String>>, vm: DiscoverViewModel) {
    var cuisineMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Box {
                FilterChip(
                    selected = f.cuisineId != null,
                    onClick = { cuisineMenu = true },
                    label = { Text(f.cuisineId?.let { CuisineCatalog[it]?.name } ?: "Any cuisine") },
                )
                DropdownMenu(expanded = cuisineMenu, onDismissRequest = { cuisineMenu = false }) {
                    DropdownMenuItem(text = { Text("Any cuisine") }, onClick = { vm.update { it.copy(cuisineId = null) }; cuisineMenu = false })
                    cuisines.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { vm.update { it.copy(cuisineId = id) }; cuisineMenu = false })
                    }
                }
            }
            androidx.compose.foundation.layout.Box {
                FilterChip(
                    selected = f.maxMinutes != null,
                    onClick = { timeMenu = true },
                    label = { Text(f.maxMinutes?.let { "≤ $it min" } ?: "Any time") },
                    leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                    DropdownMenuItem(text = { Text("Any time") }, onClick = { vm.update { it.copy(maxMinutes = null) }; timeMenu = false })
                    listOf(20, 30, 45).forEach { m ->
                        DropdownMenuItem(text = { Text("$m minutes or less") }, onClick = { vm.update { it.copy(maxMinutes = m) }; timeMenu = false })
                    }
                }
            }
            FilterChip(selected = f.usePantry, onClick = { vm.update { it.copy(usePantry = !it.usePantry, onlyAvailable = if (it.usePantry) false else it.onlyAvailable) } }, label = { Text("Use my pantry") })
            if (f.usePantry) {
                FilterChip(selected = f.onlyAvailable, onClick = { vm.update { it.copy(onlyAvailable = !it.onlyAvailable) } }, label = { Text("Only what I have") })
            }
            FilterChip(selected = f.highProtein, onClick = { vm.update { it.copy(highProtein = !it.highProtein) } }, label = { Text("High protein") })
            FilterChip(selected = f.favouritesOnly, onClick = { vm.update { it.copy(favouritesOnly = !it.favouritesOnly) } }, label = { Text("Favourites") })
        }
    }
}

@Composable
private fun RecipeCard(r: RankedRecipe, favourite: Boolean, onClick: () -> Unit) {
    val recipe = r.recipe
    val cuisine = CuisineCatalog[recipe.cuisineId]?.name
    NouriCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(cuisine, recipe.region).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (favourite) Icon(Icons.Outlined.Favorite, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${RecipeText.minutes(recipe.totalMinutes)} · ${Fmt.kcal(r.perServing.energyKcal)} kcal · ${Fmt.gramsShort(r.perServing.proteinG)} protein per serving",
            style = MaterialTheme.typography.bodySmall,
        )
        if (recipe.origin != RecipeOrigin.EDITORIAL_TRADITIONAL) OriginBadge(recipe.origin, Modifier.padding(top = 6.dp))
        r.pantry?.let { p ->
            if (p.required.isNotEmpty()) {
                Text(
                    if (p.missing.isEmpty()) "You have everything" else "Missing ${p.missing.size}: ${p.missing.take(3).joinToString { it.line.displayName.lowercase() }}${if (p.missing.size > 3) "…" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.missing.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val reasons = r.reasons.filterNot { it.startsWith("Uses ") && r.pantry != null }.take(2)
        if (reasons.isNotEmpty()) {
            Text("Why: " + reasons.joinToString("; "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
