package app.nouri.core.recommend

import app.nouri.core.model.FoodPreferences
import app.nouri.core.model.Nutrients
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.recipes.Availability
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.PantryMatch
import app.nouri.core.recipes.PantryMatcher
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeAnalysis
import app.nouri.core.recipes.RecipeAnalyzer
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecommendationContext(
    val preferences: FoodPreferences = FoodPreferences(),
    /** Canonical ids the user has (pantry + confirmed scan chips). Empty = not cooking from pantry. */
    val available: Set<String> = emptySet(),
    val expiringSoon: Set<String> = emptySet(),
    /** Energy left for today, if targets are set. */
    val remainingKcal: Double? = null,
    val onlyAvailable: Boolean = false,
    val maxMinutes: Int? = null,
    val cuisineFilter: String? = null,
    val recentlyCookedRecipeIds: List<String> = emptyList(),
    val recentCuisineIds: List<String> = emptyList(),
    val favouriteRecipeIds: Set<String> = emptySet(),
)

data class RankedRecipe(
    val recipe: Recipe,
    val score: Double,
    val reasons: List<String>,
    val perServing: Nutrients,
    val pantry: PantryMatch?,
    val analysis: RecipeAnalysis,
)

data class Exclusion(val recipe: Recipe, val reason: String)

/**
 * The parts of ranking that depend only on the recipe (ingredient analysis and
 * nutrition), computed once and reused while filters and pantry change.
 */
class PreparedRecipe(val recipe: Recipe, val analysis: RecipeAnalysis, val perServing: Nutrients)

data class RankingResult(val ranked: List<RankedRecipe>, val excluded: List<Exclusion>)

/**
 * Explainable recipe ranking. Hard filters (allergies, required diets, dislikes)
 * remove recipes; everything else only changes order, so users can still pick
 * anything they like.
 */
class RecipeRanker(private val engine: NutritionEngine = NutritionEngine()) {

    fun prepare(recipe: Recipe): PreparedRecipe =
        PreparedRecipe(recipe, RecipeAnalyzer.analyze(recipe), engine.calculateRecipe(recipe).perServing)

    fun rank(recipes: List<Recipe>, ctx: RecommendationContext): RankingResult = rankPrepared(recipes.map(::prepare), ctx)

    fun rankPrepared(recipes: List<PreparedRecipe>, ctx: RecommendationContext): RankingResult {
        val excluded = mutableListOf<Exclusion>()
        val ranked = recipes.mapNotNull { prepared ->
            val recipe = prepared.recipe
            val analysis = prepared.analysis
            exclusionReason(recipe, analysis, ctx)?.let { excluded += Exclusion(recipe, it); return@mapNotNull null }
            val pantry = if (ctx.available.isNotEmpty()) PantryMatcher.match(recipe, ctx.available) else null
            if (ctx.onlyAvailable && pantry != null && pantry.missing.isNotEmpty()) {
                excluded += Exclusion(recipe, "Needs ${pantry.missing.size} ingredient(s) you don't have")
                return@mapNotNull null
            }
            score(prepared, pantry, ctx)
        }.sortedByDescending { it.score }
        return RankingResult(ranked, excluded)
    }

    private fun exclusionReason(recipe: Recipe, analysis: RecipeAnalysis, ctx: RecommendationContext): String? {
        val prefs = ctx.preferences
        val allergens = analysis.allergens intersect prefs.allergies
        if (allergens.isNotEmpty()) return "Contains ${allergens.joinToString { it.label.lowercase() }}"
        for (diet in prefs.requiredDiets) {
            val ok = when (diet) {
                DietGoal.HALAL -> analysis.halal != HalalStatus.NOT_PERMISSIBLE
                DietGoal.LOWER_CALORIE, DietGoal.HIGHER_PROTEIN -> true
                else -> diet in analysis.diets
            }
            if (!ok) return "Not ${diet.label.lowercase()}"
        }
        val disliked = recipe.ingredients.filter { !it.optional }
            .mapNotNull { RecipeAnalyzer.canonical(it)?.id }
            .firstOrNull { it in prefs.dislikedIngredients }
        if (disliked != null) return "Contains an ingredient you dislike"
        ctx.cuisineFilter?.let { if (recipe.cuisineId != it) return "Different cuisine" }
        return null
    }

