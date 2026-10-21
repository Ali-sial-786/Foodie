package app.nouri.core.recipes

import app.nouri.core.recipes.Difficulty.EASY
import app.nouri.core.recipes.Difficulty.MEDIUM
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.QuantityUnit.CLOVE
import app.nouri.core.units.QuantityUnit.CUP
import app.nouri.core.units.QuantityUnit.GRAM
import app.nouri.core.units.QuantityUnit.MILLILITRE
import app.nouri.core.units.QuantityUnit.PIECE
import app.nouri.core.units.QuantityUnit.PINCH
import app.nouri.core.units.QuantityUnit.TABLESPOON
import app.nouri.core.units.QuantityUnit.TEASPOON

/**
 * Nouri's built-in recipes: editorial write-ups of widely known home-style
 * dishes (no external source is claimed), plus explicitly labelled variations.
 * Nutrition is always calculated by the engine from the ingredient list.
 */
object RecipeCatalog {
    val all: List<Recipe> by lazy { buildRecipes() }
    val byId: Map<String, Recipe> by lazy { all.associateBy { it.id } }
    operator fun get(id: String): Recipe? = byId[id]
}

private const val POULTRY_SAFETY = StandardRecipeNotes.POULTRY
private const val MINCE_SAFETY = StandardRecipeNotes.MINCE
private const val EGG_SAFETY = StandardRecipeNotes.EGG
private const val RICE_SAFETY = StandardRecipeNotes.RICE
private const val FRIDGE_3_DAYS = StandardRecipeNotes.FRIDGE_3_DAYS
private const val REHEAT = StandardRecipeNotes.REHEAT
private const val DEEP_FRY = StandardRecipeNotes.DEEP_FRY
private const val BEEF_SAFETY = StandardRecipeNotes.BEEF

private class RecipeBuilder(val id: String, val name: String, val cuisine: String, val region: String?, val servings: Int, val prep: Int, val cook: Int, val difficulty: Difficulty) {
    var description = ""
    var origin = RecipeOrigin.EDITORIAL_TRADITIONAL
    var variantOf: String? = null
    var variantSummary: String? = null
    var storage: String? = FRIDGE_3_DAYS
    var reheating: String? = REHEAT
    val equipment = mutableListOf<String>()
    val tags = mutableSetOf<String>()
    val ingredients = mutableListOf<RecipeIngredient>()
    val steps = mutableListOf<RecipeStep>()
    val safety = mutableListOf<String>()
    val notes = mutableListOf<String>()
    private var group: String? = null

    fun equipment(vararg e: String) { equipment += e }
    fun tags(vararg t: String) { tags += t }
    fun group(name: String?) { group = name }
    fun ing(
        id: String, amount: Double, unit: QuantityUnit, display: String,
        prep: String? = null, optional: Boolean = false, consumed: Double = 1.0, cooked: Boolean = false,
    ) {
        ingredients += RecipeIngredient(
            displayName = display, ingredientId = id, quantity = Quantity(amount, unit), preparation = prep,
            optional = optional, group = group, consumedFraction = consumed, measuredCooked = cooked,
        )
    }
    fun toTaste(id: String, display: String) {
        ingredients += RecipeIngredient(displayName = display, ingredientId = id, quantity = Quantity(0.0, PINCH), preparation = "to taste", group = group)
    }
    fun step(text: String, minutes: Double? = null, tempC: Int? = null) {
        steps += RecipeStep(text, minutes?.let { (it * 60).toInt() }, tempC)
    }
    fun safety(vararg s: String) { safety += s }

    fun build() = Recipe(
        id = id, name = name, cuisineId = cuisine, region = region, description = description, servings = servings,
        ingredients = ingredients.toList(), steps = steps.toList(), prepMinutes = prep, cookMinutes = cook,
        difficulty = difficulty, equipment = equipment.toList(), tags = tags.toSet(), origin = origin,
        variantOf = variantOf, variantSummary = variantSummary, storage = storage, reheating = reheating,
        foodSafety = safety.toList(), notes = notes.toList(),
    )
}

private class RecipeListBuilder {
    val items = mutableListOf<Recipe>()
    fun recipe(
        id: String, name: String, cuisine: String, region: String?, servings: Int, prep: Int, cook: Int,
        difficulty: Difficulty = MEDIUM, block: RecipeBuilder.() -> Unit,
    ) {
        items += RecipeBuilder(id, name, cuisine, region, servings, prep, cook, difficulty).apply(block).build()
    }
}

