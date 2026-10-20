package app.nouri.core.insights

import app.nouri.core.model.Nutrients
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * Totals for one diary day. A day with [entryCount] 0 counts as "not logged",
 * which is different from a logged day with little food: averages only use
 * logged days so missed days don't read as low intake.
 */
@Serializable
data class DailyIntake(val date: LocalDate, val nutrients: Nutrients, val entryCount: Int) {
    val isLogged: Boolean get() = entryCount > 0
}

/**
 * One logged food, flattened for statistics. [mealType] is free text (e.g. the
 * diary's meal name) so this package doesn't depend on the diary model.
 */
data class ConsumedItem(
    val date: LocalDate,
    val name: String,
    val cuisineId: String?,
    val mealType: String,
    val nutrients: Nutrients,
)

/** Common look-back windows for statistics screens. */
enum class RangePreset(val days: Int) {
    SEVEN(7),
    THIRTY(30),
    NINETY(90),
}

/** Inclusive range of calendar days. */
@Serializable
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(start <= endInclusive) { "start ($start) must not be after endInclusive ($endInclusive)" }
    }

    val dayCount: Int get() = start.daysUntil(endInclusive) + 1

    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive

    fun dates(): List<LocalDate> = List(dayCount) { start.plus(it, DateTimeUnit.DAY) }

    companion object {
        /** The [preset] number of days ending on (and including) [today]. */
        fun last(preset: RangePreset, today: LocalDate): DateRange = last(preset.days, today)

        /** The last [days] days ending on (and including) [endInclusive]. */
        fun last(days: Int, endInclusive: LocalDate): DateRange {
            require(days >= 1) { "days must be at least 1" }
            return DateRange(endInclusive.minus(days - 1, DateTimeUnit.DAY), endInclusive)
        }
    }
}
