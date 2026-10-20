package app.nouri.core.label

import app.nouri.core.label.LabelAmounts.G
import app.nouri.core.label.LabelAmounts.KCAL
import app.nouri.core.label.LabelAmounts.KJ
import app.nouri.core.label.LabelAmounts.MCG
import app.nouri.core.label.LabelAmounts.MG
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.Nutrients
import app.nouri.core.units.formatAmount
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads a nutrition facts panel from raw OCR text (ML Kit text recognition: lines separated by
 * '\n', reading order not guaranteed). Pure and deterministic; it never invents a value — what
 * cannot be read is reported in [LabelParseResult.missingFields], and every assumption in
 * [LabelParseResult.warnings].
 *
 * Supported layouts:
 * - US FDA "Nutrition Facts", 2016+ and older format (per serving, "Calories 230", %DV ignored);
 * - Canadian bilingual "Nutrition Facts / Valeur nutritive" (English, French or both per line);
 * - EU/UK tables ("Energy 1550 kJ / 370 kcal", "of which saturates", salt) with "Per 100g" and
 *   optional "Per serving (40g)" columns, also when OCR returns the names and each value column
 *   as separate blocks.
 *
 * OCR robustness: O/0 and l/I/1 confusions inside numbers, missing spaces ("Protein12g"),
 * comma decimals, values on the line after their name, "<1g"/"less than 1g" (read as half the
 * bound, with a warning), sodium in g or mg, and "% Daily Value" captions on the same line.
 * Space-separated digit groups are not joined in general ("2 30" may be two columns); the only
 * exception is a US/Canadian calorie row reading exactly "d dd" with a first group of 1–4,
 * which is not a valid label calorie value (labels round to 0, 5 or 10 kcal steps), so it can
 * only be a split number. "1 550 kJ" style thousands groups are not joined.
 *
 * Plausibility checks (each adds a warning): printed energy more than 20% (and more than
 * 10 kcal) away from the 4/4/9 Atwater estimate — fibre (≈2 kcal/g), polyols and alcohol
 * explain smaller gaps, so the tolerance is deliberately wide; sugars above carbohydrate;
 * saturated fat above fat; impossible amounts per 100 g (any macronutrient over 100 g, more than
 * 950 kcal, more sodium than pure salt).
 *
 * Confidence: [ConfidenceLevel.HIGHER] when the format was recognised from the text, the gram
 * basis is known (per 100 g or a serving weight), energy and all three macronutrients were read
 * and there are no warnings; [ConfidenceLevel.MODERATE] when at most one core value is missing
 * or there are warnings; [ConfidenceLevel.LOW] otherwise.
 */
object NutritionLabelParser {

    /**
     * @param hint the format the caller expects (e.g. from the device locale); used only when the
     *   text itself does not identify the format, so a recognised label always wins.
     */
    fun parse(ocrText: String, hint: LabelRegion? = null): LabelParseResult = LabelParseRun(ocrText, hint).parse()
}

/** Mutable state for one [NutritionLabelParser.parse] call. */
private class LabelParseRun(ocrText: String, private val hint: LabelRegion?) {

    private class Value(val amount: Double, val note: String?)

    private val lines: List<LabelLine> = LabelText.lines(ocrText)
    private val warnings = mutableListOf<String>()
    private val readings = LinkedHashMap<LabelColumn, LinkedHashMap<LabelField, Double>>()

    /** Nutrient scanning ignores text before this index (serving sizes, consumed lines). */
    private val skipBefore = IntArray(lines.size)
    private val columns = mutableListOf<LabelColumn>()
    private var region = LabelRegion.UNKNOWN
    private var regionRecognised = false
    private var usedDefaultColumn = false
    private var afterFootnote = false

    // Column-major reading order: a block of names, then one block of values per column.
    private val pending = ArrayDeque<LabelField>()
    private val blockFields = mutableListOf<LabelField>()
    private var blockIndex = -1
    private var blockColumn: LabelColumn? = null
    private var nextBlockColumn: LabelColumn? = null
    private var previousWasValueLine = false

    fun parse(): LabelParseResult {
        val detected = LabelLexicon.detectRegion(lines.joinToString("\n") { it.folded })
        regionRecognised = detected != LabelRegion.UNKNOWN
        region = if (regionRecognised) detected else hint ?: LabelRegion.UNKNOWN
        val serving = readServingSize()
        val servingsPerContainer = readServingsPerContainer()
        val productName = readProductName()
        readNutrients()
        return assemble(serving, servingsPerContainer, productName)
    }