private fun buildRecipes(): List<Recipe> = RecipeListBuilder().apply {
    // ================================================================ Pakistani & Indian
    recipe("chicken_karahi", "Chicken karahi", "pakistani", "Peshawar / Lahore", 4, 15, 35) {
        description = "Bone-in chicken cooked fast and hot with tomatoes, ginger, garlic and green chillies until the masala clings to the meat."
        equipment("Karahi or wide heavy wok", "Wooden spoon")
        tags("high-protein", "gluten-free", "dinner")
        ing("chicken_curry_cut", 1000.0, GRAM, "Chicken, bone-in, skinless", "cut into 12–14 pieces")
        ing("vegetable_oil", 6.0, TABLESPOON, "Oil (or half ghee)")
        ing("tomato", 500.0, GRAM, "Tomatoes", "quartered or roughly chopped")
        ing("ginger", 2.0, TABLESPOON, "Ginger", "1 tbsp paste, 1 tbsp julienned")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("green_chili", 6.0, PIECE, "Green chillies", "slit")
        ing("black_pepper", 1.0, TEASPOON, "Black pepper", "coarsely crushed")
        ing("cumin", 1.0, TEASPOON, "Cumin", "roasted and crushed")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli flakes or powder")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander", "chopped")
        step("Heat the oil in the karahi over high heat. Add the chicken and stir-fry until it loses its pink colour and starts to brown.", 8.0)
        step("Add the garlic and ginger paste and fry for 1 minute until fragrant.", 1.0)
        step("Add the tomatoes and salt. Cover and cook on medium heat until the tomatoes soften and their skins lift.", 8.0)
        step("Uncover, remove any loose tomato skins, and cook on high heat, stirring and pressing the tomatoes, until the masala is thick and the oil separates.", 12.0)
        step("Add black pepper, cumin, chilli and green chillies; cook 2–3 minutes more until the chicken is cooked through.", 3.0)
        step("Finish with julienned ginger and coriander. Serve with naan or roti.")
        safety(POULTRY_SAFETY)
    }
    recipe("chicken_karahi_lighter", "Lower-oil chicken karahi (variation)", "pakistani", "Peshawar / Lahore", 4, 15, 35) {
        origin = RecipeOrigin.EDITORIAL_VARIATION
        variantOf = "chicken_karahi"
        variantSummary = "Uses one-third of the oil and boneless chicken breast; the masala is cooked down with a splash of water instead of extra fat."
        description = "A lighter weeknight version of chicken karahi. It is not the traditional preparation — expect a less glossy masala."
        equipment("Non-stick karahi or wok")
        tags("high-protein", "lower-calorie", "gluten-free", "dinner")
        ing("chicken_breast", 700.0, GRAM, "Chicken breast, boneless", "cut into 3 cm pieces")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("tomato", 500.0, GRAM, "Tomatoes", "chopped")
        ing("ginger", 2.0, TABLESPOON, "Ginger", "paste and julienne")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("green_chili", 6.0, PIECE, "Green chillies", "slit")
        ing("black_pepper", 1.0, TEASPOON, "Black pepper")
        ing("cumin", 1.0, TEASPOON, "Cumin", "roasted and crushed")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        step("Heat the oil in a non-stick karahi over high heat and sear the chicken for 4–5 minutes; remove to a plate.", 5.0)
        step("Add garlic and ginger paste to the pan, then the tomatoes and salt. Cover and cook until soft.", 8.0)
        step("Mash the tomatoes and cook uncovered until thick, adding a splash of water if it catches.", 8.0)
        step("Return the chicken with pepper, cumin and green chillies; cook until the chicken is just done.", 6.0)
        step("Garnish with ginger and coriander.")
        safety(POULTRY_SAFETY)
    }
    recipe("chicken_biryani", "Chicken biryani", "pakistani", "Karachi / Sindhi", 6, 40, 60, Difficulty.INVOLVED) {
        description = "Layered rice and spiced chicken with fried onions, yogurt, mint and potatoes, finished on gentle steam (dum)."
        equipment("Large heavy pot with lid", "Large pan for boiling rice", "Colander")
        tags("celebration", "rice", "gluten-free")
        group("Chicken masala")
        ing("chicken_curry_cut", 1000.0, GRAM, "Chicken, bone-in, skinless", "large pieces")
        ing("onion", 3.0, PIECE, "Onions", "thinly sliced")
        ing("vegetable_oil", 120.0, MILLILITRE, "Oil")
        ing("yogurt", 1.0, CUP, "Plain yogurt", "whisked")
        ing("tomato", 2.0, PIECE, "Tomatoes", "chopped")
        ing("potato", 2.0, PIECE, "Potatoes", "halved")
        ing("ginger", 1.0, TABLESPOON, "Ginger paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic paste")
        ing("garam_masala", 2.0, TABLESPOON, "Biryani masala / garam masala")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("green_chili", 4.0, PIECE, "Green chillies")
        ing("salt", 2.0, TEASPOON, "Salt")
        group("Rice")
        ing("rice_white", 500.0, GRAM, "Basmati rice", "rinsed and soaked 30 minutes")
        ing("salt", 1.0, TABLESPOON, "Salt for the rice water", consumed = 0.2)
        ing("bay_leaf", 2.0, PIECE, "Bay leaves")
        group("Layering")
        ing("mint", 1.0, QuantityUnit.HANDFUL, "Mint leaves")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Coriander leaves")
        ing("ghee", 2.0, TABLESPOON, "Ghee", "melted")
        ing("saffron", 1.0, PINCH, "Saffron", "soaked in 3 tbsp warm milk")
        ing("milk_whole", 3.0, TABLESPOON, "Milk")
        ing("lemon_juice", 1.0, TABLESPOON, "Lemon juice")
        group(null)
        step("Fry the onions in the oil until deep golden. Remove half for layering.", 15.0)
        step("Add ginger, garlic and chicken to the remaining onions; fry 5 minutes. Add tomatoes, spices, salt and potatoes and cook until the tomatoes break down.", 10.0)
        step("Stir in the yogurt and green chillies; cover and cook until the chicken is almost tender and the masala is thick.", 20.0)
        step("Meanwhile boil plenty of water with salt and bay leaves. Add the drained rice and boil until about 70% cooked (a grain breaks with a firm centre); drain.", 6.0)
        step("Layer half the rice over the chicken, then mint, coriander and fried onions; add the remaining rice. Drizzle with ghee, saffron milk and lemon juice.")
        step("Seal the lid (or cover tightly with foil) and steam on the lowest heat until the rice is fully cooked.", 20.0)
        step("Rest 5 minutes, then mix gently from the sides and serve with raita.")
        safety(POULTRY_SAFETY, RICE_SAFETY)
    }
    recipe("masoor_dal_tadka", "Masoor dal with tadka", "pakistani", "Pakistani / North Indian home-style", 4, 10, 30, EASY) {
        description = "Everyday red lentils simmered with turmeric and finished with a sizzling cumin–garlic tempering."
        equipment("Saucepan", "Small frying pan")
        tags("vegetarian", "vegan-adaptable", "budget", "gluten-free", "high-fibre")
        ing("lentils_red", 1.0, CUP, "Red lentils (masoor dal)", "rinsed")
        ing("water", 4.0, CUP, "Water")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("tomato", 1.0, PIECE, "Tomato", "chopped")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("Tadka")
        ing("ghee", 1.0, TABLESPOON, "Ghee")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds")
        ing("garlic", 3.0, CLOVE, "Garlic", "sliced")
        ing("onion", 1.0, PIECE, "Onion", "thinly sliced")
        ing("chili_powder", 0.5, TEASPOON, "Red chilli powder")
        ing("cilantro", 2.0, TABLESPOON, "Coriander", "chopped")
        group(null)
        step("Simmer the lentils with water, turmeric, tomato and salt, skimming the foam, until soft and creamy.", 25.0)
        step("Whisk the dal to the consistency you like, adding hot water if too thick.")
        step("For the tadka, heat ghee and oil; fry the onion until golden, then add cumin and garlic until the garlic turns light gold. Take off the heat and stir in chilli powder.", 8.0)
        step("Pour the sizzling tadka over the dal, garnish with coriander and serve with rice or roti.")
        storage = "Refrigerate up to 4 days; dal thickens as it cools."
        reheating = "Loosen with a splash of water and reheat until bubbling."
    }
    recipe("chana_masala", "Chana masala", "indian", "Punjabi", 4, 10, 30, EASY) {
        description = "Chickpeas simmered in a tangy onion-tomato gravy with warm spices."
        equipment("Heavy saucepan")
        tags("vegetarian", "vegan", "gluten-free", "high-fibre", "budget")
        ing("chickpeas_cooked", 2.0, QuantityUnit.CAN, "Chickpeas", "drained and rinsed")
        ing("vegetable_oil", 3.0, TABLESPOON, "Oil")
        ing("onion", 2.0, PIECE, "Onions", "finely chopped")
        ing("tomato", 3.0, PIECE, "Tomatoes", "puréed")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "grated")
        ing("garlic", 4.0, CLOVE, "Garlic", "minced")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala / chana masala")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("lemon_juice", 1.0, TABLESPOON, "Lemon juice")
        ing("water", 1.0, CUP, "Water")
        step("Fry the cumin in oil for a few seconds, then the onions until deep golden.", 10.0)
        step("Add ginger and garlic for 1 minute, then tomatoes, spices and salt; cook until the oil separates.", 10.0)
        step("Add chickpeas and water; simmer, mashing a few chickpeas to thicken the gravy.", 10.0)
        step("Finish with lemon juice and serve with rice, roti or bhatura.")
    }
    recipe("aloo_anday", "Aloo anday (potato and egg curry)", "pakistani", "Pakistani home-style", 4, 15, 30, EASY) {
        description = "Boiled eggs and potatoes in a simple tomato–onion masala — a classic Pakistani home dinner."
        equipment("Saucepan", "Pot for boiling eggs")
        tags("vegetarian", "budget", "gluten-free")
        ing("egg", 6.0, PIECE, "Eggs", "hard-boiled and peeled")
        ing("potato", 3.0, PIECE, "Potatoes", "peeled and cubed")
        ing("onion", 2.0, PIECE, "Onions", "finely chopped")
        ing("tomato", 3.0, PIECE, "Tomatoes", "chopped")
        ing("vegetable_oil", 4.0, TABLESPOON, "Oil")
        ing("ginger", 1.0, TEASPOON, "Ginger paste")
        ing("garlic", 1.0, TEASPOON, "Garlic paste")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("coriander_seed", 1.0, TEASPOON, "Ground coriander")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("garam_masala", 0.5, TEASPOON, "Garam masala")
        ing("water", 2.0, CUP, "Water")
        ing("cilantro", 2.0, TABLESPOON, "Coriander", "chopped")
        step("Fry the onions in oil until golden; add ginger and garlic for 1 minute.", 10.0)
        step("Add tomatoes, salt and powdered spices; cook until a thick masala forms and oil separates.", 8.0)
        step("Add potatoes and water; cover and simmer until the potatoes are tender.", 12.0)
        step("Score the eggs lightly, add them to the gravy and simmer 3 minutes. Finish with garam masala and coriander.", 3.0)
        safety("Hard-boil eggs until the yolks are firm.")
    }
    recipe("aloo_murgh", "Aloo murgh (chicken and potato curry)", "pakistani", "Punjabi home-style", 4, 15, 40) {
        description = "Chicken and potatoes in a yogurt–tomato gravy, everyday Punjabi comfort food."
        equipment("Heavy pot with lid")
        tags("high-protein", "gluten-free", "dinner")
        ing("chicken_breast", 600.0, GRAM, "Chicken breast or thigh", "cubed")
        ing("potato", 3.0, PIECE, "Potatoes", "cubed")
        ing("onion", 2.0, PIECE, "Onions", "sliced")
        ing("tomato", 2.0, PIECE, "Tomatoes", "chopped")
        ing("yogurt", 0.5, CUP, "Plain yogurt", "whisked")
        ing("vegetable_oil", 4.0, TABLESPOON, "Oil")
        ing("garlic", 4.0, CLOVE, "Garlic", "minced")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "grated")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("coriander_seed", 2.0, TEASPOON, "Ground coriander")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("water", 1.5, CUP, "Water")
        step("Brown the onions in oil, then add garlic and ginger.", 10.0)
        step("Add the chicken and fry until it changes colour; add tomatoes, salt and powdered spices and cook until soft.", 10.0)
        step("Lower the heat, stir in the yogurt a spoonful at a time so it doesn't split, then cook until the oil separates.", 5.0)
        step("Add potatoes and water; cover and simmer until both chicken and potatoes are tender.", 15.0)
        step("Sprinkle garam masala and serve with roti or rice.")
        safety(POULTRY_SAFETY)
    }
    recipe("chicken_tikka", "Chicken tikka", "pakistani", "Punjabi", 4, 15, 15) {
        description = "Yogurt-and-spice marinated chicken pieces grilled until charred at the edges."
        equipment("Grill, oven or griddle", "Skewers")
        tags("high-protein", "low-carb", "gluten-free", "grill")
        ing("chicken_thigh", 800.0, GRAM, "Chicken thigh or breast", "3 cm cubes")
        ing("yogurt", 0.75, CUP, "Plain yogurt", consumed = 0.5)
        ing("lemon_juice", 2.0, TABLESPOON, "Lemon juice")
        ing("ginger", 1.0, TABLESPOON, "Ginger paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic paste")
        ing("chili_powder", 2.0, TEASPOON, "Kashmiri or red chilli powder")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil for basting", consumed = 0.6)
        step("Mix everything except the oil with the chicken. Marinate at least 2 hours (or overnight) in the fridge.")
        step("Thread onto skewers. Grill or roast at high heat, turning and basting with oil, until charred at the edges and cooked through.", 15.0, 230)
        step("Rest 2 minutes; serve with onion rings, lemon and green chutney.")
        safety(POULTRY_SAFETY, "Discard leftover marinade that has touched raw chicken.")
    }
    recipe("chicken_tikka_bowl", "Chicken tikka rice bowl (variation)", "pakistani", "Modern Pakistani", 4, 20, 20, EASY) {
        origin = RecipeOrigin.EDITORIAL_VARIATION
        variantOf = "chicken_tikka"
        variantSummary = "Chicken tikka served as a high-protein bowl with basmati rice, kachumber salad and a yogurt–mint sauce."
        description = "A balanced bowl built around grilled chicken tikka. A modern serving style, not a traditional dish."
        equipment("Grill or oven", "Saucepan")
        tags("high-protein", "meal-prep", "gluten-free", "lunch")
        ing("chicken_breast", 600.0, GRAM, "Chicken breast", "3 cm cubes")
        ing("yogurt", 0.5, CUP, "Plain yogurt for marinade", consumed = 0.5)
        ing("chili_powder", 2.0, TEASPOON, "Chilli powder")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala")
        ing("garlic", 1.0, TABLESPOON, "Garlic paste")
        ing("ginger", 1.0, TABLESPOON, "Ginger paste")
        ing("lemon_juice", 2.0, TABLESPOON, "Lemon juice")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("rice_white", 1.0, CUP, "Basmati rice")
        ing("cucumber", 1.0, PIECE, "Cucumber", "diced")
        ing("tomato", 2.0, PIECE, "Tomatoes", "diced")
        ing("onion", 0.5, PIECE, "Red onion", "finely diced")
        ing("greek_yogurt_nonfat", 1.0, CUP, "Greek yogurt for sauce")
        ing("mint", 2.0, TABLESPOON, "Mint", "chopped")
        step("Marinate the chicken with yogurt, spices, garlic, ginger, lemon and salt for at least 30 minutes.")
        step("Cook the rice: simmer with 2 cups water, covered, until absorbed; rest 10 minutes.", 15.0)
        step("Grill or roast the chicken on a rack until charred and cooked through.", 15.0, 230)
        step("Mix cucumber, tomato and onion with a pinch of salt; stir mint into the Greek yogurt.")
        step("Build bowls with rice, chicken, salad and yogurt sauce.")
        safety(POULTRY_SAFETY, RICE_SAFETY)
    }
    recipe("seekh_kebab", "Seekh kebab with yogurt salad", "pakistani", "Lahori / Mughlai", 4, 20, 15) {
        description = "Spiced minced-meat kebabs grilled on skewers, served with a cooling yogurt and cucumber salad."
        equipment("Flat skewers", "Grill, griddle or oven")
        tags("high-protein", "low-carb", "grill", "gluten-free")
        ing("beef_ground_lean", 600.0, GRAM, "Lean minced beef (or lamb)")
        ing("onion", 1.0, PIECE, "Onion", "grated and squeezed dry")
        ing("green_chili", 3.0, PIECE, "Green chillies", "finely chopped")
        ing("ginger", 1.0, TABLESPOON, "Ginger paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic paste")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Coriander", "finely chopped")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli flakes")
        ing("cumin", 1.0, TEASPOON, "Roasted cumin")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil for brushing", consumed = 0.6)
        group("Yogurt salad")
        ing("yogurt", 1.0, CUP, "Plain yogurt")
        ing("cucumber", 1.0, PIECE, "Cucumber", "grated")
        ing("mint", 1.0, TABLESPOON, "Mint", "chopped")
        group(null)
        step("Knead the mince with onion, chillies, ginger, garlic, coriander, spices and salt for 3–4 minutes until sticky. Chill 30 minutes.")
        step("With wet hands, press portions onto skewers into long kebabs.")
        step("Grill over high heat, turning and brushing with oil, until browned and cooked through.", 12.0)
        step("Mix the yogurt salad and serve alongside with onions and lemon.")
        safety(MINCE_SAFETY)
    }
    recipe("palak_paneer", "Palak paneer", "indian", "Punjabi", 4, 15, 25) {
        description = "Paneer cubes in a smooth, gently spiced spinach purée."
        equipment("Pot for blanching", "Blender", "Frying pan")
        tags("vegetarian", "gluten-free", "high-protein")
        ing("spinach", 500.0, GRAM, "Spinach", "washed")
        ing("paneer", 250.0, GRAM, "Paneer", "cubed")
        ing("onion", 1.0, PIECE, "Onion", "chopped")
        ing("tomato", 1.0, PIECE, "Tomato", "chopped")
        ing("ghee", 1.0, TABLESPOON, "Ghee")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("garlic", 3.0, CLOVE, "Garlic")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("green_chili", 2.0, PIECE, "Green chillies")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("cream_heavy", 3.0, TABLESPOON, "Cream", optional = true)
        ing("salt", 1.0, TEASPOON, "Salt")
        step("Blanch the spinach for 1 minute, cool in iced water, then blend with the green chillies.", 2.0)
        step("Heat ghee and oil; fry cumin, then onion until soft, then garlic, ginger and tomato until mushy.", 10.0)
        step("Add the spinach purée and salt; simmer 5 minutes. Add paneer and garam masala and warm through.", 6.0)
        step("Swirl in cream if using and serve with roti.")
    }

    // ================================================================ Middle East & Africa
    recipe("shakshuka", "Shakshuka", "moroccan", "Maghrebi / Levantine", 2, 10, 20, EASY) {
        description = "Eggs gently poached in a spiced tomato and pepper sauce."
        equipment("Lidded frying pan")
        tags("vegetarian", "gluten-free", "breakfast", "one-pan")
        ing("olive_oil", 2.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Onion", "sliced")
        ing("bell_pepper", 1.0, PIECE, "Red pepper", "sliced")
        ing("garlic", 2.0, CLOVE, "Garlic", "sliced")
        ing("tomato_canned", 1.0, QuantityUnit.CAN, "Chopped tomatoes")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("paprika", 1.0, TEASPOON, "Paprika")
        ing("chili_powder", 0.5, TEASPOON, "Chilli flakes")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("egg", 4.0, PIECE, "Eggs")
        ing("parsley", 2.0, TABLESPOON, "Parsley or coriander")
        step("Soften the onion and pepper in olive oil, then add garlic and spices for 1 minute.", 8.0)
        step("Add tomatoes and salt; simmer until thickened.", 8.0)
        step("Make four wells and crack in the eggs. Cover and cook on low until the whites are set.", 6.0)
        step("Scatter herbs and serve with bread.")
        safety(EGG_SAFETY)
        storage = "Best eaten fresh; the sauce alone keeps 3 days refrigerated."
    }
    recipe("menemen", "Menemen", "turkish", "Anatolian", 2, 5, 12, EASY) {
        description = "Soft scrambled eggs with tomatoes and green peppers, a Turkish breakfast favourite."
        equipment("Frying pan")
        tags("vegetarian", "gluten-free", "breakfast", "quick")
        ing("olive_oil", 1.0, TABLESPOON, "Olive oil")
        ing("butter", 1.0, TABLESPOON, "Butter")
        ing("green_chili", 2.0, PIECE, "Long green peppers (sivri biber)", "sliced")
        ing("tomato", 3.0, PIECE, "Tomatoes", "grated or finely chopped")
        ing("egg", 4.0, PIECE, "Eggs", "lightly beaten")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("chili_powder", 0.5, TEASPOON, "Pul biber (chilli flakes)", optional = true)
        step("Cook the peppers in oil and butter until soft.", 3.0)
        step("Add tomatoes and salt and cook until most of the liquid has evaporated.", 5.0)
        step("Pour in the eggs and stir gently just until softly set.", 2.0)
        step("Sprinkle chilli flakes and serve with bread.")
        safety(EGG_SAFETY)
        storage = "Best eaten immediately."
        reheating = null
    }
    recipe("mercimek_corbasi", "Mercimek çorbası (red lentil soup)", "turkish", "Anatolian", 6, 10, 30, EASY) {
        description = "Silky red lentil soup with onion, carrot and a paprika butter drizzle."
        equipment("Large pot", "Stick blender")
        tags("vegetarian", "budget", "soup", "gluten-free")
        ing("lentils_red", 1.0, CUP, "Red lentils", "rinsed")
        ing("onion", 1.0, PIECE, "Onion", "chopped")
        ing("carrot", 1.0, PIECE, "Carrot", "chopped")
        ing("potato", 1.0, PIECE, "Small potato", "chopped")
        ing("tomato_paste", 1.0, TABLESPOON, "Tomato paste")
        ing("olive_oil", 2.0, TABLESPOON, "Olive oil")
        ing("water", 6.0, CUP, "Water or stock")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("butter", 1.0, TABLESPOON, "Butter for drizzling")
        ing("paprika", 1.0, TEASPOON, "Paprika or pul biber")
        ing("lemon", 1.0, PIECE, "Lemon wedges to serve", optional = true)
        step("Soften onion, carrot and potato in olive oil; stir in tomato paste.", 6.0)
        step("Add lentils, water, cumin and salt; simmer until everything is very soft.", 20.0)
        step("Blend until smooth, thinning with hot water if needed.")
        step("Melt the butter with paprika and drizzle over each bowl; serve with lemon.")
    }
    recipe("jollof_rice", "Jollof rice", "nigerian", "Nigerian", 6, 15, 45) {
        description = "Rice cooked in a rich, smoky tomato–pepper base — the West African party staple."
        equipment("Heavy pot with tight lid", "Blender")
        tags("vegan", "gluten-free", "rice", "party")
        ing("rice_white", 500.0, GRAM, "Long-grain parboiled rice", "rinsed")
        ing("tomato", 4.0, PIECE, "Tomatoes")
        ing("bell_pepper", 2.0, PIECE, "Red bell peppers")
        ing("onion", 2.0, PIECE, "Onions")
        ing("green_chili", 1.0, PIECE, "Scotch bonnet", "to taste")
        ing("tomato_paste", 4.0, TABLESPOON, "Tomato paste")
        ing("vegetable_oil", 100.0, MILLILITRE, "Vegetable oil")
        ing("garlic", 3.0, CLOVE, "Garlic")
        ing("ginger", 1.0, TABLESPOON, "Ginger")
        ing("bay_leaf", 2.0, PIECE, "Bay leaves")
        ing("oregano", 1.0, TEASPOON, "Dried thyme")
        ing("garam_masala", 1.0, TEASPOON, "Curry powder")
        ing("salt", 2.0, TEASPOON, "Salt (or stock cubes)")
        ing("water", 3.0, CUP, "Water or stock")
        step("Blend tomatoes, peppers, one onion, scotch bonnet, garlic and ginger until smooth.")
        step("Fry the second onion (sliced) in oil, add tomato paste and fry 3 minutes until darkened.", 6.0)
        step("Add the blended base, bay, thyme, curry powder and salt; cook until reduced and the oil rises.", 15.0)
        step("Stir in rice and water, cover tightly (foil under the lid) and cook on low until the rice is tender.", 25.0)
        step("Let the bottom catch slightly for the smoky 'party' flavour, then fluff and serve.")
        safety(RICE_SAFETY)
    }
    recipe("misir_wat", "Misir wat", "ethiopian", "Ethiopian", 4, 10, 35, EASY) {
        description = "Red lentils slow-cooked with lots of onion and berbere spice."
        equipment("Heavy saucepan")
        tags("vegan", "gluten-free", "budget")
        ing("onion", 2.0, PIECE, "Red onions", "very finely chopped")
        ing("vegetable_oil", 4.0, TABLESPOON, "Oil (or niter kibbeh)")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("garam_masala", 2.0, TABLESPOON, "Berbere spice")
        ing("tomato_paste", 2.0, TABLESPOON, "Tomato paste")
        ing("lentils_red", 1.0, CUP, "Red lentils", "rinsed")
        ing("water", 3.5, CUP, "Water")
        ing("salt", 1.0, TEASPOON, "Salt")
        step("Cook the onions in a dry pan until softened, then add the oil and continue until deep golden.", 12.0)
        step("Add garlic, ginger, berbere and tomato paste; cook 2 minutes.", 2.0)
        step("Add lentils and water; simmer, stirring, until thick and creamy.", 20.0)
        step("Season and serve with injera.")
    }

    // ================================================================ East & Southeast Asia
    recipe("egg_fried_rice", "Egg fried rice", "chinese", "Cantonese home-style", 2, 5, 10, EASY) {
        description = "Day-old rice stir-fried hot with egg and spring onions."
        equipment("Wok or large frying pan")
        tags("vegetarian", "quick", "leftovers", "dairy-free")
        ing("rice_white", 400.0, GRAM, "Cooked rice, cold (day-old)", cooked = true)
        ing("egg", 3.0, PIECE, "Eggs", "beaten")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("scallion", 3.0, PIECE, "Spring onions", "sliced")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("soy_sauce", 1.5, TABLESPOON, "Light soy sauce")
        ing("sesame_oil", 1.0, TEASPOON, "Sesame oil")
        ing("peas", 0.5, CUP, "Frozen peas", optional = true)
        step("Heat the wok until smoking. Add oil, then the eggs; scramble until just set and push aside.", 1.0)
        step("Add garlic and the white parts of the spring onions for 20 seconds.", 0.5)
        step("Add the rice, breaking up clumps, and toss over high heat until hot and slightly toasted.", 4.0)
        step("Season with soy sauce, mix the egg back in, finish with sesame oil and spring onion greens.")
        safety(RICE_SAFETY)
        storage = "Refrigerate promptly; eat within 1 day."
    }
    recipe("kimchi_fried_rice", "Kimchi bokkeumbap (kimchi fried rice)", "korean", "Korean home-style", 2, 5, 10, EASY) {
        description = "Leftover rice fried with chopped kimchi and gochujang, topped with a fried egg."
        equipment("Frying pan")
        tags("quick", "leftovers", "spicy")
        ing("rice_glutinous", 400.0, GRAM, "Cooked short-grain rice, cold", cooked = true)
        ing("kimchi", 1.0, CUP, "Well-fermented kimchi", "chopped, plus 2 tbsp juice")
        ing("gochujang", 1.0, TABLESPOON, "Gochujang")
        ing("vegetable_oil", 1.5, TABLESPOON, "Oil")
        ing("sesame_oil", 1.0, TEASPOON, "Sesame oil")
        ing("egg", 2.0, PIECE, "Eggs")
        ing("scallion", 2.0, PIECE, "Spring onions", "sliced")
        ing("sesame_seeds", 1.0, TEASPOON, "Sesame seeds")
        step("Fry the kimchi in oil until slightly caramelised.", 3.0)
        step("Add gochujang and kimchi juice, then the rice; stir-fry until well coated and hot.", 4.0)
        step("Finish with sesame oil. Fry the eggs separately.", 3.0)
        step("Serve topped with an egg, spring onions and sesame seeds.")
        safety(RICE_SAFETY, EGG_SAFETY)
        storage = "Best eaten fresh; refrigerate leftovers for 1 day."
    }
    recipe("sigeumchi_namul_bowl", "Spinach namul and egg rice bowl", "korean", "Korean home-style", 2, 10, 10, EASY) {
        origin = RecipeOrigin.EDITORIAL_VARIATION
        variantOf = "bibimbap"
        variantSummary = "A pared-down bibimbap-style bowl using just seasoned spinach (sigeumchi namul), egg and rice."
        description = "Seasoned blanched spinach and a fried egg over rice — a simple bowl in the spirit of bibimbap."
        equipment("Pot", "Frying pan")
        tags("vegetarian", "quick", "leftovers", "dairy-free")
        ing("spinach", 300.0, GRAM, "Spinach")
        ing("sesame_oil", 2.0, TEASPOON, "Sesame oil")
        ing("soy_sauce", 2.0, TEASPOON, "Soy sauce")
        ing("garlic", 1.0, CLOVE, "Garlic", "minced")
        ing("sesame_seeds", 1.0, TEASPOON, "Sesame seeds")
        ing("rice_glutinous", 400.0, GRAM, "Cooked short-grain rice", cooked = true)
        ing("egg", 2.0, PIECE, "Eggs")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("gochujang", 1.0, TABLESPOON, "Gochujang", optional = true)
        step("Blanch the spinach 30 seconds, rinse in cold water and squeeze dry.", 1.0)
        step("Season with sesame oil, soy sauce, garlic and sesame seeds.")
        step("Fry the eggs sunny-side up.", 3.0)
        step("Top warm rice with spinach and egg; add gochujang if you like.")
        safety(EGG_SAFETY, RICE_SAFETY)
    }
    recipe("gyeran_mari", "Gyeran-mari (rolled omelette)", "korean", "Korean", 2, 5, 10, EASY) {
        description = "Thin layers of seasoned egg rolled into a soft omelette log, a common banchan and lunchbox dish."
        equipment("Non-stick frying pan (rectangular if available)", "Spatula")
        tags("vegetarian", "gluten-free", "dairy-free", "quick")
        ing("egg", 4.0, PIECE, "Eggs")
        ing("scallion", 1.0, PIECE, "Spring onion", "finely chopped")
        ing("carrot", 0.25, PIECE, "Carrot", "finely diced")
        ing("salt", 1.0, PINCH, "Salt")
        ing("vegetable_oil", 1.0, TEASPOON, "Oil")
        step("Beat the eggs with salt, spring onion and carrot.")
        step("Heat a lightly oiled pan on medium-low; pour in a thin layer. When almost set, roll it to one end.", 2.0)
        step("Pour in another thin layer, letting it flow under the roll; roll back. Repeat until the egg is used.", 6.0)
        step("Rest a minute, then slice into thick rounds.")
        safety(EGG_SAFETY)
    }
    recipe("chicken_adobo", "Chicken adobo", "filipino", "Tagalog", 4, 10, 40, EASY) {
        description = "Chicken braised in vinegar, soy sauce, garlic, bay and black pepper until glossy."
        equipment("Lidded pot")
        tags("high-protein", "dairy-free")
        ing("chicken_curry_cut", 1000.0, GRAM, "Chicken thighs and drumsticks")
        ing("soy_sauce", 0.33, CUP, "Soy sauce")
        ing("vinegar", 0.33, CUP, "Cane or white vinegar")
        ing("garlic", 8.0, CLOVE, "Garlic", "crushed")
        ing("bay_leaf", 3.0, PIECE, "Bay leaves")
        ing("black_pepper", 1.0, TEASPOON, "Whole black peppercorns")
        ing("water", 0.5, CUP, "Water")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("brown_sugar", 1.0, TEASPOON, "Brown sugar", optional = true)
        step("Combine chicken, soy sauce, garlic, bay and pepper; marinate 30 minutes if time allows.")
        step("Brown the chicken in oil, add the marinade, vinegar and water; simmer uncovered for 5 minutes without stirring (lets the vinegar mellow).", 5.0)
        step("Cover and simmer until tender, then reduce the sauce until glossy.", 30.0)
        step("Serve with steamed rice.")
        safety(POULTRY_SAFETY)
    }

    // ================================================================ Latin America, Europe, North America
    recipe("tinga_de_pollo", "Tinga de pollo", "mexican", "Pueblan", 4, 10, 30, EASY) {
        description = "Shredded chicken simmered in a smoky tomato–onion sauce, for tacos and tostadas."
        equipment("Saucepan", "Blender")
        tags("high-protein", "dairy-free", "gluten-free")
        ing("chicken_breast", 600.0, GRAM, "Chicken breast")
        ing("tomato", 4.0, PIECE, "Tomatoes")
        ing("onion", 1.5, PIECE, "Onions", "half for the sauce, the rest sliced")
        ing("garlic", 2.0, CLOVE, "Garlic")
        ing("chili_powder", 2.0, TEASPOON, "Chipotle or ancho chilli powder")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("tortilla_corn", 8.0, PIECE, "Corn tortillas", optional = true)
        step("Poach the chicken in salted water until cooked; shred.", 15.0)
        step("Blend tomatoes, half an onion, garlic, chilli and oregano.")
        step("Soften the sliced onion in oil, add the sauce and simmer 5 minutes; stir in the chicken and cook until thick.", 10.0)
        step("Serve in warm tortillas or on tostadas.")
        safety(POULTRY_SAFETY)
    }
    recipe("frittata_spinach_cheese", "Spinach and cheese frittata", "italian", "Italian home-style", 4, 10, 15, EASY) {
        description = "A thick open omelette with spinach and cheese, finished in the oven; good warm or cold."
        equipment("Oven-safe frying pan")
        tags("vegetarian", "gluten-free", "high-protein", "quick")
        ing("egg", 8.0, PIECE, "Eggs")
        ing("spinach", 150.0, GRAM, "Spinach")
        ing("cheddar", 80.0, GRAM, "Cheese (cheddar, feta or parmesan)", "grated or crumbled")
        ing("onion", 1.0, PIECE, "Onion", "sliced")
        ing("olive_oil", 1.0, TABLESPOON, "Olive oil")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("black_pepper", 0.25, TEASPOON, "Black pepper")
        step("Heat the oven to 200 °C. Soften the onion in oil, add spinach and wilt.", 6.0, 200)
        step("Beat eggs with salt, pepper and most of the cheese; pour into the pan and cook on low until the edges set.", 4.0)
        step("Top with remaining cheese and bake until just set in the centre.", 8.0, 200)
        safety(EGG_SAFETY)
    }
    recipe("spinach_egg_cheese_rice", "Spinach, egg and cheese rice skillet", "international", null, 2, 5, 12, EASY) {
        origin = RecipeOrigin.EDITORIAL_VARIATION
        variantOf = "egg_fried_rice"
        variantSummary = "A fusion take on egg fried rice for leftover rice, adding spinach and melted cheese."
        description = "A quick one-pan dinner from leftover rice, eggs, spinach and cheese. A fusion dish, not a traditional recipe."
        equipment("Frying pan with lid")
        tags("vegetarian", "quick", "leftovers")
        ing("rice_white", 350.0, GRAM, "Cooked rice, cold", cooked = true)
        ing("egg", 3.0, PIECE, "Eggs", "beaten")
        ing("spinach", 100.0, GRAM, "Spinach", "roughly chopped")
        ing("cheddar", 50.0, GRAM, "Cheese", "grated")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("olive_oil", 1.0, TABLESPOON, "Oil")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("chili_powder", 0.25, TEASPOON, "Chilli flakes", optional = true)
        step("Fry garlic in oil, add spinach and wilt.", 2.0)
        step("Add the rice and salt; fry until hot and lightly crisp.", 4.0)
        step("Pour over the eggs, stir gently until nearly set, scatter cheese, cover and cook until melted.", 3.0)
        safety(RICE_SAFETY, EGG_SAFETY)
        storage = "Eat within 1 day."
    }
    recipe("horiatiki", "Horiatiki (Greek village salad)", "greek", "National", 4, 15, 0, EASY) {
        description = "Chunky tomatoes, cucumber, onion and peppers with olives, a slab of feta and good olive oil."
        equipment("Large bowl")
        tags("vegetarian", "gluten-free", "no-cook", "salad")
        ing("tomato", 4.0, PIECE, "Ripe tomatoes", "wedges")
        ing("cucumber", 1.0, PIECE, "Cucumber", "thick slices")
        ing("onion", 0.5, PIECE, "Red onion", "thinly sliced")
        ing("bell_pepper", 1.0, PIECE, "Green pepper", "rings")
        ing("feta", 200.0, GRAM, "Feta", "one slab")
        ing("olive_oil", 4.0, TABLESPOON, "Extra-virgin olive oil")
        ing("vinegar", 1.0, TABLESPOON, "Red wine vinegar")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("salt", 0.5, TEASPOON, "Salt")
        step("Combine the vegetables with salt.")
        step("Top with the feta, sprinkle oregano, and dress with olive oil and vinegar just before serving.")
        storage = "Best fresh; undressed vegetables keep 1 day refrigerated."
        reheating = null
    }
    recipe("pasta_pomodoro", "Pasta al pomodoro", "italian", "Roman / Neapolitan", 4, 5, 20, EASY) {
        description = "Pasta with a simple sauce of tomatoes, garlic, olive oil and basil."
        equipment("Large pot", "Frying pan")
        tags("vegetarian", "vegan", "quick", "budget")
        ing("pasta", 400.0, GRAM, "Spaghetti or penne")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("garlic", 3.0, CLOVE, "Garlic", "sliced")
        ing("tomato_canned", 1.0, QuantityUnit.CAN, "Whole peeled tomatoes", "crushed by hand")
        ing("basil", 0.5, CUP, "Fresh basil")
        ing("salt", 1.0, TEASPOON, "Salt for the sauce")
        ing("parmesan", 2.0, TABLESPOON, "Parmesan", optional = true)
        step("Gently fry garlic in olive oil until fragrant, add tomatoes and salt; simmer until thick.", 15.0)
        step("Boil the pasta in well-salted water until al dente; reserve a cup of pasta water.", 10.0)
        step("Toss pasta with sauce and basil, loosening with pasta water. Add parmesan if using.")
    }
    recipe("chicken_potato_traybake", "Chicken, potato and tomato traybake", "british_irish", "British home-style", 4, 15, 45, EASY) {
        description = "Chicken thighs roasted on one tray with potatoes, onions, tomatoes, garlic and herbs."
        equipment("Large roasting tray")
        tags("high-protein", "one-pan", "gluten-free", "dairy-free")
        ing("chicken_thigh", 700.0, GRAM, "Boneless chicken thighs")
        ing("potato", 600.0, GRAM, "Potatoes", "quartered")
        ing("onion", 2.0, PIECE, "Red onions", "wedges")
        ing("tomato", 3.0, PIECE, "Tomatoes", "halved")
        ing("garlic", 6.0, CLOVE, "Garlic", "unpeeled")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("oregano", 2.0, TEASPOON, "Dried thyme or oregano")
        ing("paprika", 1.0, TEASPOON, "Paprika")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        step("Heat the oven to 210 °C. Toss potatoes and onions with half the oil, herbs and salt; roast 15 minutes.", 15.0, 210)
        step("Add chicken, tomatoes and garlic, drizzle with the rest of the oil and paprika; roast until the chicken is golden and cooked through.", 30.0, 210)
        safety(POULTRY_SAFETY)
    }
    recipe("bibimbap", "Bibimbap", "korean", "Jeonju (Jeolla)", 2, 30, 20) {
        description = "Rice topped with seasoned vegetables, beef, a fried egg and gochujang sauce, mixed at the table."
        equipment("Frying pan", "Pot")
        tags("balanced", "dairy-free")
        ing("rice_glutinous", 400.0, GRAM, "Cooked short-grain rice", cooked = true)
        ing("beef_stew", 150.0, GRAM, "Beef (ribeye or sirloin)", "thinly sliced")
        ing("soy_sauce", 2.0, TABLESPOON, "Soy sauce")
        ing("sugar", 1.0, TEASPOON, "Sugar")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("spinach", 150.0, GRAM, "Spinach")
        ing("bean_sprouts", 150.0, GRAM, "Soybean or mung bean sprouts")
        ing("carrot", 1.0, PIECE, "Carrot", "julienned")
        ing("zucchini", 0.5, PIECE, "Courgette", "julienned")
        ing("mushroom", 100.0, GRAM, "Shiitake mushrooms", "sliced")
        ing("egg", 2.0, PIECE, "Eggs")
        ing("sesame_oil", 1.5, TABLESPOON, "Sesame oil")
        ing("vegetable_oil", 1.5, TABLESPOON, "Oil")
        ing("gochujang", 2.0, TABLESPOON, "Gochujang")
        ing("sesame_seeds", 1.0, TEASPOON, "Sesame seeds")
        step("Marinate beef with 1 tbsp soy sauce, sugar and 1 clove garlic.")
        step("Blanch spinach and sprouts separately; season each with a little sesame oil, salt and garlic.", 3.0)
        step("Stir-fry carrot, courgette and mushrooms separately in a little oil.", 6.0)
        step("Stir-fry the beef until just cooked; fry the eggs sunny-side up.", 4.0)
        step("Arrange everything over the rice; mix gochujang with remaining sesame oil and soy sauce and serve on the side.")
        safety(EGG_SAFETY, RICE_SAFETY)
    }

    southAsianKitchen()
    eastAndSoutheastAsianKitchen()
    middleEastAndAfricanKitchen()
    europeanAndAmericasKitchen()
}.items

