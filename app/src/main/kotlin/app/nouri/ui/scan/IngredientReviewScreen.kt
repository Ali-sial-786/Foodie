package app.nouri.ui.scan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.ai.IngredientCertainty
import app.nouri.core.scan.IngredientChip
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.ErrorBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.SectionTitle
import app.nouri.ui.components.SimulatedBanner
import app.nouri.ui.home.RecipeIdea
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IngredientReviewScreen(nav: AppNavigator, route: Route.IngredientReview) {
    val container = LocalAppContainer.current
    val vm = viewModel { IngredientReviewViewModel(container, route.captureId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { NouriTopBar("Your ingredients", onBack = nav::back) }) { padding ->
        if (s.missingCapture) {
            EmptyState(Icons.AutoMirrored.Outlined.HelpOutline, "Photos not available", "Please take the photo again.", Modifier.padding(padding)) {
                Button(onClick = { nav.replace(Route.Camera(ScanMode.INGREDIENTS.name)) }) { Text("Open camera") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.photos.forEachIndexed { i, f -> PhotoThumb(f, "Ingredient photo ${i + 1}", Modifier.size(96.dp).clip(MaterialTheme.shapes.small)) }
                }
            }
            when (s.phase) {
                AnalysisPhase.FAILED -> s.error?.let { e ->
                    item {
                        AnalysisError(e, onAction = { a ->
                            when (a) {
                                ErrorAction.RETRY -> vm.analyse()
                                ErrorAction.OPEN_AI_SETTINGS -> nav.to(Route.AiSettings)
                                ErrorAction.DESCRIBE_INSTEAD -> adding = true
                                ErrorAction.RETAKE -> nav.replace(Route.Camera(ScanMode.INGREDIENTS.name))
                            }
                        })
                    }
                }
                AnalysisPhase.DONE -> {
                    if (s.simulated) item { SimulatedBanner() }
                    item {
                        NouriCard {
                            Text("Check what was found", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tap to include or exclude. Items marked “maybe” start unselected — nothing is assumed without your confirmation.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                s.chips.forEach { chip -> IngredientChipView(chip, onToggle = { vm.toggle(chip.id) }, onEdit = { editing = chip.id }) }
                                FilterChip(selected = false, onClick = { adding = true }, label = { Text("Add") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(onClick = vm::addSelectedToPantry, enabled = s.selected.isNotEmpty() && !s.addedToPantry) {
                                    Text(if (s.addedToPantry) "Added to pantry" else "Add ${s.selected.size} to pantry")
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = s.includePantry, onClick = { vm.setIncludePantry(!s.includePantry) }, label = { Text("Include my pantry") })
                            FilterChip(selected = s.onlyAvailable, onClick = { vm.setOnlyAvailable(!s.onlyAvailable) }, label = { Text("Only what I have") })
                        }
                    }
                    item { SectionTitle("Recipes you could make") }
                    if (s.ideas.isEmpty()) item {
                        Text("No matching recipes in the collection yet. Try including your pantry, or allow a few missing ingredients.", style = MaterialTheme.typography.bodyMedium)
                    }
                    items(s.ideas, key = { it.recipe.id }) { r ->
                        Column {
                            RecipeIdea(r) { nav.to(Route.RecipeDetail(r.recipe.id)) }
                            r.pantry?.let { p ->
                                if (p.missing.isNotEmpty()) Text("Missing: ${p.missing.joinToString { it.line.displayName }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                            }
                        }
                    }
                    if (s.canGenerate) {
                        item {
                            NouriCard {
                                Text("New ideas from ${s.providerLabel}", style = MaterialTheme.typography.titleMedium)
                                Text("AI-generated recipes are labelled as such. Nutrition is calculated on your phone from the ingredient list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = vm::generate, enabled = !s.generating && s.selected.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                                    Text("  Generate recipes")
                                }
                                if (s.generating) LoadingBox(label = "Asking ${s.providerLabel}…")
                                s.generationError?.let { ErrorBanner(it, Modifier.padding(top = 8.dp)) }
                            }
                        }
                        items(s.generated, key = { it.id }) { recipe ->
                            NouriCard(onClick = { scope.launch { nav.to(Route.RecipeDetail(vm.saveGenerated(recipe))) } }) {
                                Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                                Text("AI-generated · ${recipe.totalMinutes} min · ${recipe.servings} servings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (recipe.description.isNotBlank()) Text(recipe.description, style = MaterialTheme.typography.bodySmall)
                                Text("Tap to save and open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                else -> item { AnalysisProgress(s.phase, s.providerLabel, vm::cancel) }
            }
        }
    }

    val editChip = editing?.let { id -> s.chips.firstOrNull { it.id == id } }
    if (adding || editChip != null) {
        var text by rememberSaveable(editing) { mutableStateOf(editChip?.name ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editChip == null) "Add ingredient" else "Rename ingredient") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Ingredient") }, modifier = Modifier.fillMaxWidth())
                    if (editChip?.ingredientId == null && editChip != null) {
                        Text("Not recognised yet — try a simpler name (e.g. “red lentils”).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.upsert(editChip?.id, text); adding = false; editing = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = {
                Row {
                    if (editChip != null) TextButton(onClick = { vm.remove(editChip.id); editing = null }) { Text("Remove") }
                    TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun IngredientChipView(chip: IngredientChip, onToggle: () -> Unit, onEdit: () -> Unit) {
    val label = buildString {
        append(chip.name)
        chip.quantity?.let { append(" · $it") }
        if (chip.certainty == IngredientCertainty.POSSIBLE) append(" (maybe)")
    }
    InputChip(
        selected = chip.selected,
        onClick = onToggle,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit or remove ${chip.name}", modifier = Modifier.size(16.dp))
            }
        },
    )
}
