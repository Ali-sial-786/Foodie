package app.nouri.core.ontology

import app.nouri.core.units.FoodPortion
import kotlinx.serialization.Serializable

@Serializable
enum class IngredientCategory(val label: String) {
    POULTRY("Poultry"),
    RED_MEAT("Meat"),
    PORK("Pork"),
    SEAFOOD("Fish & seafood"),
    EGG("Eggs"),
    DAIRY("Dairy"),
    PLANT_PROTEIN("Plant protein"),
    LEGUME("Lentils & beans"),
    GRAIN("Grains & flours"),
    BREAD("Breads"),
    PASTA_NOODLE("Pasta & noodles"),
    VEGETABLE("Vegetables"),
    HERB("Fresh herbs"),
    FRUIT("Fruit"),
    NUT_SEED("Nuts & seeds"),
    FAT_OIL("Fats & oils"),
    SPICE("Spices"),
    CONDIMENT("Sauces & condiments"),
    SWEETENER("Sweeteners"),
    BEVERAGE("Drinks"),
    BAKING("Baking"),
    OTHER("Other"),
}

/**
 * Allergens tracked at ingredient level. Covers the US major allergens (FALCPA +
 * FASTER Act) and Canada's priority allergens (which add mustard and sulphites).
 */
@Serializable
enum class Allergen(val label: String) {
    MILK("Milk"),
    EGG("Egg"),
    PEANUT("Peanut"),
    TREE_NUT("Tree nuts"),
    SOY("Soy"),
    WHEAT("Wheat"),
    GLUTEN("Gluten"),
    FISH("Fish"),
    CRUSTACEAN("Crustacean shellfish"),
    MOLLUSC("Molluscs"),
    SESAME("Sesame"),
    MUSTARD("Mustard"),
    SULPHITES("Sulphites"),
}

/** What kind of animal product (if any) an ingredient is. Drives diet suitability. */
@Serializable
enum class AnimalOrigin { NONE, POULTRY, RED_MEAT, PORK, FISH, SHELLFISH, DAIRY, EGG, HONEY, GELATIN }

/**
 * Halal status is evaluated per ingredient, never assumed per cuisine.
 * - PERMISSIBLE: plant foods, dairy, eggs, fish (per the majority of schools).
 * - REQUIRES_HALAL_SOURCE: meat/poultry — permissible only if slaughtered according to
 *   halal requirements; the app asks the user to confirm their source.
 * - NOT_PERMISSIBLE: pork and pork derivatives, alcoholic beverages.
 * - VERIFY: depends on the product (gelatin, some cheeses' rennet, vanilla extract with
 *   alcohol, wine vinegar, shellfish under some schools).
 */
@Serializable
enum class HalalStatus { PERMISSIBLE, REQUIRES_HALAL_SOURCE, NOT_PERMISSIBLE, VERIFY }

@Serializable
enum class StorageLocation { PANTRY, FRIDGE, FREEZER }

/** Dietary transformations the recipe engine can apply. */
@Serializable
enum class DietGoal(val label: String) {
    VEGETARIAN("Vegetarian"),
    VEGAN("Vegan"),
    DAIRY_FREE("Dairy-free"),
    GLUTEN_FREE("Gluten-free"),
    HALAL("Halal"),
    LOWER_CALORIE("Lower calorie"),
    HIGHER_PROTEIN("Higher protein"),
    NUT_FREE("Nut-free"),
}

@Serializable
data class Substitution(
    val replacementId: String,
    /** Replacement grams per gram of the original. */
    val ratio: Double = 1.0,
    val goals: Set<DietGoal>,
    val note: String = "",
)

/**
 * A canonical ingredient in the normalised ontology. Names, transliterations and
 * regional spellings all resolve to one [id]; nutrition comes from the referenced
 * USDA records ([rawNdb] for as-purchased/raw weights, [cookedNdb] for cooked weights).
 */
@Serializable
data class CanonicalIngredient(
    val id: String,
    val name: String,
    val category: IngredientCategory,
    val aliases: List<String> = emptyList(),
    val rawNdb: String? = null,
    val cookedNdb: String? = null,
    /** Non-USDA reference (e.g. "approx:paneer") when USDA has no suitable record. */
    val fallbackRef: String? = null,
    val densityGPerMl: Double? = null,
    val portions: List<FoodPortion> = emptyList(),
    val allergens: Set<Allergen> = emptySet(),
    val animalOrigin: AnimalOrigin = AnimalOrigin.NONE,
    val halal: HalalStatus = HalalStatus.PERMISSIBLE,
    val storage: StorageLocation = StorageLocation.PANTRY,
    /** Typical shelf life in days at [storage]; null for long-lived staples. */
    val shelfLifeDays: Int? = null,
    /** Salt, oil, common spices: assumed available and not counted as "missing". */
    val pantryStaple: Boolean = false,
    /** Edible fraction for bone-in or unpeeled forms when the USDA record is edible-only. */
    val edibleFraction: Double = 1.0,
    val substitutions: List<Substitution> = emptyList(),
    /** Cooked weight / raw weight for typical preparation (rice ≈ 3.0 absorbed water, meat ≈ 0.75). */
    val cookedYield: Double? = null,
) {
    val isVegetarian: Boolean
        get() = animalOrigin in setOf(AnimalOrigin.NONE, AnimalOrigin.DAIRY, AnimalOrigin.EGG, AnimalOrigin.HONEY)

    val isVegan: Boolean get() = animalOrigin == AnimalOrigin.NONE

    val containsGluten: Boolean get() = Allergen.GLUTEN in allergens

    val containsDairy: Boolean get() = Allergen.MILK in allergens

    val primaryRef: String?
        get() = rawNdb?.let { "usda:$it" } ?: cookedNdb?.let { "usda:$it" } ?: fallbackRef

    val cookedRef: String? get() = cookedNdb?.let { "usda:$it" }
}
