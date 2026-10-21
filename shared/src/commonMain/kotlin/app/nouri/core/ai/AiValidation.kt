package app.nouri.core.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Lenient decoding of model output followed by semantic validation and repair. */
object AiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Extracts the JSON object from model text that may include code fences or
     * stray prose before/after the object.
     */
    fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    fun <T> decode(text: String, serializer: KSerializer<T>): Result<T> {
        val obj = extractObject(text) ?: return Result.failure(SerializationException("No JSON object in response"))
        return runCatching { json.decodeFromString(serializer, obj) }
    }
}

/** A validated value plus the repairs that were made, kept for transparency and benchmarking. */
data class Validated<T>(val value: T, val warnings: List<String>)

object AiValidator {
    const val MAX_ITEMS = 12
    const val MAX_INGREDIENTS = 40

    fun meal(raw: MealAnalysis): Result<Validated<MealAnalysis>> {
        val warnings = mutableListOf<String>()
        val items = raw.items.mapNotNull { item ->
            val label = item.label.trim()
            if (label.isEmpty()) {
                warnings += "Dropped an item without a name"
                return@mapNotNull null
            }
            val grams = item.estimatedGrams?.let { repairRange(it, label, warnings) }
            val candidates = item.candidates
                .filter { it.name.isNotBlank() }
                .distinctBy { it.name.trim().lowercase() }
                .take(4)
                .ifEmpty { listOf(DishCandidate(label, Likelihood.MEDIUM)) }
            item.copy(label = label, estimatedGrams = grams, candidates = candidates)
        }.take(MAX_ITEMS)
        if (raw.items.size > MAX_ITEMS) warnings += "Kept the first $MAX_ITEMS of ${raw.items.size} items"

        if (items.isEmpty()) {
            val reason = if (raw.imageQuality == "not_food") "No food was found in the photo" else "Nothing could be identified in the photo"
            return Result.failure(IllegalArgumentException(reason))
        }
        val question = raw.suggestedQuestion?.takeIf { q ->
            q.text.isNotBlank() && q.options.size in 2..5 && (q.itemIndex == null || q.itemIndex in items.indices)
        }
        if (raw.suggestedQuestion != null && question == null) warnings += "Discarded a malformed clarifying question"
        return Result.success(Validated(raw.copy(items = items, suggestedQuestion = question), warnings))
    }

    private fun repairRange(range: GramRange, label: String, warnings: MutableList<String>): GramRange? {
        val values = listOf(range.low, range.typical, range.high)
        if (values.any { !it.isFinite() || it <= 0 }) {
            warnings += "Ignored an invalid portion estimate for $label"
            return null
        }
        val sorted = values.sorted()
        val repaired = GramRange(sorted[0], sorted[1], sorted[2])
        if (repaired != range) warnings += "Reordered the portion range for $label"
        if (repaired.high >= 5000) {
            warnings += "Ignored an implausible portion estimate for $label"
            return null
        }
        return repaired
    }

    fun ingredients(raw: IngredientAnalysis): Result<Validated<IngredientAnalysis>> {
        val warnings = mutableListOf<String>()
        val items = raw.items
            .filter { it.name.isNotBlank() }
            .map { it.copy(name = it.name.trim(), quantity = it.quantity?.takeIf { q -> q.isFinite() && q > 0 && q < 1000 }) }
            .distinctBy { it.name.lowercase() }
            .take(MAX_INGREDIENTS)
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("No ingredients were recognised"))
        if (items.size < raw.items.size) warnings += "Removed ${raw.items.size - items.size} empty or duplicate items"
        return Result.success(Validated(raw.copy(items = items), warnings))
    }

    fun recipes(raw: GeneratedRecipes): Result<Validated<GeneratedRecipes>> {
        val warnings = mutableListOf<String>()
        val valid = raw.recipes.filter { r ->
            val problems = buildList {
                if (r.name.isBlank()) add("name")
                if (r.ingredients.none { it.name.isNotBlank() }) add("ingredients")
                if (r.steps.none { it.text.isNotBlank() }) add("steps")
                if (r.servings !in 1..24) add("servings")
                if (r.prepMinutes !in 0..1440 || r.cookMinutes !in 0..2880) add("times")
            }
            if (problems.isNotEmpty()) warnings += "Dropped recipe \"${r.name}\" (invalid ${problems.joinToString()})"
            problems.isEmpty()
        }.map { r ->
            r.copy(
                ingredients = r.ingredients.filter { it.name.isNotBlank() }.map {
                    it.copy(amount = it.amount?.takeIf { a -> a.isFinite() && a >= 0 }, grams = it.grams?.takeIf { g -> g.isFinite() && g >= 0 && g < 10_000 })
                },
                steps = r.steps.filter { it.text.isNotBlank() },
            )
        }
        if (valid.isEmpty()) return Result.failure(IllegalArgumentException("No usable recipes in the response"))
        return Result.success(Validated(raw.copy(recipes = valid), warnings))
    }

    fun importedRecipe(raw: ImportedRecipe): Result<Validated<ImportedRecipe>> {
        if (!raw.isRecipe || raw.recipe == null) return Result.success(Validated(raw.copy(isRecipe = false, recipe = null), emptyList()))
        // Imported recipes may omit times; clamp instead of rejecting, then reuse the generated-recipe checks.
        val r = raw.recipe.copy(
            servings = raw.recipe.servings.coerceIn(1, 24),
            prepMinutes = raw.recipe.prepMinutes.coerceIn(0, 1440),
            cookMinutes = raw.recipe.cookMinutes.coerceIn(0, 2880),
        )
        val checked = recipes(GeneratedRecipes(listOf(r))).getOrElse { return Result.failure(IllegalArgumentException("The recipe is missing its name, ingredients or steps")) }
        return Result.success(Validated(raw.copy(recipe = checked.value.recipes.single(), sourceName = raw.sourceName?.trim()?.takeIf { it.isNotEmpty() }), checked.warnings))
    }

    fun mealText(raw: ParsedMealText): Result<Validated<ParsedMealText>> {
        val items = raw.items.filter { it.food.isNotBlank() }.map {
            it.copy(
                amount = it.amount?.takeIf { a -> a.isFinite() && a > 0 },
                grams = it.grams?.takeIf { g -> g.isFinite() && g > 0 && g < 5000 },
                recipeFraction = it.recipeFraction?.takeIf { f -> f > 0 && f <= 1.0 },
            )
        }
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("No foods found in the text"))
        return Result.success(Validated(raw.copy(items = items), emptyList()))
    }
}
