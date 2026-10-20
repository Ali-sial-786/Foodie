package app.nouri.core.recipes

import app.nouri.core.ai.GeneratedRecipe
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit

enum class Availability { HAVE, SUBSTITUTE_AVAILABLE, STAPLE_ASSUMED, MISSING, OPTIONAL_MISSING }

data class IngredientAvailability(
    val line: RecipeIngredient,
    val ingredientId: String?,
    val status: Availability,
    /** What the user has instead (e.g. chicken thighs for chicken breast). */
    val substituteId: String? = null,
)

data class PantryMatch(val recipeId: String, val items: List<IngredientAvailability>) {
    val required: List<IngredientAvailability> get() = items.filter { it.status != Availability.OPTIONAL_MISSING && !it.line.optional }
    val missing: List<IngredientAvailability> get() = items.filter { it.status == Availability.MISSING }
    val haveCount: Int get() = required.count { it.status == Availability.HAVE || it.status == Availability.SUBSTITUTE_AVAILABLE }

    /** Share of non-staple required ingredients the user has (staples are not counted either way). */
    val coverage: Double
        get() {
            val counted = required.filter { it.status != Availability.STAPLE_ASSUMED }
            return if (counted.isEmpty()) 1.0 else counted.count { it.status != Availability.MISSING }.toDouble() / counted.size
        }
}

object PantryMatcher {
    /** Ingredients that can stand in for each other for "what can I make" purposes. */
    private val equivalenceGroups: List<Set<String>> = listOf(
        setOf("chicken_breast", "chicken_thigh", "chicken_curry_cut", "chicken_drumstick"),
        setOf("rice_white", "rice_glutinous", "rice_brown"),
        setOf("yogurt", "greek_yogurt_whole", "greek_yogurt_nonfat"),
        setOf("lentils_red", "lentils_brown", "moong_dal"),
        setOf("milk_whole", "milk_low_fat", "milk_skim"),
        setOf("cheddar", "mozzarella", "feta", "parmesan"),
        setOf("tomato", "tomato_canned"),
        setOf("beef_ground", "beef_ground_lean", "lamb_ground"),
        setOf("beef_stew", "lamb", "goat"),
        setOf("lemon", "lemon_juice", "lime"),
        setOf("pasta", "egg_noodles"),
    )

    fun match(recipe: Recipe, available: Set<String>, assumeStaples: Boolean = true): PantryMatch =
        PantryMatch(
            recipe.id,
            recipe.ingredients.map { line ->
                val ing = RecipeAnalyzer.canonical(line)
                val id = ing?.id
                val status = when {
                    id != null && id in available -> Availability.HAVE
                    id != null && equivalent(id, available) != null -> Availability.SUBSTITUTE_AVAILABLE
                    ing?.pantryStaple == true && assumeStaples -> Availability.STAPLE_ASSUMED
                    line.quantity.amount == 0.0 -> Availability.STAPLE_ASSUMED
                    line.optional -> Availability.OPTIONAL_MISSING
                    else -> Availability.MISSING
                }
                IngredientAvailability(line, id, status, if (status == Availability.SUBSTITUTE_AVAILABLE) equivalent(id!!, available) else null)
            },
        )

    private fun equivalent(id: String, available: Set<String>): String? =
        equivalenceGroups.firstOrNull { id in it }?.firstOrNull { it != id && it in available }
}

