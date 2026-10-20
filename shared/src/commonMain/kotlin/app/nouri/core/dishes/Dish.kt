package app.nouri.core.dishes

import app.nouri.core.units.FoodPortion
import kotlinx.serialization.Serializable

@Serializable
enum class DishCategory(val label: String) {
    RICE_DISH("Rice dish"),
    CURRY_STEW("Curry / stew"),
    LENTIL_LEGUME("Lentils & legumes"),
    BREAD("Bread"),
    NOODLE_SOUP("Noodle soup"),
    NOODLES_PASTA("Noodles & pasta"),
    GRILLED_MEAT("Grilled / roasted"),
    SANDWICH_WRAP("Sandwich / wrap"),
    SALAD("Salad"),
    SIDE("Side / condiment"),
    BREAKFAST("Breakfast"),
    FRIED_SNACK("Fried snack"),
    PIZZA("Pizza"),
    DESSERT("Dessert"),
    BOWL("Bowl"),
    SUSHI("Sushi"),
    DUMPLING("Dumplings"),
    SOUP("Soup"),
}

/** One component of a dish's reference recipe. Exactly one of [ingredientId] / [foodRefId] is set. */
@Serializable
data class DishComponent(
    val ingredientId: String? = null,
    val foodRefId: String? = null,
    val grams: Double,
    val consumedFraction: Double = 1.0,
    /** [grams] are cooked weight (e.g. cooked rice in a bowl). */
    val cooked: Boolean = false,
) {
    init {
        require((ingredientId == null) != (foodRefId == null)) { "Set exactly one of ingredientId/foodRefId" }
        require(grams >= 0) { "Negative grams" }
    }
}

/**
 * A named dish as people eat it, with an editorial reference recipe used to
 * compute typical nutrition per 100 g. These are Nouri's own write-ups of widely
 * known dishes — not reproductions of any published recipe — and the computed
 * values are labelled "typical recipe" in the app.
 */
@Serializable
data class DishDefinition(
    val id: String,
    val name: String,
    val cuisineId: String,
    val region: String? = null,
    val category: DishCategory,
    val aliases: List<String> = emptyList(),
    val composition: List<DishComponent>,
    /** Cooked weight of the batch described by [composition]. */
    val batchCookedGrams: Double,
    val servingGrams: Double,
    val servingLowGrams: Double,
    val servingHighGrams: Double,
    val portions: List<FoodPortion> = emptyList(),
    /** Relative spread of energy density across cooks (oil, ghee, cream, sugar). */
    val energyVariability: Double,
    val variabilityNote: String,
    /** Dishes commonly confused with this one in photos. */
    val lookalikes: List<String> = emptyList(),
    /** What distinguishes this dish visually; given to vision providers as context. */
    val visualCues: String = "",
    /** Energy multiplier typical of restaurant preparation relative to the reference recipe. */
    val restaurantEnergyFactor: Double = 1.15,
    /** Shown when common versions of the dish contain non-halal ingredients the reference may not. */
    val halalCaution: String? = null,
) {
    init {
        require(servingLowGrams <= servingGrams && servingGrams <= servingHighGrams) { "Bad serving range for $id" }
        require(batchCookedGrams > 0) { "Batch weight must be positive for $id" }
        require(energyVariability in 0.0..1.0) { "Variability out of range for $id" }
    }
}
