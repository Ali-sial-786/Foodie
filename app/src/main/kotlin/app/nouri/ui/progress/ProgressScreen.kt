package app.nouri.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.core.insights.RangePreset
import app.nouri.core.progress.WeightUnit
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.components.DailyBarChart
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LabeledValue
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.SectionTitle
import app.nouri.ui.components.WeightChart
import app.nouri.ui.components.WeightPoint
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(nav: AppNavigator, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm = viewModel { ProgressViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return LoadingBox(Modifier.padding(contentPadding))
    var logging by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Progress", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RangePreset.entries.forEachIndexed { i, r ->
                    SegmentedButton(selected = s.range == r, onClick = { vm.setRange(r) }, shape = SegmentedButtonDefaults.itemShape(i, RangePreset.entries.size)) { Text("${r.days} days") }
                }
            }
        }
        item {
            NouriCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weight", style = MaterialTheme.typography.titleMedium)
                        s.latest?.let { Text("Latest ${s.unit.format(it.weightKg)} · ${Fmt.shortDate(it.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Button(onClick = { logging = true }) { Text("Log weight") }
                }
                if (s.trend.size >= 2) {
                    WeightChart(s.trend.map { WeightPoint(it.date, s.unit.fromKg(it.scaleKg), s.unit.fromKg(it.trendKg)) }, s.unit.symbol, Modifier.padding(top = 12.dp))
                    s.weightSummary?.let { Text(it.describe(s.unit, s.today), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                } else {
                    EmptyState(
                        Icons.Outlined.MonitorWeight, "No trend yet",
                        "Log your weight a few times a week. The trend line smooths out daily water swings, so single readings matter less.",
                    )
                }
            }
        }
        item {
            NouriCard {
                Text("Energy per day", style = MaterialTheme.typography.titleMedium)
                DailyBarChart(s.bars, s.targets?.energyKcal, Modifier.padding(top = 8.dp)) { Fmt.kcalUnit(it) }
                val avg = s.intake.averagePerLoggedDay
                if (avg != null) {
                    LabeledValue("Average on logged days", Fmt.kcalUnit(avg.energyKcal))
                    LabeledValue("Average protein", Fmt.gramsShort(avg.proteinG))
                    LabeledValue("Days logged", "${s.intake.loggedDays} of ${s.intake.totalDays}")
                    s.intake.daysWithinCalorieTarget?.let { LabeledValue("Days within 10% of target", "$it") }
                    Text("Today is left out of averages until it's over.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        s.suggestion?.let { sug ->
            item {
                NouriCard {
                    Text("A possible target adjustment", style = MaterialTheme.typography.titleMedium)
                    Text(sug.reason, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Your logs suggest you maintain at about ${Fmt.kcalUnit(sug.observedTdeeKcal)} (an estimate from ${sug.daysUsed} days). Nothing changes unless you accept.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { vm.acceptSuggestion(sug) }) { Text("Use ${Fmt.kcal(sug.suggestedTargetKcal)} kcal") }
                        OutlinedButton(onClick = { nav.to(Route.TargetSettings) }) { Text("Edit targets") }
                    }
                }
            }
        }
        item { SectionTitle("Observations") }
        s.insights.forEach { insight ->
            item {
                NouriCard {
                    Text(insight.title, style = MaterialTheme.typography.titleSmall)
                    Text(insight.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            NouriCard(onClick = { nav.to(Route.TargetSettings) }) {
                Text("Daily targets", style = MaterialTheme.typography.titleMedium)
                val t = s.targets
                if (t == null) {
                    Text("No targets set. Nouri works fine without them — add them any time.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${Fmt.kcalUnit(t.energyKcal)} · P ${Fmt.gramsShort(t.proteinG)} · C ${Fmt.gramsShort(t.carbsG)} · F ${Fmt.gramsShort(t.fatG)}", style = MaterialTheme.typography.bodyMedium)
                    Text(if (t.manual) "Set by you" else t.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (logging) {
        LogWeightDialog(s.unit, s.latest?.weightKg, onDismiss = { logging = false }) { value ->
            vm.logWeight(value, s.unit, s.today)
            logging = false
        }
    }
}

@Composable
private fun LogWeightDialog(unit: WeightUnit, lastKg: Double?, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(lastKg?.let { Fmt.decimal(unit.fromKg(it)) } ?: "") }
    val value = text.toUserDouble()
    val valid = value != null && unit.toKg(value) in 20.0..400.0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.MonitorWeight, contentDescription = null) },
        title = { Text("Log weight") },
        text = {
            Column {
                NumberField(text, { text = it }, "Weight", suffix = unit.symbol, isError = text.isNotBlank() && !valid)
                Text("For the most consistent trend, weigh yourself at a similar time of day.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
