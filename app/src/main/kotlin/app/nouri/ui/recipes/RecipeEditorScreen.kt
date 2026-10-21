package app.nouri.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.InfoBanner
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.theme.NouriTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(nav: AppNavigator, route: Route.RecipeEditor) {
    val container = LocalAppContainer.current
    val vm = viewModel { RecipeEditorViewModel(container, route.recipeId, route.draftId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()
    LaunchedEffect(savedId) { savedId?.let { nav.replace(Route.RecipeDetail(it)) } }

    Scaffold(topBar = {
        NouriTopBar(if (s.isNew) "New recipe" else "Edit recipe", onBack = nav::back) {
            TextButton(onClick = vm::save, enabled = !s.loading && s.errors.isEmpty()) { Text("Save") }
        }
    }) { padding ->
        if (s.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val f = s.fields
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.savesCopyOf != null) {
                InfoBanner(
                    "This saves your own copy. The original recipe stays unchanged.",
                    Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            OutlinedTextField(f.name, { v -> vm.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            CuisinePicker(f.cuisineId) { id -> vm.update { it.copy(cuisineId = id) } }
            OutlinedTextField(f.region, { v -> vm.update { it.copy(region = v) } }, label = { Text("Region (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f.description, { v -> vm.update { it.copy(description = v) } }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(f.servings, { v -> vm.update { it.copy(servings = v) } }, "Servings", Modifier.weight(1f), decimal = false)
                NumberField(f.prepMinutes, { v -> vm.update { it.copy(prepMinutes = v) } }, "Prep", Modifier.weight(1f), suffix = "min", decimal = false)
                NumberField(f.cookMinutes, { v -> vm.update { it.copy(cookMinutes = v) } }, "Cook", Modifier.weight(1f), suffix = "min", decimal = false)
            }
            OutlinedTextField(
                value = f.ingredients,
                onValueChange = { v -> vm.update { it.copy(ingredients = v) } },
                label = { Text("Ingredients") },
                supportingText = { Text("One per line, e.g. “2 cups basmati rice” or “Salt, to taste”. End a line with “:” to start a group; a blank line ends it.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            if (s.lines.isNotEmpty()) ParsedLines(s)
            OutlinedTextField(
                value = f.steps,
                onValueChange = { v -> vm.update { it.copy(steps = v) } },
                label = { Text("Method") },
                supportingText = { Text("One step per line. Times like “simmer for 20 minutes” become step timers.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            NumberField(
                f.cookedWeight, { v -> vm.update { it.copy(cookedWeight = v) } }, "Cooked weight of the whole pot (optional)",
                Modifier.fillMaxWidth(), suffix = "g",
                supportingText = "Weigh the finished dish (minus the pot) once. Water lost or absorbed while cooking changes calories per gram, so this makes portion logging much more accurate.",
            )
            s.preview?.let { r ->
                NouriCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Per serving (preview)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Fmt.kcalUnit(r.perServing.energyKcal), style = MaterialTheme.typography.titleLarge)
                            Text(Fmt.macroLine(r.perServing), style = MaterialTheme.typography.bodySmall)
                        }
                        ConfidenceBadge(r.confidence)
                    }
                    r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            s.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = s.errors.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save recipe") }
        }
    }
}

@Composable
private fun ParsedLines(s: EditorState) {
    val statuses = s.preview?.lines?.map { it.status }
    NouriCard {
        Text("How Nouri reads your ingredients", style = MaterialTheme.typography.titleSmall)
        s.lines.forEachIndexed { i, line ->
            val status = statuses?.getOrNull(i)
            val unresolved = status == LineStatus.UNRESOLVED || line.matchedName == null
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (unresolved || !line.confident) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    contentDescription = if (unresolved) "Not recognised" else if (!line.confident) "Unsure match" else "Recognised",
                    tint = when {
                        unresolved -> NouriTheme.extra.lowConfidence
                        !line.confident -> NouriTheme.extra.moderateConfidence
                        else -> NouriTheme.extra.higherConfidence
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                val q = RecipeText.quantity(line.parsed.quantity).ifEmpty { "to taste" }
                Text(
                    when {
                        unresolved -> "$q · “${line.parsed.name}” — not recognised; it won't be counted"
                        !line.confident -> "$q · ${line.matchedName}? (closest match for “${line.parsed.name}”)"
                        else -> "$q · ${line.matchedName}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CuisinePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = CuisineCatalog[selected]?.name ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Cuisine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CuisineCatalog.all.sortedBy { it.name }.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c.id); expanded = false })
            }
        }
    }
}