    // --- Serving size --------------------------------------------------------------------

    private fun readServingSize(): ServingSize? {
        // "Serving size 2/3 cup (55g)", possibly with the size on the next line.
        for (i in lines.indices) {
            val line = lines[i].original
            val lead = SERVING_SIZE_LEAD.find(line) ?: continue
            val from = lead.range.last + 1
            val remainder = line.substring(from)
            val cut = servingCutLength(remainder)
            skipBefore[i] = from + cut
            val inline = remainder.substring(0, cut).trim()
            servingFrom(inline)?.let { return it }
            val next = lines.getOrNull(i + 1)?.original ?: continue
            val nextCut = servingCutLength(next)
            servingFrom(next.substring(0, nextCut).trim())?.let {
                skipBefore[i + 1] = nextCut
                return it
            }
        }
        // "Per 1 cup (250 mL) / pour 1 tasse (250 mL)", "Per serving (40g)": English before French.
        var french: String? = null
        for (line in lines) {
            for (m in PER_PHRASE.findAll(line.original)) {
                val lead = m.groupValues[1].lowercase()
                if (lead == "per" || lead == "pro") {
                    servingFrom(m.groupValues[2])?.let { return it }
                } else if (french == null) {
                    french = m.groupValues[2]
                }
            }
        }
        // "Per serving" heading with "(40g)" on the next line.
        for (i in lines.indices) {
            if (!BARE_PER_SERVING.containsMatchIn(lines[i].folded)) continue
            val next = lines.getOrNull(i + 1) ?: continue
            val m = PAREN_AMOUNT_LINE.matchEntire(next.original) ?: continue
            servingFrom(m.groupValues[1])?.let {
                skipBefore[i + 1] = next.original.length
                return it
            }
        }
        return french?.let { servingFrom(it) }
    }

    /** Length of the serving description at the start of [remainder]. */
    private fun servingCutLength(remainder: String): Int {
        val keyword = LabelLexicon.hits(LabelText.fold(remainder)).firstOrNull()?.start ?: remainder.length
        val separator = BILINGUAL_SEPARATOR.find(remainder)?.range?.first ?: remainder.length
        val stop = minOf(keyword, separator)
        val open = remainder.indexOf('(')
        if (open in 0 until stop) {
            val close = remainder.indexOf(')', open)
            return if (close >= 0) close + 1 else remainder.length
        }
        return stop
    }

    private fun servingFrom(raw: String): ServingSize? {
        var text = LabelAmounts.fixOcrDigits(raw).trim().trimEnd(',', ';', ':', '.', '/').trim()
        if (text.none { it.isDigit() }) return null
        // "serving (40g)" → "40g"
        val before = text.substringBefore('(').trim().lowercase()
        if ('(' in text && before in GENERIC_SERVING_WORDS) {
            text = text.substringAfter('(').substringBefore(')').trim()
        }
        val grams = GRAMS.find(text)?.let { m ->
            val v = LabelAmounts.number(m.groupValues[1], G) ?: return@let null
            if (m.groupValues[2].lowercase() == "kg") v * 1000.0 else v
        } ?: OUNCES.findAll(text).firstOrNull { it.groupValues[2].isEmpty() }?.let { m ->
            LabelAmounts.number(m.groupValues[1], G)?.times(OUNCE_G)
        }
        val millilitres = MILLILITRES.find(text)?.let { m ->
            val v = LabelAmounts.number(m.groupValues[1], G) ?: return@let null
            if (m.groupValues[2].lowercase().startsWith("l")) v * 1000.0 else v
        } ?: OUNCES.findAll(text).firstOrNull { it.groupValues[2].isNotEmpty() }?.let { m ->
            LabelAmounts.number(m.groupValues[1], G)?.times(FLUID_OUNCE_ML)
        }
        val household = text.substringBefore('(').trim()
            .let { SERVING_WORD.replace(it, "").trim() }
            .takeIf { it.isNotEmpty() && it.any(Char::isLetter) && !PURE_METRIC.matches(it) }
            ?.let { text.substringBefore('(').trim() }
        return ServingSize(
            text = text,
            grams = grams?.takeIf { it > 0.0 },
            millilitres = millilitres?.takeIf { it > 0.0 },
            householdText = household,
        )
    }

