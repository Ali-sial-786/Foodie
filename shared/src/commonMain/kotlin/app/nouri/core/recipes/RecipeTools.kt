package app.nouri.core.recipes

import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.CanonicalIngredient
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.ontology.IngredientCategory
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitKind
import app.nouri.core.units.formatAmount
import kotlin.math.pow
import kotlin.math.roundToInt

data class RecipeAnalysis(
    /** Diets the recipe satisfies as written (optional ingredients excluded). */
    val diets: Set<DietGoal>,
    val halal: HalalStatus,
    val allergens: Set<Allergen>,
    /** Allergens only present in optional ingredients. */
    val optionalAllergens: Set<Allergen>,
    val halalNotes: List<String>,
    val unresolvedIngredients: List<String>,
)

/** Ingredient-level checks; cuisines are never assumed to be halal or vegetarian. */
object RecipeAnalyzer {
    fun canonical(line: RecipeIngredient, normalizer: IngredientNormalizer = defaultNormalizer): CanonicalIngredient? =
        line.ingredientId?.let(IngredientCatalog::get) ?: normalizer.resolve(line.displayName)?.ingredient

    fun analyze(recipe: Recipe, normalizer: IngredientNormalizer = defaultNormalizer): RecipeAnalysis {
        val required = recipe.ingredients.filter { !it.optional }
        val resolved = required.map { it to canonical(it, normalizer) }
        val ingredients = resolved.mapNotNull { it.second }
        val optional = recipe.ingredients.filter { it.optional }.mapNotNull { canonical(it, normalizer) }
        val unresolved = resolved.filter { it.second == null }.map { it.first.displayName }

        val halal = when {
            ingredients.any { it.halal == HalalStatus.NOT_PERMISSIBLE } -> HalalStatus.NOT_PERMISSIBLE
            ingredients.any { it.halal == HalalStatus.VERIFY } || unresolved.isNotEmpty() -> HalalStatus.VERIFY
            ingredients.any { it.halal == HalalStatus.REQUIRES_HALAL_SOURCE } -> HalalStatus.REQUIRES_HALAL_SOURCE
            else -> HalalStatus.PERMISSIBLE
        }
        val halalNotes = buildList {
            ingredients.filter { it.halal == HalalStatus.NOT_PERMISSIBLE }.forEach { add("Contains ${it.name.lowercase()}") }
            ingredients.filter { it.halal == HalalStatus.REQUIRES_HALAL_SOURCE }.distinctBy { it.animalOrigin }.forEach {
                add("Use halal-certified ${it.name.lowercase().substringBefore(',').substringBefore(" (")}")
            }
            ingredients.filter { it.halal == HalalStatus.VERIFY }.forEach { add("Check the source of ${it.name.lowercase()}") }
        }
        val allergens = ingredients.flatMap { it.allergens }.toSet()
        val diets = buildSet {
            if (unresolved.isEmpty()) {
                if (ingredients.all { it.isVegetarian }) add(DietGoal.VEGETARIAN)
                if (ingredients.all { it.isVegan }) add(DietGoal.VEGAN)
                if (ingredients.none { it.containsDairy }) add(DietGoal.DAIRY_FREE)
                if (ingredients.none { it.containsGluten }) add(DietGoal.GLUTEN_FREE)
                if (ingredients.none { Allergen.TREE_NUT in it.allergens || Allergen.PEANUT in it.allergens }) add(DietGoal.NUT_FREE)
                if (halal == HalalStatus.PERMISSIBLE || halal == HalalStatus.REQUIRES_HALAL_SOURCE) add(DietGoal.HALAL)
            }
        }
        return RecipeAnalysis(diets, halal, allergens, optional.flatMap { it.allergens }.toSet() - allergens, halalNotes, unresolved)
    }

    internal val defaultNormalizer: IngredientNormalizer by lazy { IngredientNormalizer() }
}

data class ScaledRecipe(val recipe: Recipe, val factor: Double, val notes: List<String>)

/**
 * Changes servings. Ingredient amounts scale, but cooking does not scale
 * linearly: times, temperatures and pans are kept and explained instead.
 */
object RecipeScaler {
    fun scale(recipe: Recipe, servings: Int): ScaledRecipe {
        require(servings in 1..100) { "Servings must be between 1 and 100" }
        val factor = servings.toDouble() / recipe.servings
        if (factor == 1.0) return ScaledRecipe(recipe, 1.0, emptyList())
        val notes = mutableListOf<String>()
        val ingredients = recipe.ingredients.map { line ->
            val canonical = RecipeAnalyzer.canonical(line)
            val q = line.quantity
            if (q.amount == 0.0) return@map line
            var amount = q.amount * factor
            if (canonical?.category == IngredientCategory.SPICE && factor >= 2.0) {
                // Seasoning rarely needs to scale fully; start lower and adjust to taste.
                amount = q.amount * factor.pow(0.85)
            }
            if (q.unit == QuantityUnit.PIECE && canonical?.category in WHOLE_ITEMS) {
                // Whole eggs; other whole items to the nearest half.
                val rounded = if (canonical?.category == IngredientCategory.EGG) maxOf(1.0, kotlin.math.round(amount))
                else maxOf(0.5, (amount * 2).roundToInt() / 2.0)
                if (rounded != amount) notes += "${line.displayName}: rounded ${formatAmount(amount)} to ${formatAmount(rounded)}"
                amount = rounded
            }
            line.copy(quantity = q.copy(amount = amount), gramsOverride = line.gramsOverride?.times(factor))
        }
        if (recipe.ingredients.any { RecipeAnalyzer.canonical(it)?.category == IngredientCategory.SPICE } && factor >= 2.0) {
            notes += "Spices and salt are scaled a little less than other ingredients; taste and adjust."
        }
        if (factor >= 1.5) {
            notes += "Use a larger pot or pan, or brown meat and vegetables in batches so they don't steam."
            notes += "Simmering, roasting and baking can take longer with more food; check doneness rather than relying on times."
        } else if (factor <= 0.67) {
            notes += "Use a smaller pan so sauces don't reduce too quickly, and start checking doneness earlier."
        }
        val scaled = recipe.copy(
            servings = servings,
            ingredients = ingredients,
            cookedYieldGrams = recipe.cookedYieldGrams?.times(factor),
        )
        return ScaledRecipe(scaled, factor, notes)
    }