// ==================================================================== More South Asian dishes
private fun RecipeListBuilder.southAsianKitchen() {
    recipe("butter_chicken", "Butter chicken (murgh makhani)", "indian", "Punjabi / Delhi", 4, 25, 35) {
        description = "Marinated, charred chicken in a smooth tomato, butter and cream sauce scented with fenugreek leaves. Marinate for at least an hour, or overnight."
        equipment("Grill pan or oven tray", "Heavy pan", "Blender")
        tags("high-protein", "gluten-free", "dinner", "celebration")
        group("Marinade")
        ing("chicken_thigh", 700.0, GRAM, "Chicken thighs, boneless", "cut into large pieces")
        ing("yogurt", 100.0, GRAM, "Plain yogurt")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("lemon_juice", 1.0, TABLESPOON, "Lemon juice")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("Sauce")
        ing("butter", 3.0, TABLESPOON, "Butter")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("onion", 1.0, PIECE, "Onion", "chopped")
        ing("tomato_canned", 400.0, GRAM, "Tomatoes, canned or fresh", "puréed")
        ing("cashews", 30.0, GRAM, "Cashews", "soaked 20 minutes")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("fenugreek", 1.0, TEASPOON, "Dried fenugreek leaves (kasuri methi)", "crushed")
        ing("sugar", 1.0, TEASPOON, "Sugar")
        ing("cream_heavy", 100.0, MILLILITRE, "Cream")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("cilantro", 1.0, TABLESPOON, "Fresh coriander", "to garnish", optional = true)
        step("Mix the chicken with all the marinade ingredients. Cover and refrigerate for 1 hour, or overnight.")
        step("Grill or roast the chicken at high heat until charred at the edges and just cooked through.", 15.0, 230)
        step("Melt 2 tbsp butter with the oil; cook the onion until soft and golden.", 8.0)
        step("Add the tomatoes, drained cashews and chilli powder; simmer until thick, then blend until completely smooth.", 12.0)
        step("Return the sauce to the pan with the chicken, garam masala, crushed fenugreek, sugar and salt; simmer 5 minutes.", 5.0)
        step("Stir in the cream and the last tablespoon of butter. Serve with naan or rice.")
        safety(POULTRY_SAFETY)
    }
    recipe("butter_chicken_lighter", "Lighter butter chicken (variation)", "indian", "Punjabi / Delhi", 4, 25, 30) {
        origin = RecipeOrigin.EDITORIAL_VARIATION
        variantOf = "butter_chicken"
        variantSummary = "Chicken breast, a quarter of the butter, no cashews, and low-fat milk with Greek yogurt in place of cream."
        description = "A lighter weeknight take on butter chicken. It is not the traditional dish — the sauce is tangier and less rich."
        equipment("Grill pan or oven tray", "Heavy pan", "Blender")
        tags("high-protein", "lower-calorie", "gluten-free", "dinner")
        group("Marinade")
        ing("chicken_breast", 700.0, GRAM, "Chicken breast", "cut into large pieces")
        ing("greek_yogurt_nonfat", 100.0, GRAM, "Nonfat Greek yogurt")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("lemon_juice", 1.0, TABLESPOON, "Lemon juice")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("Sauce")
        ing("butter", 1.0, TABLESPOON, "Butter")
        ing("vegetable_oil", 1.0, TEASPOON, "Oil")
        ing("onion", 1.0, PIECE, "Onion", "chopped")
        ing("tomato_canned", 400.0, GRAM, "Tomatoes, canned", "puréed")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("fenugreek", 1.0, TEASPOON, "Dried fenugreek leaves (kasuri methi)", "crushed")
        ing("milk_low_fat", 120.0, MILLILITRE, "Low-fat milk")
        ing("greek_yogurt_nonfat", 3.0, TABLESPOON, "Nonfat Greek yogurt", "stirred in off the heat")
        ing("salt", 0.5, TEASPOON, "Salt")
        step("Marinate the chicken for at least 1 hour.")
        step("Grill or roast at high heat until charred at the edges and just cooked through.", 12.0, 230)
        step("Cook the onion in the butter and oil until soft; add tomatoes and chilli and simmer until thick, then blend.", 15.0)
        step("Add the chicken, garam masala, fenugreek, milk and salt; simmer 5 minutes.", 5.0)
        step("Take off the heat and stir in the Greek yogurt so it doesn't split.")
        safety(POULTRY_SAFETY)
    }
    recipe("chicken_korma", "Chicken korma", "indian", "Mughlai / Awadhi", 4, 20, 45) {
        description = "Chicken braised gently in yogurt with golden fried onions, ground almonds and whole spices — fragrant rather than fiery."
        equipment("Heavy lidded pot", "Blender or spice grinder")
        tags("gluten-free", "high-protein", "dinner", "celebration")
        ing("chicken_curry_cut", 1000.0, GRAM, "Chicken, bone-in, skinless", "curry-cut pieces")
        ing("vegetable_oil", 5.0, TABLESPOON, "Oil or ghee")
        ing("onion", 2.0, PIECE, "Onions", "thinly sliced")
        ing("yogurt", 200.0, GRAM, "Plain yogurt", "whisked")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("almonds", 40.0, GRAM, "Almonds or cashews", "blanched and ground")
        ing("cardamom", 4.0, PIECE, "Green cardamom pods")
        ing("cloves", 4.0, PIECE, "Cloves")
        ing("cinnamon", 1.0, QuantityUnit.STICK, "Cinnamon stick")
        ing("bay_leaf", 2.0, PIECE, "Bay leaves")
        ing("coriander_seed", 2.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("saffron", 1.0, PINCH, "Saffron", "soaked in 2 tbsp warm milk", optional = true)
        step("Fry the onions in the oil until deep golden; lift them out, leaving the oil, and blend with 3 tbsp of the yogurt to a paste.", 15.0)
        step("Fry the whole spices in the same oil for 30 seconds, then the ginger and garlic for 1 minute.", 2.0)
        step("Add the chicken and sear lightly; add coriander, chilli and salt.", 6.0)
        step("Lower the heat, stir in the remaining yogurt a spoonful at a time so it doesn't curdle, then the onion paste and ground almonds.")
        step("Cover and cook gently until the chicken is tender, adding a splash of water if it catches.", 25.0)
        step("Finish with garam masala and saffron if using. Serve with naan or rice.")
        safety(POULTRY_SAFETY)
    }
    recipe("beef_nihari", "Beef nihari", "pakistani", "Karachi / Muhajir", 6, 20, 240, Difficulty.INVOLVED) {
        description = "Beef (traditionally shank) slow-cooked overnight-style in a rich, spiced gravy thickened with flour and finished with ginger, chillies and lemon."
        equipment("Large heavy pot with lid")
        tags("high-protein", "celebration", "dinner")
        ing("beef_stew", 1000.0, GRAM, "Beef shank or stewing beef", "large pieces")
        ing("vegetable_oil", 6.0, TABLESPOON, "Oil")
        ing("ghee", 2.0, TABLESPOON, "Ghee")
        ing("onion", 1.0, PIECE, "Onion", "thinly sliced")
        ing("ginger", 2.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 2.0, TABLESPOON, "Garlic", "paste")
        ing("coriander_seed", 2.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 2.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("fennel_seed", 1.0, TEASPOON, "Fennel seeds", "ground")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala (or nihari masala)")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("cardamom", 4.0, PIECE, "Green cardamom pods")
        ing("cloves", 4.0, PIECE, "Cloves")
        ing("bay_leaf", 2.0, PIECE, "Bay leaves")
        ing("atta", 4.0, TABLESPOON, "Whole-wheat flour (atta)", "mixed with 1 cup water")
        ing("water", 2000.0, MILLILITRE, "Water")
        ing("salt", 2.0, TEASPOON, "Salt")
        group("To serve")
        ing("ginger", 1.0, PIECE, "Ginger", "julienned")
        ing("green_chili", 3.0, PIECE, "Green chillies", "sliced")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander", "chopped")
        ing("lemon", 1.0, PIECE, "Lemon", "wedges", optional = true)
        step("Heat the oil and ghee; fry the onion until golden, then lift out half for garnish.", 12.0)
        step("Add the beef, ginger and garlic and sear until the meat changes colour.", 8.0)
        step("Stir in all the ground and whole spices and salt; fry 2 minutes, adding a splash of water so they don't burn.", 2.0)
        step("Add the water, bring to the boil, cover and simmer on the lowest heat until the meat is very tender.", 210.0)
        step("Stir in the flour slurry and simmer, stirring, until the gravy is silky and thick.", 15.0)
        step("Serve topped with fried onion, ginger, chillies, coriander and lemon, with naan.")
        safety(BEEF_SAFETY)
    }
    recipe("haleem", "Haleem (simplified home style)", "pakistani", "Karachi / Muhajir", 8, 30, 180, Difficulty.INVOLVED) {
        description = "A simplified one-pot haleem: beef, cracked wheat and mixed lentils cooked until they melt together, topped with fried onions, ginger and mint. Traditional haleem uses whole wheat berries and barley and is stirred for many hours."
        equipment("Large heavy pot", "Hand blender or wooden masher")
        tags("high-protein", "celebration", "ramadan")
        ing("beef_stew", 750.0, GRAM, "Boneless beef", "cubed")
        ing("bulgur", 150.0, GRAM, "Cracked wheat or bulgur", "soaked 1 hour")
        ing("chana_dal", 60.0, GRAM, "Chana dal")
        ing("lentils_red", 40.0, GRAM, "Masoor dal")
        ing("moong_dal", 40.0, GRAM, "Moong dal")
        ing("urad_dal", 40.0, GRAM, "Urad dal")
        ing("rice_white", 30.0, GRAM, "Rice")
        ing("vegetable_oil", 8.0, TABLESPOON, "Oil or ghee")
        ing("onion", 2.0, PIECE, "Onions", "thinly sliced")
        ing("ginger", 2.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 2.0, TABLESPOON, "Garlic", "paste")
        ing("chili_powder", 2.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 1.0, TEASPOON, "Turmeric")
        ing("coriander_seed", 1.0, TABLESPOON, "Ground coriander")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala")
        ing("water", 2500.0, MILLILITRE, "Water")
        ing("salt", 2.5, TEASPOON, "Salt")
        group("To serve")
        ing("ginger", 1.0, PIECE, "Ginger", "julienned")
        ing("mint", 1.0, QuantityUnit.HANDFUL, "Mint leaves")
        ing("green_chili", 3.0, PIECE, "Green chillies", "sliced")
        ing("lemon", 1.0, PIECE, "Lemon", "wedges", optional = true)
        step("Fry the onions in the oil until crisp and brown; set aside half for garnish.", 15.0)
        step("Add the beef, ginger, garlic and spices to the remaining onions and oil; fry until well browned.", 10.0)
        step("Add 1 litre of water and simmer, covered, until the beef falls apart.", 90.0)
        step("Meanwhile, boil the wheat, lentils and rice in the remaining water until completely soft.", 60.0)
        step("Shred the beef, combine everything and cook on low heat, mashing and stirring often, until thick and stretchy.", 30.0)
        step("Serve with fried onions, ginger, mint, chillies and lemon.")
        safety(BEEF_SAFETY)
    }
    recipe("dal_makhani", "Dal makhani", "indian", "Punjabi", 6, 15, 120, Difficulty.MEDIUM) {
        description = "Whole black lentils and kidney beans simmered slowly with tomato, butter and cream until velvety. Soak the lentils and beans overnight."
        equipment("Pressure cooker or heavy pot")
        tags("vegetarian", "gluten-free", "dinner")
        ing("urad_dal", 200.0, GRAM, "Whole black lentils (sabut urad)", "soaked overnight")
        ing("kidney_beans", 50.0, GRAM, "Dried kidney beans (rajma)", "soaked overnight")
        ing("water", 1500.0, MILLILITRE, "Water")
        ing("butter", 4.0, TABLESPOON, "Butter")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("tomato_canned", 300.0, GRAM, "Tomato purée")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("fenugreek", 1.0, TEASPOON, "Dried fenugreek leaves (kasuri methi)")
        ing("cream_heavy", 100.0, MILLILITRE, "Cream")
        ing("salt", 1.5, TEASPOON, "Salt")
        step("Boil the drained lentils and beans in fresh water until completely soft (or pressure-cook 25 minutes). Kidney beans must boil hard for at least 10 minutes.", 60.0)
        step("Melt 3 tbsp butter; fry the ginger and garlic, then add tomato purée and chilli and cook until thick.", 12.0)
        step("Add the cooked lentils with their liquid and salt; simmer on low heat, stirring and mashing a little, until creamy.", 45.0)
        step("Stir in garam masala, crushed fenugreek, cream and the remaining butter.")
        safety("Dried kidney beans contain a natural toxin destroyed only by boiling hard for at least 10 minutes; slow cookers alone are not enough.")
    }
    recipe("rajma", "Rajma (kidney bean curry)", "indian", "Punjabi", 4, 15, 75) {
        description = "Kidney beans simmered in a thick onion-tomato masala; the classic Punjabi comfort meal with rice. Soak the beans overnight."
        equipment("Pressure cooker or heavy pot")
        tags("vegan", "gluten-free", "high-fibre", "dinner")
        ing("kidney_beans", 250.0, GRAM, "Dried kidney beans", "soaked overnight")
        ing("vegetable_oil", 3.0, TABLESPOON, "Oil")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds")
        ing("onion", 2.0, PIECE, "Onions", "finely chopped")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("tomato_canned", 400.0, GRAM, "Tomatoes", "puréed")
        ing("coriander_seed", 2.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        step("Boil the soaked beans hard for 10 minutes, then simmer (or pressure-cook) until creamy inside.", 50.0)
        step("Fry cumin in the oil, then onions until golden, then ginger and garlic.", 12.0)
        step("Add tomatoes and powdered spices; cook until the oil separates.", 10.0)
        step("Add the beans with some cooking liquid and simmer, mashing a few beans to thicken.", 15.0)
        step("Finish with garam masala and coriander. Serve with rice.")
        safety("Dried kidney beans contain a natural toxin destroyed only by boiling hard for at least 10 minutes; slow cookers alone are not enough.")
    }
    recipe("aloo_gobi", "Aloo gobi", "indian", "Punjabi", 4, 15, 30, EASY) {
        description = "Potatoes and cauliflower cooked dry with cumin, turmeric, ginger and green chilli."
        equipment("Wide lidded pan")
        tags("vegan", "gluten-free", "dinner")
        ing("potato", 400.0, GRAM, "Potatoes", "cut into chunks")
        ing("cauliflower", 500.0, GRAM, "Cauliflower", "florets")
        ing("vegetable_oil", 3.0, TABLESPOON, "Oil")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds")
        ing("onion", 1.0, PIECE, "Onion", "chopped")
        ing("tomato", 1.0, PIECE, "Tomato", "chopped")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "grated")
        ing("green_chili", 2.0, PIECE, "Green chillies", "slit")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("coriander_seed", 1.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 0.5, TEASPOON, "Red chilli powder")
        ing("garam_masala", 0.5, TEASPOON, "Garam masala")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        step("Fry cumin in the oil, then the onion until soft, then ginger and green chillies.", 6.0)
        step("Add potatoes, turmeric, coriander, chilli and salt; cover and cook 10 minutes, stirring once.", 10.0)
        step("Add cauliflower and tomato; cover and cook on low heat until both are tender but holding their shape.", 12.0)
        step("Uncover, sprinkle with garam masala and coriander and let any moisture cook off.")
    }
    recipe("keema_matar", "Keema matar", "pakistani", "Punjabi", 4, 10, 35) {
        description = "Spiced minced beef cooked with peas, onions and tomatoes — a quick everyday dish with roti or rice."
        equipment("Heavy frying pan or karahi")
        tags("high-protein", "gluten-free", "dinner")
        ing("beef_ground", 500.0, GRAM, "Minced beef (or lamb)")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("onion", 2.0, PIECE, "Onions", "finely chopped")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "paste")
        ing("garlic", 1.0, TABLESPOON, "Garlic", "paste")
        ing("tomato", 2.0, PIECE, "Tomatoes", "chopped")
        ing("yogurt", 3.0, TABLESPOON, "Plain yogurt")
        ing("peas", 200.0, GRAM, "Peas, frozen or fresh")
        ing("coriander_seed", 2.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 1.0, TEASPOON, "Red chilli powder")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("green_chili", 2.0, PIECE, "Green chillies", "sliced")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        step("Fry onions in the oil until golden; add ginger and garlic.", 10.0)
        step("Add the mince and cook, breaking it up, until browned and the liquid has evaporated.", 10.0)
        step("Add tomatoes, yogurt, powdered spices and salt; cook until the masala is thick and the oil separates.", 10.0)
        step("Add the peas and green chillies; cook until the peas are tender.", 5.0)
        step("Finish with garam masala and coriander.")
        safety(MINCE_SAFETY)
    }
    recipe("sarson_ka_saag", "Sarson ka saag", "pakistani", "Punjabi", 4, 20, 60) {
        description = "Mustard greens and spinach slow-cooked and mashed with ginger, garlic and a little cornmeal, finished with ghee. Traditionally eaten with makki di roti."
        equipment("Large pot", "Wooden masher or hand blender")
        tags("vegetarian", "gluten-free", "winter")
        ing("mustard_greens", 500.0, GRAM, "Mustard greens (sarson)", "chopped")
        ing("spinach", 250.0, GRAM, "Spinach", "chopped")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "chopped")
        ing("garlic", 6.0, CLOVE, "Garlic")
        ing("green_chili", 3.0, PIECE, "Green chillies")
        ing("water", 300.0, MILLILITRE, "Water")
        ing("cornmeal", 30.0, GRAM, "Cornmeal (makki ka atta)")
        ing("ghee", 3.0, TABLESPOON, "Ghee")
        ing("onion", 1.0, PIECE, "Onion", "finely chopped")
        ing("salt", 1.25, TEASPOON, "Salt")
        ing("butter", 1.0, TABLESPOON, "Butter", "to serve", optional = true)
        step("Simmer the greens with ginger, half the garlic, chillies, salt and the water until very soft.", 40.0)
        step("Mash or blend coarsely; whisk in the cornmeal and cook, stirring, until thick.", 10.0)
        step("Heat the ghee; fry the onion and remaining garlic until golden and pour over the saag.", 8.0)
        step("Serve with a knob of butter and makki di roti.")
    }
    recipe("raita", "Cucumber and mint raita", "pakistani", null, 3, 10, 0, EASY) {
        description = "Cool yogurt with grated cucumber, mint and roasted cumin, served alongside spicy dishes."
        equipment("Box grater", "Bowl", "Small dry pan")
        tags("vegetarian", "gluten-free", "side", "quick")
        storage = "Refrigerate and eat within 1 day."
        reheating = null
        ing("yogurt", 450.0, GRAM, "Plain yogurt", "whisked")
        ing("cucumber", 150.0, GRAM, "Cucumber", "grated and squeezed")
        ing("mint", 1.0, TABLESPOON, "Mint", "chopped")
        ing("cumin", 1.0, TEASPOON, "Cumin seeds", "dry-roasted and crushed")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("chili_powder", 1.0, PINCH, "Chilli powder", optional = true)
        step("Whisk the yogurt until smooth and stir in the cucumber, mint, cumin and salt.")
        step("Chill until serving; dust with chilli if you like.")
    }
    recipe("kheer", "Kheer (rice pudding)", "pakistani", null, 6, 10, 60, EASY) {
        description = "Rice simmered slowly in milk with sugar and cardamom until thick and creamy, topped with nuts."
        equipment("Heavy-based pot")
        tags("vegetarian", "gluten-free", "dessert", "eid")
        ing("milk_whole", 1000.0, MILLILITRE, "Whole milk")
        ing("rice_white", 60.0, GRAM, "Basmati rice", "rinsed and soaked 30 minutes")
        ing("sugar", 80.0, GRAM, "Sugar")
        ing("cardamom", 4.0, PIECE, "Green cardamom pods", "lightly crushed")
        ing("almonds", 20.0, GRAM, "Almonds", "slivered")
        ing("pistachios", 15.0, GRAM, "Pistachios", "chopped")
        ing("saffron", 1.0, PINCH, "Saffron", optional = true)
        ing("raisins", 2.0, TABLESPOON, "Raisins", optional = true)
        step("Bring the milk to the boil in a heavy pot, add the drained rice and cardamom, and simmer on low heat, stirring often so it doesn't stick.", 45.0)
        step("When the rice is very soft and the milk has thickened, stir in the sugar and saffron and cook 5 minutes more.", 5.0)
        step("Serve warm or chilled, topped with almonds and pistachios.")
        storage = "Refrigerate within 2 hours; eat within 2 days."
    }
}

