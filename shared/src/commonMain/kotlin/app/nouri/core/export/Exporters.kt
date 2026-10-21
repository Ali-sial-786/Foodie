package app.nouri.core.export

import app.nouri.core.diary.DiaryDay
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.progress.BodyMeasurement
import app.nouri.core.progress.NumberText
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightUnit
import kotlinx.datetime.LocalDate

/**
 * One diary line, flattened for export. Nutrient values are as logged (many are
 * estimates; [confidence] carries the label when known). Null means unknown and
 * is exported as an empty cell, not zero.
 */
data class DiaryExportRow(
    val date: LocalDate,
    /** Local time as "HH:mm", when known. */
    val time: String?,
    val meal: String,
    val food: String,
    val amount: Double?,
    val unit: String?,
    val grams: Double?,
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    /** Where the numbers came from, e.g. "Photo estimate", "USDA", "Label". */
    val source: String,
    val confidence: String? = null,
)

/** Diary entries as export rows, in date and time order, with the source and confidence of every value. */
object DiaryExportRows {
    fun from(entries: List<DiaryEntry>): List<DiaryExportRow> =
        entries.sortedWith(compareBy<DiaryEntry>({ it.date }, { it.loggedAtEpochMs })).map(::row)

    fun row(e: DiaryEntry): DiaryExportRow {
        val minute = DiaryDay.minuteOfDay(e.loggedAtEpochMs, e.timeZoneId)
        val n = e.nutrients
        return DiaryExportRow(
            date = e.date,
            time = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}",
            meal = e.mealType.label,
            food = e.name,
            amount = e.quantity?.amount,
            unit = e.quantity?.unit?.symbol,
            grams = e.snapshot.grams,
            energyKcal = n.energyKcal,
            proteinG = n.proteinG,
            carbsG = n.carbsG,
            fatG = n.fatG,
            fiberG = n.fiberG,
            sugarG = n.sugarG,
            sodiumMg = n.sodiumMg,
            source = e.snapshot.provenance.source.label,
            confidence = e.snapshot.confidence.label,
        )
    }
}

/**
 * Food diary as CSV with a header row; rows are written in the order given.
 * Numbers use `.` as decimal separator with no grouping: energy and sodium as
 * whole numbers, macros and grams to 0.1, amounts to 0.01 (trailing zeros
 * trimmed). Dates are ISO 8601 (yyyy-MM-dd).
 */
object DiaryCsvExporter {
    val HEADER: List<String> = listOf(
        "date", "time", "meal", "food", "amount", "unit", "grams",
        "energy_kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "sodium_mg",
        "source", "confidence",
    )

    fun export(rows: List<DiaryExportRow>): String = buildString {
        append(Csv.row(HEADER))
        for (r in rows) {
            append(
                Csv.row(
                    listOf(
                        r.date.toString(),
                        r.time.orEmpty(),
                        r.meal,
                        r.food,
                        number(r.amount, 2),
                        r.unit.orEmpty(),
                        number(r.grams, 1),
                        number(r.energyKcal, 0),
                        number(r.proteinG, 1),
                        number(r.carbsG, 1),
                        number(r.fatG, 1),
                        number(r.fiberG, 1),
                        number(r.sugarG, 1),
                        number(r.sodiumMg, 0),
                        r.source,
                        r.confidence.orEmpty(),
                    ),
                ),
            )
        }
    }
}

/**
 * Weigh-ins and body measurements as one CSV, oldest first, one record per row
 * with a `type` column ("weight" or "measurement"). Weight is converted to
 * [unit] (to 0.01, trailing zeros trimmed) and named in the header, e.g.
 * `weight_lb`; tape measurements stay in centimetres (to 0.1).
 */
object WeightCsvExporter {
    fun header(unit: WeightUnit): List<String> =
        listOf("date", "time", "type", "weight_${unit.symbol}", "waist_cm", "hip_cm", "chest_cm", "note")

    fun export(entries: List<WeightEntry>, measurements: List<BodyMeasurement>, unit: WeightUnit): String {
        data class Line(val date: LocalDate, val order: Int, val minute: Int?, val fields: List<String>)

        val lines = entries.map { e ->
            Line(
                e.date, 0, e.minuteOfDay,
                listOf(
                    e.date.toString(), e.minuteOfDay?.let(::timeOfDay).orEmpty(), "weight",
                    number(unit.fromKg(e.weightKg), 2), "", "", "", e.note.orEmpty(),
                ),
            )
        } + measurements.map { m ->
            Line(
                m.date, 1, null,
                listOf(
                    m.date.toString(), "", "measurement", "",
                    number(m.waistCm, 1), number(m.hipCm, 1), number(m.chestCm, 1), m.note.orEmpty(),
                ),
            )
        }
        return buildString {
            append(Csv.row(header(unit)))
            lines.sortedWith(compareBy<Line> { it.date }.thenBy { it.order }.thenBy(nullsLast()) { it.minute })
                .forEach { append(Csv.row(it.fields)) }
        }
    }

    private fun timeOfDay(minuteOfDay: Int): String =
        "${(minuteOfDay / 60).toString().padStart(2, '0')}:${(minuteOfDay % 60).toString().padStart(2, '0')}"
}

/** Machine-readable number: '.' decimal point, ASCII minus, no grouping; empty when unknown. */
private fun number(value: Double?, decimals: Int): String =
    if (value == null || !value.isFinite()) "" else NumberText.format(value, decimals, trimZeros = true)
