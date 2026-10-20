package app.nouri.core.progress

import kotlin.math.abs
import kotlin.math.floor

/**
 * Locale-independent number formatting for user-facing text and exports.
 *
 * commonMain has no `String.format`, so this provides the few shapes the app
 * needs. Output is English-style (`,` thousands separator, `.` decimal point);
 * localisation is a UI concern and out of scope here. Rounding is half away
 * from zero with a tiny tolerance so values such as 0.15 (stored as
 * 0.1499999…) round the way people expect.
 */
object NumberText {

    /** Unicode minus sign, used in prose so "−1.2 kg" doesn't look like a hyphen. */
    const val MINUS = "−"

    private val powersOfTen = LongArray(10).also { p ->
        p[0] = 1
        for (i in 1 until p.size) p[i] = p[i - 1] * 10
    }

    /** Rounds half away from zero to [decimals] places. */
    fun round(value: Double, decimals: Int = 0): Double {
        val factor = powersOfTen[decimals].toDouble()
        val scaled = abs(value) * factor
        val rounded = floor(scaled + 0.5 + 1e-9 * scaled.coerceAtLeast(1.0)) / factor
        return if (value < 0) -rounded else rounded
    }

    /** Rounds to the nearest multiple of [step], e.g. kcal to the nearest 10. */
    fun roundToStep(value: Double, step: Double): Double = round(value / step) * step

    /**
     * Formats with exactly [decimals] fractional digits ("1.20"), or fewer when
     * [trimZeros] is set ("1.2", "3"). [grouping] inserts thousands separators.
     * [minus] is the sign used for negative values; "-0.0" never appears.
     */
    fun format(
        value: Double,
        decimals: Int = 0,
        trimZeros: Boolean = false,
        grouping: Boolean = false,
        minus: String = "-",
    ): String {
        require(decimals in 0..6) { "Unsupported number of decimals: $decimals" }
        if (!value.isFinite()) return "0"
        val factor = powersOfTen[decimals]
        val scaledAbs = abs(value) * factor
        val units = floor(scaledAbs + 0.5 + 1e-9 * scaledAbs.coerceAtLeast(1.0)).toLong()
        val integerPart = units / factor
        var fraction = if (decimals == 0) "" else (units % factor).toString().padStart(decimals, '0')
        if (trimZeros) fraction = fraction.trimEnd('0')
        val sb = StringBuilder()
        if (value < 0 && units != 0L) sb.append(minus)
        sb.append(if (grouping) group(integerPart) else integerPart.toString())
        if (fraction.isNotEmpty()) sb.append('.').append(fraction)
        return sb.toString()
    }

    /** Whole number with thousands separators: 1850.4 → "1,850". */
    fun grouped(value: Double): String = format(value, 0, grouping = true, minus = MINUS)

    /** Energy for prose, rounded to [step] kcal: "1,850 kcal". */
    fun kcal(value: Double, step: Double = 10.0): String = "${grouped(roundToStep(value, step))} kcal"

    /** Whole grams for prose: "112 g". */
    fun grams(value: Double): String = "${grouped(value)} g"

    /** Value with an explicit sign for changes: "+0.4", "−1.2", "0.0". */
    fun signed(value: Double, decimals: Int): String {
        val text = format(value, decimals, minus = MINUS)
        return if (round(value, decimals) > 0.0) "+$text" else text
    }

    /** "1 day", "5 days". */
    fun count(n: Int, singular: String, plural: String = singular + "s"): String =
        "${grouped(n.toDouble())} ${if (n == 1) singular else plural}"

    private fun group(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val lead = digits.length % 3
        if (lead > 0) sb.append(digits, 0, lead)
        var i = lead
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