// ==================================================================== East & Southeast Asia
private fun RecipeListBuilder.eastAndSoutheastAsianKitchen() {
    recipe("pad_thai", "Pad Thai", "thai", "Central", 2, 20, 10) {
        description = "Stir-fried rice noodles with chicken, tofu and egg in a sweet-sour tamarind sauce, finished with bean sprouts, peanuts and lime."
        equipment("Wok or large frying pan")
        tags("dairy-free", "gluten-free", "dinner")
        ing("rice_noodles", 170.0, GRAM, "Flat rice noodles, dried", "soaked in warm water 20 minutes")
        ing("chicken_breast", 200.0, GRAM, "Chicken breast (or prawns)", "thinly sliced")
        ing("tofu_firm", 100.0, GRAM, "Firm tofu", "small cubes")
        ing("egg", 2.0, PIECE, "Eggs")
        ing("vegetable_oil", 2.5, TABLESPOON, "Oil")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        group("Sauce")
        ing("tamarind", 2.0, TABLESPOON, "Tamarind paste")
        ing("fish_sauce", 2.0, TABLESPOON, "Fish sauce")
        ing("brown_sugar", 1.5, TABLESPOON, "Palm or brown sugar")
        group("To finish")
        ing("bean_sprouts", 100.0, GRAM, "Bean sprouts")
        ing("scallion", 3.0, PIECE, "Spring onions", "cut into lengths")
        ing("peanuts", 30.0, GRAM, "Roasted peanuts", "chopped")
        ing("lime", 1.0, PIECE, "Lime", "wedges")
        ing("chili_powder", 0.5, TEASPOON, "Chilli flakes", optional = true)
        step("Stir the tamarind, fish sauce and sugar together until dissolved.")
        step("Heat the oil in a wok over high heat; stir-fry the garlic, chicken and tofu until the chicken is cooked.", 4.0)
        step("Push everything aside, scramble the eggs in the space, then add the drained noodles and the sauce; toss until the noodles soften and absorb it.", 4.0)
        step("Add bean sprouts and spring onions and toss for 30 seconds.", 1.0)
        step("Serve with peanuts, lime and chilli on top.")
        safety(POULTRY_SAFETY, EGG_SAFETY)
    }
    recipe("green_curry_chicken", "Thai green curry with chicken", "thai", "Central", 4, 25, 20) {
        description = "Chicken and aubergine simmered in coconut milk with a fresh green curry paste of chillies, lemongrass, galangal and coriander, finished with basil. A good store-bought paste works too (use about 3 tbsp)."
        equipment("Blender or mortar and pestle", "Wok or saucepan")
        tags("dairy-free", "gluten-free", "dinner")
        group("Curry paste")
        ing("green_chili", 8.0, PIECE, "Green bird's eye chillies")
        ing("lemongrass", 2.0, PIECE, "Lemongrass stalks", "tender part, sliced")
        ing("garlic", 4.0, CLOVE, "Garlic")
        ing("onion", 70.0, GRAM, "Shallots or onion")
        ing("ginger", 1.0, TABLESPOON, "Galangal or ginger", "sliced")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Coriander, with stems")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("coriander_seed", 1.0, TEASPOON, "Ground coriander")
        ing("lime", 1.0, TABLESPOON, "Lime juice")
        ing("black_pepper", 0.5, TEASPOON, "White or black pepper")
        group("Curry")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("coconut_milk", 400.0, MILLILITRE, "Coconut milk")
        ing("chicken_broth", 200.0, MILLILITRE, "Chicken stock or water")
        ing("chicken_thigh", 500.0, GRAM, "Chicken thighs, boneless", "sliced")
        ing("eggplant", 200.0, GRAM, "Thai or regular aubergine", "chunks")
        ing("fish_sauce", 2.0, TABLESPOON, "Fish sauce")
        ing("brown_sugar", 1.0, TABLESPOON, "Palm or brown sugar")
        ing("basil", 0.5, CUP, "Thai basil leaves")
        ing("lime", 2.0, PIECE, "Makrut lime leaves", "torn", optional = true)
        step("Blend all the paste ingredients to a smooth paste with a splash of water.", 3.0)
        step("Fry the paste in the oil with a few spoonfuls of the thick coconut cream until fragrant and the oil separates.", 4.0)
        step("Add the chicken and stir to coat, then the rest of the coconut milk, stock, aubergine, fish sauce and sugar.", 2.0)
        step("Simmer until the chicken is cooked and the aubergine tender.", 12.0)
        step("Stir in the basil and lime leaves. Serve with jasmine rice.")
        safety(POULTRY_SAFETY)
    }
    recipe("tom_kha_gai", "Tom kha gai (coconut chicken soup)", "thai", "Central", 4, 15, 20, EASY) {
        description = "A fragrant coconut-milk soup with chicken, lemongrass, galangal and mushrooms, balanced with lime and fish sauce."
        equipment("Saucepan")
        tags("dairy-free", "gluten-free", "soup")
        ing("chicken_broth", 500.0, MILLILITRE, "Chicken stock")
        ing("coconut_milk", 400.0, MILLILITRE, "Coconut milk")
        ing("lemongrass", 3.0, PIECE, "Lemongrass stalks", "bruised, cut into lengths")
        ing("ginger", 30.0, GRAM, "Galangal or ginger", "sliced")
        ing("chicken_breast", 400.0, GRAM, "Chicken breast", "thinly sliced")
        ing("mushroom", 150.0, GRAM, "Mushrooms (oyster or straw)", "sliced")
        ing("fish_sauce", 2.5, TABLESPOON, "Fish sauce")
        ing("lime", 3.0, TABLESPOON, "Lime juice")
        ing("brown_sugar", 1.0, TEASPOON, "Palm or brown sugar")
        ing("green_chili", 3.0, PIECE, "Bird's eye chillies", "bruised")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        ing("lime", 3.0, PIECE, "Makrut lime leaves", "torn", optional = true)
        step("Simmer the stock with lemongrass, galangal and lime leaves for 5 minutes.", 5.0)
        step("Add the coconut milk and bring back to a gentle simmer (don't boil hard, or it can split).", 3.0)
        step("Add the chicken and mushrooms; simmer until the chicken is cooked through.", 8.0)
        step("Off the heat, season with fish sauce, lime juice and sugar; add the chillies and coriander.")
        safety(POULTRY_SAFETY)
    }
    recipe("chicken_teriyaki", "Chicken teriyaki", "japanese", "Kanto", 2, 5, 15, EASY) {
        description = "Pan-fried chicken thighs glazed in a glossy soy and sugar sauce. Mirin and sake are traditional; the sugar version below works without them."
        equipment("Frying pan")
        tags("high-protein", "dairy-free", "dinner", "quick")
        ing("chicken_thigh", 400.0, GRAM, "Chicken thighs, boneless (skin on if you like)")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("soy_sauce", 3.0, TABLESPOON, "Soy sauce")
        ing("sugar", 1.5, TABLESPOON, "Sugar")
        ing("water", 2.0, TABLESPOON, "Water")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("white_wine", 2.0, TABLESPOON, "Mirin (replaces the water and half the sugar)", optional = true)
        ing("scallion", 1.0, PIECE, "Spring onion", "sliced")
        ing("sesame_seeds", 1.0, TEASPOON, "Sesame seeds", optional = true)
        step("Mix soy sauce, sugar, water (or mirin) and ginger.")
        step("Fry the chicken skin-side down in the oil over medium heat until golden, then turn and cook until nearly done.", 10.0)
        step("Wipe out excess fat, pour in the sauce and simmer, turning the chicken, until the sauce is thick and glossy.", 3.0)
        step("Slice and serve over rice with spring onion and sesame seeds.")
        safety(POULTRY_SAFETY)
    }
    recipe("miso_soup", "Miso soup with tofu and wakame", "japanese", null, 3, 5, 10, EASY) {
        description = "A light everyday soup of miso dissolved in stock with soft tofu, wakame and spring onion. Dashi stock is traditional; water gives a lighter, vegetarian version."
        equipment("Saucepan")
        tags("vegan", "quick", "soup")
        ing("water", 800.0, MILLILITRE, "Dashi stock or water")
        ing("miso", 3.0, TABLESPOON, "Miso paste")
        ing("tofu_firm", 200.0, GRAM, "Silken or firm tofu (about half a block)", "small cubes")
        ing("wakame", 20.0, GRAM, "Wakame", "rehydrated")
        ing("scallion", 2.0, PIECE, "Spring onions", "thinly sliced")
        step("Bring the stock to a gentle simmer and add the tofu and wakame.", 3.0)
        step("Take the pan off the heat; dissolve the miso in a ladle of the stock and stir it back in (boiling miso dulls its flavour).")
        step("Serve topped with spring onion.")
        storage = "Best eaten fresh; refrigerate within 2 hours and eat within 1 day."
    }
    recipe("oyakodon", "Oyakodon (chicken and egg rice bowl)", "japanese", "Kanto", 2, 10, 15, EASY) {
        description = "Chicken and onion simmered in a sweet soy broth, set with softly cooked egg and served over rice."
        equipment("Small frying pan with lid")
        tags("high-protein", "dairy-free", "dinner", "quick")
        ing("chicken_broth", 200.0, MILLILITRE, "Dashi or chicken stock")
        ing("soy_sauce", 2.0, TABLESPOON, "Soy sauce")
        ing("sugar", 1.0, TABLESPOON, "Sugar")
        ing("white_wine", 2.0, TABLESPOON, "Mirin", optional = true)
        ing("onion", 100.0, GRAM, "Onion", "thinly sliced")
        ing("chicken_thigh", 250.0, GRAM, "Chicken thigh, boneless", "bite-size pieces")
        ing("egg", 4.0, PIECE, "Eggs", "lightly beaten")
        ing("rice_glutinous", 400.0, GRAM, "Cooked short-grain rice", cooked = true)
        ing("scallion", 1.0, PIECE, "Spring onion", "sliced")
        step("Simmer stock, soy sauce, sugar (and mirin) with the onion until the onion softens.", 4.0)
        step("Add the chicken and simmer until cooked through.", 7.0)
        step("Pour in the eggs in a circle, cover and cook until just set but still soft.", 2.0)
        step("Slide over bowls of hot rice and top with spring onion.")
        safety(POULTRY_SAFETY, EGG_SAFETY, RICE_SAFETY)
    }
    recipe("bulgogi", "Beef bulgogi", "korean", "Seoul", 4, 15, 10) {
        description = "Thinly sliced beef marinated in soy, sugar, garlic and sesame oil, then cooked quickly over high heat. Grated pear in the marinade tenderises the beef."
        equipment("Large frying pan or grill pan")
        tags("high-protein", "dairy-free", "dinner")
        ing("beef_steak", 600.0, GRAM, "Beef sirloin or ribeye", "very thinly sliced")
        ing("soy_sauce", 4.0, TABLESPOON, "Soy sauce")
        ing("brown_sugar", 2.0, TABLESPOON, "Brown sugar")
        ing("apple", 0.5, PIECE, "Asian pear or apple", "grated", optional = true)
        ing("garlic", 4.0, CLOVE, "Garlic", "minced")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("sesame_oil", 1.5, TABLESPOON, "Sesame oil")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("onion", 1.0, PIECE, "Onion", "sliced")
        ing("scallion", 3.0, PIECE, "Spring onions", "cut into lengths")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("sesame_seeds", 1.0, TABLESPOON, "Toasted sesame seeds")
        step("Mix soy sauce, sugar, pear, garlic, ginger, sesame oil and pepper; marinate the beef with the onion for at least 30 minutes.")
        step("Cook in batches in a very hot oiled pan until caramelised at the edges.", 6.0)
        step("Toss with spring onions, sprinkle with sesame seeds and serve with rice and lettuce leaves.")
        safety(BEEF_SAFETY)
    }
    recipe("japchae", "Japchae (stir-fried glass noodles)", "korean", "Seoul", 4, 25, 15) {
        description = "Chewy sweet-potato glass noodles tossed with beef, spinach, carrot, mushrooms and onion in a soy and sesame dressing."
        equipment("Large frying pan or wok", "Pot")
        tags("dairy-free", "celebration")
        ing("glass_noodles", 200.0, GRAM, "Sweet-potato glass noodles (dangmyeon)")
        ing("beef_steak", 150.0, GRAM, "Beef sirloin", "thin strips")
        ing("spinach", 200.0, GRAM, "Spinach")
        ing("carrot", 1.0, PIECE, "Carrot", "julienned")
        ing("onion", 1.0, PIECE, "Onion", "sliced")
        ing("mushroom", 100.0, GRAM, "Shiitake mushrooms", "sliced")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("soy_sauce", 4.0, TABLESPOON, "Soy sauce")
        ing("sugar", 1.5, TABLESPOON, "Sugar")
        ing("sesame_oil", 2.0, TABLESPOON, "Sesame oil")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("sesame_seeds", 1.0, TABLESPOON, "Toasted sesame seeds")
        ing("egg", 1.0, PIECE, "Egg, fried thin and sliced, to garnish", optional = true)
        step("Boil the noodles until chewy-tender, rinse in cold water, drain and snip into shorter lengths.", 7.0)
        step("Blanch the spinach for 30 seconds, squeeze dry and season with a little sesame oil and garlic.", 1.0)
        step("Stir-fry onion, carrot and mushrooms separately in a little oil; stir-fry the beef with 1 tbsp soy sauce.", 8.0)
        step("Toss the noodles in the pan with the remaining soy sauce, sugar and sesame oil until glossy, then combine with everything.", 3.0)
        step("Finish with sesame seeds. Serve warm or at room temperature.")
        safety(BEEF_SAFETY)
    }
    recipe("pho_ga", "Phở gà (chicken pho)", "vietnamese", "Northern (Hanoi)", 4, 20, 90) {
        description = "A clear chicken broth perfumed with charred onion and ginger, star anise and cinnamon, poured over rice noodles and shredded chicken with fresh herbs."
        equipment("Large stock pot", "Fine sieve")
        tags("dairy-free", "gluten-free", "soup")
        group("Broth")
        ing("chicken_curry_cut", 1200.0, GRAM, "Chicken pieces, bone-in")
        ing("water", 3000.0, MILLILITRE, "Water")
        ing("onion", 2.0, PIECE, "Onions", "halved and charred")
        ing("ginger", 60.0, GRAM, "Ginger", "halved and charred")
        ing("star_anise", 3.0, PIECE, "Star anise")
        ing("cinnamon", 1.0, QuantityUnit.STICK, "Cinnamon stick")
        ing("cloves", 4.0, PIECE, "Cloves")
        ing("coriander_seed", 1.0, TEASPOON, "Coriander seeds")
        ing("fish_sauce", 3.0, TABLESPOON, "Fish sauce")
        ing("sugar", 1.0, TABLESPOON, "Sugar (rock sugar is traditional)")
        ing("salt", 1.5, TEASPOON, "Salt")
        group("Bowls")
        ing("rice_noodles", 300.0, GRAM, "Flat rice noodles (bánh phở), dried", "about 75 g per bowl")
        ing("bean_sprouts", 150.0, GRAM, "Bean sprouts")
        ing("scallion", 4.0, PIECE, "Spring onions", "sliced")
        ing("cilantro", 1.0, QuantityUnit.BUNCH, "Coriander")
        ing("basil", 0.5, CUP, "Thai basil", optional = true)
        ing("lime", 2.0, PIECE, "Limes", "wedges")
        ing("green_chili", 2.0, PIECE, "Chillies", "sliced")
        step("Char the onion and ginger over a flame or under the grill until blackened in spots.", 8.0)
        step("Toast the star anise, cinnamon, cloves and coriander seeds in a dry pan until fragrant.", 2.0)
        step("Put the chicken in the water, bring to the boil and skim; add the charred aromatics and spices and simmer gently.", 45.0)
        step("Lift out the chicken, shred the meat and return the bones to simmer 30 minutes more. Strain and season with fish sauce, sugar and salt.", 30.0)
        step("Cook the noodles, divide into bowls with the chicken, ladle over boiling broth and serve with the herbs, sprouts, lime and chilli.")
        safety(POULTRY_SAFETY)
    }
    recipe("lemongrass_chicken_bowl", "Lemongrass chicken rice bowl with pickled vegetables", "vietnamese", "Southern (Saigon)", 4, 25, 20) {
        description = "Caramelised lemongrass chicken over rice with quick-pickled carrot and radish, cucumber and herbs — the flavours of a bánh mì in a bowl."
        equipment("Frying pan or grill", "Jar or bowl for pickles", "Pot for rice")
        tags("high-protein", "dairy-free", "gluten-free", "dinner")
        group("Chicken")
        ing("chicken_thigh", 600.0, GRAM, "Chicken thighs, boneless")
        ing("lemongrass", 2.0, PIECE, "Lemongrass stalks", "finely minced")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("fish_sauce", 2.0, TABLESPOON, "Fish sauce")
        ing("brown_sugar", 1.0, TABLESPOON, "Brown sugar")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        group("Quick pickles")
        ing("carrot", 2.0, PIECE, "Carrots", "julienned")
        ing("radish", 200.0, GRAM, "Daikon radish", "julienned")
        ing("vinegar", 4.0, TABLESPOON, "Rice vinegar")
        ing("sugar", 2.0, TABLESPOON, "Sugar")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("Bowls")
        ing("rice_white", 300.0, GRAM, "Jasmine rice")
        ing("cucumber", 1.0, PIECE, "Cucumber", "sliced")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Coriander")
        ing("green_chili", 1.0, PIECE, "Chilli", "sliced")
        ing("mayonnaise", 2.0, TABLESPOON, "Mayonnaise", optional = true)
        step("Toss carrot and daikon with vinegar, sugar and salt; leave to pickle while you cook.")
        step("Marinate the chicken with lemongrass, garlic, fish sauce, sugar, pepper and half the oil for at least 15 minutes.")
        step("Cook the rice.", 15.0)
        step("Fry or grill the chicken in the remaining oil until caramelised and cooked through, then slice.", 12.0)
        step("Build bowls of rice, chicken, drained pickles, cucumber, coriander and chilli.")
        safety(POULTRY_SAFETY, RICE_SAFETY)
    }
    recipe("kung_pao_chicken", "Kung pao chicken", "chinese", "Sichuan", 4, 15, 10) {
        description = "Diced chicken stir-fried with dried chillies, peanuts and spring onion in a glossy sweet, sour and savoury sauce. Add ground Sichuan pepper for the authentic tingle."
        equipment("Wok")
        tags("high-protein", "dairy-free", "dinner", "quick")
        ing("chicken_breast", 500.0, GRAM, "Chicken breast or thigh", "diced")
        ing("soy_sauce", 1.0, TABLESPOON, "Soy sauce (marinade)")
        ing("cornstarch", 1.0, TABLESPOON, "Cornstarch (marinade)")
        group("Sauce")
        ing("soy_sauce", 2.0, TABLESPOON, "Soy sauce")
        ing("vinegar", 1.5, TABLESPOON, "Chinese black vinegar (or rice vinegar)")
        ing("sugar", 1.0, TABLESPOON, "Sugar")
        ing("cornstarch", 1.0, TEASPOON, "Cornstarch")
        ing("water", 3.0, TABLESPOON, "Water")
        group("Stir-fry")
        ing("vegetable_oil", 3.0, TABLESPOON, "Oil")
        ing("chili_powder", 1.0, TEASPOON, "Dried red chillies (8–10) or chilli flakes")
        ing("garlic", 3.0, CLOVE, "Garlic", "sliced")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "sliced")
        ing("scallion", 4.0, PIECE, "Spring onions", "cut into short lengths")
        ing("peanuts", 60.0, GRAM, "Roasted peanuts")
        step("Toss the chicken with the marinade soy sauce and cornstarch. Mix the sauce ingredients.")
        step("Heat the oil in a wok; fry the chillies (and Sichuan pepper) for a few seconds until fragrant but not black.", 0.5)
        step("Add the chicken and stir-fry until almost cooked; add garlic, ginger and spring onions.", 5.0)
        step("Pour in the sauce and toss until glossy and the chicken is cooked; stir in the peanuts and serve with rice.", 2.0)
        safety(POULTRY_SAFETY)
    }
    recipe("tofu_vegetable_stir_fry", "Tofu and vegetable stir-fry", "chinese", "Cantonese", 3, 15, 12, EASY) {
        description = "Crisp-edged tofu with broccoli, peppers, carrot and mushrooms in a simple garlic, ginger and soy sauce."
        equipment("Wok or large frying pan")
        tags("vegan", "high-protein", "dinner", "quick")
        ing("tofu_firm", 400.0, GRAM, "Firm tofu", "pressed and cubed")
        ing("cornstarch", 1.0, TABLESPOON, "Cornstarch (for the tofu)")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("broccoli", 250.0, GRAM, "Broccoli", "small florets")
        ing("bell_pepper", 1.0, PIECE, "Red pepper", "sliced")
        ing("carrot", 1.0, PIECE, "Carrot", "thinly sliced")
        ing("mushroom", 150.0, GRAM, "Mushrooms", "sliced")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "grated")
        group("Sauce")
        ing("soy_sauce", 3.0, TABLESPOON, "Soy sauce")
        ing("sugar", 1.0, TEASPOON, "Sugar")
        ing("cornstarch", 1.0, TEASPOON, "Cornstarch")
        ing("water", 4.0, TABLESPOON, "Water")
        ing("sesame_oil", 1.0, TEASPOON, "Sesame oil")
        ing("scallion", 2.0, PIECE, "Spring onions", "sliced")
        step("Toss the tofu in cornstarch and fry in half the oil until golden on all sides; set aside.", 8.0)
        step("Stir-fry broccoli, carrot and pepper in the remaining oil with a splash of water until bright and just tender; add mushrooms, garlic and ginger.", 5.0)
        step("Return the tofu, pour in the mixed sauce and toss until glossy. Finish with spring onions; serve with rice.", 1.0)
    }
}

