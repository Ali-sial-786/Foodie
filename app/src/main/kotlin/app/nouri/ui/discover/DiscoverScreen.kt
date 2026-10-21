package app.nouri.ui.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.ontology.DietGoal
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.WorldRegion
import app.nouri.core.recommend.CuisineCount
import app.nouri.core.recommend.DishResult
import app.nouri.core.recommend.RecipeSearch
import app.nouri.core.recommend.RecipeSort
import app.nouri.core.recommend.RecipeUsage
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.recipes.OriginBadge
import app.nouri.ui.recipes.RecipeIndicators
import app.nouri.ui.recipes.RecipeText
import app.nouri.ui.recipes.VersionsSheet
import app.nouri.ui.recipes.toVersion

@Composable
fun DiscoverScreen(nav: AppNavigator, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm = viewModel { DiscoverViewModel(container) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var versionsOf by remember { mutableStateOf<DishResult?>(null) }
    versionsOf?.let { dish ->
        VersionsSheet(
            title = dish.versions.first().recipe.name, versions = dish.versions.map { it.toVersion(state?.usage.orEmpty()) }, currentId = null,
            onDismiss = { versionsOf = null },
        ) { id ->
            versionsOf = null
            nav.to(Route.RecipeDetail(id))
        }
    }

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
        item { FilterRow(filters, state?.search?.cuisines.orEmpty(), vm) }
        state?.search?.let { s ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.matchingRecipes == s.total) "${s.results.size} dishes · ${s.total} recipes"
                        else "${s.results.size} dishes · ${s.matchingRecipes} of ${s.total} recipes match",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (filters.activeCount > 0) {
                        TextButton(onClick = { vm.update { it.cleared() } }) { Text("Clear filters (${filters.activeCount})") }
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.to(Route.RecipeImport) }, label = { Text("Import recipe") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.Pantry) }, label = { Text("Pantry") }, leadingIcon = { Icon(Icons.Outlined.Kitchen, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.Grocery) }, label = { Text("Grocery list") }, leadingIcon = { Icon(Icons.Outlined.ShoppingBasket, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { nav.to(Route.RecipeEditor()) }, label = { Text("New recipe") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
            }
        }
        val s = state?.search
        val usage = state?.usage.orEmpty()
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
            items(s.results, key = { it.shown.recipe.id }) { dish ->
                RecipeCard(
                    dish, usage = usage[dish.shown.recipe.id], favourite = dish.shown.recipe.id in s.favourites,
                    onVersions = { versionsOf = dish },
                ) { nav.to(Route.RecipeDetail(dish.shown.recipe.id)) }
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
                            s.hiddenByPreferences.take(30).forEach { e ->
                                Text("• ${e.recipe.name}: ${e.reason}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (s.hiddenByPreferences.size > 30) {
                                Text("…and ${s.hiddenByPreferences.size - 30} more", style = MaterialTheme.typography.bodySmall)
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
private fun FilterRow(f: DiscoverFilters, cuisines: List<Pair<WorldRegion, List<CuisineCount>>>, vm: DiscoverViewModel) {
    var cuisineSheet by remember { mutableStateOf(false) }
    var courseMenu by remember { mutableStateOf(false) }
    var dietMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    var kcalMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterChip(
                selected = f.sort != RecipeSort.BEST_MATCH,
                onClick = { sortMenu = true },
                label = { Text("Sort: ${f.sort.label}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                RecipeSort.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(sort.label) }, onClick = { vm.update { it.copy(sort = sort) }; sortMenu = false })
                }
            }
        }
        FilterChip(
            selected = f.cuisineId != null || f.worldRegion != null,
            onClick = { cuisineSheet = true },
            label = { Text(f.cuisineId?.let { CuisineCatalog[it]?.name } ?: f.worldRegion?.label ?: "Any cuisine") },
            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        Box {
            FilterChip(
                selected = f.course != null,
                onClick = { courseMenu = true },
                label = { Text(f.course?.label ?: "Any course") },
                leadingIcon = { Icon(Icons.Outlined.RestaurantMenu, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                DropdownMenuItem(text = { Text("Any course") }, onClick = { vm.update { it.copy(course = null) }; courseMenu = false })
                RecipeCourse.entries.forEach { course ->
                    DropdownMenuItem(text = { Text(course.label) }, onClick = { vm.update { it.copy(course = course) }; courseMenu = false })
                }
            }
        }
        Box {
            FilterChip(
                selected = f.diets.isNotEmpty(),
                onClick = { dietMenu = true },
                label = { Text(if (f.diets.isEmpty()) "Any diet" else f.diets.joinToString { it.label }) },
                leadingIcon = { Icon(Icons.Outlined.Eco, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = dietMenu, onDismissRequest = { dietMenu = false }) {
                RecipeSearch.DIET_FILTERS.forEach { diet ->
                    val on = diet in f.diets
                    DropdownMenuItem(
                        text = { Text(diet.label) },
                        // Stays open so several diets can be combined.
                        onClick = { vm.update { it.copy(diets = if (on) it.diets - diet else it.diets + diet) } },
                        leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    )
                }
                Text(
                    "Checked against every ingredient. Halal means no pork or alcohol; meat must be halal-certified.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp),
                )
            }
        }
        Box {
            FilterChip(
                selected = f.maxMinutes != null,
                onClick = { timeMenu = true },
                label = { Text(f.maxMinutes?.let { "≤ $it min" } ?: "Any time") },
                leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                DropdownMenuItem(text = { Text("Any time") }, onClick = { vm.update { it.copy(maxMinutes = null) }; timeMenu = false })
                listOf(20, 30, 45, 60, 90).forEach { m ->
                    DropdownMenuItem(text = { Text("$m minutes or less") }, onClick = { vm.update { it.copy(maxMinutes = m) }; timeMenu = false })
                }
            }
        }
        Box {
            FilterChip(
                selected = f.maxKcal != null,
                onClick = { kcalMenu = true },
                label = { Text(f.maxKcal?.let { "≤ $it kcal" } ?: "Any calories") },
                leadingIcon = { Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = kcalMenu, onDismissRequest = { kcalMenu = false }) {
                DropdownMenuItem(text = { Text("Any calories") }, onClick = { vm.update { it.copy(maxKcal = null) }; kcalMenu = false })
                listOf(300, 450, 600, 800).forEach { k ->
                    DropdownMenuItem(text = { Text("$k kcal or less per serving") }, onClick = { vm.update { it.copy(maxKcal = k) }; kcalMenu = false })
                }
            }
        }
        FilterChip(selected = f.easyOnly, onClick = { vm.update { it.copy(easyOnly = !it.easyOnly) } }, label = { Text("Easy") })
        FilterChip(selected = f.highProtein, onClick = { vm.update { it.copy(highProtein = !it.highProtein) } }, label = { Text("High protein") })
        FilterChip(selected = f.usePantry, onClick = { vm.update { it.copy(usePantry = !it.usePantry, onlyAvailable = if (it.usePantry) false else it.onlyAvailable) } }, label = { Text("Use my pantry") })
        if (f.usePantry) {
            FilterChip(selected = f.onlyAvailable, onClick = { vm.update { it.copy(onlyAvailable = !it.onlyAvailable) } }, label = { Text("Only what I have") })
        }
        FilterChip(selected = f.favouritesOnly, onClick = { vm.update { it.copy(favouritesOnly = !it.favouritesOnly) } }, label = { Text("Favourites") })
    }
    if (cuisineSheet) {
        CuisineSheet(f, cuisines, onDismiss = { cuisineSheet = false }) { region, cuisineId ->
            vm.update { it.copy(worldRegion = region, cuisineId = cuisineId) }
            cuisineSheet = false
        }
    }
}

/** Cuisines grouped by part of the world, with recipe counts; a heading picks the whole region. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CuisineSheet(
    f: DiscoverFilters,
    groups: List<Pair<WorldRegion, List<CuisineCount>>>,
    onDismiss: () -> Unit,
    onPick: (WorldRegion?, String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = f.cuisineId == null && f.worldRegion == null,
                    onClick = { onPick(null, null) },
                    label = { Text("Any cuisine (${groups.sumOf { g -> g.second.sumOf { it.count } }})") },
                )
            }
            items(groups, key = { it.first.name }) { (region, cuisines) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = f.worldRegion == region && f.cuisineId == null,
                        onClick = { onPick(region, null) },
                        label = { Text("All ${region.label} (${cuisines.sumOf { it.count }})", style = MaterialTheme.typography.titleSmall) },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cuisines.forEach { c ->
                            FilterChip(
                                selected = f.cuisineId == c.id,
                                onClick = { onPick(null, c.id) },
                                label = { Text("${c.name} (${c.count})") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(dish: DishResult, usage: RecipeUsage?, favourite: Boolean, onVersions: () -> Unit, onClick: () -> Unit) {
    val r = dish.shown
    val recipe = r.recipe
    val cuisine = CuisineCatalog[recipe.cuisineId]?.name
    NouriCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(RecipeCourse.of(recipe).label, cuisine, recipe.region).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (favourite) Icon(Icons.Outlined.Favorite, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                RecipeText.minutes(recipe.totalMinutes),
                "${Fmt.kcal(r.perServing.energyKcal)} kcal",
                "${Fmt.gramsShort(r.perServing.proteinG)} protein per serving",
                when {
                    DietGoal.VEGAN in r.analysis.diets -> "Vegan"
                    DietGoal.VEGETARIAN in r.analysis.diets -> "Vegetarian"
                    else -> null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipeIndicators(recipe.difficulty, usage?.rating, usage?.cookedCount ?: 0, Modifier.weight(1f))
            if (dish.versionCount > 1) {
                AssistChip(
                    onClick = onVersions,
                    label = { Text("${dish.versionCount} versions") },
                    leadingIcon = { Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.semantics { contentDescription = "${dish.versionCount} versions of this dish. Choose one" },
                )
            }
        }
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
