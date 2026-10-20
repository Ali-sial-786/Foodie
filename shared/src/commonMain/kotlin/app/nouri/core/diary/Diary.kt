package app.nouri.core.diary

import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.units.Quantity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class MealType(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snacks");

    companion object {
        /** A sensible default meal for a local time of day (minutes after midnight). */
        fun forMinuteOfDay(minute: Int): MealType = when (minute) {
            in 240 until 630 -> BREAKFAST // 04:00–10:30
            in 630 until 900 -> LUNCH // 10:30–15:00
            in 900 until 1050 -> SNACK // 15:00–17:30
            in 1050 until 1350 -> DINNER // 17:30–22:30
            else -> SNACK
        }
    }
}

/** How an entry was created. Used for analytics on the user's own device and for UI badges. */
@Serializable
enum class EntryOrigin { PHOTO_SCAN, BARCODE, NUTRITION_LABEL, SEARCH, NATURAL_LANGUAGE, RECIPE, QUICK_ADD, COPY, FAVOURITE }

/**
 * The nutrition values used at the moment of logging, frozen. Historical entries
 * are never recalculated when reference data changes; editing an entry creates a
 * new snapshot explicitly.
 */
@Serializable
data class NutritionSnapshot(
    val nutrients: Nutrients,
    val grams: Double?,
    val provenance: Provenance,
    val confidence: ConfidenceLevel,
    val precision: Precision,
    /** Calorie range for estimates (photo scans, generic portions). */
    val energyRange: EstimateRange? = null,
    val capturedAtEpochMs: Long,
    val notes: List<String> = emptyList(),
)

@Serializable
data class DiaryEntry(
    val id: String,
    /** Diary day the user sees this entry under (local calendar, see [DiaryDay]). */
    val date: LocalDate,
    val mealType: MealType,
    val loggedAtEpochMs: Long,
    /** IANA zone at logging time, kept so history displays correctly after travel. */
    val timeZoneId: String,
    val name: String,
    /** Quantity exactly as entered by the user. */
    val quantity: Quantity? = null,
    val foodRefId: String? = null,
    val recipeId: String? = null,
    /** Share of a recipe batch eaten (0.5 = half the pot). */
    val recipeFraction: Double? = null,
    val snapshot: NutritionSnapshot,
    val origin: EntryOrigin,
    val scanId: String? = null,
    /** Stable key for the user action that created this entry; prevents double-logging on retries. */
    val idempotencyKey: String,
    val note: String? = null,
) {
    val nutrients: Nutrients get() = snapshot.nutrients
}

data class MealSummary(val mealType: MealType, val entries: List<DiaryEntry>, val total: Nutrients)

data class DailySummary(
    val date: LocalDate,
    val consumed: Nutrients,
    val targets: DailyTargets?,
    val meals: List<MealSummary>,
    val entryCount: Int,
    /** True when any entry is a low-confidence estimate; the UI shows totals as approximate. */
    val containsEstimates: Boolean,
) {
    val remaining: Nutrients? get() = targets?.let { (it.asNutrients() - consumed) }

    fun progress(target: Double?, consumedValue: Double): Double? =
        target?.takeIf { it > 0 }?.let { consumedValue / it }
}

/** Minimal target view used by the diary (full targets live in the progress package). */
@Serializable
data class DailyTargets(
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
) {
    fun asNutrients() = Nutrients(energyKcal, proteinG, carbsG, fatG, fiberG = fiberG)
}

object DiaryCalculator {
    fun summarize(date: LocalDate, entries: List<DiaryEntry>, targets: DailyTargets?): DailySummary {
        val day = entries.filter { it.date == date }
        val meals = MealType.entries.map { meal ->
            val list = day.filter { it.mealType == meal }.sortedBy { it.loggedAtEpochMs }
            MealSummary(meal, list, Nutrients.sum(list.map { it.nutrients }))
        }
        return DailySummary(
            date = date,
            consumed = Nutrients.sum(day.map { it.nutrients }),
            targets = targets,
            meals = meals,
            entryCount = day.size,
            containsEstimates = day.any { it.snapshot.precision == Precision.ESTIMATED },
        )
    }

