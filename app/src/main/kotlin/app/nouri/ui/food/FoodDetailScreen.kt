package app.nouri.ui.food

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.MealType
import app.nouri.core.nutrition.LineStatus
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.NutritionTable
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodDetailScreen(nav: AppNavigator, route: Route.FoodDetail) {
    val container = LocalAppContainer.current
    val vm = viewModel {
        FoodDetailViewModel(container, route.foodId, LocalDate.parse(route.date), runCatching { MealType.valueOf(route.meal) }.getOrDefault(MealType.SNACK), route.title)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) nav.back() }

    Scaffold(topBar = { NouriTopBar(s.title.ifBlank { "Food" }, onBack = nav::back) }) { padding ->
        when {
            s.loading -> LoadingBox(Modifier.padding(padding))
            s.notFound -> EmptyState(Icons.Outlined.SearchOff, "Food not found", "This food is no longer in the database.", Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.forEach { m -> FilterChip(selected = m == s.meal, onClick = { vm.setMeal(m) }, label = { Text(m.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(s.amountText, vm::setAmount, "Amount", Modifier.weight(0.4f))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(0.6f)) {
                        OutlinedTextField(
                            value = s.selected?.label.orEmpty(), onValueChange = {}, readOnly = true, label = { Text("Unit") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            s.options.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.setUnit(o); expanded = false }) }
                        }
                    }
                }
                val r = s.result
                if (r != null && r.status != LineStatus.UNRESOLVED) {
                    NouriCard {
                        Text(
                            s.energyRange?.format() ?: Fmt.kcalUnit(r.nutrients.energyKcal, s.precision),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        r.gramsEaten?.let { Text("${Fmt.gramsShort(it)} edible portion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        ConfidenceBadge(s.confidence, Modifier.padding(vertical = 6.dp))
                        NutritionTable(r.nutrients, precision = s.precision)
                    }
                    val notes = r.notes + listOfNotNull(r.conversion?.assumption, s.variabilityNote)
                    if (notes.isNotEmpty()) {
                        NouriCard {
                            Text("How this was worked out", style = MaterialTheme.typography.titleSmall)
                            notes.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else if (r != null) {
                    Text(r.notes.firstOrNull() ?: "That unit can't be converted for this food. Try grams.", color = MaterialTheme.colorScheme.error)
                }
                s.food?.let { f ->
                    NouriCard {
                        Text("Source", style = MaterialTheme.typography.titleSmall)
                        Text(f.provenance().source.label + (f.datasetVersion?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        Text(f.sourceDetail ?: f.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Per 100 g: ${Fmt.kcalUnit(f.per100g.energyKcal)} · ${Fmt.macroLine(f.per100g)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = { vm.log() }, enabled = r != null && r.status != LineStatus.UNRESOLVED, modifier = Modifier.fillMaxWidth()) {
                    Text("Log to ${s.meal.label}")
                }
            }
        }
    }

    duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = vm::dismissDuplicate,
            title = { Text("Log this again?") },
            text = { Text("${d.existing.name} was logged to ${d.existing.mealType.label.lowercase()} a moment ago.") },
            confirmButton = { TextButton(onClick = vm::confirmDuplicate) { Text("Log again") } },
            dismissButton = { TextButton(onClick = vm::dismissDuplicate) { Text("Cancel") } },
        )
    }
}
