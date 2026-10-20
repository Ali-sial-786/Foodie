package app.nouri.ui.recipes

import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.recipes.RecipeStep
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitKind
import app.nouri.core.units.formatAmount

/** Pure text helpers for recipes (no Android), so they are unit-testable on the JVM. */
object RecipeText {
    private val plurals = mapOf(
        QuantityUnit.CUP to "cups", QuantityUnit.SLICE to "slices", QuantityUnit.CLOVE to "cloves",
        QuantityUnit.SERVING to "servings", QuantityUnit.HANDFUL to "handfuls", QuantityUnit.BOWL to "bowls",
        QuantityUnit.PLATE to "plates", QuantityUnit.CAN to "cans", QuantityUnit.PACKAGE to "packages",
        QuantityUnit.BUNCH to "bunches", QuantityUnit.STICK to "sticks", QuantityUnit.SCOOP to "scoops",
        QuantityUnit.PINCH to "pinches",
    )

    /** "2 cups", "1 medium", "250 g"; empty for unquantified amounts ("to taste"). */
    fun quantity(q: Quantity): String {
        if (q.amount <= 0.0) return ""
        val amount = formatAmount(q.amount)
        val size = q.size?.name?.lowercase()
        val unit = when {
            q.unit == QuantityUnit.PIECE -> null
            q.amount > 1.0 -> plurals[q.unit] ?: q.unit.symbol
            else -> q.unit.symbol
        }
        return listOfNotNull(amount, size, unit).joinToString(" ")
    }

    /** One editable line per ingredient, e.g. "2 medium onion, thinly sliced (optional)". */
    fun ingredientLine(line: RecipeIngredient): String = buildString {
        val q = quantity(line.quantity)
        if (q.isNotEmpty()) append(q).append(' ')
        append(line.displayName)
        val prep = line.preparation?.takeIf { it.isNotBlank() }
        if (prep != null) append(", ").append(prep)
        if (line.optional) append(" (optional)")
    }

    /** The whole ingredient list as editable text: "Group:" headings, and a blank line between groups. */
    fun ingredientsText(ingredients: List<RecipeIngredient>): String = buildString {
        var group: String? = null
        ingredients.forEach { line ->
            if (line.group != group) {
                group = line.group
                if (isNotEmpty()) append('\n')
                group?.let { append(it).append(":\n") }
            }
            append(ingredientLine(line)).append('\n')
        }
    }.trimEnd()

    fun stepsText(steps: List<RecipeStep>): String = steps.joinToString("\n") { it.text }

    /** "1:05:00" or "12:34". */
    fun countdown(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "45 min", "1 h 15 min". */
    fun minutes(total: Int): String = when {
        total < 60 -> "$total min"
        total % 60 == 0 -> "${total / 60} h"
        else -> "${total / 60} h ${total % 60} min"
    }
}

/** A recipe ingredient line typed by the user, before it is resolved to a canonical ingredient. */
data class ParsedIngredientLine(
    val raw: String,
    /** 0 = unquantified ("salt, to taste"); the engine leaves it out of totals. */
    val amount: Double,
    val unit: QuantityUnit,
    val size: PortionSize?,
    val name: String,
    val preparation: String?,
    val optional: Boolean,
    val group: String?,
) {
    val quantity: Quantity get() = Quantity(amount, unit, size)
}

/**
 * Parses "amount unit name, preparation" lines. Deliberately simple and
 * predictable: anything it can't read as an amount stays in the name, and the
 * editor shows the result so the user can correct it.
 */
object RecipeLineParser {
    private val fractions = mapOf('½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75, '⅛' to 0.125)
    private val number = """(?:\d+(?:[.,]\d+)?)"""
    private val mixed = Regex("""^(\d+)\s+(\d+)/(\d+)\b""")
    private val simpleFraction = Regex("""^(\d+)/(\d+)\b""")
    private val range = Regex("""^($number)\s*(?:-|–|to)\s*($number)(?![\d/])""")
    private val plain = Regex("""^($number)""")
    private val optionalMarker = Regex("""\(\s*optional\s*\)|^optional\s*:?\s+|,?\s+optional$""", RegexOption.IGNORE_CASE)
    private val toTaste = Regex("""\bto taste\b""", RegexOption.IGNORE_CASE)
    private val sizes = mapOf("small" to PortionSize.SMALL, "medium" to PortionSize.MEDIUM, "large" to PortionSize.LARGE, "big" to PortionSize.LARGE)
    private val stepTime = Regex("""(\d+)\s*(?:(?:-|–|to)\s*(\d+)\s*)?(hours?|hrs?|h|minutes?|mins?|m)\b""", RegexOption.IGNORE_CASE)

    /** Parses a whole ingredient block; a line ending in ":" starts a group and a blank line ends it. */
    fun parseAll(text: String): List<ParsedIngredientLine> {
        var group: String? = null
        val result = mutableListOf<ParsedIngredientLine>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                group = null
                continue
            }
            if (line.endsWith(':')) {
                group = line.removeSuffix(":").trim().ifEmpty { null }
                continue
            }
            parse(line, group)?.let(result::add)
        }
        return result
    }