    /** Daily totals for a date range (days without entries are omitted, not zero-filled). */
    fun dailyTotals(entries: List<DiaryEntry>): Map<LocalDate, Nutrients> =
        entries.groupBy { it.date }.mapValues { (_, list) -> Nutrients.sum(list.map { it.nutrients }) }
}

/**
 * Determines the diary day for a timestamp. [dayStartMinute] lets people who eat
 * after midnight (night shifts, Ramadan suhoor) keep those meals on the previous
 * day; default 0 = calendar midnight.
 */
object DiaryDay {
    fun of(epochMs: Long, timeZoneId: String, dayStartMinute: Int = 0): LocalDate {
        require(dayStartMinute in 0 until 24 * 60) { "dayStartMinute must be within a day" }
        val zone = runCatching { TimeZone.of(timeZoneId) }.getOrElse { TimeZone.UTC }
        val shifted = Instant.fromEpochMilliseconds(epochMs - dayStartMinute * 60_000L)
        return shifted.toLocalDateTime(zone).date
    }

    fun minuteOfDay(epochMs: Long, timeZoneId: String): Int {
        val zone = runCatching { TimeZone.of(timeZoneId) }.getOrElse { TimeZone.UTC }
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return t.hour * 60 + t.minute
    }
}

sealed interface DuplicateCheck {
    data object Unique : DuplicateCheck

    /** Same user action (idempotency key) — must not be stored again. */
    data class Exact(val existing: DiaryEntry) : DuplicateCheck

    /** Looks like an accidental double-tap; the UI asks before saving. */
    data class Likely(val existing: DiaryEntry) : DuplicateCheck
}

object DuplicateGuard {
    private const val WINDOW_MS = 3 * 60_000L

    fun check(candidate: DiaryEntry, existing: List<DiaryEntry>): DuplicateCheck {
        existing.firstOrNull { it.idempotencyKey == candidate.idempotencyKey }?.let { return DuplicateCheck.Exact(it) }
        val likely = existing.firstOrNull {
            it.date == candidate.date &&
                it.mealType == candidate.mealType &&
                it.name.equals(candidate.name, ignoreCase = true) &&
                kotlin.math.abs(it.loggedAtEpochMs - candidate.loggedAtEpochMs) <= WINDOW_MS &&
                kotlin.math.abs(it.nutrients.energyKcal - candidate.nutrients.energyKcal) <= maxOf(1.0, it.nutrients.energyKcal * 0.01)
        }
        return if (likely != null) DuplicateCheck.Likely(likely) else DuplicateCheck.Unique
    }
}

/** Copy/duplicate operations. Snapshots are carried over unchanged; ids and keys are new. */
object DiaryOperations {
    fun copyEntries(
        source: List<DiaryEntry>,
        toDate: LocalDate,
        toMeal: MealType? = null,
        nowEpochMs: Long,
        timeZoneId: String,
        newId: () -> String,
    ): List<DiaryEntry> = source.map { e ->
        val id = newId()
        e.copy(
            id = id,
            date = toDate,
            mealType = toMeal ?: e.mealType,
            loggedAtEpochMs = nowEpochMs,
            timeZoneId = timeZoneId,
            origin = EntryOrigin.COPY,
            idempotencyKey = "copy:${e.id}:$toDate:${toMeal ?: e.mealType}",
            scanId = null,
        )
    }

    /** Rescales an entry to a new quantity, keeping its frozen per-gram nutrition. */
    fun rescale(entry: DiaryEntry, newGrams: Double, newQuantity: Quantity?, nowEpochMs: Long): DiaryEntry {
        val oldGrams = entry.snapshot.grams
        require(oldGrams != null && oldGrams > 0) { "Entry has no gram basis to rescale" }
        val factor = newGrams / oldGrams
        val snapshot = entry.snapshot.copy(
            nutrients = entry.snapshot.nutrients * factor,
            grams = newGrams,
            energyRange = entry.snapshot.energyRange?.times(factor),
            capturedAtEpochMs = nowEpochMs,
        )
        return entry.copy(quantity = newQuantity, snapshot = snapshot)
    }
}