    private fun readServingsPerContainer(): Double? {
        for (i in lines.indices) {
            val text = LabelAmounts.fixOcrDigits(lines[i].folded)
            SERVINGS_BEFORE.find(text)?.let { return LabelAmounts.number(it.groupValues[1], null) }
            SERVINGS_AFTER.find(text)?.let { return LabelAmounts.number(it.groupValues[1], null) }
            SERVINGS_IN_PACK.find(text)?.let { return LabelAmounts.number(it.groupValues[1], null) }
            if (!SERVINGS_LABEL_ONLY.containsMatchIn(text)) continue
            val next = lines.getOrNull(i + 1) ?: continue
            val m = SERVINGS_VALUE_LINE.matchEntire(LabelAmounts.fixOcrDigits(next.folded)) ?: continue
            skipBefore[i + 1] = next.folded.length
            return LabelAmounts.number(m.groupValues[1], null)
        }
        return null
    }

    /** Best effort: a title-like line above the panel heading, if any. */
    private fun readProductName(): String? {
        val heading = lines.indexOfFirst { HEADING.containsMatchIn(it.folded) }
        if (heading <= 0) return null
        return lines.subList(0, heading).firstOrNull { line ->
            val t = line.original
            val letters = t.count { it.isLetter() }
            t.length in 3..60 && letters >= 3 && letters >= t.length * 0.6 &&
                LabelLexicon.hits(line.folded).isEmpty() && !BOILERPLATE.containsMatchIn(line.folded)
        }?.original
    }

    // --- Nutrient rows --------------------------------------------------------------------

    private fun readNutrients() {
        var i = 0
        while (i < lines.size) {
            val folded = lines[i].folded
            val start = skipBefore[i]
            if (start >= folded.length) {
                i++
                continue
            }
            // "Calories 110 % Daily Value*": that % belongs to the caption, not to 110.
            val text = PERCENT_CAPTION.replace(folded.substring(start), " ")
            if (isFootnote(text)) {
                previousWasValueLine = false
                i++
                continue
            }
            val hits = LabelLexicon.hits(text)
            if (hits.none { !it.field.structural }) {
                val markers = LabelLexicon.columnMarkers(text)
                if (markers.isNotEmpty()) {
                    onColumnHeading(markers)
                    previousWasValueLine = false
                    i++
                    continue
                }
            }
            if (hits.isEmpty()) {
                val reading = LabelAmounts.read(LabelAmounts.fixOcrDigits(text))
                if (reading.clean && reading.amounts.isNotEmpty() && reading.amounts.any { !it.inParentheses }) {
                    i += onValueLine(i, reading.amounts)
                } else {
                    previousWasValueLine = false
                    i++
                }
                continue
            }
            i += onRow(i, text, hits)
            previousWasValueLine = false
        }
    }

    /** Footnotes ("2,000 calories a day…", the old DV reference table) must not supply values. */
    private fun isFootnote(text: String): Boolean {
        if (FOOTNOTE_START.containsMatchIn(text)) {
            afterFootnote = true
            return true
        }
        if (DV_REFERENCE_ROW.containsMatchIn(text)) return true
        if (afterFootnote && region == LabelRegion.US) {
            // Old US labels list "Total Carbohydrate 300g 375g" under the footnote.
            val amounts = LabelAmounts.read(LabelAmounts.fixOcrDigits(text)).amounts
            if (amounts.count { it.unit == G || it.unit == MG } >= 2) return true
        }
        return false
    }

    private fun onColumnHeading(markers: List<LabelColumn>) {
        val columnMajor = pending.isNotEmpty() || blockFields.isNotEmpty()
        if (columnMajor && markers.size == 1) nextBlockColumn = markers.single()
        markers.forEach { if (it !in columns) columns += it }
    }

