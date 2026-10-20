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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nouri.AppContainer
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.DiaryOperations
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.nutrition.Precision
import app.nouri.core.units.Quantity
import app.nouri.core.units.formatAmount
import app.nouri.core.util.SystemTime
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.common.deleteEntries
import app.nouri.ui.common.logEntries
import app.nouri.ui.components.ConfidenceBadge
import app.nouri.ui.components.EmptyState
import app.nouri.ui.components.Fmt
import app.nouri.ui.components.LabeledValue
import app.nouri.ui.components.LoadingBox
import app.nouri.ui.components.NouriCard
import app.nouri.ui.components.NouriTopBar
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.NutritionTable
import app.nouri.ui.components.toUserDouble
import app.nouri.ui.navigation.AppNavigator
import app.nouri.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EntryDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val _entry = MutableStateFlow<DiaryEntry?>(null)
    val entry: StateFlow<DiaryEntry?> = _entry.asStateFlow()
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()
    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    init {
        viewModelScope.launch {
            val e = c.diary.get(id)
            _entry.value = e
            _missing.value = e == null
        }
    }

    /** Editing creates a new snapshot from the frozen per-gram values; reference data is not re-read. */
    fun save(grams: Double?, meal: MealType) = viewModelScope.launch {
        val e = _entry.value ?: return@launch
        var updated = e.copy(mealType = meal)
        if (grams != null && e.snapshot.grams != null && grams > 0 && kotlin.math.abs(grams - e.snapshot.grams!!) > 0.01) {
            updated = DiaryOperations.rescale(updated, grams, Quantity.grams(grams), SystemTime.nowEpochMs())
        }
        c.diary.update(updated)
        c.messages.show("Saved")
        _closed.value = true
    }

    fun delete() = viewModelScope.launch {
        val e = _entry.value ?: return@launch
        c.deleteEntries(listOf(e.id), e.name)
        _closed.value = true
    }

    fun logAgainToday() = viewModelScope.launch {
        val e = _entry.value ?: return@launch
        val now = SystemTime.nowEpochMs()
        val today = SystemTime.diaryToday(c.settings.current().dayStartMinute)
        val copy = DiaryOperations.copyEntries(listOf(e), today, MealType.forMinuteOfDay(SystemTime.minuteOfDay()), now, SystemTime.zoneId(), c::newId)
            .map { it.copy(origin = EntryOrigin.FAVOURITE, idempotencyKey = "relog:${e.id}:${now / 1000}") }
        c.logEntries(copy, force = true)
    }
}

@Composable
fun EntryDetailScreen(nav: AppNavigator, route: Route.EntryDetail) {
    val container = LocalAppContainer.current
    val vm = viewModel { EntryDetailViewModel(container, route.entryId) }
    val entry by vm.entry.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    LaunchedEffect(closed) { if (closed) nav.back() }

    Scaffold(topBar = {
        NouriTopBar(entry?.name ?: "Entry", onBack = nav::back) {
            if (entry != null) IconButton(onClick = vm::delete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete entry") }
        }
    }) { padding ->
        val e = entry
        when {
            missing -> EmptyState(Icons.Outlined.SearchOff, "Entry not found", "It may have been deleted.", Modifier.padding(padding))
            e == null -> LoadingBox(Modifier.padding(padding))
            else -> {
                var grams by rememberSaveable { mutableStateOf(e.snapshot.grams?.let { formatAmount(it) } ?: "") }
                var meal by rememberSaveable { mutableStateOf(e.mealType) }
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NouriCard {
                        val estimate = e.snapshot.precision == Precision.ESTIMATED
                        Text(e.snapshot.energyRange?.takeIf { estimate }?.format() ?: Fmt.kcalUnit(e.nutrients.energyKcal, e.snapshot.precision), style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(e.quantity?.toString(), e.snapshot.grams?.let { Fmt.gramsShort(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        ConfidenceBadge(e.snapshot.confidence, Modifier.padding(vertical = 6.dp))
                        NutritionTable(e.nutrients, precision = e.snapshot.precision)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MealType.entries.forEach { m -> FilterChip(selected = m == meal, onClick = { meal = m }, label = { Text(m.label) }) }
                    }
                    if (e.snapshot.grams != null) {
                        NumberField(grams, { grams = it }, "Amount eaten", Modifier.fillMaxWidth(), suffix = "g", supportingText = "Nutrition scales from the values saved when you logged this.")
                    }
                    Button(onClick = { vm.save(grams.toUserDouble(), meal) }, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
                    OutlinedButton(onClick = vm::logAgainToday, modifier = Modifier.fillMaxWidth()) { Text("Log again today") }
                    NouriCard {
                        Text("Where these numbers come from", style = MaterialTheme.typography.titleSmall)
                        LabeledValue("Source", e.snapshot.provenance.source.label)
                        e.snapshot.provenance.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        e.snapshot.provenance.datasetVersion?.let { LabeledValue("Dataset", it) }
                        LabeledValue("Logged", "${Fmt.shortDate(e.date)} · ${e.origin.label()}")
                        e.snapshot.notes.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text("Saved values don't change if reference data is updated later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun EntryOrigin.label(): String = when (this) {
    EntryOrigin.PHOTO_SCAN -> "photo scan"
    EntryOrigin.BARCODE -> "barcode"
    EntryOrigin.NUTRITION_LABEL -> "nutrition label"
    EntryOrigin.SEARCH -> "search"
    EntryOrigin.NATURAL_LANGUAGE -> "description"
    EntryOrigin.RECIPE -> "recipe"
    EntryOrigin.QUICK_ADD -> "quick add"
    EntryOrigin.COPY -> "copied"
    EntryOrigin.FAVOURITE -> "logged again"
}
