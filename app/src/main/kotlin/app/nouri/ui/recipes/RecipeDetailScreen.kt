package app.nouri.ui.recipes

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.MealType
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.RecipeNutritionReport
import app.nouri.core.pantry.DeductionProposal
import app.nouri.core.recipes.Availability
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeAdjustment
import app.nouri.core.recipes.RecipeAnalysis
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LabeledValue
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.MacroRow
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.SectionTitle
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.theme.NouriTheme
import kotlin.math.roundToInt

@Composable
fun RecipeDetailScreen(nav: AppNavigator, route: Route.RecipeDetail) {
    val container = LocalAppContainer.current
    val vm = viewModel { RecipeDetailViewModel(container, route.recipeId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is RecipeEvent.Open -> nav.to(Route.RecipeDetail(e.recipeId))
                RecipeEvent.Closed -> nav.back()
            }
        }
    }

    Scaffold(topBar = {
        NouriTopBar(s.base?.name ?: "Recipe", onBack = nav::back) {
            if (s.base != null) {
                IconButton(onClick = vm::toggleFavourite) {
                    Icon(
                        if (s.favourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (s.favourite) "Remove from favourites" else "Add to favourites",
                    )
                }
                val editable = s.saved && s.base?.origin != RecipeOrigin.EDITORIAL_TRADITIONAL
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (editable) "Edit" else "Edit a copy") },
                        onClick = { menu = false; nav.to(Route.RecipeEditor(route.recipeId)) },
                    )
                    if (s.saved) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }) { padding ->
        when {
            s.notFound -> EmptyState(Icons.Outlined.SearchOff, "Recipe not found", "It may have been deleted.", Modifier.padding(padding))
            s.loading || s.scaled == null -> LoadingBox(Modifier.padding(padding))
            else -> RecipeBody(s, vm, nav, padding, onLog = { logging = true })
        }
    }

    if (logging && s.scaled != null && s.report != null) {
        LogPortionDialog(s.scaled!!, s.report!!, onDismiss = { logging = false }) { mode, amount, meal ->
            vm.logPortion(mode, amount, meal)
            logging = false
        }
    }
    s.deductions?.let { proposals -> DeductionDialog(proposals, onDismiss = vm::dismissDeductions, onApply = vm::applyDeductions) }
    duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Log this again?") },
            text = { Text("${d.existing.name} was logged to ${d.existing.mealType.label.lowercase()} a moment ago.") },
            confirmButton = { TextButton(onClick = vm::confirmDuplicate) { Text("Log again") } },
            dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recipe?") },
            text = { Text("Diary entries you logged from it keep their saved nutrition.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecipeBody(s: RecipeDetailState, vm: RecipeDetailViewModel, nav: AppNavigator, padding: PaddingValues, onLog: () -> Unit) {
    val base = s.base!!
    val recipe = s.scaled!!
    val report = s.report!!
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(base, s, nav, vm::rate) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.to(Route.CookMode(base.id, s.servings.toDouble())) }, modifier = Modifier.weight(1f)) { Text("Start cooking") }
                OutlinedButton(onClick = onLog, modifier = Modifier.weight(1f)) { Text("Log a portion") }
            }
        }
        if (s.versions.size > 1) {
            item { SectionTitle("${s.versions.size} versions of this dish") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.versions.forEach { v ->
                        VersionCard(v, current = v.recipe.id == base.id, modifier = Modifier.width(240.dp)) {
                            if (v.recipe.id != base.id) nav.replace(Route.RecipeDetail(v.recipe.id))
                        }
                    }
                }
            }
        }
        item { NutritionCard(report) }
        s.analysis?.let { item { DietCard(it) } }
        item { ServingsRow(s.servings, base.servings, vm::setServings) }
        if (s.scaleNotes.isNotEmpty()) {
            item {
                Column {
                    s.scaleNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { SectionTitle("Ingredients") }
        item { IngredientList(s) }
        val missing = s.pantry?.missing.orEmpty()
        if (!s.pantryEmpty && missing.isNotEmpty()) {
            item {
                OutlinedButton(onClick = vm::addMissingToGrocery, modifier = Modifier.fillMaxWidth()) {
                    Text("Add ${missing.size} missing to grocery list")
                }
            }
        } else if (s.pantryEmpty) {
            item {
                OutlinedButton(onClick = vm::addMissingToGrocery, modifier = Modifier.fillMaxWidth()) { Text("Add ingredients to grocery list") }
            }
        }
        if (recipe.equipment.isNotEmpty()) {
            item { SectionTitle("Equipment") }
            item { Text(recipe.equipment.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
        }
        item { SectionTitle("Method") }
        recipe.steps.forEachIndexed { i, step ->
            item(key = "step$i") {
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.text, style = MaterialTheme.typography.bodyLarge)
                        val extras = listOfNotNull(step.timerSeconds?.let { "Timer ${RecipeText.minutes((it + 59) / 60)}" }, step.temperatureC?.let { "$it °C" })
                        if (extras.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        val keeping = listOfNotNull(recipe.storage?.let { "Storage: $it" }, recipe.reheating?.let { "Reheating: $it" }) + recipe.foodSafety
        if (keeping.isNotEmpty()) {
            item {
                NouriCard {
                    Text("Food safety & storage", style = MaterialTheme.typography.titleSmall)
                    keeping.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (recipe.notes.isNotEmpty()) {
            item {
                NouriCard {
                    Text("Notes", style = MaterialTheme.typography.titleSmall)
                    recipe.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { SectionTitle("Make a variation") }
        item {
            Text(
                "Variations are saved as separate recipes; the original stays as it is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecipeAdjustment.entries.forEach { adj ->
                    AssistChip(onClick = { vm.createVariation(adj) }, label = { Text(adj.label) })
                }
            }
        }
        item {
            NouriCard {
                Text("After cooking", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = vm::markCooked, enabled = !s.cookedJustNow) { Text(if (s.cookedJustNow) "Cooked" else "Mark as cooked") }
                    OutlinedButton(onClick = vm::proposePantryUpdate, enabled = !s.pantryEmpty) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update pantry")
                    }
                }
                Text(
                    "Nouri suggests what to take out of your pantry; nothing changes until you confirm.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(base: Recipe, s: RecipeDetailState, nav: AppNavigator, onRate: (Int?) -> Unit) {
    val cuisine = CuisineCatalog[base.cuisineId]?.name
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(base.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            listOfNotNull(RecipeCourse.of(base).label, cuisine, base.region, RecipeText.minutes(base.totalMinutes)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecipeIndicators(base.difficulty, s.rating, s.cookedCount)
        OriginBadge(base.origin)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your rating", style = MaterialTheme.typography.labelLarge)
            RatingBar(s.rating, onRate)
        }
        base.variantOf?.let { originalId ->
            Text(
                "Variation of ${s.originalName ?: "another recipe"} — see the original",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { nav.to(Route.RecipeDetail(originalId)) }.padding(vertical = 4.dp),
            )
            base.variantSummary?.let { Text("Changes: $it", style = MaterialTheme.typography.bodySmall) }
        }
        base.attribution?.let { Text("Source: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (base.description.isNotBlank()) Text(base.description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NutritionCard(report: RecipeNutritionReport) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val per = report.perServing
    NouriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Per serving", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.kcalUnit(per.energyKcal), style = MaterialTheme.typography.headlineSmall)
            }
            ConfidenceBadge(report.confidence)
        }
        Spacer(Modifier.height(8.dp))
        MacroRow(per, null)
        Spacer(Modifier.height(8.dp))
        val quantified = report.lines.filter { it.status != LineStatus.UNQUANTIFIED }
        val calculated = quantified.count { it.status == LineStatus.RESOLVED || it.status == LineStatus.ESTIMATED }
        Text(
            "Calculated from $calculated of ${quantified.size} measured ingredients (${(report.completeness * 100).roundToInt()}%). " +
                "About ${Fmt.gramsShort(report.cookedGramsPerServing)} cooked per serving" +
                if (report.cookedWeightMeasured) " (weighed)." else " (estimated cooked weight).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide ingredient breakdown" else "Show ingredient breakdown") }
        if (expanded) {
            report.lines.forEach { line ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.line.displayName, style = MaterialTheme.typography.bodyMedium)
                        val detail = listOfNotNull(
                            line.gramsEaten?.let { "${Fmt.gramsShort(it)} eaten" },
                            line.food?.let { it.provenance().source.label + (it.datasetVersion?.let { v -> " ($v)" } ?: "") },
                            statusText(line.status),
                        )
                        Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        (line.notes + listOfNotNull(line.conversion?.assumption)).distinct().forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(if (line.status == LineStatus.UNQUANTIFIED) "–" else Fmt.kcal(line.nutrients.energyKcal), style = MaterialTheme.typography.titleSmall)
                }
            }
            Text("Whole batch: ${Fmt.kcalUnit(report.total.energyKcal)} for ${report.servings} servings", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun statusText(status: LineStatus): String = when (status) {
    LineStatus.RESOLVED -> "calculated"
    LineStatus.ESTIMATED -> "estimated"
    LineStatus.UNQUANTIFIED -> "to taste, not counted"
    LineStatus.UNRESOLVED -> "not found, not counted"
}

@Composable
private fun DietCard(a: RecipeAnalysis) {
    NouriCard {
        Text("Diet & allergens", style = MaterialTheme.typography.titleSmall)
        if (a.diets.isNotEmpty()) LabeledValue("Suitable for", a.diets.joinToString { it.label })
        LabeledValue("Allergens", if (a.allergens.isEmpty()) "None identified" else a.allergens.joinToString { it.label })
        if (a.optionalAllergens.isNotEmpty()) LabeledValue("In optional ingredients", a.optionalAllergens.joinToString { it.label })
        Text(halalText(a.halal), style = MaterialTheme.typography.bodyMedium, color = halalColor(a.halal), modifier = Modifier.padding(top = 4.dp))
        a.halalNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (a.unresolvedIngredients.isNotEmpty()) {
            Text(
                "Couldn't check: ${a.unresolvedIngredients.joinToString()}. Diet labels are only shown when every ingredient is known.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Based on the ingredient list only. Always check product labels if you have an allergy.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ServingsRow(servings: Int, original: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Servings", style = MaterialTheme.typography.titleMedium)
            if (servings != original) Text("Recipe makes $original", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = { onChange(servings - 1) }, enabled = servings > 1) { Icon(Icons.Outlined.Remove, contentDescription = "Fewer servings") }
        Text("$servings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "$servings servings" })
        FilledTonalIconButton(onClick = { onChange(servings + 1) }, enabled = servings < 100) { Icon(Icons.Outlined.Add, contentDescription = "More servings") }
    }
}

@Composable
private fun IngredientList(s: RecipeDetailState) {
    val recipe = s.scaled!!
    val availability = s.pantry?.items.orEmpty()
    Column {
        var group: String? = null
        recipe.ingredients.forEachIndexed { i, line ->
            if (line.group != group) {
                group = line.group
                group?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            }
            val status = availability.getOrNull(i)?.status
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!s.pantryEmpty && status != null) {
                    val (icon, tint, desc) = when (status) {
                        Availability.HAVE -> Triple(Icons.Outlined.CheckCircle, NouriTheme.extra.higherConfidence, "In your pantry")
                        Availability.SUBSTITUTE_AVAILABLE -> Triple(Icons.Outlined.SwapHoriz, NouriTheme.extra.moderateConfidence, "You have a substitute")
                        Availability.STAPLE_ASSUMED -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.outline, "Kitchen staple")
                        Availability.MISSING, Availability.OPTIONAL_MISSING -> Triple(Icons.Outlined.RadioButtonUnchecked, MaterialTheme.colorScheme.outline, "Not in your pantry")
                    }
                    Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                val q = RecipeText.quantity(line.quantity)
                Text(
                    buildString {
                        if (q.isNotEmpty()) append(q).append("  ")
                        append(line.displayName)
                        line.preparation?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                        if (line.optional) append(" (optional)")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (!s.pantryEmpty) {
            Text("Staples like salt, oil and common spices are assumed to be in your kitchen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogPortionDialog(recipe: Recipe, report: RecipeNutritionReport, onDismiss: () -> Unit, onLog: (PortionMode, Double, MealType) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(PortionMode.SERVINGS) }
    var text by rememberSaveable { mutableStateOf("1") }
    var meal by rememberSaveable { mutableStateOf(MealType.forMinuteOfDay(SystemTime.minuteOfDay())) }
    val raw = text.toUserDouble()
    val amount = when (mode) {
        PortionMode.FRACTION -> raw?.div(100.0)
        else -> raw
    }
    val nutrients = amount?.takeIf { it > 0 }?.let {
        when (mode) {
            PortionMode.SERVINGS -> report.forServings(it)
            PortionMode.GRAMS -> report.forPortionGrams(it)
            PortionMode.FRACTION -> report.forFraction(it.coerceAtMost(1.0))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a portion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PortionMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = {
                                mode = m
                                text = when (m) { PortionMode.SERVINGS -> "1"; PortionMode.GRAMS -> report.cookedGramsPerServing.roundToInt().toString(); PortionMode.FRACTION -> "50" }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, PortionMode.entries.size),
                        ) { Text(m.label, maxLines = 1) }
                    }
                }
                NumberField(
                    text, { text = it },
                    label = when (mode) { PortionMode.SERVINGS -> "Servings eaten"; PortionMode.GRAMS -> "Cooked weight eaten"; PortionMode.FRACTION -> "Share of the pot" },
                    suffix = when (mode) { PortionMode.SERVINGS -> "of ${recipe.servings}"; PortionMode.GRAMS -> "g"; PortionMode.FRACTION -> "%" },
                    supportingText = when (mode) {
                        PortionMode.GRAMS -> if (report.cookedWeightMeasured) "Uses the weighed pot (${Fmt.gramsShort(report.cookedWeightGrams)})." else "Pot weight is estimated (${Fmt.gramsShort(report.cookedWeightGrams)}), so this is approximate."
                        PortionMode.FRACTION -> "e.g. 50 for half the pot"
                        PortionMode.SERVINGS -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealType.entries.forEach { m -> FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.label) }) }
                }
                nutrients?.let { Text("${Fmt.kcalUnit(it.energyKcal)} · ${Fmt.macroLine(it)}", style = MaterialTheme.typography.titleSmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let { onLog(mode, it, meal) } }, enabled = nutrients != null && (mode != PortionMode.FRACTION || amount <= 1.0)) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeductionDialog(proposals: List<DeductionProposal>, onDismiss: () -> Unit, onApply: (List<DeductionProposal>) -> Unit) {
    val selected = remember(proposals) { mutableStateListOf<String>().apply { addAll(proposals.filter { it.remaining != null }.map { it.pantryItemId }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update your pantry?") },
        text = {
            Column {
                Text("Untick anything you didn't use. Items without comparable amounts are left unticked for you to decide.", style = MaterialTheme.typography.bodySmall)
                proposals.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = p.pantryItemId in selected,
                            onCheckedChange = { on -> if (on) selected.add(p.pantryItemId) else selected.remove(p.pantryItemId) },
                        )
                        Column {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                            val detail = when {
                                p.removeItem -> "Used ${RecipeText.quantity(p.used)} — remove from pantry"
                                p.remaining != null -> "Used ${RecipeText.quantity(p.used)} — ${RecipeText.quantity(p.remaining!!).ifEmpty { "none" }} left"
                                else -> "Used ${RecipeText.quantity(p.used)}; amounts can't be compared — mark as opened"
                            }
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(proposals.filter { it.pantryItemId in selected }) }) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
