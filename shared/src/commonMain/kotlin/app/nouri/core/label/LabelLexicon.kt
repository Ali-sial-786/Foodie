package app.nouri.core.label

/**
 * What a label row is about. Only [stored] fields end up in [app.nouri.core.model.Nutrients];
 * the others are still recognised so that their numbers are never mistaken for a neighbouring
 * row's value, and so they keep their slot when names and values are read as separate columns.
 */
internal enum class LabelField(
    val displayName: String,
    val stored: Boolean = false,
    val structural: Boolean = false,
) {
    ENERGY("Energy", stored = true),
    FAT("Fat", stored = true),
    SATURATED_FAT("Saturated fat", stored = true),
    CARBOHYDRATE("Carbohydrate", stored = true),
    FIBRE("Fibre", stored = true),
    SUGARS("Sugars", stored = true),
    PROTEIN("Protein", stored = true),
    SODIUM("Sodium", stored = true),
    SALT("Salt", stored = true),
    TRANS_FAT("Trans fat"),
    OTHER_FAT("Unsaturated fat"),
    CHOLESTEROL("Cholesterol"),
    ADDED_SUGARS("Added sugars"),
    OTHER_CARBOHYDRATE("Polyols/starch"),
    CALORIES_FROM_FAT("Calories from fat"),
    VITAMIN_OR_MINERAL("Vitamin/mineral"),

    /** Headings and captions ("Nutrition Facts", "% Daily Value"): delimit rows, never carry a value. */
    STRUCTURE("Heading", structural = true),
}

/** Which basis a column of values refers to. */
internal enum class LabelColumn { PER_100G, PER_100ML, SERVING }

internal data class KeywordHit(val field: LabelField, val start: Int, val end: Int)

/**
 * English, French and German label vocabulary. All matching runs on folded text
 * (lower-case, accents removed, see [LabelText.fold]).
 */
internal object LabelLexicon {

    private val phrases: List<Pair<String, LabelField>> = buildList {
        fun add(field: LabelField, vararg names: String) = names.forEach { add(it to field) }
        add(LabelField.ENERGY, "calories", "calorie", "energy", "energie", "valeur energetique", "brennwert")
        add(LabelField.CALORIES_FROM_FAT, "calories from fat", "fat calories", "fat cal")
        add(LabelField.FAT, "total fat", "fat", "lipides", "lipids", "matieres grasses", "fett")
        add(
            LabelField.SATURATED_FAT,
            "saturated fat", "saturated", "saturates", "of which saturates", "sat fat", "sat. fat",
            "saturated fatty acids", "satures", "acides gras satures", "dont acides gras satures",
            "gesattigte fettsauren", "davon gesattigte fettsauren",
        )
        add(LabelField.TRANS_FAT, "trans fat", "trans", "trans fatty acids")
        add(
            LabelField.OTHER_FAT,
            "polyunsaturated fat", "polyunsaturated", "polyunsaturates", "poly-unsaturates",
            "monounsaturated fat", "monounsaturated", "monounsaturates", "mono-unsaturates",
            "polyinsatures", "monoinsatures", "omega-3", "omega-6",
        )
        add(LabelField.CHOLESTEROL, "cholesterol")
        add(LabelField.SODIUM, "sodium", "natrium")
        add(LabelField.SALT, "salt", "sel", "salz")
        add(
            LabelField.CARBOHYDRATE,
            "total carbohydrate", "total carbohydrates", "total carb", "total carbs",
            "carbohydrate", "carbohydrates", "glucides", "kohlenhydrate",
        )
        add(
            LabelField.FIBRE,
            "dietary fiber", "dietary fibre", "fiber", "fibre", "fibres", "fibres alimentaires", "ballaststoffe",
        )
        add(LabelField.SUGARS, "total sugars", "sugars", "sugar", "of which sugars", "sucres", "dont sucres", "zucker", "davon zucker")
        add(LabelField.ADDED_SUGARS, "added sugars", "includes", "sucres ajoutes")
        add(LabelField.OTHER_CARBOHYDRATE, "sugar alcohol", "sugar alcohols", "polyols", "starch", "of which starch", "amidon")
        add(LabelField.PROTEIN, "protein", "proteins", "proteines", "proteine", "eiweiss", "eiweiß")
        add(
            LabelField.VITAMIN_OR_MINERAL,
            "vitamin a", "vitamin c", "vitamin d", "vitamin e", "vitamin k", "vitamin b6", "vitamin b12",
            "vitamin", "vitamine", "calcium", "iron", "fer", "potassium", "magnesium", "zinc",
            "phosphorus", "folate", "niacin", "riboflavin", "thiamin",
        )
        add(
            LabelField.STRUCTURE,
            "nutrition facts", "valeur nutritive", "nutrition information", "nutritional information",
            "nutrition declaration", "valeurs nutritionnelles", "typical values", "amount per serving",
            "amount", "teneur", "daily value", "valeur quotidienne", "reference intake", "serving size",
            "servings per container", "servings per package",
        )
    }

    private val index: Map<String, LabelField> = phrases.associate { (phrase, field) -> compact(phrase) to field }

    /**
     * Longest phrases first so "saturated fat" wins over "fat" and "polyunsaturated" over
     * "saturated". A keyword must not continue into a letter, except OCR digit look-alikes
     * glued to a value ("Proteinl2g", "Trans FatOg").
     */
    private val keywordRegex: Regex = Regex(
        "(?<![a-z\\u00df-\\u00ff])(?:" +
            phrases.map { it.first }.distinct().sortedByDescending { it.length }
                .joinToString("|") { phrase -> phrase.split(' ').joinToString("\\s*") { Regex.escape(it) } } +
            ")(?=$|[^a-z\\u00df-\\u00ff]|[oil|]+\\d|o+\\s*(?:mg|g|kcal|kj)(?![a-z]))",
    )

