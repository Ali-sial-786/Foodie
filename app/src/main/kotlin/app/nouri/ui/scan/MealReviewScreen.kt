package app.nouri.ui.scan

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.MealType
import app.nouri.core.scan.FoodMatch
import app.nouri.core.scan.MealScanResult
import app.nouri.core.scan.PrepContext
import app.nouri.core.scan.ScanEdit
import app.nouri.core.scan.ScanItem
import app.nouri.core.units.formatAmount
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.SimulatedBanner
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route

@Composable
fun MealReviewScreen(nav: AppNavigator, route: Route.MealReview) {
    val container = LocalAppContainer.current
    val vm = viewModel { MealReviewViewModel(container, route.captureId) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.logged) { if (s.logged) nav.toTab(Route.Diary) }
    var searchFor by remember { mutableStateOf<SearchTarget?>(null) }
    var editGrams by remember { mutableStateOf<ScanItem?>(null) }

    Scaffold(topBar = { NouriTopBar("Review meal", onBack = nav::back) }) { padding ->
        if (s.missingCapture) {
            EmptyState(Icons.AutoMirrored.Outlined.HelpOutline, "Photos not available", "Please take the photo again.", Modifier.padding(padding)) {
                Button(onClick = { nav.replace(Route.Camera(ScanMode.MEAL.name)) }) { Text("Open camera") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.photos.forEachIndexed { i, f -> PhotoThumb(f, "Meal photo ${i + 1}", Modifier.size(96.dp).clip(MaterialTheme.shapes.small)) }
                }
            }
            when (s.phase) {
                AnalysisPhase.PREPARING, AnalysisPhase.ANALYSING, AnalysisPhase.CALCULATING -> item { AnalysisProgress(s.phase, s.providerLabel, vm::cancel) }
                AnalysisPhase.FAILED -> s.error?.let { e ->
                    item {
                        AnalysisError(e, onAction = { a ->
                            when (a) {
                                ErrorAction.RETRY -> vm.analyse()
                                ErrorAction.OPEN_AI_SETTINGS -> nav.to(Route.AiSettings)
                                ErrorAction.DESCRIBE_INSTEAD -> nav.replace(Route.AddFood(s.date.toString(), s.meal.name, mode = "DESCRIBE"))
                                ErrorAction.RETAKE -> nav.replace(Route.Camera(ScanMode.MEAL.name))
                            }
                        })
                    }
                }
                AnalysisPhase.DONE -> s.result?.let { result -> resultItems(result, s, vm, onSearch = { searchFor = it }, onEditGrams = { editGrams = it }) }
            }
        }
    }

    searchFor?.let { target ->
        FoodSearchSheet(
            title = if (target.itemId == null) "Add an item" else "Change food",
            alternatives = target.alternatives,
            results = s.searchResults.map { it.match to "${it.title} · ${it.subtitle}" },
            onQuery = vm::search,
            onPick = { match ->
                val itemId = target.itemId
                if (itemId == null) vm.edit(ScanEdit.AddItem(match, match.food.servingGrams ?: 100.0)) else vm.edit(ScanEdit.ChangeFood(itemId, match))
                searchFor = null
                vm.clearSearch()
            },
            onDismiss = { searchFor = null; vm.clearSearch() },
        )
    }
    editGrams?.let { item -> GramsDialog(item, onDismiss = { editGrams = null }) { g, weighed -> vm.edit(ScanEdit.SetGrams(item.id, g, weighed)); editGrams = null } }
    s.duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Log this again?") },
            text = { Text("${d.existing.name} was logged a moment ago. Log this meal anyway?") },
            confirmButton = { TextButton(onClick = vm::confirmDuplicate) { Text("Log anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Cancel") } },
        )
    }
}