// ================================================================ Middle East & Africa
private fun RecipeListBuilder.middleEastAndAfricanKitchen() {
    recipe("chicken_shawarma", "Chicken shawarma (home oven)", "lebanese", "Levantine", 4, 20, 30) {
        description = "Spiced, yogurt-marinated chicken thighs roasted hot and sliced, wrapped in flatbread with tahini sauce and salad. A home oven stands in for the vertical spit."
        equipment("Mixing bowl", "Roasting tray", "Sharp knife")
        tags("high-protein", "dinner", "street food")
        group("Marinade")
        ing("chicken_thigh", 800.0, GRAM, "Chicken thighs, boneless and skinless")
        ing("yogurt", 100.0, GRAM, "Plain yogurt")
        ing("lemon_juice", 3.0, TABLESPOON, "Lemon juice")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("garlic", 4.0, CLOVE, "Garlic", "crushed")
        ing("cumin", 2.0, TEASPOON, "Ground cumin")
        ing("paprika", 2.0, TEASPOON, "Paprika")
        ing("allspice", 1.0, TEASPOON, "Ground allspice")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("cinnamon", 0.25, TEASPOON, "Ground cinnamon")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("salt", 1.5, TEASPOON, "Salt")
        group("To serve")
        ing("pita", 4.0, PIECE, "Pita or thin flatbreads")
        ing("tahini", 3.0, TABLESPOON, "Tahini", "whisked with lemon, garlic and water")
        ing("tomato", 2.0, PIECE, "Tomatoes", "sliced")
        ing("lettuce", 1.0, CUP, "Lettuce", "shredded")
        ing("onion", 0.5, PIECE, "Red onion", "thinly sliced", optional = true)
        step("Mix all the marinade ingredients, add the chicken and turn to coat. Marinate in the fridge for at least 1 hour (or overnight).", 60.0)
        step("Heat the oven to 220 °C. Spread the chicken on a tray in one layer.", tempC = 220)
        step("Roast until charred at the edges and cooked through.", 25.0, 220)
        step("Rest for 5 minutes, then slice thinly and toss in the pan juices.", 5.0)
        step("Thin the tahini with lemon juice and water to a pourable sauce. Wrap the chicken in warm flatbread with the sauce, tomato and lettuce.")
        safety(POULTRY_SAFETY)
    }
    recipe("falafel", "Falafel", "lebanese", "Levantine", 4, 25, 20) {
        description = "Crisp fritters of soaked (not cooked) chickpeas blended with herbs, onion and spices. Egyptian ta'ameya is made the same way with dried fava beans."
        equipment("Food processor", "Deep pan or wok", "Thermometer", "Slotted spoon")
        tags("vegan", "dairy-free", "street food")
        notes += "Start the chickpeas the night before: they must soak 12–24 hours. Canned chickpeas make falafel fall apart in the oil."
        ing("chickpeas_dry", 250.0, GRAM, "Dried chickpeas", "soaked overnight, drained and dried")
        ing("onion", 1.0, PIECE, "Onion", "roughly chopped")
        ing("garlic", 4.0, CLOVE, "Garlic")
        ing("parsley", 30.0, GRAM, "Flat-leaf parsley")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        ing("cumin", 2.0, TEASPOON, "Ground cumin")
        ing("coriander_seed", 1.0, TEASPOON, "Ground coriander")
        ing("chili_powder", 0.25, TEASPOON, "Cayenne or chilli powder")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("baking_soda", 0.5, TEASPOON, "Bicarbonate of soda")
        ing("vegetable_oil", 750.0, MILLILITRE, "Oil for deep-frying", "about 7% is absorbed", consumed = 0.07)
        group("Tahini sauce")
        ing("tahini", 3.0, TABLESPOON, "Tahini")
        ing("lemon_juice", 2.0, TABLESPOON, "Lemon juice")
        ing("water", 3.0, TABLESPOON, "Water")
        step("Pulse the soaked raw chickpeas, onion, garlic, herbs, spices and salt to a coarse, damp crumb that holds when squeezed (not a paste).", 3.0)
        step("Chill the mixture for 30 minutes, then stir in the bicarbonate of soda.", 30.0)
        step("Heat the oil to 175 °C. Shape walnut-sized balls or patties.", tempC = 175)
        step("Fry in batches until deep golden and crisp, turning once. Drain on paper.", 4.0, 175)
        step("Whisk the tahini, lemon juice and water into a smooth sauce. Serve with pita and salad.")
        safety(DEEP_FRY)
        storage = "Best eaten fresh; refrigerate leftovers for up to 2 days."
        reheating = "Re-crisp in a hot oven (200 °C) for 8–10 minutes."
    }
    recipe("hummus", "Hummus", "lebanese", "Levantine", 8, 15, 90, EASY) {
        description = "Chickpeas cooked very soft and blended with tahini, lemon and garlic until smooth, finished with olive oil."
        equipment("Saucepan", "Food processor or blender")
        tags("vegan", "dairy-free", "gluten-free", "snack", "meal-prep")
        ing("chickpeas_dry", 250.0, GRAM, "Dried chickpeas", "soaked overnight")
        ing("baking_soda", 0.5, TEASPOON, "Bicarbonate of soda", "for cooking the chickpeas")
        ing("tahini", 6.0, TABLESPOON, "Tahini")
        ing("lemon_juice", 4.0, TABLESPOON, "Lemon juice")
        ing("garlic", 2.0, CLOVE, "Garlic")
        ing("cumin", 0.5, TEASPOON, "Ground cumin")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("water", 100.0, MILLILITRE, "Ice-cold water", "added gradually")
        ing("olive_oil", 2.0, TABLESPOON, "Extra-virgin olive oil", "to finish")
        ing("paprika", 0.5, TEASPOON, "Paprika or sumac", "to finish", optional = true)
        step("Drain the soaked chickpeas, cover with fresh water, add the bicarbonate of soda and simmer until completely soft and falling apart.", 75.0)
        step("Drain, keeping some cooking water. Blend the hot chickpeas with garlic, lemon juice and salt.", 2.0)
        step("Add the tahini and blend, adding ice-cold water a spoonful at a time, until very smooth and creamy.", 4.0)
        step("Season, spread in a shallow bowl and finish with olive oil and paprika.")
        storage = "Refrigerate covered for up to 4 days."
        reheating = null
    }
    recipe("tabbouleh", "Tabbouleh", "lebanese", "Mountain", 4, 25, 0, EASY) {
        description = "A herb salad rather than a grain salad: lots of finely chopped parsley with tomato, mint, spring onion and a little fine bulgur, dressed with lemon and olive oil."
        equipment("Sharp knife", "Large bowl")
        tags("vegan", "dairy-free", "salad", "no-cook")
        ing("bulgur", 50.0, GRAM, "Fine bulgur")
        ing("parsley", 150.0, GRAM, "Flat-leaf parsley (about 3 bunches)", "leaves and tender stems, finely chopped")
        ing("mint", 1.0, QuantityUnit.HANDFUL, "Mint leaves", "finely chopped")
        ing("tomato", 3.0, PIECE, "Firm tomatoes", "finely diced")
        ing("scallion", 4.0, PIECE, "Spring onions", "finely sliced")
        ing("lemon_juice", 4.0, TABLESPOON, "Lemon juice")
        ing("olive_oil", 4.0, TABLESPOON, "Extra-virgin olive oil")
        ing("allspice", 0.25, TEASPOON, "Ground allspice or seven-spice", optional = true)
        ing("salt", 1.0, TEASPOON, "Salt")
        step("Rinse the bulgur and leave it to soften in the lemon juice and diced tomato for 15 minutes.", 15.0)
        step("Chop the parsley and mint finely by hand (a food processor bruises them).")
        step("Combine everything, dress with olive oil and salt, and serve straight away.")
        storage = "Best fresh; dressed salad keeps 1 day refrigerated."
        reheating = null
    }
    recipe("mujaddara", "Mujaddara (lentils and rice with crispy onions)", "palestinian", "Levantine", 4, 15, 50) {
        description = "Brown lentils and rice cooked together with cumin and allspice, topped with a generous heap of deeply browned onions."
        equipment("Wide frying pan", "Lidded saucepan")
        tags("vegan", "dairy-free", "gluten-free", "high-fibre", "budget")
        ing("lentils_brown", 250.0, GRAM, "Brown or green lentils", "rinsed")
        ing("rice_white", 150.0, GRAM, "Long-grain rice", "rinsed")
        ing("onion", 750.0, GRAM, "Onions (about 4 large)", "halved and thinly sliced")
        ing("olive_oil", 6.0, TABLESPOON, "Olive oil")
        ing("cumin", 2.0, TEASPOON, "Ground cumin")
        ing("allspice", 0.5, TEASPOON, "Ground allspice")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("water", 900.0, MILLILITRE, "Water")
        ing("yogurt", 200.0, GRAM, "Plain yogurt, to serve", optional = true)
        step("Simmer the lentils in the water until just tender but still holding their shape.", 15.0)
        step("Meanwhile fry the onions slowly in the oil, stirring often, until deep brown and crisp at the edges. Set half aside for the top.", 30.0)
        step("Stir the rice, spices, salt and half the onions into the lentils; top up with water to cover by 1 cm if needed.")
        step("Cover and cook on low heat until the rice is tender and the water absorbed, then rest off the heat for 10 minutes.", 20.0)
        step("Fluff and serve topped with the reserved onions, with yogurt or salad on the side.")
        safety(RICE_SAFETY)
    }
    recipe("koshari", "Koshari", "egyptian", "Cairene", 6, 20, 60, Difficulty.INVOLVED) {
        description = "Cairo's layered street dish of rice, lentils, small pasta and chickpeas under a sharp garlic-vinegar tomato sauce and crispy onions."
        equipment("Three saucepans", "Frying pan")
        tags("vegan", "dairy-free", "high-fibre", "street food", "budget")
        ing("rice_white", 200.0, GRAM, "Short- or medium-grain rice", "rinsed")
        ing("lentils_brown", 200.0, GRAM, "Brown lentils", "rinsed")
        ing("pasta", 200.0, GRAM, "Small macaroni or broken vermicelli")
        ing("chickpeas_cooked", 240.0, GRAM, "Chickpeas, cooked or canned", "drained")
        group("Crispy onions")
        ing("onion", 500.0, GRAM, "Onions", "thinly sliced")
        ing("vegetable_oil", 5.0, TABLESPOON, "Oil")
        group("Tomato sauce")
        ing("tomato_canned", 800.0, GRAM, "Crushed tomatoes")
        ing("garlic", 6.0, CLOVE, "Garlic", "crushed")
        ing("vinegar", 3.0, TABLESPOON, "White vinegar")
        ing("cumin", 1.5, TEASPOON, "Ground cumin")
        ing("chili_powder", 0.5, TEASPOON, "Chilli flakes or cayenne")
        ing("salt", 2.0, TEASPOON, "Salt (divided)")
        step("Fry the onions in the oil until dark golden and crisp; lift out and keep the oil.", 20.0)
        step("Simmer the lentils in water until tender, about 20 minutes; drain.", 20.0)
        step("Fry the rice in 1 tbsp of the onion oil, add 1.5 times its volume of water and salt, cover and cook on low until tender. Stir in the lentils.", 18.0)
        step("Boil the pasta in salted water until al dente; drain.", 9.0)
        step("Fry the garlic in a little onion oil, add the tomatoes, cumin, chilli and salt and simmer until thick; stir in the vinegar.", 15.0)
        step("Layer rice and lentils, pasta and chickpeas; spoon over the sauce and top with crispy onions.")
        safety(RICE_SAFETY)
    }
    recipe("vegetable_couscous", "Vegetable couscous with chickpeas", "moroccan", "Moroccan home-style", 4, 20, 40) {
        description = "Steamed couscous with a gently spiced stew of carrots, courgettes, pumpkin, tomatoes and chickpeas in broth."
        equipment("Large pot", "Bowl with a plate or lid")
        tags("vegan", "dairy-free", "high-fibre", "dinner")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Onion", "sliced")
        ing("tomato", 2.0, PIECE, "Tomatoes", "grated")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("turmeric", 1.0, TEASPOON, "Turmeric")
        ing("cinnamon", 1.0, QuantityUnit.STICK, "Cinnamon stick")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("vegetable_broth", 1000.0, MILLILITRE, "Vegetable stock")
        ing("carrot", 300.0, GRAM, "Carrots", "halved lengthways")
        ing("pumpkin", 300.0, GRAM, "Pumpkin or squash", "large chunks")
        ing("zucchini", 2.0, PIECE, "Courgettes", "thick batons")
        ing("chickpeas_cooked", 240.0, GRAM, "Chickpeas, cooked or canned", "drained")
        ing("raisins", 3.0, TABLESPOON, "Raisins", optional = true)
        ing("couscous", 300.0, GRAM, "Couscous")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Coriander and parsley", "chopped")
        ing("salt", 1.5, TEASPOON, "Salt")
        step("Soften the onion in the oil, then add tomato, ginger, turmeric, cinnamon and pepper and cook 3 minutes.", 8.0)
        step("Add the stock and carrots; simmer 10 minutes. Add the pumpkin, courgettes, chickpeas and raisins and simmer until all the vegetables are tender.", 25.0)
        step("Pour 400 ml of hot broth from the pot over the couscous, cover and leave 5 minutes, then fluff with a fork.", 5.0)
        step("Mound the couscous on a platter, arrange the vegetables on top and ladle over more broth. Scatter with herbs.")
    }
    recipe("lamb_tagine", "Lamb tagine with dates and almonds", "moroccan", "Moroccan home-style", 6, 20, 120, Difficulty.MEDIUM) {
        description = "Lamb shoulder braised slowly with onion, ginger, saffron and cinnamon until tender, finished with sweet dates, honey and toasted almonds."
        equipment("Tagine or heavy lidded casserole")
        tags("high-protein", "gluten-free", "dairy-free", "dinner", "slow-cooked")
        ing("lamb", 1000.0, GRAM, "Lamb shoulder, boneless", "4 cm cubes")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("onion", 2.0, PIECE, "Onions", "grated")
        ing("garlic", 3.0, CLOVE, "Garlic", "crushed")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("turmeric", 1.0, TEASPOON, "Turmeric")
        ing("cinnamon", 1.0, QuantityUnit.STICK, "Cinnamon stick")
        ing("black_pepper", 1.0, TEASPOON, "Black pepper")
        ing("saffron", 1.0, PINCH, "Saffron threads", "soaked in 2 tbsp warm water")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("water", 500.0, MILLILITRE, "Water")
        ing("dates", 6.0, PIECE, "Dates", "pitted")
        ing("honey", 1.0, TABLESPOON, "Honey")
        ing("almonds", 40.0, GRAM, "Almonds", "blanched and toasted")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander", "chopped")
        step("Mix the lamb with the onion, garlic, ginger, spices, saffron, salt and oil in the pot and leave 15 minutes.", 15.0)
        step("Brown lightly over medium heat, then add the water, cover and simmer very gently until the lamb is tender, adding a splash of water if it dries out.", 100.0)
        step("Add the dates and honey and simmer uncovered until the sauce is thick and glossy.", 15.0)
        step("Scatter with almonds and coriander. Serve with bread or couscous.")
        safety("Braise lamb until it is tender and steaming hot throughout.")
    }
    recipe("groundnut_soup", "Groundnut soup with chicken", "ghanaian", "Akan / Ashanti", 6, 15, 60) {
        description = "A rich peanut and tomato soup with bone-in chicken, ginger and chilli, traditionally eaten with rice balls (omo tuo) or fufu."
        equipment("Large pot", "Blender")
        tags("high-protein", "gluten-free", "dairy-free", "soup")
        ing("chicken_curry_cut", 1200.0, GRAM, "Chicken, bone-in, skinless", "jointed")
        ing("onion", 2.0, PIECE, "Onions", "1 sliced, 1 blended")
        ing("ginger", 1.0, TABLESPOON, "Ginger")
        ing("garlic", 3.0, CLOVE, "Garlic")
        ing("green_chili", 2.0, PIECE, "Scotch bonnet or hot chillies", "whole or blended, to taste")
        ing("tomato", 3.0, PIECE, "Tomatoes")
        ing("tomato_paste", 2.0, TABLESPOON, "Tomato paste")
        ing("peanut_butter", 250.0, GRAM, "Unsweetened smooth peanut butter")
        ing("water", 1500.0, MILLILITRE, "Water")
        ing("salt", 2.0, TEASPOON, "Salt")
        step("Season the chicken with salt, the sliced onion and half the ginger and garlic; steam it in its own juices in the covered pot.", 10.0)
        step("Blend the tomatoes, remaining onion, ginger, garlic and chillies; add to the pot with the tomato paste and 1 litre of water. Simmer 15 minutes.", 15.0)
        step("Whisk the peanut butter with 500 ml hot water until smooth, then stir it into the soup.")
        step("Simmer gently, stirring now and then, until the oil rises to the surface and the chicken is very tender.", 35.0)
        safety(POULTRY_SAFETY)
        notes += "Contains peanuts."
    }
    recipe("suya", "Beef suya", "nigerian", "Hausa", 4, 20, 12, EASY) {
        description = "Thin strips of beef coated in yaji, a spicy ground-peanut seasoning, then grilled hot and served with raw onion and tomato."
        equipment("Skewers (soak wooden ones)", "Grill, griddle or oven grill")
        tags("high-protein", "gluten-free", "dairy-free", "street food")
        ing("beef_steak", 600.0, GRAM, "Beef sirloin or flank", "very thinly sliced")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        group("Yaji (suya spice)")
        ing("peanuts", 60.0, GRAM, "Roasted peanuts", "finely ground")
        ing("chili_powder", 2.0, TEASPOON, "Cayenne or chilli powder")
        ing("paprika", 2.0, TEASPOON, "Paprika")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated or ground")
        ing("garlic", 1.0, TEASPOON, "Garlic", "grated or powder")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("To serve")
        ing("onion", 1.0, PIECE, "Red onion", "thinly sliced")
        ing("tomato", 2.0, PIECE, "Tomatoes", "sliced")
        step("Mix the yaji ingredients. Rub the beef with oil, then press most of the yaji onto it. Rest 30 minutes.", 30.0)
        step("Thread onto skewers and grill on very high heat, turning once, until browned and just cooked.", 10.0)
        step("Dust with the remaining yaji and serve with sliced onion and tomato.")
        safety(BEEF_SAFETY)
        notes += "Contains peanuts."
        storage = "Best eaten hot; refrigerate leftovers for up to 2 days."
    }
    recipe("doro_wat", "Doro wat (Ethiopian chicken stew)", "ethiopian", "Amhara", 6, 30, 120, Difficulty.INVOLVED) {
        description = "A deep red stew of chicken and hard-boiled eggs in a slow-cooked base of onions, spiced butter and berbere, served with injera."
        equipment("Heavy pot", "Wooden spoon")
        tags("high-protein", "gluten-free", "dinner", "celebration")
        ing("chicken_curry_cut", 1200.0, GRAM, "Chicken, bone-in, skinless", "jointed and scored")
        ing("lemon_juice", 2.0, TABLESPOON, "Lemon juice", "for rinsing the chicken")
        ing("onion", 1000.0, GRAM, "Red onions", "very finely chopped")
        ing("ghee", 6.0, TABLESPOON, "Niter kibbeh (spiced butter) or ghee")
        ing("garam_masala", 4.0, TABLESPOON, "Berbere spice blend")
        ing("garlic", 6.0, CLOVE, "Garlic", "minced")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "minced")
        ing("water", 500.0, MILLILITRE, "Water or chicken stock")
        ing("egg", 6.0, PIECE, "Eggs", "hard-boiled and peeled")
        ing("salt", 1.5, TEASPOON, "Salt")
        step("Rub the chicken with lemon juice and a little salt and set aside.", 10.0)
        step("Cook the onions in a dry pot over medium-low heat, stirring often, until they collapse and turn jammy.", 30.0)
        step("Add the niter kibbeh and berbere and cook, stirring, until dark red and fragrant.", 20.0)
        step("Add garlic, ginger, water and the chicken. Cover and simmer gently until the chicken is tender.", 45.0)
        step("Score the eggs, add them to the stew and simmer 10 minutes more so they take on the sauce.", 10.0)
        step("Serve with injera.")
        safety(POULTRY_SAFETY, EGG_SAFETY)
        notes += "Berbere blends vary in heat; start with less if yours is very hot."
    }
    recipe("fried_plantain", "Dodo (fried plantain)", "nigerian", "Yoruba", 4, 5, 10, EASY) {
        description = "Ripe plantain slices fried until caramelised at the edges, served as a side with rice, beans or eggs."
        equipment("Frying pan", "Slotted spoon")
        tags("vegan", "dairy-free", "gluten-free", "side")
        ing("plantain", 3.0, PIECE, "Ripe plantains (yellow with black spots)", "peeled, sliced on the diagonal")
        ing("vegetable_oil", 500.0, MILLILITRE, "Oil for frying", "about 6% is absorbed", consumed = 0.06)
        ing("salt", 0.5, TEASPOON, "Salt")
        step("Toss the plantain slices with the salt.")
        step("Heat the oil to about 175 °C and fry the plantain in batches until golden brown on both sides.", 5.0, 175)
        step("Drain on paper and serve warm.")
        safety(DEEP_FRY)
        storage = "Best eaten fresh; refrigerate leftovers for 1 day."
        reheating = "Warm in a hot dry pan or oven."
    }
}