    private fun compact(text: String): String = text.filterNot { it.isWhitespace() }

    fun hits(folded: String): List<KeywordHit> = keywordRegex.findAll(folded).mapNotNull { m ->
        index[compact(m.value)]?.let { KeywordHit(it, m.range.first, m.range.last + 1) }
    }.toList()

    // --- Region detection ---------------------------------------------------------------

    private class Marker(pattern: String, val region: LabelRegion, val weight: Int) {
        val regex = Regex(pattern)
    }

    private val regionMarkers = listOf(
        Marker("total\\s*fat", LabelRegion.US, 3),
        Marker("total\\s*carb", LabelRegion.US, 3),
        Marker("dietary\\s*fiber", LabelRegion.US, 2),
        Marker("servings?\\s*per\\s*container", LabelRegion.US, 3),
        Marker("amount\\s*per\\s*serving", LabelRegion.US, 2),
        Marker("added\\s*sugars", LabelRegion.US, 3),
        Marker("total\\s*sugars", LabelRegion.US, 2),
        Marker("calories\\s*from\\s*fat", LabelRegion.US, 3),
        Marker("serving\\s*size", LabelRegion.US, 1),
        Marker("nutrition\\s*facts", LabelRegion.US, 1),
        Marker("daily\\s*value", LabelRegion.US, 1),
        Marker("nutrition\\s*facts", LabelRegion.CANADA, 1),
        Marker("daily\\s*value", LabelRegion.CANADA, 1),
        Marker("valeur\\s*nutritive", LabelRegion.CANADA, 4),
        Marker("valeurs?\\s*quotidiennes?", LabelRegion.CANADA, 4),
        Marker("(?<![a-z])teneur(?![a-z])", LabelRegion.CANADA, 2),
        Marker("(?<![a-z])tasses?(?![a-z])", LabelRegion.CANADA, 2),
        Marker("lipides", LabelRegion.CANADA, 1),
        Marker("glucides", LabelRegion.CANADA, 1),
        Marker("proteines", LabelRegion.CANADA, 1),
        Marker("(?<![a-z])kj(?![a-z])", LabelRegion.EU, 3),
        Marker("(?<![\\d.,])100\\s*(?:g|gr|ml)(?![a-z])", LabelRegion.EU, 2),
        Marker("of\\s*which", LabelRegion.EU, 2),
        Marker("saturates", LabelRegion.EU, 2),
        Marker("(?<![a-z])salt(?![a-z])", LabelRegion.EU, 2),
        Marker("typical\\s*values", LabelRegion.EU, 2),
        Marker("reference\\s*intake|(?<![a-z])ri\\*", LabelRegion.EU, 2),
        Marker("nutrition(?:al)?\\s*(?:information|declaration)", LabelRegion.EU, 1),
        Marker("valeurs\\s*nutritionnelles", LabelRegion.EU, 3),
        Marker("matieres\\s*grasses", LabelRegion.EU, 2),
        Marker("(?<![a-z])sel(?![a-z])", LabelRegion.EU, 1),
        Marker("nahrwert|brennwert", LabelRegion.EU, 3),
    )

    /**
     * Scores distinctive vocabulary per region. Returns [LabelRegion.UNKNOWN] when nothing
     * distinctive was found or two regions tie.
     */
    fun detectRegion(foldedText: String): LabelRegion {
        val scores = regionMarkers.filter { it.regex.containsMatchIn(foldedText) }
            .groupBy { it.region }
            .mapValues { (_, markers) -> markers.sumOf { it.weight } }
        val best = scores.maxByOrNull { it.value } ?: return LabelRegion.UNKNOWN
        if (best.value < 2 || scores.count { it.value == best.value } > 1) return LabelRegion.UNKNOWN
        return best.key
    }

    // --- Column headings ------------------------------------------------------------------

    private val per100g = Regex("""(?<![\d.,])100\s*(?:g|gr|grams?|gramm)(?![a-z])""")
    private val per100ml = Regex("""(?<![\d.,])100\s*ml(?![a-z])""")
    private val perServing = Regex(
        """(?<![a-z])(?:per|pour|par|pro|je)\s*(?:1\s*)?(?:serving|portion)(?![a-z])""" +
            """|(?<![a-z])(?:per|pour|pro)\s+(?!100\s*(?:g|gr|ml)(?![a-z]))[^()%/]{0,30}?\(\s*\d[^()]{0,25}\)""" +
            """|(?<![a-z])per\s*\d+(?:[.,]\d+)?\s*(?:g|ml)\s*(?:serving|portion)""",
    )
    private val referenceIntakeBefore = Regex("""(?:%|(?<![a-z])ri|gda)\s*\*?\s*$""")

    /** Column bases named on a heading line, in reading order ("Per 100g | Per serving (40g)"). */
    fun columnMarkers(folded: String): List<LabelColumn> {
        val found = buildList {
            per100g.findAll(folded).forEach { add(it.range.first to LabelColumn.PER_100G) }
            per100ml.findAll(folded).forEach { add(it.range.first to LabelColumn.PER_100ML) }
            perServing.findAll(folded).forEach { m ->
                // "%RI per serving" is a percentage column, not a second serving column.
                val before = folded.substring(maxOf(0, m.range.first - 8), m.range.first)
                if (!referenceIntakeBefore.containsMatchIn(before)) add(m.range.first to LabelColumn.SERVING)
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }
}