    private val WHOLE_ITEMS = setOf(
        IngredientCategory.EGG, IngredientCategory.VEGETABLE, IngredientCategory.FRUIT, IngredientCategory.BREAD,
    )
}

/** Adjustments the user can ask for. */
enum class RecipeAdjustment(val label: String, val goal: DietGoal?) {
    VEGETARIAN("Vegetarian", DietGoal.VEGETARIAN),
    VEGAN("Vegan", DietGoal.VEGAN),
    DAIRY_FREE("Dairy-free", DietGoal.DAIRY_FREE),
    GLUTEN_FREE("Gluten-free", DietGoal.GLUTEN_FREE),
    HALAL("Halal", DietGoal.HALAL),
    NUT_FREE("Nut-free", DietGoal.NUT_FREE),
    LOWER_CALORIE("Lower calorie", DietGoal.LOWER_CALORIE),
    HIGHER_PROTEIN("Higher protein", DietGoal.HIGHER_PROTEIN),
    SPICIER("Spicier", null),
    MILDER("Milder", null),
    KID_FRIENDLY("Kid-friendly", null),
}

data class RecipeVariation(val recipe: Recipe, val changes: List<String>, val warnings: List<String>) {
    val changedAnything: Boolean get() = changes.isNotEmpty()
}

/**
 * Builds a variation of a recipe. Variations are separate recipes linked by
 * [Recipe.variantOf]; the traditional version is never overwritten.
 */
object RecipeTransformer {
    private val chiliIds = setOf("green_chili", "chili_powder")
    private val fatIds = setOf("vegetable_oil", "olive_oil", "ghee", "butter", "mustard_oil", "coconut_oil", "sesame_oil")
    private val proteinCategories = setOf(
        IngredientCategory.POULTRY, IngredientCategory.RED_MEAT, IngredientCategory.SEAFOOD,
        IngredientCategory.PLANT_PROTEIN, IngredientCategory.LEGUME, IngredientCategory.EGG,
    )