    fun parse(input: String, group: String? = null): ParsedIngredientLine? {
        var text = input.trim().trimStart('-', '•', '*', '·').trim()
        if (text.isEmpty()) return null
        val optional = optionalMarker.containsMatchIn(text)
        text = text.replace(optionalMarker, "").trim()

        val (amount, afterAmount) = readAmount(text)
        var rest = afterAmount.trim()

        var size: PortionSize? = null
        var unit: QuantityUnit? = null
        if (amount != null) {
            val words = rest.split(Regex("\\s+")).toMutableList()
            fun takeSize() {
                sizes[words.firstOrNull()?.lowercase()]?.let { size = it; words.removeAt(0) }
            }
            takeSize()
            if (words.size >= 2) QuantityUnit.parse("${words[0]} ${words[1]}")?.let { unit = it; words.removeAt(0); words.removeAt(0) }
            if (unit == null && words.size >= 2) {
                // A lone token is the food itself ("2 eggs"), never a unit.
                QuantityUnit.parse(words[0].trimEnd(','))?.let { unit = it; words.removeAt(0) }
            }
            if (size == null) takeSize()
            if (words.firstOrNull()?.lowercase() == "of") words.removeAt(0)
            rest = words.joinToString(" ")
        }

        val commaIndex = rest.indexOf(',')
        var name = (if (commaIndex >= 0) rest.substring(0, commaIndex) else rest).trim()
        var preparation = if (commaIndex >= 0) rest.substring(commaIndex + 1).trim().ifEmpty { null } else null
        val tasted = toTaste.containsMatchIn(name) || preparation?.let { toTaste.containsMatchIn(it) } == true
        if (toTaste.containsMatchIn(name)) name = name.replace(toTaste, "").trim().trimEnd(',').trim()
        if (name.isEmpty()) return null

        val quantified = amount != null && amount > 0.0 && !tasted
        if (tasted && preparation == null) preparation = "to taste"
        return ParsedIngredientLine(
            raw = input.trim(),
            amount = if (quantified) amount else 0.0,
            unit = when {
                !quantified -> QuantityUnit.PINCH
                unit != null -> unit
                else -> QuantityUnit.PIECE
            },
            size = if (quantified) size else null,
            name = name,
            preparation = preparation,
            optional = optional,
            group = group,
        )
    }

    /** A free-standing amount such as "2", "500 g" or "1 1/2 cups"; null when blank or unreadable. */
    fun parseQuantity(text: String): Quantity? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val (amount, rest) = readAmount(t)
        if (amount == null || amount <= 0.0) return null
        val word = rest.trim()
        if (word.isEmpty()) return Quantity(amount, QuantityUnit.PIECE)
        val unit = QuantityUnit.parse(word) ?: return null
        return Quantity(amount, unit)
    }

    /** The first timed duration mentioned in a step ("simmer for 10–12 minutes" → 600 s). Lower bound, so cooks check early. */
    fun stepTimerSeconds(step: String): Int? {
        val m = stepTime.find(step) ?: return null
        val value = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[3].lowercase()
        val seconds = if (unit.startsWith("h")) value * 3600 else value * 60
        return seconds.takeIf { it in 30..(12 * 3600) }
    }

    private fun readAmount(text: String): Pair<Double?, String> {
        // Attached units: "200g chicken", "1.5kg lamb".
        Regex("""^($number)([a-zA-Z]+)\b(.*)$""").find(text)?.let { m ->
            val unit = QuantityUnit.parse(m.groupValues[2])
            if (unit != null && unit.kind != UnitKind.COUNT) {
                return toDouble(m.groupValues[1]) to (unit.symbol + " " + m.groupValues[3].trim())
            }
        }
        mixed.find(text)?.let { m ->
            val v = m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / m.groupValues[3].toDouble().coerceAtLeast(1.0)
            return v to text.substring(m.range.last + 1)
        }
        simpleFraction.find(text)?.let { m ->
            return m.groupValues[1].toDouble() / m.groupValues[2].toDouble().coerceAtLeast(1.0) to text.substring(m.range.last + 1)
        }
        range.find(text)?.let { m ->
            return ((toDouble(m.groupValues[1]) + toDouble(m.groupValues[2])) / 2.0) to text.substring(m.range.last + 1)
        }
        plain.find(text)?.let { m ->
            var v = toDouble(m.groupValues[1])
            var rest = text.substring(m.range.last + 1)
            // "1½" or "1 ½"
            val trimmed = rest.trimStart()
            fractions[trimmed.firstOrNull()]?.let { f -> v += f; rest = trimmed.drop(1) }
            return v to rest
        }
        fractions[text.first()]?.let { f -> return f to text.drop(1) }
        return null to text
    }

    private fun toDouble(s: String): Double = s.replace(',', '.').toDouble()
}
