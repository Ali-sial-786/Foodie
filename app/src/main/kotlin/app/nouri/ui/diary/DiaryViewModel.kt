package app.nouri.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nouri.AppContainer
import app.nouri.core.diary.DailySummary
import app.nouri.core.diary.DiaryCalculator
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.MealType
import app.nouri.core.util.SystemTime
import app.nouri.data.repo.LogResult
import app.nouri.ui.common.deleteEntries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class DiaryState(val date: LocalDate, val today: LocalDate, val summary: DailySummary)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(private val c: AppContainer, initialDate: LocalDate? = null) : ViewModel() {
    private val date = MutableStateFlow(initialDate ?: SystemTime.diaryToday())

    val state: StateFlow<DiaryState?> = combine(c.settings.settings, date.flatMapLatest { d -> c.diary.observeDay(d).map { d to it } }) { s, (d, entries) ->
        DiaryState(d, SystemTime.diaryToday(s.dayStartMinute), DiaryCalculator.summarize(d, entries, s.dailyTargets))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previousDay() { date.value = date.value.minus(1, DateTimeUnit.DAY) }
    fun nextDay() { date.value = date.value.plus(1, DateTimeUnit.DAY) }
    fun setDate(d: LocalDate) { date.value = d }

    fun delete(entry: DiaryEntry) = viewModelScope.launch { c.deleteEntries(listOf(entry.id), entry.name) }

    /** Copies one meal from the shown day to today (e.g. "same breakfast as yesterday"). */
    fun copyMealToToday(meal: MealType) = viewModelScope.launch {
        val today = SystemTime.diaryToday(c.settings.current().dayStartMinute)
        report(c.diary.copyMeal(date.value, meal, today, meal, SystemTime.zoneId(), c::newId), "Copied ${meal.label.lowercase()} to today")
    }

    fun copyPreviousDayHere() = viewModelScope.launch {
        val target = date.value
        report(c.diary.copyDay(target.minus(1, DateTimeUnit.DAY), target, SystemTime.zoneId(), c::newId), "Copied the previous day's entries")
    }

    private fun report(result: LogResult, success: String) {
        when (result) {
            is LogResult.Logged -> if (result.entries.isEmpty()) c.messages.show("Nothing to copy") else {
                val ids = result.entries.map { it.id }
                c.messages.show(success, "Undo") { c.diary.delete(ids) }
            }
            LogResult.AlreadyLogged -> c.messages.show("Those entries were already copied")
            is LogResult.PossibleDuplicate -> Unit
        }
    }
}
