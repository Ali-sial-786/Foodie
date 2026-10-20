package app.nouri.core.util

import app.nouri.core.diary.DiaryDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Wall-clock helpers for platform code; domain logic receives dates as parameters instead. */
object SystemTime {
    fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun zoneId(): String = TimeZone.currentSystemDefault().id

    fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    /** Today's diary day, honouring a late-night day boundary. */
    fun diaryToday(dayStartMinute: Int = 0): LocalDate = DiaryDay.of(nowEpochMs(), zoneId(), dayStartMinute)

    fun minuteOfDay(): Int = DiaryDay.minuteOfDay(nowEpochMs(), zoneId())

    fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    fun LocalDate.minusDays(days: Int): LocalDate = minus(days, DateTimeUnit.DAY)
}