    /** Returns the number of lines consumed. */
    private fun onRow(i: Int, text: String, hits: List<KeywordHit>): Int {
        val segments = hits.mapIndexed { k, hit ->
            val end = hits.getOrNull(k + 1)?.start ?: text.length
            hit to LabelAmounts.read(LabelAmounts.fixOcrDigits(text.substring(hit.end, end)))
        }
        val leading = LabelAmounts.read(LabelAmounts.fixOcrDigits(text.substring(0, hits.first().start)))
        val lineHasAmounts = leading.amounts.isNotEmpty() || segments.any { it.second.amounts.isNotEmpty() }
        var consumed = 1
        for ((k, segment) in segments.withIndex()) {
            val (hit, reading) = segment
            if (hit.field.structural || reading.amounts.isEmpty()) continue
            var amounts = reading.amounts
            if (hit.field == LabelField.ENERGY && k == segments.lastIndex) {
                kcalContinuation(i, amounts)?.let {
                    amounts = it
                    consumed = 2
                }
            }
            val values = valuesFor(hit.field, amounts)
            record(hit.field, values, rowColumns())
        }
        if (!lineHasAmounts && isNameOnly(text, hits)) {
            enqueue(hits.filterNot { it.field.structural }.map { it.field })
        } else {
            // "Total Fat 8g Saturated Fat" + "1g" on the next line.
            val (lastHit, lastReading) = segments.last()
            val tail = text.substring(lastHit.end)
            if (!lastHit.field.structural && lastReading.amounts.isEmpty() && tail.none { it.isLetterOrDigit() }) {
                enqueue(listOf(lastHit.field))
            }
        }
        return consumed
    }

    /** Returns the number of lines consumed. */
    private fun onValueLine(i: Int, lineAmounts: List<LabelAmount>): Int {
        if (pending.isEmpty()) {
            if (blockFields.isEmpty()) {
                previousWasValueLine = true
                return 1
            }
            // Next value column for the same block of names.
            pending.addAll(blockFields)
            blockFields.clear()
            startBlock()
        } else if (!previousWasValueLine) {
            startBlock()
        }
        previousWasValueLine = true
        val field = pending.removeFirst()
        blockFields += field
        var amounts = lineAmounts
        var consumed = 1
        if (field == LabelField.ENERGY) {
            kcalContinuation(i, amounts)?.let {
                amounts = it
                consumed = 2
            }
        }
        val values = valuesFor(field, amounts)
        record(field, values, if (values.size > 1) rowColumns() else listOfNotNull(blockColumn))
        return consumed
    }

    private fun startBlock() {
        blockIndex++
        blockColumn = nextBlockColumn ?: rowColumns().getOrNull(blockIndex)
        nextBlockColumn = null
    }

    private fun enqueue(fields: List<LabelField>) {
        if (fields.isEmpty()) return
        if (pending.isEmpty() && blockFields.isNotEmpty()) {
            // A new block of names: forget the previous one.
            blockFields.clear()
            blockIndex = -1
        }
        for (field in fields) if (pending.lastOrNull() != field) pending.addLast(field)
    }

    private fun isNameOnly(text: String, hits: List<KeywordHit>): Boolean {
        val residual = StringBuilder(text)
        for (hit in hits) for (k in hit.start until hit.end) residual[k] = ' '
        val leftover = WORD.findAll(residual).map { it.value }
            .filterNot { it in LabelAmounts.unitWords || it in NAME_FILLERS }
        return leftover.sumOf { it.length } <= 3
    }

    /** UK style "Energy 1550 kJ" with "370 kcal" alone on the next line. */
    private fun kcalContinuation(i: Int, amounts: List<LabelAmount>): List<LabelAmount>? {
        if (amounts.any { it.unit == KCAL } || amounts.none { it.unit == KJ }) return null
        val next = lines.getOrNull(i + 1) ?: return null
        if (skipBefore[i + 1] > 0 || LabelLexicon.hits(next.folded).isNotEmpty()) return null
        val reading = LabelAmounts.read(LabelAmounts.fixOcrDigits(next.folded))
        if (!reading.clean || reading.amounts.none { it.unit == KCAL } || reading.amounts.any { it.unit == KJ }) return null
        return amounts + reading.amounts
    }

    private fun rowColumns(): List<LabelColumn> = columns.ifEmpty {
        usedDefaultColumn = true
        listOf(if (region == LabelRegion.EU) LabelColumn.PER_100G else LabelColumn.SERVING)
    }

    private fun record(field: LabelField, values: List<Value?>, targets: List<LabelColumn>) {
        if (!field.stored) return
        for ((column, value) in targets.zip(values)) {
            if (value == null) continue
            val map = readings.getOrPut(column) { LinkedHashMap() }
            if (field in map) continue
            map[field] = value.amount
            value.note?.let { warnings += it }
        }
    }

