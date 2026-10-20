package app.nouri.ui.diary

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.MealSummary
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.nutrition.Precision
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.CalorieRing
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.MacroRow
import app.nouri.ui.components.NouriCard
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import app.nouri.ui.theme.NouriTheme
import kotlinx.datetime.LocalDate

private const val DAY_MS = 86_400_000L

@Composable
fun DiaryScreen(nav: AppNavigator, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm = viewModel { DiaryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return LoadingBox(Modifier.padding(contentPadding))
    var picking by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousDay) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous day") }
                Text(
                    Fmt.dayLabel(s.date, s.today),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).clickable { picking = true }.semantics { contentDescription = "Diary for ${Fmt.dayLabel(s.date, s.today)}. Choose date" },
                )
                IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.CalendarMonth, contentDescription = "Choose date") }
                IconButton(onClick = vm::nextDay) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next day") }
            }
        }
        item {
            NouriCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CalorieRing(s.summary.consumed.energyKcal, s.summary.targets?.energyKcal, size = 104.dp, approximate = s.summary.containsEstimates)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.kcalUnit(s.summary.consumed.energyKcal)} eaten", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${s.summary.entryCount} ${if (s.summary.entryCount == 1) "entry" else "entries"}" + if (s.summary.containsEstimates) " · includes estimates" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                MacroRow(s.summary.consumed, s.summary.targets)
            }
        }
        s.summary.meals.forEach { meal ->
            item(key = meal.mealType.name) {
                MealSection(
                    meal = meal,
                    onAdd = { nav.to(Route.AddFood(s.date.toString(), meal.mealType.name)) },
                    onOpen = { nav.to(Route.EntryDetail(it.id)) },
                    onDelete = vm::delete,
                    onCopyToToday = if (s.date != s.today && meal.entries.isNotEmpty()) ({ vm.copyMealToToday(meal.mealType) }) else null,
                )
            }
        }
        if (s.summary.entryCount == 0) {
            item {
                TextButton(onClick = vm::copyPreviousDayHere, modifier = Modifier.fillMaxWidth()) { Text("Copy everything from the previous day") }
            }
        }
    }

    if (picking) DiaryDatePicker(s.date, onDismiss = { picking = false }) { vm.setDate(it); picking = false }
}

@Composable
private fun MealSection(
    meal: MealSummary,
    onAdd: () -> Unit,
    onOpen: (DiaryEntry) -> Unit,
    onDelete: (DiaryEntry) -> Unit,
    onCopyToToday: (() -> Unit)?,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    NouriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meal.mealType.label, style = MaterialTheme.typography.titleMedium)
                if (meal.entries.isNotEmpty()) {
                    Text("${Fmt.kcalUnit(meal.total.energyKcal)} · ${Fmt.macroLine(meal.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, contentDescription = "Add to ${meal.mealType.label}") }
            if (onCopyToToday != null) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${meal.mealType.label}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy to today") }, onClick = { menu = false; onCopyToToday() })
                }
            }
        }
        meal.entries.forEachIndexed { i, entry ->
            if (i == 0) Spacer(Modifier.height(8.dp)) else HorizontalDivider(Modifier.padding(vertical = 2.dp))
            EntryRow(entry, onClick = { onOpen(entry) }, onDelete = { onDelete(entry) })
        }
    }
}

@Composable
private fun EntryRow(entry: DiaryEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val estimate = entry.snapshot.precision == Precision.ESTIMATED
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(entry.quantity?.toString(), entry.snapshot.grams?.takeIf { entry.quantity?.unit?.symbol != "g" }?.let { Fmt.gramsShort(it) })
            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (estimate && entry.snapshot.confidence == ConfidenceLevel.LOW) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = "Low-confidence estimate", tint = NouriTheme.extra.lowConfidence, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            (if (estimate) "~" else "") + Fmt.kcal(entry.nutrients.energyKcal, entry.snapshot.precision),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { contentDescription = (if (estimate) "about " else "") + "${Fmt.kcal(entry.nutrients.energyKcal, entry.snapshot.precision)} kilocalories" },
        )
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${entry.name}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onClick() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    // The Material date picker works in UTC milliseconds at midnight.
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDays() * DAY_MS)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { ms -> onPicked(LocalDate.fromEpochDays(ms / DAY_MS)) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = pickerState) }
}