    fun transform(recipe: Recipe, adjustment: RecipeAdjustment): RecipeVariation {
        val changes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val newLines = mutableListOf<RecipeIngredient>()

        for (line in recipe.ingredients) {
            val ing = RecipeAnalyzer.canonical(line)
            if (ing == null) {
                newLines += line
                continue
            }
            when (adjustment) {
                RecipeAdjustment.SPICIER -> newLines += if (ing.id in chiliIds) {
                    changes += "More ${line.displayName.lowercase()} (×1.5)"
                    line.copy(quantity = line.quantity.scaled(1.5))
                } else line
                RecipeAdjustment.MILDER, RecipeAdjustment.KID_FRIENDLY -> when {
                    ing.id !in chiliIds -> newLines += line
                    line.optional || ing.id == "green_chili" -> changes += "Left out ${line.displayName.lowercase()} (serve on the side)"
                    else -> {
                        changes += "Less ${line.displayName.lowercase()} (×0.4)"
                        newLines += line.copy(quantity = line.quantity.scaled(0.4))
                    }
                }
                RecipeAdjustment.LOWER_CALORIE -> {
                    val sub = ing.substitutions.firstOrNull { DietGoal.LOWER_CALORIE in it.goals && it.replacementId != "cauliflower" }
                    when {
                        ing.id in fatIds && line.quantity.amount > 0 -> {
                            val reduced = line.quantity.scaled(0.5)
                            changes += "Half the ${line.displayName.lowercase()} (${formatAmount(line.quantity.amount)} → ${formatAmount(reduced.amount)} ${line.quantity.unit.symbol})"
                            newLines += line.copy(quantity = reduced)
                        }
                        sub != null && ing.category in setOf(IngredientCategory.DAIRY, IngredientCategory.RED_MEAT, IngredientCategory.CONDIMENT, IngredientCategory.POULTRY) ->
                            newLines += substitute(line, ing, sub.replacementId, sub.ratio, sub.note, changes, renames)
                        else -> newLines += line
                    }
                }
                RecipeAdjustment.HIGHER_PROTEIN -> when {
                    ing.category in proteinCategories && line.quantity.amount > 0 -> {
                        changes += "More ${line.displayName.lowercase()} (×1.3)"
                        newLines += line.copy(quantity = line.quantity.scaled(1.3))
                    }
                    ing.id == "yogurt" -> newLines += substitute(line, ing, "greek_yogurt_nonfat", 1.0, "Higher protein", changes, renames)
                    else -> newLines += line
                }
                else -> {
                    val goal = adjustment.goal!!
                    if (complies(ing, goal)) {
                        newLines += line
                        if (goal == DietGoal.HALAL && ing.halal == HalalStatus.REQUIRES_HALAL_SOURCE) {
                            warnings += "Use halal-certified ${ing.name.lowercase()}"
                        }
                        if (goal == DietGoal.HALAL && ing.halal == HalalStatus.VERIFY) warnings += "Check that the ${ing.name.lowercase()} is halal"
                        continue
                    }
                    val sub = ing.substitutions.firstOrNull { goal in it.goals && IngredientCatalog[it.replacementId]?.let { r -> complies(r, goal) } == true }
                    when {
                        sub != null -> newLines += substitute(line, ing, sub.replacementId, sub.ratio, sub.note, changes, renames)
                        line.optional -> changes += "Left out ${line.displayName.lowercase()}"
                        else -> {
                            warnings += "No built-in substitute for ${line.displayName.lowercase()}; replace or remove it"
                            newLines += line
                        }
                    }
                }
            }
        }
        if (adjustment == RecipeAdjustment.KID_FRIENDLY) warnings += "Serve chilli, pickles and strong sauces on the side; cut food into small pieces for young children."

        val steps = recipe.steps.map { step -> step.copy(text = renameInText(step.text, renames)) }
        val variant = recipe.copy(
            id = "${recipe.id}__${adjustment.name.lowercase()}",
            name = "${recipe.name.removeSuffix(" (variation)")} — ${adjustment.label.lowercase()} variation",
            ingredients = newLines,
            steps = steps,
            origin = RecipeOrigin.USER,
            variantOf = recipe.id,
            variantSummary = if (changes.isEmpty()) "No changes were needed" else changes.joinToString("; "),
            cookedYieldGrams = null,
            tags = recipe.tags + adjustment.label.lowercase(),
        )
        return RecipeVariation(variant, changes, warnings.distinct())
    }

