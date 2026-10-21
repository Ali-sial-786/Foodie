package app.nouri.core.parse

import app.nouri.core.ai.ParsedFoodMention
import app.nouri.core.ai.ParsedMealText
import app.nouri.core.dishes.DishCatalog
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.units.QuantityUnit

/**
 * Deterministic parser for short meal descriptions. Works offline and is the
 * fallback when no AI provider is connected; AI parsing (if enabled) produces the
 * same [ParsedMealText] shape so both paths share resolution and nutrition code.
 */
object MealTextParser {

    private val numberWords = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "dozen" to 12.0,
        "half" to 0.5, "quarter" to 0.25, "couple" to 2.0, "few" to 3.0, "single" to 1.0, "double" to 2.0,
    )
    private val unicodeFractions = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125)

    /** Informal measures mapped to explicit quantities (documented assumptions). */
    private val informalMeasures: Map<String, Pair<Double, QuantityUnit>> = mapOf(
        "splash" to (2.0 to QuantityUnit.TABLESPOON),
        "dash" to (1.0 to QuantityUnit.TEASPOON),
        "drizzle" to (1.0 to QuantityUnit.TEASPOON),
        "dollop" to (1.0 to QuantityUnit.TABLESPOON),
        "spoon" to (1.0 to QuantityUnit.TABLESPOON),
        "spoonful" to (1.0 to QuantityUnit.TABLESPOON),
        "glass" to (250.0 to QuantityUnit.MILLILITRE),
        "mug" to (300.0 to QuantityUnit.MILLILITRE),
        "bottle" to (500.0 to QuantityUnit.MILLILITRE),
        "katori" to (1.0 to QuantityUnit.CUP),
        "little" to (1.0 to QuantityUnit.TABLESPOON),
        "bit" to (1.0 to QuantityUnit.TABLESPOON),
    )

    private val leadingNoise = Regex("""^(i\s+(had|ate|drank|have|just\s+had|just\s+ate)|had|ate|drank|for\s+(breakfast|lunch|dinner|snack)\s*,?\s*i\s+(had|ate))\s+""")
    private val trailingNoise = Regex("""\s+(for|at)\s+(breakfast|lunch|dinner|supper|snack|brunch|iftar|suhoor|sehri)\b.*$|\s+(today|yesterday|this\s+morning|tonight|earlier)\b.*$""")
    private val separators = Regex("""\s*(?:,|;|\+|&|\band\b|\bwith\b|\bplus\b|\balong\s+with\b|\bthen\b|\n)\s*""")
    private val recipeFractionPattern = Regex(
        """^(?:(\d+(?:\.\d+)?|half|a\s+half|a\s+third|a\s+quarter|third|quarter|all|the\s+whole)\s+(?:of\s+)?)?(?:the|my|our)\s+(.+?)\s+(?:recipe\s+)?(?:that\s+|which\s+)?(?:i|we)\s+(?:made|cooked|prepared|baked)\b.*$""",
    )

    fun parse(text: String): ParsedMealText {
        val lower = normalizeText(text)
        val mealHint = Regex("""\b(breakfast|lunch|dinner|supper|snack|brunch|iftar|suhoor|sehri)\b""").find(lower)?.value
        val body = lower.replace(leadingNoise, "").replace(trailingNoise, "").trim().trimEnd('.', '!')

        recipeMention(body)?.let { return ParsedMealText(listOf(it), mealHint) }

        val protectedBody = protectCompoundNames(body)
        val items = protectedBody.split(separators)
            .map { it.replace(PLACEHOLDER, " ").trim() }
            .filter { it.isNotBlank() }
            .mapNotNull(::parseSegment)
        return ParsedMealText(items, mealHint)
    }

    internal fun normalizeText(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            val f = unicodeFractions[ch]
            if (f != null) sb.append(' ').append(f.toString()).append(' ') else sb.append(ch)
        }
        return sb.toString()
            .replace('’', '\'')
            .replace(Regex("""(\d)\s*x\b"""), "$1 ") // "2x roti"
            .replace(Regex("""(\d)([a-z])"""), "$1 $2") // "150g"
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun recipeMention(body: String): ParsedFoodMention? {
        val m = recipeFractionPattern.find(body) ?: return null
        val fractionText = m.groupValues[1].trim()
        val name = m.groupValues[2].trim().removeSuffix(" recipe")
        val fraction = when (fractionText) {
            "", "all", "the whole" -> 1.0
            "half", "a half" -> 0.5
            "third", "a third" -> 1.0 / 3
            "quarter", "a quarter" -> 0.25
            else -> fractionText.toDoubleOrNull()?.let { if (it > 1) null else it }
        } ?: return null
        return ParsedFoodMention(food = name, recipeFraction = fraction, recipeReference = name)
    }

    /** Keeps names like "rice and peas" or "mac and cheese" in one piece. */
    private fun protectCompoundNames(body: String): String {
        var result = body
        val names = (DishCatalog.all.flatMap { it.aliases + it.name.lowercase() } + IngredientCatalog.all.flatMap { it.aliases })
            .filter { Regex("""\b(and|with|&)\b""").containsMatchIn(it) }
            .sortedByDescending { it.length }
        for (name in names) {
            if (result.contains(name)) result = result.replace(name, name.replace(" ", PLACEHOLDER))
        }
        return result
    }

    private fun parseSegment(segment: String): ParsedFoodMention? {
        var tokens = segment.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return null
        var amount: Double? = null
        var unit: QuantityUnit? = null
        var grams: Double? = null

        // Leading amount: "2", "1.5", "1/2", "1 1/2", "half a", "a couple of", "two"
        fun takeNumber(): Double? {
            val t = tokens.firstOrNull() ?: return null
            t.toDoubleOrNull()?.let { tokens.removeAt(0); return it }
            Regex("""^(\d+)/(\d+)$""").matchEntire(t)?.let { m ->
                tokens.removeAt(0)
                return m.groupValues[1].toDouble() / m.groupValues[2].toDouble()
            }
            numberWords[t]?.let { v ->
                tokens.removeAt(0)
                if (t == "half" && tokens.firstOrNull() in setOf("a", "an")) tokens.removeAt(0)
                if (t in setOf("a", "an") && tokens.firstOrNull() in setOf("half", "quarter", "couple", "few", "dozen")) {
                    val next = numberWords.getValue(tokens.removeAt(0))
                    if (tokens.firstOrNull() == "of") tokens.removeAt(0)
                    return next
                }
                if (tokens.firstOrNull() == "of" && t in setOf("couple", "few", "dozen")) tokens.removeAt(0)
                return v
            }
            return null
        }
        amount = takeNumber()
        // Mixed numbers: "1 1/2 cups"
        if (amount != null) {
            tokens.firstOrNull()?.let { t -> Regex("""^(\d+)/(\d+)$""").matchEntire(t) }?.let { m ->
                tokens.removeAt(0)
                amount = amount!! + m.groupValues[1].toDouble() / m.groupValues[2].toDouble()
            }
        }

        // Unit or informal measure
        tokens.firstOrNull()?.let { t ->
            val two = if (tokens.size >= 2) "${tokens[0]} ${tokens[1]}" else null
            val parsedTwo = two?.let(QuantityUnit::parse)
            when {
                parsedTwo != null -> { unit = parsedTwo; tokens.removeAt(0); tokens.removeAt(0) }
                informalMeasures.containsKey(t.trimEnd('s')) -> {
                    val (q, u) = informalMeasures.getValue(t.trimEnd('s'))
                    amount = (amount ?: 1.0) * q
                    unit = u
                    tokens.removeAt(0)
                }
                QuantityUnit.parse(t) != null && !(t.length == 1 && tokens.size == 1) -> {
                    val u = QuantityUnit.parse(t)!!
                    // "piece/whole/unit" words only count as units when followed by a food.
                    if (tokens.size > 1 || u.kind != app.nouri.core.units.UnitKind.COUNT) {
                        unit = u
                        tokens.removeAt(0)
                    }
                }
            }
        }
        if (tokens.firstOrNull() == "of") tokens.removeAt(0)
        tokens = tokens.filter { it !in fillerWords }.toMutableList()
        val food = tokens.joinToString(" ").trim().trimEnd('.', ',')
        if (food.isBlank()) return null
        if (unit?.kind == app.nouri.core.units.UnitKind.MASS && amount != null) grams = amount!! * unit!!.massGrams!!
        if (amount != null && unit == null) unit = QuantityUnit.PIECE
        // "deep brand naans" → brand "deep", food "naans"
        val branded = brandPattern.matchEntire(food)
        return ParsedFoodMention(
            food = branded?.groupValues?.get(2) ?: food,
            amount = amount, unit = unit?.symbol, grams = grams,
            brand = branded?.groupValues?.get(1),
        )
    }

    private val brandPattern = Regex("""^(.+?)\s+brand(?:ed)?\s+(.+)$""")

    private const val PLACEHOLDER = "_" // a word character, so word-boundary separators cannot split protected names
    private val fillerWords = setOf("some", "of", "the", "my", "about", "around", "approx", "approximately", "roughly")
}