    // --- Units ----------------------------------------------------------------------------

    private fun valuesFor(field: LabelField, amounts: List<LabelAmount>): List<Value?> {
        if (!field.stored) return emptyList()
        return if (field == LabelField.ENERGY) energyValues(amounts) else massValues(field, amounts)
    }

    private fun energyValues(amounts: List<LabelAmount>): List<Value?> {
        val usable = amounts.filter { it.unit == null || it.unit == KCAL || it.unit == KJ }
        joinSplitCalories(usable)?.let { return listOf(it) }
        val kcal = usable.filter { it.unit == KCAL }
        val kj = usable.filter { it.unit == KJ }
        if (kcal.isNotEmpty() && kcal.size >= kj.size) return kcal.map { energy(it, 1.0) }
        if (kj.isNotEmpty()) {
            // Pair each kJ value with the kcal value that follows it, column by column.
            val groups = mutableListOf<MutableList<LabelAmount>>()
            for (a in usable.filter { it.unit != null }) {
                val group = groups.lastOrNull()
                if (group == null || group.any { it.unit == a.unit }) groups += mutableListOf(a) else group += a
            }
            return groups.map { group ->
                group.firstOrNull { it.unit == KCAL }?.let { energy(it, 1.0) }
                    ?: energy(group.first { it.unit == KJ }, 1.0 / KJ_PER_KCAL)
            }
        }
        val bare = usable.filter { it.unit == null }
        if (bare.isEmpty()) return emptyList()
        if (region == LabelRegion.EU) {
            warnings += "Energy: the unit (kJ or kcal) could not be read, so the value was not used"
            return emptyList()
        }
        // US and Canadian labels print "Calories 230" without a unit.
        return bare.map { energy(it, 1.0) }
    }

    private fun joinSplitCalories(amounts: List<LabelAmount>): Value? {
        if (region != LabelRegion.US && region != LabelRegion.CANADA) return null
        if (columns.size > 1 || amounts.size != 2) return null
        val (a, b) = amounts
        if (a.unit != null || b.unit != null || a.lessThan || b.lessThan || a.trace || b.trace) return null
        if (!SPLIT_HUNDREDS.matches(a.raw) || !SPLIT_TENS.matches(b.raw)) return null
        val joined = (a.raw + b.raw).toDouble()
        return Value(joined, "Energy: \"${a.raw} ${b.raw}\" read as ${joined.roundToInt()} kcal (digits split by OCR)")
    }

    private fun energy(a: LabelAmount, factor: Double): Value {
        val unit = if (a.unit == KJ) "kJ" else "kcal"
        return when {
            a.trace -> Value(0.0, "Energy: \"${a.raw}\" read as 0 kcal")
            a.lessThan -> {
                val kcal = a.value / 2.0 * factor
                Value(kcal, "Energy: \"<${a.raw} $unit\" read as ${formatAmount(kcal)} kcal")
            }
            else -> Value(a.value * factor, null)
        }
    }

    private fun massValues(field: LabelField, amounts: List<LabelAmount>): List<Value?> {
        val withUnit = amounts.filter { it.unit == G || it.unit == MG || it.unit == MCG }
        val chosen = withUnit.ifEmpty { amounts.filter { it.unit == null } }
        return chosen.map { massValue(field, it) }
    }

    private fun massValue(field: LabelField, a: LabelAmount): Value {
        // Canonical units: mg for sodium, g for everything else.
        val targetUnit = if (field == LabelField.SODIUM) MG else G
        val assumedUnit = when {
            a.unit != null -> a.unit
            field == LabelField.SODIUM && region != LabelRegion.EU -> MG
            else -> G
        }
        val factor = when (targetUnit) {
            MG -> when (assumedUnit) { G -> 1000.0; MCG -> 0.001; else -> 1.0 }
            else -> when (assumedUnit) { MG -> 0.001; MCG -> 0.000001; else -> 1.0 }
        }
        val name = field.displayName
        return when {
            a.trace -> Value(0.0, "$name: \"${a.raw}\" read as 0 $targetUnit")
            a.lessThan -> {
                val v = a.value / 2.0 * factor
                Value(v, "$name: \"<${a.raw} $assumedUnit\" read as ${formatAmount(v)} $targetUnit (half the stated limit)")
            }
            a.unit == null -> Value(a.value * factor, "$name: no unit printed; read as ${a.raw} $assumedUnit")
            else -> Value(a.value * factor, null)
        }
    }

