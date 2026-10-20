package app.nouri.ui.components

import app.nouri.core.model.Nutrients
import app.nouri.core.nutrition.NutritionRounding
import app.nouri.core.nutrition.Precision
import app.nouri.core.progress.NumberText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

/** Display formatting shared by every screen, so numbers never imply more precision than they have. */
object Fmt {
    fun kcal(value: Double, precision: Precision = Precision.CALCULATED): String =
        NumberText.grouped(NutritionRounding.energy(value, precision).toDouble())

    fun kcalUnit(value: Double, precision: Precision = Precision.CALCULATED): String = "${kcal(value, precision)} kcal"

    fun grams(value: Double?, precision: Precision = Precision.CALCULATED): String =
        if (value == null) "–" else NutritionRounding.formatGrams(value, precision)

    fun gramsShort(value: Double): String = "${value.roundToInt()} g"

    fun macroLine(n: Nutrients, precision: Precision = Precision.CALCULATED): String =
        "P ${grams(n.proteinG, precision)} · C ${grams(n.carbsG, precision)} · F ${grams(n.fatG, precision)}"

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> "${weekday(date.dayOfWeek)} ${date.day} ${month(date.month)}"
    }

    fun shortDate(date: LocalDate): String = "${date.day} ${month(date.month)}"

    fun weekday(d: DayOfWeek): String = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    fun month(m: Month): String = m.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    fun time(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun decimal(value: Double, decimals: Int = 1): String = NumberText.format(value, decimals)
}

/** Parses user-typed numbers leniently ("1,5" → 1.5); null for blank or invalid input. */
fun String.toUserDouble(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
