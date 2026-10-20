package app.nouri.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * User-facing confidence labels. Deliberately coarse: model-generated percentages
 * are not calibrated probabilities, so the app never shows them as such.
 */
@Serializable
enum class ConfidenceLevel(val label: String, val rank: Int) {
    HIGHER("Higher confidence", 3),
    MODERATE("Moderate confidence", 2),
    LOW("Low confidence", 1);

    companion object {
        /** The weakest link determines the overall level. */
        fun min(vararg levels: ConfidenceLevel): ConfidenceLevel =
            levels.minByOrNull { it.rank } ?: LOW
    }
}

/**
 * A central estimate with a plausible range, e.g. 520–720 kcal around 610 kcal.
 * Ranges are produced from portion and preparation uncertainty, not from model
 * self-reported confidence.
 */
@Serializable
data class EstimateRange(val low: Double, val central: Double, val high: Double) {
    init {
        require(low <= central && central <= high) { "Invalid range: $low..$central..$high" }
    }

    val width: Double get() = high - low

    /** Relative half-width of the range (0.2 = ±20%). */
    val relativeSpread: Double get() = if (central <= 0.0) 0.0 else (high - low) / 2.0 / central

    operator fun plus(other: EstimateRange) =
        EstimateRange(low + other.low, central + other.central, high + other.high)

    operator fun times(factor: Double): EstimateRange {
        require(factor >= 0) { "Range factor must be non-negative" }
        return EstimateRange(low * factor, central * factor, high * factor)
    }

    /** Human friendly text, rounded to a sensible step so it never implies false precision. */
    fun format(unit: String = "kcal", step: Int = 10): String {
        fun r(v: Double) = ((v / step).roundToInt() * step)
        val lo = r(low)
        val hi = r(high)
        return if (lo == hi) "about $lo $unit" else "$lo–$hi $unit"
    }

    companion object {
        fun exact(value: Double) = EstimateRange(value, value, value)

        /** Symmetric relative spread, e.g. around(600.0, 0.15) = 510..600..690. */
        fun around(central: Double, relativeSpread: Double): EstimateRange {
            val spread = relativeSpread.coerceAtLeast(0.0)
            return EstimateRange(
                (central * (1 - spread)).coerceAtLeast(0.0),
                central,
                central * (1 + spread),
            )
        }
    }
}