private data class SearchTarget(val itemId: String?, val alternatives: List<FoodMatch>)

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(
    result: MealScanResult,
    s: MealReviewState,
    vm: MealReviewViewModel,
    onSearch: (SearchTarget) -> Unit,
    onEditGrams: (ScanItem) -> Unit,
) {
    if (result.simulated) item { SimulatedBanner() }
    item { SummaryCard(result, s) }
    result.question?.let { q ->
        item {
            NouriCard {
                Text(q.text, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.options.forEach { o -> AssistChip(onClick = { vm.edit(ScanEdit.Answer(o)) }, label = { Text(o) }) }
                }
            }
        }
    }
    items(result.items, key = { it.id }) { item ->
        ItemCard(
            item = item,
            onToggle = { vm.edit(ScanEdit.SetIncluded(item.id, it)) },
            onChange = { onSearch(SearchTarget(item.id, item.alternatives)) },
            onScale = { f -> vm.edit(ScanEdit.ScalePortion(item.id, f)) },
            onEditGrams = { onEditGrams(item) },
            onPrep = { vm.setPrep(item.id, it) },
            onAddFat = { id, label, g -> vm.addFat(item.id, id, label, g) },
            onClearFats = { vm.edit(ScanEdit.ClearAddedFats(item.id)) },
            onRemove = { vm.edit(ScanEdit.Remove(item.id)) },
        )
    }
    item {
        TextButton(onClick = { onSearch(SearchTarget(null, emptyList())) }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add something that was missed")
        }
    }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MealType.entries.forEach { m -> FilterChip(selected = m == s.meal, onClick = { vm.setMeal(m) }, label = { Text(m.label) }) }
        }
    }
    item {
        val count = result.includedItems.count { it.match != null }
        Button(onClick = { vm.log() }, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
            Text("Log $count ${if (count == 1) "item" else "items"} · ${result.totalEnergyRange.format()}")
        }
        Text(
            "Estimates are saved with their range and source. You can edit them later in your diary.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SummaryCard(result: MealScanResult, s: MealReviewState) {
    NouriCard {
        Text("Estimated", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(result.totalEnergyRange.format(), style = MaterialTheme.typography.headlineMedium)
        Text("Most likely about ${Fmt.kcalUnit(result.total.energyKcal, app.nouri.core.nutrition.Precision.ESTIMATED)} · ${Fmt.macroLine(result.total, app.nouri.core.nutrition.Precision.ESTIMATED)}", style = MaterialTheme.typography.bodySmall)
        ConfidenceBadge(result.overallConfidence, Modifier.padding(vertical = 6.dp))
        Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
        (result.warnings + s.providerWarnings).distinct().forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result.unidentified.isNotEmpty()) {
            Text("Not identified: ${result.unidentified.joinToString()}. Add them below if you ate them.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (result.imageQuality != "good") {
            Text("Photo quality: ${result.imageQuality}. A clearer, top-down photo improves the estimate.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Text("Analysed by ${s.providerLabel}. Calories are calculated from reference data, not by the AI.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemCard(
    item: ScanItem,
    onToggle: (Boolean) -> Unit,
    onChange: () -> Unit,
    onScale: (Double) -> Unit,
    onEditGrams: () -> Unit,
    onPrep: (PrepContext) -> Unit,
    onAddFat: (String, String, Double) -> Unit,
    onClearFats: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    NouriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                if (item.isUnmatched) {
                    Text("Not matched to nutrition data — choose a food", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        listOfNotNull("${formatAmount(item.portion.grams)} g", item.portion.householdText, item.portion.source.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.energyRange.format(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Switch(checked = item.included, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = "Include ${item.displayName}" })
        }
        if (!item.isUnmatched) ConfidenceBadge(item.confidence.overall, Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onScale(0.75) }, enabled = !item.isUnmatched) { Icon(Icons.Outlined.Remove, contentDescription = "Smaller portion of ${item.displayName}") }
            IconButton(onClick = { onScale(1.25) }, enabled = !item.isUnmatched) { Icon(Icons.Outlined.Add, contentDescription = "Larger portion of ${item.displayName}") }
            TextButton(onClick = onEditGrams, enabled = !item.isUnmatched) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Amount") }
            TextButton(onClick = onChange) { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (item.isUnmatched) "Choose food" else "Not right?") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${item.displayName}") }
        }
        if (item.alternatives.isNotEmpty() && !expanded) {
            Text("Could also be: ${item.alternatives.take(3).joinToString { it.label }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded && !item.isUnmatched) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Preparation", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrepContext.entries.forEach { p -> FilterChip(selected = item.preparation.context == p, onClick = { onPrep(p) }, label = { Text(p.label) }) }
            }
            Text("Extra fat not visible in the photo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onAddFat("vegetable_oil", "1 tsp oil", 4.5) }, label = { Text("+ 1 tsp oil") })
                AssistChip(onClick = { onAddFat("vegetable_oil", "1 tbsp oil", 13.6) }, label = { Text("+ 1 tbsp oil") })
                AssistChip(onClick = { onAddFat("ghee", "1 tsp ghee", 4.3) }, label = { Text("+ 1 tsp ghee") })
                AssistChip(onClick = { onAddFat("butter", "1 tsp butter", 4.7) }, label = { Text("+ 1 tsp butter") })
                if (item.preparation.addedFats.isNotEmpty()) TextButton(onClick = onClearFats) { Text("Clear") }
            }
            item.preparation.addedFats.forEach { Text("Added: ${it.label}", style = MaterialTheme.typography.bodySmall) }
            item.match?.let { m ->
                Text("Matched to: ${m.food.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Text("Source: ${m.food.provenance().source.label}${m.food.datasetVersion?.let { " · $it" } ?: ""} · ${m.method.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.variabilityNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item.portion.basis?.let { Text("Portion basis: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "Confidence — identification: ${item.confidence.identification.label.lowercase()}, portion: ${item.confidence.portion.label.lowercase()}, data: ${item.confidence.data.label.lowercase()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("${Fmt.kcalUnit(item.nutrients.energyKcal, app.nouri.core.nutrition.Precision.ESTIMATED)} · ${Fmt.macroLine(item.nutrients, app.nouri.core.nutrition.Precision.ESTIMATED)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodSearchSheet(
    title: String,
    alternatives: List<FoodMatch>,
    results: List<Pair<FoodMatch, String>>,
    onQuery: (String) -> Unit,
    onPick: (FoodMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.trim().length >= 2) onQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text("Search dishes and foods") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
            if (query.isBlank() && alternatives.isNotEmpty()) {
                item { Text("Suggestions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(alternatives, key = { "a" + it.food.id }) { m ->
                    ListItem(headlineContent = { Text(m.label) }, supportingContent = { Text(m.method.label) }, modifier = Modifier.clickable { onPick(m) })
                }
            }
            items(results, key = { it.first.food.id }) { (m, label) ->
                ListItem(headlineContent = { Text(label) }, supportingContent = { Text("${Fmt.kcal(m.food.per100g.energyKcal)} kcal per 100 g") }, modifier = Modifier.clickable { onPick(m) })
            }
        }
    }
}

@Composable
private fun GramsDialog(item: ScanItem, onDismiss: () -> Unit, onSave: (Double, Boolean) -> Unit) {
    var text by rememberSaveable { mutableStateOf(formatAmount(item.portion.grams)) }
    var weighed by rememberSaveable { mutableStateOf(false) }
    val grams = text.toUserDouble()?.takeIf { it > 0 && it < 5000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Amount of ${item.displayName}") },
        text = {
            Column {
                NumberField(text, { text = it }, "Weight", suffix = "g")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { weighed = !weighed }) {
                    Checkbox(checked = weighed, onCheckedChange = { weighed = it })
                    Text("I weighed it")
                }
                Text("Estimated range was ${formatAmount(item.portion.lowGrams)}–${formatAmount(item.portion.highGrams)} g.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { grams?.let { onSave(it, weighed) } }, enabled = grams != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
