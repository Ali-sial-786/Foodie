package app.nouri.core.fooddb

import app.nouri.core.fooddb.generated.UsdaCoreFoods
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit

/** Read access to nutrition reference records. */
interface FoodRepository {
    fun get(id: String): FoodReference?
    fun search(query: String, limit: Int = 20): List<FoodReference>
}

/**
 * Foods USDA SR28 does not cover well. Values are editorial approximations from
 * typical product labels, flagged [DataQuality.APPROXIMATE] so the UI says so and
 * the estimate's confidence is lowered. Replace with label or CNF data when available.
 */
object ApproximateFoods {
    val all: List<FoodReference> = listOf(
        FoodReference(
            id = "approx:paneer",
            name = "Paneer (typical full-fat)",
            per100g = Nutrients(energyKcal = 290.0, proteinG = 18.0, carbsG = 4.0, fatG = 22.0, sodiumMg = 20.0, saturatedFatG = 14.0),
            source = NutritionSource.APPROXIMATE_FALLBACK,
            quality = DataQuality.APPROXIMATE,
            portions = listOf(FoodPortion(QuantityUnit.PIECE, 15.0, label = "1 cube (~15 g)")),
            sourceDetail = "Typical values from commercial paneer labels (range 260–320 kcal/100 g)",
        ),
        FoodReference(
            id = "approx:gochujang",
            name = "Gochujang (typical)",
            per100g = Nutrients(energyKcal = 220.0, proteinG = 5.0, carbsG = 47.0, fatG = 1.5, sodiumMg = 2300.0),
            source = NutritionSource.APPROXIMATE_FALLBACK,
            quality = DataQuality.APPROXIMATE,
            portions = listOf(FoodPortion(QuantityUnit.TABLESPOON, 20.0, label = "1 tbsp")),
            sourceDetail = "Typical values from commercial gochujang labels",
        ),
    )
}

/**
 * In-memory repository with a small token index. Ranking favours records whose
 * name starts with the query, exact token matches, and shorter (more generic) names.
 */
class InMemoryFoodRepository(foods: Collection<FoodReference>) : FoodRepository {
    private val byId: Map<String, FoodReference> = foods.associateBy { it.id }
    private val index: List<Pair<FoodReference, List<String>>> = foods.map { it to tokenize(it.name) }

    val size: Int get() = byId.size

    override fun get(id: String): FoodReference? = byId[id]

    override fun search(query: String, limit: Int): List<FoodReference> {
        val q = tokenize(query)
        if (q.isEmpty()) return emptyList()
        return index.asSequence()
            .mapNotNull { (food, tokens) -> score(q, tokens, food)?.let { food to it } }
            .sortedWith(compareByDescending<Pair<FoodReference, Double>> { it.second }.thenBy { it.first.name.length })
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(query: List<String>, tokens: List<String>, food: FoodReference): Double? {
        var total = 0.0
        for (q in query) {
            val best = tokens.maxOfOrNull { t ->
                when {
                    t == q -> 3.0
                    t.startsWith(q) -> 2.0
                    q.length >= 4 && t.contains(q) -> 1.0
                    else -> 0.0
                }
            } ?: 0.0
            if (best == 0.0) return null
            total += best
        }
        if (tokens.firstOrNull()?.startsWith(query.first()) == true) total += 1.5
        if (food.state == FoodState.RAW) total += 0.1
        total -= tokens.size * 0.05
        return total
    }

    companion object {
        private val splitter = Regex("[^a-z0-9%]+")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(splitter).filter { it.isNotBlank() && it !in stopWords }.map(::singular)

        private val stopWords = setOf("and", "or", "with", "w", "the", "of", "a", "in", "for", "to")

        internal fun singular(word: String): String = when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
            else -> word
        }
    }
}

/** Looks up ids in several repositories in order; search merges results. */
class CompositeFoodRepository(private val repositories: List<FoodRepository>) : FoodRepository {
    override fun get(id: String): FoodReference? = repositories.firstNotNullOfOrNull { it.get(id) }

    override fun search(query: String, limit: Int): List<FoodReference> =
        repositories.flatMap { it.search(query, limit) }.distinctBy { it.id }.take(limit)
}

/** Reference data compiled into the shared module (works offline on every platform). */
object CoreFoods {
    val repository: InMemoryFoodRepository by lazy {
        InMemoryFoodRepository(UsdaCoreFoods.all + ApproximateFoods.all)
    }

    val usdaRecordCount: Int get() = UsdaCoreFoods.COUNT
}
