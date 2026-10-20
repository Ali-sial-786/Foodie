package app.nouri.core.label

/** One OCR line: [original] keeps the printed case; [folded] has exactly the same length. */
internal class LabelLine(val original: String, val folded: String)

internal object LabelText {

    private val whitespace = Regex("\\s+")

    fun lines(ocrText: String): List<LabelLine> =
        ocrText.split('\n', '\r')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .map { LabelLine(it, fold(it)) }

    /** Unifies look-alike punctuation and collapses whitespace. */
    private fun normalize(line: String): String {
        val mapped = buildString(line.length) {
            for (c in line) {
                append(
                    when (c) {
                        '\t', ' ', ' ', ' ', '•', '•' -> ' '
                        '‹', '＜', '≤', '≺' -> '<'
                        '–', '—', '‐' -> '-'
                        '，' -> ','
                        '：' -> ':'
                        // UK labels sometimes print a raised decimal point ("8·5g").
                        '·' -> '.'
                        else -> c
                    },
                )
            }
        }
        return whitespace.replace(mapped, " ").trim()
    }

    /** Lower-case without accents, one character per character so indices stay aligned. */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) append(foldChar(c.lowercaseChar()))
    }

    private fun foldChar(c: Char): Char = when (c) {
        'à', 'á', 'â', 'ä', 'ã', 'å' -> 'a'
        'ç' -> 'c'
        'è', 'é', 'ê', 'ë' -> 'e'
        'ì', 'í', 'î', 'ï' -> 'i'
        'ò', 'ó', 'ô', 'ö', 'õ' -> 'o'
        'ù', 'ú', 'û', 'ü' -> 'u'
        'ý', 'ÿ' -> 'y'
        'ñ' -> 'n'
        else -> c
    }
}

/**
 * A number read from a label row. [value] is the printed number; [lessThan] marks "<1 g" /
 * "less than 1 g" and [trace] marks "trace", which callers turn into an explicit approximation.
 */
internal data class LabelAmount(
    val value: Double,
    val unit: String?,
    val raw: String,
    val lessThan: Boolean = false,
    val trace: Boolean = false,
    val inParentheses: Boolean = false,
)

/** The amounts in a row segment; [clean] is false when unrelated words interrupted the values. */
internal class SegmentReading(val amounts: List<LabelAmount>, val clean: Boolean)

internal object LabelAmounts {
    const val KCAL = "kcal"
    const val KJ = "kj"
    const val G = "g"
    const val MG = "mg"
    const val MCG = "mcg"
    const val ML = "ml"

    val unitWords = setOf("kcal", "kj", "g", "mg", "mcg", "µg", "ug", "ml")
    private val fillerWords = setOf("about", "approx", "approximately", "environ", "env", "ca", "dv", "ri", "vq", "gda")
    private val traceWords = setOf("trace", "traces")

    private val lessThanWords = Regex("""less\s*than|moins\s*de|weniger\s*als""")

    // 1 "<"  2 paren  3 number  4 unit  5 percent  6 word
    private val token = Regex(
        """(<)|([()])|(\d*[.,]?\d+(?:[.,]\d+)*)\s*(?:(kcal|kj|cal|mcg|µg|ug|mg|grams?|gm|gr|g|ml)(?![a-z])|(%))?|([a-zµß-ÿ]+)""",
    )

