package app.nouri.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.dishes.DishCatalog
import app.nouri.core.dishes.DishNutrition
import app.nouri.core.insights.ConsumedItem
import app.nouri.core.insights.DailyIntake
import app.nouri.core.insights.DateRange
import app.nouri.core.insights.Insight
import app.nouri.core.insights.InsightGenerator
import app.nouri.core.insights.IntakeStats
import app.nouri.core.insights.IntakeSummary
import app.nouri.core.insights.RangePreset
import app.nouri.core.model.Nutrients
import app.nouri.core.progress.AdaptiveTargetAdvisor
import app.nouri.core.progress.LoggedEnergyDay
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.TargetAdjustmentSuggestion
import app.nouri.core.progress.TrendPoint
import app.nouri.core.progress.WeightChangeSummary
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightTrend
import app.nouri.core.progress.WeightUnit
import app.nouri.core.util.SystemTime
import app.nouri.ui.components.DayBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

data class ProgressState(
    val today: LocalDate,
    val range: RangePreset,
    val unit: WeightUnit,
    val trend: List<TrendPoint>,
    val latest: WeightEntry?,
    val weightSummary: WeightChangeSummary?,
    val bars: List<DayBar>,
    val intake: IntakeSummary,
    val insights: List<Insight>,
    val targets: NutritionTargets?,
    val suggestion: TargetAdjustmentSuggestion?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    val range = MutableStateFlow(RangePreset.THIRTY)

    // Insights look back further than the chart, so always load 90 days.
    private val entries = c.settings.settings.flatMapLatest { s ->
        val today = SystemTime.diaryToday(s.dayStartMinute)
        c.diary.observeRange(today.minus(89, DateTimeUnit.DAY), today)
    }

    val state: StateFlow<ProgressState?> = combine(c.settings.settings, entries, c.weights.entries, range) { s, diary, weights, preset ->
        val today = SystemTime.diaryToday(s.dayStartMinute)
        val window = DateRange.last(preset, today)
        val trend = WeightTrend.compute(weights)
        val intakes = dailyIntakes(diary)
        val byDate = intakes.associateBy { it.date }
        val bars = window.dates().map { d -> DayBar(d, byDate[d]?.takeIf { it.isLogged }?.nutrients?.energyKcal) }
        val profile = s.profile
        val suggestion = if (s.targets != null && profile != null) {
            AdaptiveTargetAdvisor.suggest(intakes.filter { it.isLogged }.map { LoggedEnergyDay(it.date, it.nutrients.energyKcal) }, trend, s.targets, profile, today, unit = s.weightUnit)
        } else null
        ProgressState(
            today = today,
            range = preset,
            unit = s.weightUnit,
            trend = trend.filter { it.date in window },
            latest = weights.maxWithOrNull(compareBy<WeightEntry>({ it.date }, { it.minuteOfDay ?: 0 })),
            weightSummary = WeightTrend.summarize(trend, window.start, today),
            bars = bars,
            intake = IntakeStats.summarize(intakes, DateRange(window.start, today.minus(1, DateTimeUnit.DAY).coerceAtLeast(window.start)), s.targets),
            insights = InsightGenerator.generate(intakes, consumedItems(diary), trend, s.targets, today, s.weightUnit),
            targets = s.targets,
            suggestion = suggestion,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(r: RangePreset) { range.value = r }

    fun logWeight(value: Double, unit: WeightUnit, date: LocalDate) = viewModelScope.launch {
        val kg = unit.toKg(value)
        c.weights.save(WeightEntry(c.newId(), date, SystemTime.minuteOfDay().takeIf { date == SystemTime.today() }, kg))
        c.messages.show("Weight saved")
    }

    fun deleteWeight(id: String) = viewModelScope.launch { c.weights.delete(id) }

    fun acceptSuggestion(s: TargetAdjustmentSuggestion) = viewModelScope.launch {
        c.settings.update { st ->
            val t = st.targets ?: return@update st
            val ratio = s.suggestedTargetKcal / t.energyKcal
            // Carbohydrate absorbs the change so protein and fat targets stay where the user set them.
            val carbs = (t.carbsG + (s.suggestedTargetKcal - t.energyKcal) / 4.0).coerceAtLeast(0.0)
            st.copy(targets = t.copy(energyKcal = s.suggestedTargetKcal, carbsG = kotlin.math.round(carbs), fiberG = t.fiberG?.let { kotlin.math.round(it * ratio) }, basis = t.basis + " Adjusted to ${s.suggestedTargetKcal.toInt()} kcal from your logs and weight trend."))
        }
        c.messages.show("Energy target updated")
    }

    private fun dailyIntakes(entries: List<DiaryEntry>): List<DailyIntake> =
        entries.groupBy { it.date }.map { (d, list) -> DailyIntake(d, Nutrients.sum(list.map { it.nutrients }), list.size) }.sortedBy { it.date }

    private fun consumedItems(entries: List<DiaryEntry>): List<ConsumedItem> = entries.map { e ->
        val cuisine = e.foodRefId?.takeIf { it.startsWith(DishNutrition.PREFIX) }?.let { DishCatalog[it.removePrefix(DishNutrition.PREFIX)]?.cuisineId }
        ConsumedItem(e.date, e.name, cuisine, e.mealType.label, e.nutrients)
    }
}