    // --- Result ---------------------------------------------------------------------------

    private fun assemble(servingIn: ServingSize?, servingsPerContainer: Double?, productName: String?): LabelParseResult {
        var serving = servingIn
        var servingReadings: Map<LabelField, Double>? = readings[LabelColumn.SERVING]
        val per100gReadings: Map<LabelField, Double>? = readings[LabelColumn.PER_100G]
        val per100mlReadings = readings[LabelColumn.PER_100ML]
        if (per100mlReadings != null && servingReadings == null && (serving == null || serving.millilitres == 100.0)) {
            // Drinks: "per 100 ml" is a serving of 100 mL, not 100 g.
            servingReadings = per100mlReadings
            serving = serving ?: ServingSize("100 ml", grams = null, millilitres = 100.0, householdText = null)
        }
        val per100g = per100gReadings?.let { nutrientsOf(it) }
        val perServing = servingReadings?.let { nutrientsOf(it) }
        val primary = per100gReadings ?: servingReadings

        if (usedDefaultColumn && primary != null) {
            when (region) {
                LabelRegion.EU -> warnings += "Column headings were not found; values were read as per 100 g"
                LabelRegion.UNKNOWN -> warnings += "Label format not recognised; values were read as per serving"
                else -> Unit
            }
        }
        per100gReadings?.let { validate(it, 100.0, "per 100 g") }
        servingReadings?.let { validate(it, serving?.grams, "per serving") }
        if (per100g == null && perServing != null && serving?.grams == null && serving?.millilitres != null) {
            warnings += "Serving size is given by volume only (${formatAmount(serving.millilitres)} mL); " +
                "per-100 g values would need the product's density, so none were derived"
        }
        if (primary == null) warnings += "No nutrition values were found"

        val missing = buildList {
            val r = primary.orEmpty()
            if (LabelField.ENERGY !in r) add(NutrientFields.ENERGY_KCAL)
            if (LabelField.PROTEIN !in r) add(NutrientFields.PROTEIN_G)
            if (LabelField.CARBOHYDRATE !in r) add(NutrientFields.CARBS_G)
            if (LabelField.FAT !in r) add(NutrientFields.FAT_G)
            if (LabelField.FIBRE !in r) add(NutrientFields.FIBER_G)
            if (LabelField.SUGARS !in r) add(NutrientFields.SUGAR_G)
            if (LabelField.SODIUM !in r && LabelField.SALT !in r) add(NutrientFields.SODIUM_MG)
            if (LabelField.SATURATED_FAT !in r) add(NutrientFields.SATURATED_FAT_G)
            if (per100g == null) {
                if (serving == null) add(NutrientFields.SERVING_SIZE) else if (serving.grams == null) add(NutrientFields.SERVING_GRAMS)
            }
        }
        val distinctWarnings = warnings.distinct()
        val coreMissing = NutrientFields.CORE.count { it in missing }
        val gramBasis = per100g != null || serving?.grams != null
        val confidence = when {
            primary == null -> ConfidenceLevel.LOW
            regionRecognised && gramBasis && coreMissing == 0 && distinctWarnings.isEmpty() -> ConfidenceLevel.HIGHER
            region != LabelRegion.UNKNOWN && coreMissing <= 1 -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.LOW
        }
        return LabelParseResult(
            region = region,
            servingSize = serving,
            servingsPerContainer = servingsPerContainer?.takeIf { it > 0.0 },
            perServing = perServing,
            per100g = per100g,
            productName = productName,
            confidence = confidence,
            missingFields = missing,
            warnings = distinctWarnings,
        )
    }

    /** EU/UK "Carbohydrate" excludes fibre; the app stores total carbohydrate. */
    private fun totalCarbs(r: Map<LabelField, Double>): Double? {
        val carbs = r[LabelField.CARBOHYDRATE] ?: return null
        return if (region == LabelRegion.EU) carbs + (r[LabelField.FIBRE] ?: 0.0) else carbs
    }