/** Builds and merges shopping-list items from missing recipe ingredients. */
object ShoppingListBuilder {
    fun fromMissing(recipe: Recipe, match: PantryMatch, existing: List<GroceryItem>, nowEpochMs: Long, newId: () -> String): List<GroceryItem> {
        val result = existing.toMutableList()
        for (missing in match.missing) {
            val ing = missing.ingredientId?.let(IngredientCatalog::get)
            val name = ing?.name ?: missing.line.displayName
            val index = result.indexOfFirst { !it.checked && ((ing != null && it.ingredientId == ing.id) || it.name.equals(name, ignoreCase = true)) }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copy(
                    quantity = merge(current.quantity, missing.line.quantity),
                    forRecipeIds = (current.forRecipeIds + recipe.id).distinct(),
                )
            } else {
                result += GroceryItem(
                    id = newId(),
                    name = name,
                    ingredientId = ing?.id,
                    quantity = missing.line.quantity.takeIf { it.amount > 0 },
                    category = ing?.category?.label ?: "Other",
                    forRecipeIds = listOf(recipe.id),
                    addedAtEpochMs = nowEpochMs,
                )
            }
        }
        return result
    }

    private fun merge(a: Quantity?, b: Quantity): Quantity? = when {
        a == null -> b.takeIf { it.amount > 0 }
        a.unit == b.unit -> a.copy(amount = a.amount + b.amount)
        else -> a // different units: keep the first; the list shows the recipe link
    }
}

/** Converts AI-generated recipes into Nouri recipes; nutrition is computed afterwards by the engine. */
object GeneratedRecipeConverter {
    fun convert(generated: GeneratedRecipe, id: String, nowEpochMs: Long, normalizer: IngredientNormalizer = RecipeAnalyzer.defaultNormalizer): Recipe {
        val cuisine = CuisineCatalog.resolve(generated.cuisine)?.id ?: "international"
        val ingredients = generated.ingredients.map { gi ->
            val unit = gi.unit?.let(QuantityUnit::parse)
            val quantity = when {
                gi.amount != null && unit != null -> Quantity(gi.amount, unit)
                gi.grams != null -> Quantity.grams(gi.grams)
                gi.amount != null -> Quantity(gi.amount, QuantityUnit.PIECE)
                else -> Quantity(0.0, QuantityUnit.PINCH)
            }
            RecipeIngredient(
                displayName = gi.name,
                ingredientId = normalizer.resolve(gi.name)?.takeIf { it.isConfident }?.ingredient?.id,
                quantity = quantity,
                preparation = gi.preparation ?: if (quantity.amount == 0.0) "to taste" else null,
                optional = gi.optional,
                group = gi.group,
                gramsOverride = gi.grams?.takeIf { unit != null && unit.kind != app.nouri.core.units.UnitKind.MASS },
            )
        }
        val diets = generated.tags.mapNotNull { tag ->
            DietGoal.entries.firstOrNull { it.label.equals(tag, ignoreCase = true) || it.name.equals(tag.replace('-', '_'), ignoreCase = true) }
        }.toSet()
        return Recipe(
            id = id,
            name = generated.name.trim(),
            cuisineId = cuisine,
            region = generated.region,
            description = generated.description,
            servings = generated.servings.coerceIn(1, 24),
            ingredients = ingredients,
            steps = generated.steps.map { RecipeStep(it.text, it.timerMinutes?.let { m -> (m * 60).toInt() }, it.temperatureC) },
            prepMinutes = generated.prepMinutes,
            cookMinutes = generated.cookMinutes,
            difficulty = when (generated.difficulty.lowercase()) { "easy" -> Difficulty.EASY; "involved", "hard" -> Difficulty.INVOLVED; else -> Difficulty.MEDIUM },
            equipment = generated.equipment,
            tags = generated.tags.map { it.lowercase() }.toSet() + "ai-generated",
            origin = RecipeOrigin.AI_GENERATED,
            variantOf = generated.variantOf.takeIf { !generated.isTraditional },
            variantSummary = if (!generated.isTraditional && generated.variantOf != null) generated.description else null,
            storage = generated.storage,
            reheating = generated.reheating,
            foodSafety = generated.foodSafety,
            notes = generated.substitutions.map { "Substitute ${it.ingredient} → ${it.substitute}${it.note?.let { n -> " ($n)" } ?: ""}" },
            declaredDiets = diets,
            createdAtEpochMs = nowEpochMs,
        )
    }
}