    private fun score(prepared: PreparedRecipe, pantry: PantryMatch?, ctx: RecommendationContext): RankedRecipe {
        val recipe = prepared.recipe
        val analysis = prepared.analysis
        val reasons = mutableListOf<String>()
        val perServing = prepared.perServing
        var score = 0.0

        // Ingredients on hand (30%)
        if (pantry != null) {
            val counted = pantry.required.filter { it.status != Availability.STAPLE_ASSUMED }
            val have = counted.count { it.status != Availability.MISSING }
            score += 0.30 * pantry.coverage
            if (counted.isNotEmpty()) reasons += "Uses $have of ${counted.size} main ingredients you have" +
                if (pantry.missing.isNotEmpty()) " (missing ${pantry.missing.joinToString { it.line.displayName.lowercase() }})" else ""
            // Expiring soon (10%)
            val expiringUsed = pantry.items.filter { it.ingredientId in ctx.expiringSoon && it.status == Availability.HAVE }
            if (ctx.expiringSoon.isNotEmpty()) {
                score += 0.10 * (expiringUsed.size.toDouble() / ctx.expiringSoon.size).coerceAtMost(1.0)
                if (expiringUsed.isNotEmpty()) reasons += "Uses ${expiringUsed.joinToString { it.line.displayName.lowercase() }} before it expires"
            }
        } else {
            score += 0.15
        }

        // Nutrition fit (25%)
        val kcal = perServing.energyKcal
        ctx.remainingKcal?.let { remaining ->
            if (remaining > 150) {
                val ratio = kcal / remaining
                val fit = when {
                    ratio <= 1.0 -> 1.0 - abs(ratio - 0.8) * 0.5
                    else -> (1.0 - (ratio - 1.0) * 2).coerceAtLeast(0.0)
                }
                score += 0.20 * fit
                if (ratio <= 1.0) reasons += "Fits your ${remaining.roundToInt()} kcal remaining (about ${roundTo(kcal, 10)} kcal per serving)"
                else reasons += "About ${roundTo(kcal, 10)} kcal per serving — more than you have left today"
            }
        }
        val proteinShare = if (kcal > 0) perServing.proteinG * 4 / kcal else 0.0
        if (DietGoal.HIGHER_PROTEIN in ctx.preferences.rankingGoals) score += 0.05 * (proteinShare / 0.35).coerceAtMost(1.0)
        if (proteinShare >= 0.25 && perServing.proteinG >= 20) reasons += "High protein: ${perServing.proteinG.roundToInt()} g per serving"
        if (DietGoal.LOWER_CALORIE in ctx.preferences.rankingGoals && kcal in 1.0..550.0) score += 0.05

        // Cuisine preference (15%)
        val favourite = ctx.preferences.favouriteCuisines.any { it.equals(recipe.cuisineId, ignoreCase = true) || CuisineCatalog.resolve(it)?.id == recipe.cuisineId }
        if (favourite) {
            score += 0.15
            reasons += "${CuisineCatalog[recipe.cuisineId]?.name ?: recipe.cuisineId} — one of your favourite cuisines"
        }

        // Time (10%)
        val limit = ctx.maxMinutes ?: ctx.preferences.usualMaxMinutes
        if (limit != null) {
            score += if (recipe.totalMinutes <= limit) 0.10 else 0.10 * (limit.toDouble() / recipe.totalMinutes).coerceIn(0.0, 1.0) * 0.5
        } else {
            score += 0.10 * (1.0 - (recipe.totalMinutes / 120.0)).coerceIn(0.0, 1.0)
        }
        if (recipe.totalMinutes <= 30) reasons += "Ready in ${recipe.totalMinutes} min"

        // Skill (5%)
        if (recipe.difficulty.ordinal <= ctx.preferences.cookingSkill.ordinal) score += 0.05

        // History & variety (5% + penalties)
        if (recipe.id in ctx.favouriteRecipeIds) {
            score += 0.05
            reasons += "One of your favourites"
        }
        if (recipe.id in ctx.recentlyCookedRecipeIds.take(5)) score -= 0.08
        if (recipe.cuisineId in ctx.recentCuisineIds.take(3) && !favourite) score -= 0.03

        if (analysis.halal == HalalStatus.REQUIRES_HALAL_SOURCE && DietGoal.HALAL in ctx.preferences.requiredDiets) {
            reasons += "Halal with halal-certified meat"
        }
        return RankedRecipe(recipe, score, reasons, perServing, pantry, analysis)
    }

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step)
}
