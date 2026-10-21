package app.nouri.core.recipes

import app.nouri.core.ai.GeneratedIngredient
import app.nouri.core.ai.GeneratedRecipe
import app.nouri.core.ai.GeneratedStep
import app.nouri.core.ai.ImportedRecipe
import app.nouri.core.dishes.DishMatcher
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitKind

/**
 * Turns pasted recipe text (or on-device OCR of a screenshot) into a structured recipe
 * without any AI: section headings, ingredient lines and numbered steps are recognised
 * with simple rules. The result is shown for review before saving; anything the rules
 * couldn't work out is listed in [ImportedRecipe.notes] rather than guessed.
 */
object RecipeTextImporter {
    private val ingredientsHeading = Regex("""^(ingredients?|what you('ll| will)? need|you('ll| will)? need|shopping list)\b.*$""", RegexOption.IGNORE_CASE)
    private val stepsHeading = Regex("""^(method|instructions?|directions?|steps?|preparation|how to (make|cook)( it)?|cooking)\b\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val notesHeading = Regex("""^(notes?|tips?|nutrition( facts| information)?|storage)\b\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val servingsLine = Regex("""\b(serves|servings|serving size|yield|yields|makes|portions)\b\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val prepLine = Regex("""\bprep(aration)?\s*(time)?\s*:?\s*(\d+)\s*(h|hr|hrs|hours?|m|min|mins|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val cookLine = Regex("""\bcook(ing)?\s*(time)?\s*:?\s*(\d+)\s*(h|hr|hrs|hours?|m|min|mins|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val sourceLine = Regex("""^(recipe\s+by|recipe\s+from|source|adapted\s+from|credit)\s*:?\s+(.{2,60})$""", RegexOption.IGNORE_CASE)
    private val byline = Regex("""^By\s+([A-Z][\w.'& -]{1,40})$""")
    private val url = Regex("""https?://(www\.)?([a-z0-9.-]+\.[a-z]{2,})""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^\s*([•\-*▢☐□◻▪●○·–—]|\[\s?\])\s*""")
    private val stepNumber = Regex("""^\s*(step\s*)?\d{1,2}\s*[.):\-]\s*|^\s*step\s*\d{1,2}\s*""", RegexOption.IGNORE_CASE)
    private val amountPattern = Regex(
        """^(\d+\s+\d+/\d+|\d+\s*[½⅓⅔¼¾⅛]|\d+/\d+|\d+(?:[.,]\d+)?(?:\s*(?:-|–|to)\s*\d+(?:[.,]\d+)?)?|[½⅓⅔¼¾⅛])\s*""",
    )
    private val unicodeFractions = mapOf('½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75, '⅛' to 0.125)
    private val parenthesisedMass = Regex("""\((\d+(?:[.,]\d+)?)\s*(g|grams?|kg|oz|ounces?)\)""", RegexOption.IGNORE_CASE)
    private val toTaste = Regex("""\b(to taste|as needed|as required|for garnish|optional)\b""", RegexOption.IGNORE_CASE)

    private enum class Section { NONE, INGREDIENTS, STEPS, NOTES }

    fun parse(text: String): ImportedRecipe {
        val lines = text.replace('\r', '\n').lines().map { it.trim() }
        val notes = mutableListOf<String>()
        var title: String? = null
        var servings: Int? = null
        var prep: Int? = null
        var cook: Int? = null
        var source: String? = null
        var section = Section.NONE
        var group: String? = null
        val ingredients = mutableListOf<GeneratedIngredient>()
        val steps = mutableListOf<String>()
        var stepContinues = false

        for (raw in lines) {
            if (raw.isBlank()) {
                stepContinues = false
                continue
            }
            servingsLine.find(raw)?.let { servings = servings ?: it.groupValues[2].toIntOrNull() }
            prepLine.find(raw)?.let { prep = prep ?: minutes(it.groupValues[3], it.groupValues[4]) }
            cookLine.find(raw)?.let { cook = cook ?: minutes(it.groupValues[3], it.groupValues[4]) }
            if (source == null) {
                (sourceLine.matchEntire(raw)?.groupValues?.get(2) ?: byline.matchEntire(raw)?.groupValues?.get(1))
                    ?.let { source = it.trim().trimEnd('.') }
                    ?: url.find(raw)?.let { source = it.groupValues[2].lowercase() }
            }
            when {
                ingredientsHeading.matches(raw.trimEnd(':')) && raw.length < 40 -> { section = Section.INGREDIENTS; group = null; continue }
                stepsHeading.matches(raw) -> { section = Section.STEPS; continue }
                notesHeading.matches(raw) -> { section = Section.NOTES; continue }
            }
            if (servingsLine.containsMatchIn(raw) && raw.length < 40) continue
            if ((prepLine.containsMatchIn(raw) || cookLine.containsMatchIn(raw)) && raw.length < 60) continue
            if (sourceLine.matches(raw) || byline.matches(raw) || url.containsMatchIn(raw) && raw.length < 100) continue

            val line = raw.replace(bullet, "").trim()
            when (section) {
                Section.NONE -> when {
                    title == null && !startsWithAmount(line) && line.length <= 80 -> title = line.trimEnd(':')
                    startsWithAmount(line) && line.length <= 90 -> ingredients += ingredient(line, group)
                    // Long text before any ingredient is usually an introduction, not a step.
                    ingredients.isNotEmpty() && (stepNumber.containsMatchIn(raw) || line.length > 60) -> steps += line.replace(stepNumber, "").trim()
                }
                Section.INGREDIENTS -> when {
                    line.endsWith(':') && line.length < 50 -> group = line.trimEnd(':').removePrefix("For the ").removePrefix("for the ").trim()
                    stepNumber.containsMatchIn(raw) && line.length > 60 -> { section = Section.STEPS; steps += line.replace(stepNumber, "").trim() }
                    else -> ingredients += ingredient(line, group)
                }
                Section.STEPS -> {
                    val numbered = stepNumber.containsMatchIn(raw)
                    val stepText = raw.replace(stepNumber, "").replace(bullet, "").trim()
                    if (stepText.isEmpty()) continue
                    // A wrapped line (no number, previous step unfinished) continues the previous step.
                    if (!numbered && stepContinues && steps.isNotEmpty() && !steps.last().endsWith('.')) {
                        steps[steps.lastIndex] = steps.last() + " " + stepText
                    } else {
                        steps += stepText
                    }
                    stepContinues = true
                }
                Section.NOTES -> Unit
            }
        }

        if (ingredients.isEmpty() || steps.isEmpty()) {
            return ImportedRecipe(
                isRecipe = false,
                notes = listOf(
                    if (ingredients.isEmpty()) "No ingredient list was found — make sure the text includes the ingredients."
                    else "No method steps were found — make sure the text includes the instructions.",
                ),
            )
        }
        val name = title ?: "Imported recipe".also { notes += "No title was found; please name the recipe." }
        if (servings == null) notes += "Servings weren't stated; 4 is assumed — please check."
        if (prep == null && cook == null) notes += "Prep and cooking times weren't stated."
        ingredients.filter { it.amount == null && it.preparation == null }.takeIf { it.isNotEmpty() }?.let { missing ->
            notes += "No amount for: ${missing.joinToString { it.name }}."
        }
        val cuisine = DishMatcher.shared.search(name, 1).firstOrNull()?.cuisineId ?: "international"
        return ImportedRecipe(
            isRecipe = true,
            recipe = GeneratedRecipe(
                name = name,
                cuisine = cuisine,
                servings = (servings ?: 4).coerceIn(1, 24),
                prepMinutes = prep ?: 0,
                cookMinutes = cook ?: 0,
                ingredients = ingredients,
                steps = steps.map { GeneratedStep(it, timerMinutes(it)) },
            ),
            sourceName = source,
            notes = notes,
        )
    }

    private fun startsWithAmount(line: String) = amountPattern.find(line)?.value?.isNotBlank() == true

    private fun minutes(value: String, unit: String): Int? {
        val n = value.toIntOrNull() ?: return null
        return if (unit.lowercase().startsWith("h")) n * 60 else n
    }

    /** "Simmer for 20 minutes" → 20. */
    private fun timerMinutes(step: String): Double? =
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:-|–|to)?\s*(\d+)?\s*(minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE).find(step)?.let { m ->
            val n = (m.groupValues[2].takeIf { it.isNotEmpty() } ?: m.groupValues[1]).replace(',', '.').toDoubleOrNull() ?: return null
            if (m.groupValues[3].lowercase().startsWith("h")) n * 60 else n
        }

    internal fun ingredient(line: String, group: String?): GeneratedIngredient {
        var rest = line.trim()
        var amount: Double? = null
        amountPattern.find(rest)?.takeIf { it.value.isNotBlank() }?.let { m ->
            amount = parseAmount(m.groupValues[1])
            rest = rest.substring(m.range.last + 1).trim()
        }
        var unit: String? = null
        if (amount != null) {
            val words = rest.split(' ').filter { it.isNotBlank() }
            val two = words.take(2).joinToString(" ").trimEnd('.', ',')
            val one = words.firstOrNull()?.trimEnd('.', ',')
            when {
                words.size > 2 && QuantityUnit.parse(two) != null -> { unit = QuantityUnit.parse(two)!!.symbol; rest = words.drop(2).joinToString(" ") }
                one != null && words.size > 1 && QuantityUnit.parse(one) != null -> { unit = QuantityUnit.parse(one)!!.symbol; rest = words.drop(1).joinToString(" ") }
            }
        }
        rest = rest.removePrefix("of ").trim()
        var grams: Double? = null
        parenthesisedMass.find(rest)?.let { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            val u = QuantityUnit.parse(m.groupValues[2])
            if (v != null && u?.kind == UnitKind.MASS) grams = v * u.massGrams!! * (amount ?: 1.0)
            rest = rest.removeRange(m.range).replace("  ", " ").trim()
        }
        var preparation: String? = null
        val comma = rest.indexOf(',')
        if (comma > 0) {
            preparation = rest.substring(comma + 1).trim().ifEmpty { null }
            rest = rest.substring(0, comma).trim()
        }
        toTaste.find(rest)?.let { m ->
            preparation = listOfNotNull(preparation, m.value.lowercase()).joinToString(", ")
            rest = rest.removeRange(m.range).trim().trimEnd(',', '-').trim()
        }
        val optional = preparation?.contains("optional") == true || line.contains("(optional)", ignoreCase = true)
        rest = rest.replace("(optional)", "", ignoreCase = true).trim()
        return GeneratedIngredient(
            name = rest.ifEmpty { line },
            amount = amount,
            unit = unit,
            grams = grams,
            preparation = preparation,
            optional = optional,
            group = group,
        )
    }

    private fun parseAmount(text: String): Double? {
        val t = text.trim().replace(',', '.')
        Regex("""^(\d+(?:\.\d+)?)\s*(?:-|–|to)\s*(\d+(?:\.\d+)?)$""").matchEntire(t)?.let { m ->
            return (m.groupValues[1].toDouble() + m.groupValues[2].toDouble()) / 2 // "2–3 cloves" → 2.5
        }
        Regex("""^(\d+)\s+(\d+)/(\d+)$""").matchEntire(t)?.let { m ->
            return m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / m.groupValues[3].toDouble()
        }
        Regex("""^(\d+)/(\d+)$""").matchEntire(t)?.let { m -> return m.groupValues[1].toDouble() / m.groupValues[2].toDouble() }
        Regex("""^(\d*)\s*([½⅓⅔¼¾⅛])$""").matchEntire(t)?.let { m ->
            return (m.groupValues[1].toDoubleOrNull() ?: 0.0) + unicodeFractions.getValue(m.groupValues[2][0])
        }
        return t.toDoubleOrNull()
    }
}
