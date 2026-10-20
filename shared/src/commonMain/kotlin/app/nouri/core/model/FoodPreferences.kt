package app.nouri.core.model

import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.DietGoal
import app.nouri.core.recipes.Difficulty
import app.nouri.core.units.MeasurementSystem
import kotlinx.serialization.Serializable

/** Food preferences used for ranking and as hard filters. Every field is optional. */
@Serializable
data class FoodPreferences(
    val favouriteCuisines: List<String> = emptyList(),
    /** Canonical ingredient ids the user dislikes; recipes needing them are filtered out. */
    val dislikedIngredients: Set<String> = emptySet(),
    /** Strict: recipes containing these allergens (outside optional ingredients) are never suggested. */
    val allergies: Set<Allergen> = emptySet(),
    /** Strict dietary requirements (vegetarian, vegan, halal, gluten-free, dairy-free, nut-free). */
    val requiredDiets: Set<DietGoal> = emptySet(),
    val cookingSkill: Difficulty = Difficulty.MEDIUM,
    val usualMaxMinutes: Int? = null,
    val householdSize: Int = 1,
    val equipment: Set<String> = emptySet(),
    /** "budget" | "moderate" | "any" */
    val budget: String = "any",
    val measurementSystem: MeasurementSystem = MeasurementSystem.METRIC,
    /** Goals for ranking (not filters): higher protein, lower calorie. */
    val rankingGoals: Set<DietGoal> = emptySet(),
)