    private val digitRun = Regex("""[0-9OoIil|]+(?:[.,][0-9OoIil|]+)*""")
    private val unitAhead = Regex("""^\s*(?:kcal|kj|mcg|mg|ml|g)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)

    /**
     * Repairs OCR letter/digit confusions inside numbers: O→0 and l/I/|→1 ("1O g", "l60mg",
     * "Og"). Only runs that stand alone (not inside a word) and contain a real digit, or are
     * zeros directly before a unit, are touched; a trailing "l" is left alone because it may be
     * a litre symbol ("1l"). Lengths are preserved.
     */
    fun fixOcrDigits(text: String): String {
        val out = StringBuilder(text)
        for (m in digitRun.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > 0 && text[start - 1].isLetter()) continue
            val run = m.value
            val unitFollows = unitAhead.containsMatchIn(text.substring(end))
            if (end < text.length && text[end].isLetter() && !unitFollows) continue
            if (run.none { it.isDigit() } && !(unitFollows && run.any { it == 'O' || it == 'o' })) continue
            for ((k, c) in run.withIndex()) {
                val fixed = when (c) {
                    'O', 'o' -> '0'
                    'l' -> if (k == run.lastIndex && !unitFollows) 'l' else '1'
                    'I', 'i', '|' -> '1'
                    else -> c
                }
                out[start + k] = fixed
            }
        }
        return out.toString()
    }

    /** Reads the amounts at the start of a (folded, digit-fixed) row segment. */
    fun read(segment: String): SegmentReading {
        val text = lessThanWords.replace(segment, "<")
        val amounts = mutableListOf<LabelAmount>()
        val loneUnits = mutableListOf<String>()
        var lessThan = false
        var depth = 0
        var clean = true
        for (m in token.findAll(text)) {
            val groups = m.groups
            when {
                groups[1] != null -> lessThan = true
                groups[2] != null -> depth = if (m.value == "(") depth + 1 else maxOf(0, depth - 1)
                groups[3] != null -> {
                    if (groups[5] != null) {
                        // A percentage (% Daily Value, %RI) is never an amount.
                        lessThan = false
                        continue
                    }
                    val raw = groups[3]!!.value
                    val unit = groups[4]?.value?.let(::normalizeUnit)
                    val value = number(raw, unit) ?: continue
                    amounts += LabelAmount(value, unit, raw, lessThan = lessThan, inParentheses = depth > 0)
                    lessThan = false
                }
                else -> {
                    val word = groups[6]!!.value
                    when (word) {
                        in unitWords -> loneUnits += normalizeUnit(word)
                        in traceWords -> amounts += LabelAmount(0.0, null, word, trace = true, inParentheses = depth > 0)
                        in fillerWords -> Unit
                        else -> {
                            clean = false
                            break
                        }
                    }
                }
            }
        }
        if (loneUnits.isEmpty()) return SegmentReading(amounts, clean)
        // "Energy (kJ/kcal) 1550/370" or "Fat (g) 8.5": units printed once for the whole row.
        var k = 0
        val withUnits = amounts.map {
            if (it.unit == null && !it.trace) it.copy(unit = loneUnits[k++ % loneUnits.size]) else it
        }
        return SegmentReading(withUnits, clean)
    }

    private fun normalizeUnit(unit: String): String = when (unit) {
        "cal" -> KCAL
        "µg", "ug", "mcg" -> MCG
        "grams", "gram", "gm", "gr", "g" -> G
        else -> unit
    }

    /**
     * Interprets "1,1", "0.5", "1,200" and "1.550". A separator followed by exactly three digits
     * is a thousands separator only for units where large values are normal (mg, kJ, kcal or no
     * unit); "1,125 g" stays 1.125 g.
     */
    fun number(raw: String, unit: String?): Double? {
        val separators = raw.count { it == '.' || it == ',' }
        if (separators == 0) return raw.toDoubleOrNull()
        val last = raw.indexOfLast { it == '.' || it == ',' }
        val intPart = raw.substring(0, last).filter { it.isDigit() }
        val fraction = raw.substring(last + 1)
        val thousandsAllowed = unit == null || unit == MG || unit == KJ || unit == KCAL
        val thousands = fraction.length == 3 && thousandsAllowed && intPart.isNotEmpty() && !intPart.startsWith('0') &&
            (separators == 1 || raw.filter { it == '.' || it == ',' }.toSet().size == 1)
        return if (thousands) (intPart + fraction).toDoubleOrNull() else "$intPart.$fraction".toDoubleOrNull()
    }
}
