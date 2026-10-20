package app.nouri.core.progress

import app.nouri.core.units.UnitConverter
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Sex used only to pick an energy equation. [UNSPECIFIED] is a first-class
 * choice: estimates then use the midpoint of the male and female equations.
 */
@Serializable
enum class Sex { FEMALE, MALE, UNSPECIFIED }

/**
 * Physical activity level (PAL) multipliers applied to resting energy. These are
 * the conventional Harris–Benedict/Mifflin multipliers; self-reported activity
 * is the largest source of error in any TDEE estimate.
 */
@Serializable
enum class ActivityLevel(val multiplier: Double, val label: String) {
    SEDENTARY(1.2, "Mostly sitting, little exercise"),
    LIGHT(1.375, "Lightly active (light exercise 1–3 days a week)"),
    MODERATE(1.55, "Moderately active (exercise 3–5 days a week)"),
    ACTIVE(1.725, "Active (hard exercise 6–7 days a week)"),
    VERY_ACTIVE(1.9, "Very active (physical job or training twice a day)"),
}

@Serializable
enum class WeightGoal { MAINTAIN, LOSE, GAIN, MUSCLE_GAIN }

/**
 * What the user told us about themselves. Every field except the goal is
 * optional so onboarding can be skipped; calculators report what is missing
 * instead of guessing.
 *
 * Only the birth *year* is stored (less sensitive than a full date), so ages are
 * accurate to within a year — irrelevant at the precision of these equations.
 */
@Serializable
data class BodyProfile(
    val sex: Sex = Sex.UNSPECIFIED,
    val birthYear: Int? = null,
    val heightCm: Double? = null,
    val activityLevel: ActivityLevel = ActivityLevel.LIGHT,
    val goal: WeightGoal = WeightGoal.MAINTAIN,
    val targetWeightKg: Double? = null,
    /** Desired rate of change as a positive magnitude; the direction comes from [goal]. */
    val weeklyRateKg: Double = 0.5,
    val bodyFatPercent: Double? = null,
) {
    init {
        require(weeklyRateKg.isFinite() && weeklyRateKg >= 0) { "weeklyRateKg is a magnitude and must be >= 0" }
        require(heightCm == null || (heightCm.isFinite() && heightCm > 0)) { "heightCm must be positive" }
        require(targetWeightKg == null || (targetWeightKg.isFinite() && targetWeightKg > 0)) { "targetWeightKg must be positive" }
        require(bodyFatPercent == null || (bodyFatPercent > 0 && bodyFatPercent < 100)) { "bodyFatPercent must be in 0..100" }
    }

    /** Age reached during [today]'s year; may be one year high before the birthday. */
    fun ageOn(today: LocalDate): Int? = birthYear?.let { today.year - it }
}

/** One weigh-in. Several per day are allowed; trend calculations average them. */
@Serializable
data class WeightEntry(
    val id: String,
    val date: LocalDate,
    /** Minutes after local midnight, when known (0..1439). */
    val minuteOfDay: Int? = null,
    val weightKg: Double,
    val note: String? = null,
) {
    init {
        require(weightKg.isFinite() && weightKg > 0) { "weightKg must be positive" }
        require(minuteOfDay == null || minuteOfDay in 0 until 24 * 60) { "minuteOfDay must be in 0..1439" }
    }
}

/** Optional tape-measure readings; any subset may be recorded. */
@Serializable
data class BodyMeasurement(
    val id: String,
    val date: LocalDate,
    val waistCm: Double? = null,
    val hipCm: Double? = null,
    val chestCm: Double? = null,
    val note: String? = null,
)

/**
 * Daily nutrition targets. [manual] targets were typed in by the user and are
 * never changed by the app; suggested targets carry a plain-language [basis]
 * explaining how they were derived (always an estimate).
 */
@Serializable
data class NutritionTargets(
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val waterMl: Double? = null,
    val manual: Boolean,
    /** Plain-language explanation of where these numbers came from. */
    val basis: String,
) {
    /** Energy implied by the macro targets with 4/4/9 kcal per gram. */
    val macroEnergyKcal: Double get() = proteinG * 4.0 + carbsG * 4.0 + fatG * 9.0
}

/** Display unit for body weight. Storage is always kilograms. */
@Serializable
enum class WeightUnit(val symbol: String) {
    KG("kg"),
    LB("lb");

    fun fromKg(kg: Double): Double = if (this == KG) kg else UnitConverter.kgToLb(kg)

    fun toKg(value: Double): Double = if (this == KG) value else UnitConverter.lbToKg(value)

    /** "72.4 kg" / "159.6 lb". */
    fun format(kg: Double, decimals: Int = 1): String =
        "${NumberText.format(fromKg(kg), decimals, minus = NumberText.MINUS)} $symbol"

    /** Absolute amount of a change, e.g. 1.2 kg for −1.2 kg; direction is worded separately. */
    fun formatMagnitude(kg: Double, decimals: Int = 1): String = format(kotlin.math.abs(kg), decimals)

    /** Signed change: "+0.4 kg", "−1.2 kg". */
    fun formatChange(kg: Double, decimals: Int = 1): String = "${NumberText.signed(fromKg(kg), decimals)} $symbol"
}