    private fun shortName(name: String): String = name.substringBefore(',').substringBefore(" (").trim().lowercase()

    /** Replaces whole-word mentions, longest names first, keeping a leading capital. */
    internal fun renameInText(text: String, renames: List<Pair<String, String>>): String {
        var result = text
        for ((from, to) in renames.distinct().sortedByDescending { it.first.length }) {
            if (from.isBlank()) continue
            val regex = Regex("\\b" + Regex.escape(from) + "\\b", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { m -> if (m.value.first().isUpperCase()) to.replaceFirstChar { it.uppercase() } else to }
        }
        return result
    }

    fun complies(ing: CanonicalIngredient, goal: DietGoal): Boolean = when (goal) {
        DietGoal.VEGETARIAN -> ing.isVegetarian
        DietGoal.VEGAN -> ing.isVegan
        DietGoal.DAIRY_FREE -> !ing.containsDairy
        DietGoal.GLUTEN_FREE -> !ing.containsGluten
        DietGoal.HALAL -> ing.halal != HalalStatus.NOT_PERMISSIBLE
        DietGoal.NUT_FREE -> Allergen.TREE_NUT !in ing.allergens && Allergen.PEANUT !in ing.allergens
        DietGoal.LOWER_CALORIE, DietGoal.HIGHER_PROTEIN -> true
    }

    private fun substitute(
        line: RecipeIngredient,
        from: CanonicalIngredient,
        replacementId: String,
        ratio: Double,
        note: String,
        changes: MutableList<String>,
        renames: MutableList<Pair<String, String>>,
    ): RecipeIngredient {
        val replacement = IngredientCatalog.require(replacementId)
        // Count-based quantities don't transfer between foods (1 chicken breast ≠ 1 "piece" of tofu): convert via grams.
        val quantity = if (line.quantity.unit.kind == UnitKind.COUNT) {
            val grams = from.portions.firstOrNull { it.unit == line.quantity.unit }?.grams?.times(line.quantity.amount)
            if (grams != null) app.nouri.core.units.Quantity.grams(grams * ratio) else line.quantity.scaled(ratio)
        } else line.quantity.scaled(ratio)
        changes += "Swapped ${line.displayName.lowercase()} for ${replacement.name.lowercase()}${if (note.isNotBlank()) " ($note)" else ""}"
        val short = shortName(replacement.name)
        renames += line.displayName to short
        renames += shortName(from.name) to short
        // Steps usually say "the chicken" rather than "chicken breast or thigh".
        shortName(from.name).split(' ').first().takeIf { it.length >= 4 }?.let { renames += it to short }
        return line.copy(displayName = replacement.name, ingredientId = replacement.id, quantity = quantity, foodRefId = null)
    }
}
