package app.nouri.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.ai.ProviderKind
import app.nouri.core.diary.DailySummary
import app.nouri.core.diary.DiaryCalculator
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.DiaryOperations
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.pantry.PantryLogic
import app.nouri.core.recommend.RankedRecipe
import app.nouri.core.recommend.RecommendationContext
import app.nouri.core.util.SystemTime
import app.nouri.ui.common.logEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class HomeState(
    val today: LocalDate,
    val summary: DailySummary,
    val suggestedMeal: MealType,
    val recent: List<DiaryEntry>,
    val ideas: List<RankedRecipe>,
    val demoMode: Boolean,
    val expiringSoon: List<String>,
    val hasTargets: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val settings = c.settings.settings

    private val today = settings.map { SystemTime.diaryToday(it.dayStartMinute) }.distinctUntilChanged()

    val state: StateFlow<HomeState?> = combine(
        settings,
        today.flatMapLatest { c.diary.observeDay(it) },
        c.diary.observeRecent(12),
        c.preparedRecipes,
        c.pantry.pantry,
    ) { s, entries, recent, recipes, pantry ->
        val date = SystemTime.diaryToday(s.dayStartMinute)
        val summary = DiaryCalculator.summarize(date, entries, s.dailyTargets)
        val expiring = PantryLogic.expiringSoon(pantry, SystemTime.today(), withinDays = 2)
        val ranking = c.core(s.preferences.measurementSystem).ranker.rankPrepared(
            recipes,
            RecommendationContext(
                preferences = s.preferences,
                available = pantry.mapNotNull { it.ingredientId }.toSet(),
                expiringSoon = expiring.mapNotNull { it.ingredientId }.toSet(),
                remainingKcal = summary.remaining?.energyKcal,
                maxMinutes = s.preferences.usualMaxMinutes,
            ),
        )
        HomeState(
            today = date,
            summary = summary,
            suggestedMeal = MealType.forMinuteOfDay(SystemTime.minuteOfDay()),
            recent = recent.distinctBy { it.name.lowercase() }.take(6),
            ideas = ranking.ranked.take(4),
            demoMode = s.ai.selected == ProviderKind.MOCK,
            expiringSoon = expiring.map { it.name },
            hasTargets = s.targets != null,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Logs a recent food again, now, with its original frozen nutrition. */
    fun relog(entry: DiaryEntry) {
        viewModelScope.launch {
            val now = SystemTime.nowEpochMs()
            val date = SystemTime.diaryToday(c.settings.current().dayStartMinute)
            val meal = MealType.forMinuteOfDay(SystemTime.minuteOfDay())
            val copy = DiaryOperations.copyEntries(listOf(entry), date, meal, now, SystemTime.zoneId(), c::newId)
                .map { it.copy(origin = EntryOrigin.FAVOURITE, idempotencyKey = "relog:${entry.id}:$now") }
            c.logEntries(copy, force = true)
        }
    }
}
