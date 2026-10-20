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

private const val POULTRY_SAFETY = "Cook chicken until the thickest piece reaches 74 °C (165 °F) and the juices run clear."
private const val MINCE_SAFETY = "Cook minced meat to 71–74 °C (160–165 °F); it should not be pink in the centre."
private const val EGG_SAFETY = "Cook eggs until whites are set; serve runny yolks only to people not at higher risk (pregnancy, young children, older adults)."
private const val RICE_SAFETY = "Cool leftover rice quickly (within 1 hour), refrigerate, and reheat only once until steaming hot."
private const val FRIDGE_3_DAYS = "Refrigerate within 2 hours in a covered container; eat within 3 days."
private const val REHEAT = "Reheat until steaming hot throughout (74 °C / 165 °F)."

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
}.items