    private fun nutrientsOf(r: Map<LabelField, Double>): Nutrients = Nutrients(
        energyKcal = r[LabelField.ENERGY] ?: 0.0,
        proteinG = r[LabelField.PROTEIN] ?: 0.0,
        carbsG = totalCarbs(r) ?: 0.0,
        fatG = r[LabelField.FAT] ?: 0.0,
        fiberG = r[LabelField.FIBRE],
        sugarG = r[LabelField.SUGARS],
        sodiumMg = r[LabelField.SODIUM] ?: r[LabelField.SALT]?.times(SODIUM_MG_PER_SALT_G),
        saturatedFatG = r[LabelField.SATURATED_FAT],
    )

    private fun validate(r: Map<LabelField, Double>, basisGrams: Double?, basis: String) {
        val energy = r[LabelField.ENERGY]
        val protein = r[LabelField.PROTEIN]
        val carbs = r[LabelField.CARBOHYDRATE]
        val totalCarbs = totalCarbs(r)
        val fat = r[LabelField.FAT]
        if (r.values.any { it < 0.0 }) warnings += "Negative values found ($basis)"
        if (energy != null && protein != null && totalCarbs != null && fat != null) {
            val atwater = protein * 4.0 + totalCarbs * 4.0 + fat * 9.0
            val gap = abs(energy - atwater)
            if (gap > ENERGY_TOLERANCE * energy && gap > ENERGY_TOLERANCE_KCAL) {
                warnings += "Energy ${formatAmount(energy)} kcal does not match the ${atwater.roundToInt()} kcal " +
                    "implied by protein, carbohydrate and fat ($basis); please check the values"
            }
        }
        val sugars = r[LabelField.SUGARS]
        if (sugars != null && carbs != null && sugars > carbs + TOLERANCE_G) {
            warnings += "Sugars (${formatAmount(sugars)} g) exceed carbohydrate (${formatAmount(carbs)} g) ($basis)"
        }
        val saturated = r[LabelField.SATURATED_FAT]
        if (saturated != null && fat != null && saturated > fat + TOLERANCE_G) {
            warnings += "Saturated fat (${formatAmount(saturated)} g) exceeds total fat (${formatAmount(fat)} g) ($basis)"
        }
        val fibre = r[LabelField.FIBRE]
        if (region != LabelRegion.EU && fibre != null && carbs != null && fibre > carbs + TOLERANCE_G) {
            warnings += "Fibre (${formatAmount(fibre)} g) exceeds total carbohydrate (${formatAmount(carbs)} g) ($basis)"
        }
        if (basisGrams == null || basisGrams <= 0.0) return
        val scale = 100.0 / basisGrams
        val overfull = listOf(
            LabelField.FAT to fat, LabelField.CARBOHYDRATE to totalCarbs, LabelField.PROTEIN to protein,
            LabelField.SUGARS to sugars, LabelField.FIBRE to fibre, LabelField.SATURATED_FAT to saturated,
        ).filter { (_, v) -> v != null && v * scale > 100.0 + TOLERANCE_G }.map { it.first.displayName }
        if (overfull.isNotEmpty()) {
            warnings += "${overfull.joinToString(", ")} above 100 g per 100 g ($basis); the values look misread"
        } else if (((protein ?: 0.0) + (totalCarbs ?: 0.0) + (fat ?: 0.0)) * scale > MAX_MACROS_PER_100G) {
            warnings += "Protein, carbohydrate and fat add up to more than the food's weight ($basis)"
        }
        if (energy != null && energy * scale > MAX_KCAL_PER_100G) {
            warnings += "Energy above ${MAX_KCAL_PER_100G.roundToInt()} kcal per 100 g ($basis); the value looks misread"
        }
        val sodium = r[LabelField.SODIUM] ?: r[LabelField.SALT]?.times(SODIUM_MG_PER_SALT_G)
        if (sodium != null && sodium * scale > MAX_SODIUM_MG_PER_100G) {
            warnings += "Sodium above what pure salt contains ($basis); the value looks misread"
        }
    }

