package app.nouri.core.ontology

import kotlin.math.min

/** The result of resolving free text to a canonical ingredient. */
data class IngredientMatch(
    val ingredient: CanonicalIngredient,
    /** The alias that matched. */
    val matchedAlias: String,
    val kind: MatchKind,
    /** Words from the input that were not part of the alias (e.g. "chopped", "boneless"). */
    val leftover: String,
) {
    val isConfident: Boolean get() = kind != MatchKind.FUZZY
}

enum class MatchKind { EXACT, CONTAINED, FUZZY }

/**
 * Resolves free-text ingredient names ("2 finely chopped pyaz", "Boneless chicken
 * breasts", "dahee", "aubergines") to canonical ingredients.
 *
 * Strategy, in order: exact alias → longest alias contained in the phrase →
 * fuzzy (edit distance) match on single tokens. Preparation descriptors are
 * stripped before matching but kept in [IngredientMatch.leftover].
 */
class IngredientNormalizer(ingredients: List<CanonicalIngredient> = IngredientCatalog.all) {

    private val aliasIndex: Map<String, CanonicalIngredient>
    private val aliasesByLength: List<Pair<String, CanonicalIngredient>>

    init {
        val map = LinkedHashMap<String, CanonicalIngredient>()
        // Canonical names win over aliases shared by several ingredients.
        for (ing in ingredients) map.putIfAbsent(normalize(ing.name), ing)
        for (ing in ingredients) {
            map.putIfAbsent(normalize(ing.id.replace('_', ' ')), ing)
            for (alias in ing.aliases) map.putIfAbsent(normalize(alias), ing)
        }
        aliasIndex = map.filterKeys { it.isNotBlank() }
        aliasesByLength = aliasIndex.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    fun resolve(text: String): IngredientMatch? {
        val cleaned = normalize(text)
        if (cleaned.isBlank()) return null
        val core = stripDescriptors(cleaned)

        // The full phrase first: "tomato paste" and "ground beef" are ingredients in
        // their own right, not "tomato"/"beef" with a descriptor.
        aliasIndex[cleaned]?.let { return IngredientMatch(it, cleaned, MatchKind.EXACT, "") }
        aliasIndex[core]?.let { return IngredientMatch(it, core, MatchKind.EXACT, leftoverOf(cleaned, core)) }

        for (phrase in listOf(cleaned, core).distinct()) {
            val padded = " $phrase "
            for ((alias, ing) in aliasesByLength) {
                if (alias.length < 3) continue
                if (padded.contains(" $alias ")) {
                    return IngredientMatch(ing, alias, MatchKind.CONTAINED, leftoverOf(cleaned, alias))
                }
            }
        }
        return fuzzy(core)?.let { (alias, ing) -> IngredientMatch(ing, alias, MatchKind.FUZZY, leftoverOf(cleaned, alias)) }
    }

    /** Top-N suggestions for autocomplete. */
    fun suggest(prefix: String, limit: Int = 8): List<CanonicalIngredient> {
        val p = normalize(prefix)
        if (p.isBlank()) return emptyList()
        return aliasIndex.entries.asSequence()
            .filter { (alias, _) -> alias.startsWith(p) || alias.contains(" $p") }
            .sortedBy { it.key.length }
            .map { it.value }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    private fun fuzzy(core: String): Pair<String, CanonicalIngredient>? {
        val tokens = core.split(' ').filter { it.length >= 4 }
        var best: Pair<String, CanonicalIngredient>? = null
        var bestDistance = Int.MAX_VALUE
        for (token in tokens) {
            val maxDistance = if (token.length >= 8) 2 else 1
            for ((alias, ing) in aliasIndex) {
                if (alias.contains(' ') || kotlin.math.abs(alias.length - token.length) > maxDistance) continue
                val d = levenshtein(token, alias, maxDistance)
                if (d <= maxDistance && d < bestDistance) {
                    bestDistance = d
                    best = alias to ing
                }
            }
        }
        return best
    }

    private fun leftoverOf(cleaned: String, alias: String): String =
        (" $cleaned ").replace(" $alias ", " ").trim().replace(Regex("\\s+"), " ")

    companion object {
        private val quantityToken = Regex("""\d+([.,]\d+)?[a-z]{0,4}""")

        private val descriptors = setOf(
            "fresh", "freshly", "chopped", "finely", "roughly", "coarsely", "diced", "minced", "sliced", "thinly",
            "thickly", "grated", "crushed", "ground", "whole", "peeled", "deseeded", "seeded", "halved", "quartered",
            "cubed", "boneless", "skinless", "organic", "large", "medium", "small", "ripe", "raw", "cooked", "boiled",
            "frozen", "dried", "dry", "canned", "tinned", "drained", "rinsed", "softened", "melted", "beaten", "lightly",
            "packed", "heaped", "level", "about", "approx", "approximately", "optional", "to", "taste", "for", "garnish",
            "garnishing", "serving", "plus", "extra", "more", "some", "few", "of", "the", "a", "an", "and", "or", "into",
            "pieces", "piece", "cut", "strips", "rings", "wedges", "julienned", "shredded", "torn", "leaves", "sprigs",
            "stems", "pinch", "handful", "bunch", "cups", "cup", "tbsp", "tsp", "tablespoons", "teaspoons", "g", "kg", "ml",
            "warm", "cold", "hot", "room", "temperature", "good", "quality", "store", "bought", "homemade", "paste",
        )

        fun normalize(text: String): String {
            val lower = text.lowercase()
                .replace('’', '\'')
                .replace(Regex("\\(.*?\\)"), " ")
                .replace(Regex("[^a-z0-9' -]"), " ")
                .replace('-', ' ')
            return lower.split(' ').filter { it.isNotBlank() }.joinToString(" ") { singularize(it) }
        }

        fun stripDescriptors(normalized: String): String {
            val kept = normalized.split(' ').filter { it !in descriptors && !quantityToken.matches(it) }
            return kept.joinToString(" ").ifBlank { normalized }
        }

        /** Conservative English singularisation; transliterated words are left alone. */
        fun singularize(word: String): String = when {
            word.length <= 3 -> word
            word == "leaves" -> "leaf"
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("oes") -> word.dropLast(2)
            word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") -> word.dropLast(2)
            word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") -> word
            word.endsWith("s") -> word.dropLast(1)
            else -> word
        }

        /** Levenshtein distance with an early exit once [limit] is exceeded. */
        fun levenshtein(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                var rowMin = curr[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                    rowMin = min(rowMin, curr[j])
                }
                if (rowMin > limit) return limit + 1
                val t = prev; prev = curr; curr = t
            }
            return prev[b.length]
        }
    }
}