// ================================================================ Europe & the Americas
private fun RecipeListBuilder.europeanAndAmericasKitchen() {
    recipe("chicken_tikka_masala", "Chicken tikka masala", "british_irish", "British-Asian", 4, 20, 40) {
        description = "Marinated, charred chicken pieces in a creamy, spiced tomato sauce. Associated with British curry houses; its exact origin is disputed."
        equipment("Grill pan or oven grill", "Large frying pan")
        tags("high-protein", "gluten-free", "dinner")
        group("Chicken tikka")
        ing("chicken_breast", 700.0, GRAM, "Chicken breast", "bite-sized chunks")
        ing("yogurt", 150.0, GRAM, "Plain yogurt")
        ing("lemon_juice", 1.0, TABLESPOON, "Lemon juice")
        ing("garam_masala", 2.0, TEASPOON, "Garam masala")
        ing("chili_powder", 1.0, TEASPOON, "Kashmiri chilli powder")
        ing("garlic", 2.0, CLOVE, "Garlic", "grated")
        ing("ginger", 1.0, TEASPOON, "Ginger", "grated")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("Masala sauce")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("butter", 1.0, TABLESPOON, "Butter")
        ing("onion", 1.0, PIECE, "Large onion", "finely chopped")
        ing("garlic", 3.0, CLOVE, "Garlic", "grated")
        ing("ginger", 1.0, TABLESPOON, "Ginger", "grated")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("paprika", 2.0, TEASPOON, "Paprika")
        ing("turmeric", 0.5, TEASPOON, "Turmeric")
        ing("garam_masala", 1.0, TEASPOON, "Garam masala")
        ing("tomato_canned", 400.0, GRAM, "Chopped tomatoes")
        ing("tomato_paste", 1.0, TABLESPOON, "Tomato paste")
        ing("cream_heavy", 150.0, MILLILITRE, "Double cream")
        ing("sugar", 1.0, TEASPOON, "Sugar")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander", "chopped")
        step("Mix the marinade ingredients, coat the chicken and refrigerate for at least 1 hour.", 60.0)
        step("Grill the chicken on a very hot grill pan or under the grill until charred in places and just cooked.", 10.0)
        step("Soften the onion in the oil and butter, then add garlic, ginger and the ground spices and fry 1 minute.", 10.0)
        step("Add the tomatoes, paste and sugar; simmer until thick, then blend smooth if you like.", 12.0)
        step("Stir in the cream and the chicken and simmer until the chicken is cooked through. Finish with coriander.", 5.0)
        safety(POULTRY_SAFETY)
    }
    recipe("spaghetti_bolognese", "Spaghetti bolognese", "italian", "Emilian", 4, 15, 90) {
        description = "A slow-cooked meat ragù with soffritto, tomato and milk, tossed with spaghetti. In Bologna ragù is served with fresh tagliatelle; spaghetti is the internationally familiar version."
        equipment("Heavy saucepan", "Large pot")
        tags("high-protein", "dinner", "meal-prep")
        ing("olive_oil", 2.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Onion", "finely chopped")
        ing("carrot", 1.0, PIECE, "Carrot", "finely chopped")
        ing("celery", 1.0, PIECE, "Celery stalk", "finely chopped")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("beef_ground", 500.0, GRAM, "Minced beef")
        ing("white_wine", 120.0, MILLILITRE, "Dry white or red wine", optional = true)
        ing("milk_whole", 100.0, MILLILITRE, "Whole milk")
        ing("tomato_canned", 400.0, GRAM, "Chopped tomatoes or passata")
        ing("tomato_paste", 2.0, TABLESPOON, "Tomato paste")
        ing("bay_leaf", 1.0, PIECE, "Bay leaf")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        ing("pasta", 360.0, GRAM, "Spaghetti, dried")
        ing("parmesan", 4.0, TABLESPOON, "Parmesan", "grated")
        step("Cook the onion, carrot and celery gently in the oil until soft and sweet.", 10.0)
        step("Add the garlic and beef and cook, breaking it up, until browned all over.", 8.0)
        step("Add the wine (if using) and let it bubble away, then the milk, simmering until absorbed.", 6.0)
        step("Add tomatoes, paste, bay leaf and seasoning. Cover partly and simmer very gently, stirring occasionally.", 60.0)
        step("Boil the spaghetti in well-salted water until al dente; toss with the sauce and a splash of pasta water. Serve with Parmesan.", 10.0)
        safety(MINCE_SAFETY)
        storage = "Refrigerate the sauce within 2 hours for up to 3 days, or freeze for 3 months."
    }
    recipe("lasagne", "Lasagne al forno", "italian", "Emilian", 8, 45, 150, Difficulty.INVOLVED) {
        description = "Layers of pasta, slow-cooked meat ragù and béchamel, baked until bubbling and golden with Parmesan."
        equipment("Heavy saucepan", "Saucepan for béchamel", "Whisk", "Baking dish (about 30 × 20 cm)")
        tags("high-protein", "dinner", "meal-prep", "celebration")
        group("Ragù")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Onion", "finely chopped")
        ing("carrot", 1.0, PIECE, "Carrot", "finely chopped")
        ing("celery", 1.0, PIECE, "Celery stalk", "finely chopped")
        ing("beef_ground", 750.0, GRAM, "Minced beef")
        ing("milk_whole", 150.0, MILLILITRE, "Whole milk")
        ing("tomato_canned", 800.0, GRAM, "Chopped tomatoes or passata")
        ing("tomato_paste", 2.0, TABLESPOON, "Tomato paste")
        ing("salt", 2.0, TEASPOON, "Salt")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        group("Béchamel")
        ing("butter", 60.0, GRAM, "Butter")
        ing("flour", 60.0, GRAM, "Plain flour")
        ing("milk_whole", 900.0, MILLILITRE, "Whole milk", "warmed")
        ing("salt", 0.5, TEASPOON, "Salt and a grating of nutmeg")
        group("To assemble")
        ing("pasta", 300.0, GRAM, "Dried lasagne sheets")
        ing("parmesan", 80.0, GRAM, "Parmesan", "grated")
        step("Make the ragù: soften the vegetables in the oil, brown the beef, add the milk and let it absorb, then add the tomatoes, paste and seasoning.", 20.0)
        step("Simmer the ragù gently, partly covered, until thick and rich.", 75.0)
        step("Make the béchamel: melt the butter, whisk in the flour and cook 1 minute, then whisk in the warm milk gradually and simmer until thick. Season with salt and nutmeg.", 10.0)
        step("Heat the oven to 190 °C. Layer ragù, pasta sheets, béchamel and Parmesan, finishing with béchamel and Parmesan.", tempC = 190)
        step("Bake until bubbling and golden, then rest 15 minutes before cutting.", 40.0, 190)
        safety(MINCE_SAFETY)
        storage = "Refrigerate within 2 hours for up to 3 days, or freeze portions for 3 months."
    }
    recipe("risotto_ai_funghi", "Mushroom risotto (risotto ai funghi)", "italian", "Northern Italian", 4, 10, 30) {
        description = "Short-grain rice stirred with hot stock until creamy, with sautéed mushrooms, butter and Parmesan."
        equipment("Wide heavy pan", "Saucepan for stock", "Ladle")
        tags("vegetarian", "gluten-free", "dinner")
        notes += "Traditional Parmigiano Reggiano is made with animal rennet; use a vegetarian hard cheese if needed."
        ing("vegetable_broth", 1200.0, MILLILITRE, "Vegetable stock", "kept hot")
        ing("olive_oil", 2.0, TABLESPOON, "Olive oil")
        ing("mushroom", 400.0, GRAM, "Mushrooms (chestnut or mixed)", "sliced")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("onion", 1.0, PIECE, "Small onion or 2 shallots", "finely chopped")
        ing("rice_glutinous", 320.0, GRAM, "Arborio or carnaroli rice")
        ing("white_wine", 120.0, MILLILITRE, "Dry white wine", optional = true)
        ing("butter", 2.0, TABLESPOON, "Butter")
        ing("parmesan", 40.0, GRAM, "Parmesan", "grated")
        ing("parsley", 15.0, GRAM, "Parsley", "chopped")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        step("Fry the mushrooms in half the oil over high heat until browned; add the garlic for the last minute and set aside.", 7.0)
        step("Soften the onion in the remaining oil, add the rice and stir until the grains turn translucent at the edges.", 5.0)
        step("Add the wine (if using) and let it evaporate, then add hot stock a ladle at a time, stirring, letting each addition absorb before the next.", 18.0)
        step("When the rice is creamy but still has a slight bite, stir in the mushrooms, butter and Parmesan off the heat. Cover and rest 2 minutes.", 2.0)
        step("Season and serve with parsley.")
        safety(RICE_SAFETY)
        storage = "Best eaten fresh; refrigerate leftovers within 1 hour and eat within 1 day."
    }
    recipe("minestrone", "Minestrone", "italian", "Italian home-style", 6, 20, 45, EASY) {
        description = "A thick vegetable soup with beans and small pasta. Every household's version is different; use what's in season."
        equipment("Large pot")
        tags("vegetarian", "high-fibre", "soup", "meal-prep")
        ing("olive_oil", 3.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Onion", "diced")
        ing("carrot", 2.0, PIECE, "Carrots", "diced")
        ing("celery", 2.0, PIECE, "Celery stalks", "diced")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("potato", 1.0, PIECE, "Potato", "diced")
        ing("zucchini", 1.0, PIECE, "Courgette", "diced")
        ing("green_beans", 100.0, GRAM, "Green beans", "cut short")
        ing("cabbage", 150.0, GRAM, "Savoy cabbage or kale", "shredded")
        ing("tomato_canned", 400.0, GRAM, "Chopped tomatoes")
        ing("vegetable_broth", 1500.0, MILLILITRE, "Vegetable stock")
        ing("white_beans", 240.0, GRAM, "Cannellini or borlotti beans, canned", "drained")
        ing("pasta", 100.0, GRAM, "Small soup pasta")
        ing("basil", 0.5, CUP, "Basil or parsley", "torn")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("parmesan", 30.0, GRAM, "Parmesan, to serve", "grated", optional = true)
        step("Soften the onion, carrot and celery in the oil.", 10.0)
        step("Add garlic, potato, courgette and green beans and cook 3 minutes, then add the tomatoes and stock.", 3.0)
        step("Simmer until the vegetables are tender.", 20.0)
        step("Add the cabbage, beans and pasta and simmer until the pasta is cooked.", 10.0)
        step("Season, stir in the herbs and serve with Parmesan and a drizzle of olive oil.")
        storage = "Refrigerate within 2 hours for up to 4 days; the pasta keeps absorbing liquid."
        reheating = "Loosen with a little water and reheat until steaming hot throughout."
    }
    recipe("moussaka", "Moussaka", "greek", "National", 6, 40, 90, Difficulty.INVOLVED) {
        description = "Baked layers of roasted aubergine and a cinnamon-scented lamb and tomato sauce under a thick, golden béchamel."
        equipment("Two baking trays", "Frying pan", "Saucepan", "Whisk", "Deep baking dish")
        tags("high-protein", "dinner", "celebration")
        ing("eggplant", 1000.0, GRAM, "Aubergines (about 2 large)", "sliced 1 cm thick")
        ing("olive_oil", 5.0, TABLESPOON, "Olive oil", "for brushing and frying")
        group("Meat sauce")
        ing("lamb_ground", 700.0, GRAM, "Minced lamb (or beef)")
        ing("onion", 1.0, PIECE, "Onion", "finely chopped")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("tomato_canned", 400.0, GRAM, "Chopped tomatoes")
        ing("tomato_paste", 1.0, TABLESPOON, "Tomato paste")
        ing("cinnamon", 1.0, TEASPOON, "Ground cinnamon")
        ing("allspice", 0.5, TEASPOON, "Ground allspice")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper")
        group("Béchamel")
        ing("butter", 60.0, GRAM, "Butter")
        ing("flour", 60.0, GRAM, "Plain flour")
        ing("milk_whole", 700.0, MILLILITRE, "Whole milk", "warmed")
        ing("egg", 2.0, PIECE, "Egg yolks or whole eggs", "beaten")
        ing("parmesan", 50.0, GRAM, "Kefalotyri or Parmesan", "grated")
        step("Heat the oven to 200 °C. Brush the aubergine slices with oil, season and roast on trays until soft and golden, turning once.", 25.0, 200)
        step("Brown the lamb with the onion and garlic, then add tomatoes, paste, spices, oregano and seasoning. Simmer until thick.", 30.0)
        step("Make the béchamel: melt the butter, whisk in the flour, then the warm milk, and simmer until thick. Off the heat, whisk in the eggs and half the cheese.", 10.0)
        step("Layer half the aubergine, all the meat sauce, then the rest of the aubergine. Spread over the béchamel and scatter with the remaining cheese.")
        step("Bake at 180 °C until deep golden. Rest 20–30 minutes before slicing.", 45.0, 180)
        safety(MINCE_SAFETY, EGG_SAFETY)
    }
    recipe("spanakopita", "Spanakopita (spinach and feta pie)", "greek", "National", 8, 30, 45) {
        description = "Spinach, feta, spring onion and herbs baked between layers of crisp, oil-brushed filo pastry."
        equipment("Large pan", "Colander", "Pastry brush", "Baking dish (about 30 × 20 cm)")
        tags("vegetarian", "lunch", "picnic")
        ing("spinach", 1000.0, GRAM, "Fresh spinach", "washed and chopped")
        ing("scallion", 6.0, PIECE, "Spring onions", "sliced")
        ing("parsley", 30.0, GRAM, "Dill and parsley", "chopped")
        ing("feta", 300.0, GRAM, "Feta", "crumbled")
        ing("egg", 2.0, PIECE, "Eggs", "beaten")
        ing("black_pepper", 0.5, TEASPOON, "Black pepper and a little nutmeg")
        ing("olive_oil", 6.0, TABLESPOON, "Olive oil", "for the filling and brushing")
        ing("phyllo", 12.0, QuantityUnit.SLICE, "Filo pastry sheets", "kept covered")
        step("Wilt the spinach in batches in a large pan, then cool and squeeze out as much water as possible.", 10.0)
        step("Soften the spring onions in 1 tbsp oil and mix with the spinach, herbs, feta, eggs and pepper.", 4.0)
        step("Heat the oven to 180 °C. Line the dish with 6 filo sheets, brushing each with oil.", tempC = 180)
        step("Spread in the filling, cover with the remaining sheets, brushing each with oil, and score the top into portions.")
        step("Bake until crisp and deep golden.", 45.0, 180)
        safety(EGG_SAFETY)
        storage = "Refrigerate within 2 hours for up to 3 days."
        reheating = "Re-crisp in the oven at 180 °C until hot throughout."
    }
    recipe("chicken_fajitas", "Chicken fajitas", "american", "Tex-Mex", 4, 15, 15, EASY) {
        description = "Strips of spiced chicken seared with peppers and onion, wrapped in warm flour tortillas with soured cream and coriander."
        equipment("Large cast-iron or heavy frying pan")
        tags("high-protein", "dinner", "quick")
        ing("chicken_breast", 600.0, GRAM, "Chicken breast", "sliced into strips")
        ing("vegetable_oil", 3.0, TABLESPOON, "Oil")
        ing("lime", 2.0, TABLESPOON, "Lime juice")
        ing("garlic", 2.0, CLOVE, "Garlic", "crushed")
        ing("chili_powder", 2.0, TEASPOON, "Chilli powder")
        ing("cumin", 1.0, TEASPOON, "Ground cumin")
        ing("paprika", 1.0, TEASPOON, "Smoked paprika")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("salt", 1.0, TEASPOON, "Salt")
        ing("bell_pepper", 3.0, PIECE, "Peppers, mixed colours", "sliced")
        ing("onion", 1.0, PIECE, "Large onion", "sliced")
        group("To serve")
        ing("tortilla_flour", 8.0, PIECE, "Flour tortillas (8 in)", "warmed")
        ing("sour_cream", 4.0, TABLESPOON, "Soured cream")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        ing("avocado", 200.0, GRAM, "Avocado or guacamole", "sliced", optional = true)
        step("Toss the chicken with half the oil, the lime juice, garlic, spices and salt. Marinate 15 minutes.", 15.0)
        step("Sear the chicken in a very hot pan in batches until browned and cooked through; set aside.", 7.0)
        step("Add the remaining oil and stir-fry the peppers and onion until charred but still crisp.", 5.0)
        step("Return the chicken to the pan, toss, and serve sizzling with warm tortillas and toppings.")
        safety(POULTRY_SAFETY)
    }
    recipe("beef_tacos", "Ground beef tacos", "american", "Tex-Mex", 4, 15, 15, EASY) {
        description = "Spiced minced beef in warm corn tortillas with lettuce, tomato, cheese and lime — the familiar Tex-Mex home version."
        equipment("Frying pan")
        tags("high-protein", "gluten-free", "dinner", "quick")
        ing("vegetable_oil", 1.0, TABLESPOON, "Oil")
        ing("onion", 1.0, PIECE, "Onion", "finely chopped")
        ing("garlic", 2.0, CLOVE, "Garlic", "minced")
        ing("beef_ground_lean", 500.0, GRAM, "Lean minced beef")
        ing("chili_powder", 2.0, TEASPOON, "Chilli powder")
        ing("cumin", 2.0, TEASPOON, "Ground cumin")
        ing("paprika", 1.0, TEASPOON, "Paprika")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("tomato_paste", 1.0, TABLESPOON, "Tomato paste")
        ing("water", 120.0, MILLILITRE, "Water")
        ing("salt", 1.0, TEASPOON, "Salt")
        group("To serve")
        ing("tortilla_corn", 12.0, PIECE, "Corn tortillas (6 in)", "warmed")
        ing("lettuce", 2.0, CUP, "Lettuce", "shredded")
        ing("tomato", 2.0, PIECE, "Tomatoes", "diced")
        ing("cheddar", 80.0, GRAM, "Cheddar or Monterey Jack", "grated")
        ing("lime", 1.0, PIECE, "Lime", "wedges")
        ing("sour_cream", 4.0, TABLESPOON, "Soured cream", optional = true)
        step("Soften the onion in the oil, then add the garlic and beef and cook, breaking it up, until browned.", 8.0)
        step("Stir in the spices and tomato paste, add the water and salt, and simmer until saucy and the beef is cooked through.", 5.0)
        step("Warm the tortillas in a dry pan. Fill with the beef and toppings and serve with lime.", 2.0)
        safety(MINCE_SAFETY)
    }
    recipe("black_bean_chili", "Black bean and vegetable chili", "american", "Southwestern home-style", 6, 15, 40, EASY) {
        description = "A smoky, meat-free chili of black and kidney beans, peppers, corn and tomatoes. Good for batch cooking."
        equipment("Large pot")
        tags("vegan", "dairy-free", "gluten-free", "high-fibre", "meal-prep")
        ing("olive_oil", 2.0, TABLESPOON, "Olive oil")
        ing("onion", 1.0, PIECE, "Large onion", "diced")
        ing("bell_pepper", 2.0, PIECE, "Peppers", "diced")
        ing("carrot", 1.0, PIECE, "Carrot", "diced")
        ing("garlic", 3.0, CLOVE, "Garlic", "minced")
        ing("chili_powder", 1.0, TABLESPOON, "Chilli powder")
        ing("cumin", 2.0, TEASPOON, "Ground cumin")
        ing("paprika", 1.0, TEASPOON, "Smoked paprika")
        ing("oregano", 1.0, TEASPOON, "Dried oregano")
        ing("tomato_canned", 800.0, GRAM, "Chopped tomatoes (2 cans)")
        ing("black_beans", 480.0, GRAM, "Black beans, canned (2 cans)", "drained and rinsed", cooked = true)
        ing("kidney_beans", 240.0, GRAM, "Kidney beans, canned (1 can)", "drained and rinsed", cooked = true)
        ing("corn", 150.0, GRAM, "Sweetcorn")
        ing("vegetable_broth", 250.0, MILLILITRE, "Vegetable stock")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("lime", 1.0, PIECE, "Lime", "wedges")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander")
        step("Soften the onion, peppers and carrot in the oil.", 8.0)
        step("Add the garlic and spices and fry for 1 minute.", 1.0)
        step("Add the tomatoes, beans, corn and stock. Simmer, stirring now and then, until thick.", 30.0)
        step("Season and serve with lime and coriander, with rice or tortillas.")
        storage = "Refrigerate within 2 hours for up to 4 days, or freeze for 3 months."
    }
    recipe("guacamole", "Guacamole", "mexican", null, 6, 10, 0, EASY) {
        description = "Ripe avocado mashed with lime, white onion, chilli, tomato and coriander."
        equipment("Molcajete or bowl and fork")
        tags("vegan", "dairy-free", "gluten-free", "snack", "no-cook")
        ing("avocado", 600.0, GRAM, "Ripe avocados, whole (about 3 large)", "halved, stoned and scooped")
        ing("lime", 2.0, TABLESPOON, "Lime juice")
        ing("onion", 50.0, GRAM, "White onion", "finely chopped")
        ing("green_chili", 1.0, PIECE, "Serrano or jalapeño chilli", "finely chopped")
        ing("tomato", 1.0, PIECE, "Tomato", "seeded and diced")
        ing("cilantro", 1.0, QuantityUnit.HANDFUL, "Fresh coriander", "chopped")
        ing("salt", 0.75, TEASPOON, "Salt")
        step("Crush the onion, chilli, half the coriander and the salt to a rough paste.")
        step("Add the avocado and mash to the texture you like, leaving some chunks.")
        step("Fold in the tomato, lime juice and remaining coriander. Taste and adjust salt and lime.")
        storage = "Press cling film onto the surface and refrigerate; best within 1 day (it browns over time)."
        reheating = null
    }
    recipe("jerk_chicken", "Jerk chicken", "caribbean", "Jamaican", 6, 20, 45) {
        description = "Chicken marinated in a fiery paste of Scotch bonnet, allspice, thyme, spring onion and ginger, then grilled or roasted until charred."
        equipment("Blender", "Barbecue or roasting tray")
        tags("high-protein", "gluten-free", "dairy-free", "dinner")
        notes += "Scotch bonnets are very hot: wear gloves to handle them and start with one."
        notes += "Traditionally smoked over pimento (allspice) wood; an oven or barbecue gives a good home result."
        ing("chicken_drumstick", 1500.0, GRAM, "Chicken drumsticks and thighs, bone-in", "slashed to the bone")
        group("Jerk marinade")
        ing("scallion", 6.0, PIECE, "Spring onions")
        ing("onion", 70.0, GRAM, "Small onion")
        ing("green_chili", 2.0, PIECE, "Scotch bonnet chillies (1–3, to taste)")
        ing("garlic", 4.0, CLOVE, "Garlic")
        ing("ginger", 1.0, TABLESPOON, "Ginger")
        ing("allspice", 3.0, TEASPOON, "Ground allspice (pimento)")
        ing("oregano", 2.0, TEASPOON, "Thyme leaves (fresh or dried)")
        ing("cinnamon", 0.5, TEASPOON, "Ground cinnamon")
        ing("black_pepper", 1.0, TEASPOON, "Black pepper")
        ing("brown_sugar", 2.0, TABLESPOON, "Brown sugar")
        ing("soy_sauce", 3.0, TABLESPOON, "Soy sauce")
        ing("lime", 3.0, TABLESPOON, "Lime juice")
        ing("vegetable_oil", 2.0, TABLESPOON, "Oil")
        ing("salt", 1.0, TEASPOON, "Salt")
        step("Blend all the marinade ingredients to a thick paste.", 2.0)
        step("Rub the paste into the chicken and marinate in the fridge for at least 4 hours, ideally overnight.", 240.0)
        step("Heat the oven to 200 °C (or a barbecue to medium-hot).", tempC = 200)
        step("Roast or grill, turning once, until charred and cooked through.", 45.0, 200)
        step("Rest 5 minutes and serve with rice and peas.", 5.0)
        safety(POULTRY_SAFETY)
    }
    recipe("rice_and_peas", "Rice and peas", "caribbean", "Jamaican", 6, 10, 35, EASY) {
        description = "Long-grain rice cooked in coconut milk with kidney beans (the \"peas\"), thyme, allspice and a whole Scotch bonnet for fragrance."
        equipment("Heavy lidded saucepan")
        tags("vegan", "dairy-free", "gluten-free", "side")
        ing("kidney_beans", 400.0, GRAM, "Kidney beans, canned", "drained", cooked = true)
        ing("coconut_milk", 400.0, MILLILITRE, "Coconut milk")
        ing("water", 500.0, MILLILITRE, "Water")
        ing("scallion", 2.0, PIECE, "Spring onions", "bruised")
        ing("garlic", 2.0, CLOVE, "Garlic", "crushed")
        ing("oregano", 1.0, TEASPOON, "Thyme (fresh sprigs or dried)")
        ing("allspice", 4.0, PIECE, "Allspice berries")
        ing("green_chili", 1.0, PIECE, "Scotch bonnet chilli, whole", "leave unbroken")
        ing("salt", 1.5, TEASPOON, "Salt")
        ing("rice_white", 400.0, GRAM, "Long-grain rice", "rinsed")
        step("Bring the coconut milk, water, beans, spring onions, garlic, thyme, allspice, chilli and salt to a simmer.", 5.0)
        step("Stir in the rice, return to a simmer, then cover tightly and cook on the lowest heat until the liquid is absorbed.", 25.0)
        step("Rest covered off the heat for 5 minutes. Remove the chilli, spring onions and thyme stems and fluff with a fork.", 5.0)
        safety(RICE_SAFETY)
    }
    recipe("overnight_oats", "Overnight oats with berries", "international", null, 1, 5, 0, EASY) {
        description = "Rolled oats soaked overnight in milk and yogurt with chia, topped with berries. No cooking needed."
        equipment("Jar or bowl with a lid")
        tags("vegetarian", "breakfast", "meal-prep", "no-cook")
        ing("oats", 50.0, GRAM, "Rolled oats")
        ing("milk_low_fat", 150.0, MILLILITRE, "Milk (or a plant milk)")
        ing("greek_yogurt_nonfat", 50.0, GRAM, "Greek yogurt")
        ing("chia_seeds", 1.0, TABLESPOON, "Chia seeds")
        ing("honey", 1.0, TEASPOON, "Honey or maple syrup")
        ing("blueberries", 75.0, GRAM, "Blueberries or other berries")
        step("Stir the oats, milk, yogurt, chia and honey together in a jar.", 2.0)
        step("Cover and refrigerate overnight (at least 6 hours).", 360.0)
        step("Stir, loosen with a splash of milk if needed, and top with berries.")
        storage = "Keep covered in the fridge; eat within 2 days."
        reheating = null
    }
    recipe("pancakes", "Buttermilk-style pancakes", "american", null, 4, 10, 20, EASY) {
        description = "Thick, fluffy American-style pancakes made with milk and baking powder, served with maple syrup."
        equipment("Mixing bowls", "Whisk", "Non-stick frying pan or griddle")
        tags("vegetarian", "breakfast")
        ing("flour", 190.0, GRAM, "Plain flour")
        ing("sugar", 2.0, TABLESPOON, "Sugar")
        ing("baking_powder", 2.0, TEASPOON, "Baking powder")
        ing("salt", 0.5, TEASPOON, "Salt")
        ing("milk_whole", 300.0, MILLILITRE, "Whole milk (or buttermilk)")
        ing("egg", 1.0, PIECE, "Egg")
        ing("butter", 3.0, TABLESPOON, "Butter", "melted")
        ing("vegetable_oil", 1.0, TEASPOON, "Oil for the pan")
        ing("maple_syrup", 4.0, TABLESPOON, "Maple syrup, to serve")
        ing("blueberries", 100.0, GRAM, "Blueberries, to serve", optional = true)
        step("Whisk the flour, sugar, baking powder and salt in a bowl.")
        step("Whisk the milk, egg and melted butter, pour into the dry ingredients and stir until just combined (a few lumps are fine). Rest 5 minutes.", 5.0)
        step("Heat a lightly oiled pan over medium heat. Pour in ladlefuls of batter and cook until bubbles appear and the edges set, then flip.", 3.0)
        step("Cook the second side until golden. Repeat with the rest of the batter and serve with maple syrup.", 2.0)
        storage = "Refrigerate cooled pancakes for up to 2 days, or freeze interleaved with paper."
        reheating = "Warm in a toaster or a dry pan."
    }
    recipe("omelette", "Cheese and herb omelette", "international", null, 1, 3, 4, EASY) {
        description = "Three eggs cooked quickly in butter and folded around grated cheese and spring onion."
        equipment("Non-stick frying pan (20 cm)", "Spatula")
        tags("vegetarian", "high-protein", "gluten-free", "breakfast", "quick")
        ing("egg", 3.0, PIECE, "Eggs")
        ing("butter", 1.0, TABLESPOON, "Butter")
        ing("cheddar", 30.0, GRAM, "Cheddar or Gruyère", "grated")
        ing("scallion", 1.0, PIECE, "Spring onion or chives", "finely sliced")
        ing("salt", 1.0, PINCH, "Salt")
        ing("black_pepper", 1.0, PINCH, "Black pepper")
        step("Beat the eggs with salt and pepper.")
        step("Melt the butter in the pan over medium-high heat until foaming. Add the eggs and stir, then let them set, tilting the pan.", 2.0)
        step("While the top is still slightly soft, scatter over the cheese and spring onion, fold and slide onto a plate.", 1.0)
        safety(EGG_SAFETY)
        storage = "Best eaten immediately."
        reheating = null
    }
}