    private companion object {
        const val KJ_PER_KCAL = 4.184
        const val SODIUM_MG_PER_SALT_G = 400.0
        const val OUNCE_G = 28.349523125
        const val FLUID_OUNCE_ML = 29.5735295625
        const val ENERGY_TOLERANCE = 0.20
        const val ENERGY_TOLERANCE_KCAL = 10.0
        const val TOLERANCE_G = 0.5
        const val MAX_MACROS_PER_100G = 105.0
        const val MAX_KCAL_PER_100G = 950.0
        const val MAX_SODIUM_MG_PER_100G = 40_000.0

        val SERVING_SIZE_LEAD = Regex("""serving\s*size\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
        val PER_PHRASE = Regex(
            """(?<![a-zA-Z])(per|pro|pour|par)\s+(?!100\s*(?:g|gr|ml)(?![a-zA-Z]))(?!(?:container|package|pack|day|gram)(?![a-zA-Z]))""" +
                """([^()/%]{0,30}?\([^()]{0,30}\)|\d+(?:[.,]\d+)?\s*(?:g|ml)(?:\s*(?:serving|portion))?(?![a-zA-Z]))""",
            RegexOption.IGNORE_CASE,
        )
        val BARE_PER_SERVING = Regex("""(?<![a-z])(?:per|pro)\s*(?:serving|portion)\s*$""")
        val PAREN_AMOUNT_LINE = Regex("""\(\s*(\d+(?:[.,]\d+)?\s*(?:g|ml))\s*\)""", RegexOption.IGNORE_CASE)
        val BILINGUAL_SEPARATOR = Regex("""\s/\s|/\s*(?=[A-Za-z])""")
        val GENERIC_SERVING_WORDS = setOf("serving", "portion", "a serving", "one serving", "1 serving", "1 portion", "une portion")
        val GRAMS = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|grams?|gr|gm|g)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
        val OUNCES = Regex("""(?<![a-zA-Z\d.,])(\d+(?:[.,]\d+)?)\s*(fl\.?\s*)?oz(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
        val MILLILITRES = Regex("""(\d+(?:[.,]\d+)?)\s*(ml|millilit(?:re|er)s?|l|lit(?:re|er)s?)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
        val SERVING_WORD = Regex("""(?<![a-zA-Z])(?:servings?|portions?)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
        val PURE_METRIC = Regex("""\d+(?:[.,]\d+)?\s*(?:kg|g|gr|grams?|mg|ml|l)""", RegexOption.IGNORE_CASE)

        val SERVINGS_BEFORE = Regex("""(\d+(?:[.,]\d+)?)\s*(?:servings?|portions?)\s*(?:per|par)\s*(?:container|package|pack|contenant)""")
        val SERVINGS_AFTER =
            Regex("""servings?\s*per\s*(?:container|package|pack)\s*:?\s*(?:about|approx\.?|approximately|abt\.?)?\s*(\d+(?:[.,]\d+)?)""")
        val SERVINGS_IN_PACK =
            Regex("""(?:pack|package|container)\s*contains\s*(?:about|approx\.?)?\s*(\d+(?:[.,]\d+)?)\s*(?:servings|portions)""")
        val SERVINGS_LABEL_ONLY = Regex("""servings?\s*per\s*(?:container|package|pack)\s*:?\s*(?:about|approx\.?)?\s*$""")
        val SERVINGS_VALUE_LINE = Regex("""(?:about|approx\.?|approximately|abt\.?)?\s*(\d+(?:[.,]\d+)?)""")

        val HEADING = Regex(
            """nutrition\s*facts|valeur\s*nutritive|nutrition(?:al)?\s*(?:information|declaration)|valeurs\s*nutritionnelles|nahrwert|typical\s*values""",
        )
        val BOILERPLATE = Regex(
            """ingredient|contains|allerg|best before|use by|keep |store|refrigerat|distributed|manufactured|made in|product of|www|http|net\s*w|poids net|%|serving""",
        )
        val PERCENT_CAPTION = Regex("""%(?=\s*\*?\s*(?:daily\s*value|valeur\s*quotidienne))""")
        val FOOTNOTE_START = Regex(
            """percent daily values? (?:are|is) based|daily values? (?:are|is) based|your daily values? may|daily value \(dv\) tells""" +
                """|contributes to a daily diet|general nutrition advice|calories per gram|2,?000 calories? a day""" +
                """|2,?000 calorie diet|5\s?% or less is a little|5\s?% ou moins""",
        )
        val DV_REFERENCE_ROW = Regex("""less\s*than\s*[\d,.]+\s*m?g\s+[\d,.]+\s*m?g|^calories\s*:\s*2,?000""")
        val SPLIT_HUNDREDS = Regex("[1-4]")
        val SPLIT_TENS = Regex("\\d0")
        val WORD = Regex("""[a-zß-ÿ]+""")
        val NAME_FILLERS = setOf("of", "which", "dont", "davon", "and", "et", "total", "incl")
    }
}
