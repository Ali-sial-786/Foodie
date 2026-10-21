package app.nouri.core.recipes

import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a recipe is served as. Stored as a tag (e.g. "main") so saved copies and
 * variations keep it without a database change.
 */
enum class RecipeCourse(val tag: String, val label: String) {
    BREAKFAST("breakfast", "Breakfast"),
    MAIN("main", "Main"),
    SOUP("soup", "Soup"),
    SALAD("salad", "Salad"),
    SIDE("side", "Side"),
    SNACK("snack", "Snack"),
    DESSERT("dessert", "Dessert"),
    BREAD("bread", "Bread"),
    DRINK("drink", "Drink"),
    SAUCE("sauce", "Sauce & condiment");

    companion object {
        fun fromTag(tag: String): RecipeCourse? = entries.firstOrNull { it.tag == tag }

        /** Built-in recipes predate the course tag; anything without one is a main. */
        fun of(recipe: Recipe): RecipeCourse = entries.firstOrNull { it.tag in recipe.tags } ?: MAIN
    }
}

/**
 * The bundled recipe library: hundreds of Nouri reference recipes kept as data rather
 * than code. Authored in the JSON files in `data/recipes/` (compact keys, documented in
 * `data/recipes/README.md`), bundled by `scripts/build_recipe_library.py` and decoded
 * here on first use. Nutrition is never stored; the engine calculates it like any other recipe.
 */
object RecipeLibrary {
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(text: String): List<Recipe> = json.decodeFromString(LibraryFile.serializer(), text).recipes.map { it.toRecipe() }

    @Serializable
    internal data class LibraryFile(val recipes: List<LibraryRecipe>)

    @Serializable
    internal data class LibraryRecipe(
        val id: String,
        val name: String,
        val cuisine: String,
        val region: String? = null,
        val course: String,
        val servings: Int,
        val prep: Int,
        val cook: Int,
        val difficulty: Difficulty = Difficulty.MEDIUM,
        val description: String,
        val equipment: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val ingredients: List<LibraryIngredient>,
        val steps: List<LibraryStep>,
        val storage: String? = null,
        val reheating: String? = null,
        val safety: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
        val variantOf: String? = null,
        val variantSummary: String? = null,
        val family: String? = null,
    ) {
        fun toRecipe(): Recipe {
            val course = requireNotNull(RecipeCourse.fromTag(course)) { "$id: unknown course \"$course\"" }
            return Recipe(
                id = id, name = name, cuisineId = cuisine, region = region, description = description, servings = servings,
                ingredients = ingredients.map { it.toLine() }, steps = steps.map { it.toStep() },
                prepMinutes = prep, cookMinutes = cook, difficulty = difficulty, equipment = equipment,
                tags = tags.toSet() + course.tag,
                origin = if (variantOf != null) RecipeOrigin.EDITORIAL_VARIATION else RecipeOrigin.EDITORIAL_TRADITIONAL,
                variantOf = variantOf, variantSummary = variantSummary, family = family,
                storage = storage?.let(StandardRecipeNotes::expand)?.ifBlank { null },
                reheating = reheating?.let(StandardRecipeNotes::expand)?.ifBlank { null },
                foodSafety = safety.map(StandardRecipeNotes::expand), notes = notes,
            )
        }
    }

    /** `q`/`u` are the amount and [QuantityUnit] name; `taste` means "to taste" (no amount). */
    @Serializable
    internal data class LibraryIngredient(
        val id: String,
        val name: String,
        val q: Double = 0.0,
        val u: QuantityUnit = QuantityUnit.PINCH,
        val prep: String? = null,
        val opt: Boolean = false,
        val group: String? = null,
        val eaten: Double = 1.0,
        val cooked: Boolean = false,
        val taste: Boolean = false,
    ) {
        fun toLine() = RecipeIngredient(
            displayName = name, ingredientId = id,
            quantity = if (taste) Quantity(0.0, QuantityUnit.PINCH) else Quantity(q, u),
            preparation = if (taste) listOfNotNull(prep, "to taste").joinToString(", ") else prep,
            optional = opt, group = group, consumedFraction = eaten, measuredCooked = cooked,
        )
    }

    /** `min` is a timer in minutes, `c` a target temperature in °C. */
    @Serializable
    internal data class LibraryStep(val t: String, val min: Double? = null, val c: Int? = null) {
        fun toStep() = RecipeStep(t, min?.let { (it * 60).toInt() }, c)
    }
}
