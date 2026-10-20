package app.nouri.core.dishes

import app.nouri.core.dishes.DishCategory.BOWL
import app.nouri.core.dishes.DishCategory.BREAD
import app.nouri.core.dishes.DishCategory.BREAKFAST
import app.nouri.core.dishes.DishCategory.CURRY_STEW
import app.nouri.core.dishes.DishCategory.DESSERT
import app.nouri.core.dishes.DishCategory.DUMPLING
import app.nouri.core.dishes.DishCategory.FRIED_SNACK
import app.nouri.core.dishes.DishCategory.GRILLED_MEAT
import app.nouri.core.dishes.DishCategory.LENTIL_LEGUME
import app.nouri.core.dishes.DishCategory.NOODLES_PASTA
import app.nouri.core.dishes.DishCategory.NOODLE_SOUP
import app.nouri.core.dishes.DishCategory.PIZZA
import app.nouri.core.dishes.DishCategory.RICE_DISH
import app.nouri.core.dishes.DishCategory.SALAD
import app.nouri.core.dishes.DishCategory.SANDWICH_WRAP
import app.nouri.core.dishes.DishCategory.SIDE
import app.nouri.core.dishes.DishCategory.SOUP
import app.nouri.core.dishes.DishCategory.SUSHI
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit

/**
 * Reference dishes. Compositions are editorial home-style recipes (raw weights)
 * with an explicit cooked batch weight, so nutrition per 100 g is calculated by
 * [DishNutrition] rather than asserted. Energy variability expresses how much real
 * preparations differ (mostly oil/ghee/cream), and feeds the calorie range shown
 * after a photo scan.
 */
object DishCatalog {
    val all: List<DishDefinition> by lazy { buildDishes() }
    val byId: Map<String, DishDefinition> by lazy { all.associateBy { it.id } }
    operator fun get(id: String): DishDefinition? = byId[id]
}

private class DishBuilder(val id: String, val name: String, val cuisineId: String, val region: String?, val category: DishCategory) {
    val aliases = mutableListOf<String>()
    val components = mutableListOf<DishComponent>()
    var batch = 0.0
    var serving = Triple(0.0, 0.0, 0.0)
    val portions = mutableListOf<FoodPortion>()
    var variability = 0.2
    var variabilityNote = ""
    val lookalikes = mutableListOf<String>()
    var cues = ""
    var restaurantFactor = 1.15
    var halalCaution: String? = null

    fun aliases(vararg a: String) { aliases += a.map { it.lowercase() } }
    fun c(id: String, grams: Double, consumed: Double = 1.0, cooked: Boolean = false) {
        components += DishComponent(ingredientId = id, grams = grams, consumedFraction = consumed, cooked = cooked)
    }
    fun food(ref: String, grams: Double) { components += DishComponent(foodRefId = ref, grams = grams) }
    fun batch(g: Double) { batch = g }
    fun serving(typical: Double, low: Double, high: Double) { serving = Triple(typical, low, high) }
    fun cup(g: Double) { portions += FoodPortion(QuantityUnit.CUP, g, label = "1 cup") }
    fun piece(g: Double, label: String) { portions += FoodPortion(QuantityUnit.PIECE, g, label = label) }
    fun plate(g: Double) { portions += FoodPortion(QuantityUnit.PLATE, g, label = "1 plate") }
    fun bowl(g: Double) { portions += FoodPortion(QuantityUnit.BOWL, g, label = "1 bowl") }
    fun slice(g: Double, label: String) { portions += FoodPortion(QuantityUnit.SLICE, g, label = label) }
    fun variability(v: Double, note: String) { variability = v; variabilityNote = note }
    fun lookalikes(vararg ids: String) { lookalikes += ids }

    fun build() = DishDefinition(
        id = id, name = name, cuisineId = cuisineId, region = region, category = category,
        aliases = (aliases + name.lowercase()).distinct(), composition = components.toList(),
        batchCookedGrams = batch, servingGrams = serving.first, servingLowGrams = serving.second,
        servingHighGrams = serving.third,
        portions = portions.toList() + FoodPortion(QuantityUnit.SERVING, serving.first, label = "1 typical serving"),
        energyVariability = variability, variabilityNote = variabilityNote, lookalikes = lookalikes.toList(),
        visualCues = cues, restaurantEnergyFactor = restaurantFactor, halalCaution = halalCaution,
    )
}

private class DishListBuilder {
    val items = mutableListOf<DishDefinition>()
    fun dish(id: String, name: String, cuisine: String, region: String?, category: DishCategory, block: DishBuilder.() -> Unit) {
        items += DishBuilder(id, name, cuisine, region, category).apply(block).build()
    }
}

private const val OIL_NOTE = "Oil and ghee vary widely between cooks and restaurants"

private fun buildDishes(): List<DishDefinition> = DishListBuilder().apply {
    // ============================================================ South Asia
    dish("chicken_biryani", "Chicken biryani", "pakistani", "Sindhi / Karachi", RICE_DISH) {
        aliases("biryani", "biriyani", "biriani", "sindhi biryani", "karachi biryani", "murgh biryani", "chicken biriyani", "hyderabadi biryani")
        c("rice_white", 500.0); c("chicken_curry_cut", 1000.0); c("onion", 300.0); c("yogurt", 250.0); c("tomato", 200.0)
        c("potato", 250.0); c("vegetable_oil", 120.0); c("ghee", 30.0); c("green_chili", 20.0); c("ginger", 20.0)
        c("garlic", 20.0); c("garam_masala", 10.0); c("chili_powder", 8.0); c("turmeric", 3.0); c("salt", 15.0)
        c("mint", 10.0); c("cilantro", 10.0); c("lemon_juice", 15.0); c("milk_whole", 30.0); c("saffron", 0.1)
        batch(2700.0); serving(350.0, 250.0, 550.0); cup(200.0); plate(400.0)
        variability(0.25, OIL_NOTE + "; the rice-to-meat ratio also differs")
        lookalikes("chicken_pulao", "plain_rice", "chicken_fried_rice", "jollof_rice")
        cues = "Layered long-grain rice with grains of mixed colour (white, yellow/orange), visible meat on the bone, fried onions, whole spices (bay leaf, cardamom, cloves), mint or coriander; Sindhi style adds potato and plum"
        restaurantFactor = 1.2
    }
    dish("chicken_pulao", "Chicken pulao (yakhni pulao)", "pakistani", "Punjabi / Pashtun", RICE_DISH) {
        aliases("pulao", "pilau", "pilaf", "yakhni pulao", "chicken pilau", "chicken pilaf", "kabuli pulao")
        c("rice_white", 500.0); c("chicken_curry_cut", 800.0); c("onion", 200.0); c("vegetable_oil", 80.0); c("yogurt", 100.0)
        c("garlic", 15.0); c("ginger", 15.0); c("garam_masala", 6.0); c("cumin", 4.0); c("black_pepper", 3.0); c("salt", 14.0)
        batch(2300.0); serving(350.0, 250.0, 500.0); cup(190.0); plate(400.0)
        variability(0.2, OIL_NOTE)
        lookalikes("chicken_biryani", "plain_rice", "chicken_fried_rice")
        cues = "Uniformly light-brown rice cooked in stock, whole spices, fewer red spices and no coloured layers"
    }
    dish("plain_rice", "Steamed white rice", "international", null, RICE_DISH) {
        aliases("rice", "white rice", "boiled rice", "steamed rice", "chawal", "basmati rice", "jasmine rice", "plain rice", "gohan")
        c("rice_white", 100.0, cooked = true)
        batch(100.0); serving(180.0, 120.0, 300.0); cup(158.0); bowl(200.0)
        variability(0.05, "Close to reference values unless butter or oil is added")
        lookalikes("chicken_pulao", "chicken_biryani")
        cues = "Plain white grains without visible fat, spices or colour"
        restaurantFactor = 1.0
    }
    dish("beef_nihari", "Beef nihari", "pakistani", "Delhi / Karachi / Lahore", CURRY_STEW) {
        aliases("nihari", "nahari", "nalli nihari", "beef nahari", "mutton nihari")
        c("beef_stew", 1000.0); c("ghee", 80.0); c("vegetable_oil", 60.0); c("atta", 40.0); c("onion", 150.0)
        c("ginger", 30.0); c("garlic", 20.0); c("garam_masala", 12.0); c("chili_powder", 10.0); c("fennel_seed", 5.0)
        c("turmeric", 3.0); c("salt", 15.0)
        batch(2000.0); serving(300.0, 200.0, 450.0); cup(240.0); bowl(300.0)
        variability(0.3, "The floating fat layer (tari) may be eaten or skimmed")
        lookalikes("haleem", "chicken_karahi", "beef_rendang")
        cues = "Glossy reddish-brown gravy with a layer of oil, large tender beef pieces, garnished with julienned ginger, green chili and lemon"
        restaurantFactor = 1.25
    }
    dish("haleem", "Haleem", "pakistani", "Hyderabadi / Pakistani", CURRY_STEW) {
        aliases("haleem", "daleem", "harees", "khichda")
        c("beef_stew", 500.0); c("bulgur", 150.0); c("chana_dal", 100.0); c("lentils_red", 50.0); c("moong_dal", 50.0)
        c("urad_dal", 50.0); c("ghee", 60.0); c("vegetable_oil", 60.0); c("onion", 200.0); c("ginger", 20.0); c("garlic", 20.0)
        c("garam_masala", 10.0); c("chili_powder", 8.0); c("turmeric", 3.0); c("salt", 14.0)
        batch(2400.0); serving(300.0, 200.0, 450.0); cup(240.0); bowl(300.0)
        variability(0.25, "Fried-onion and ghee toppings vary")
        lookalikes("beef_nihari", "masoor_dal")
        cues = "Thick, stringy porridge-like stew of wheat, lentils and shredded meat, topped with fried onions, ginger, lemon"
    }
    dish("masoor_dal", "Masoor dal (red lentil curry)", "pakistani", "Pakistani / North Indian", LENTIL_LEGUME) {
        aliases("dal", "daal", "dhal", "lentils", "masoor ki daal", "dal tadka", "tarka dal", "red lentil curry", "dal fry")
        c("lentils_red", 250.0); c("onion", 100.0); c("tomato", 150.0); c("vegetable_oil", 30.0); c("ghee", 15.0)
        c("garlic", 10.0); c("ginger", 10.0); c("cumin", 4.0); c("turmeric", 2.0); c("chili_powder", 3.0); c("salt", 8.0)
        c("green_chili", 5.0); c("cilantro", 5.0)
        batch(1300.0); serving(250.0, 150.0, 350.0); cup(240.0); bowl(250.0)
        variability(0.3, "Consistency (water) and the tadka (oil/ghee) change energy density a lot")
        lookalikes("chana_dal_curry", "misir_wat", "lentil_soup")
        cues = "Yellow-orange lentil curry, smooth or soupy, often with tempered cumin and garlic on top"
    }
    dish("chana_dal_curry", "Chana dal", "pakistani", "Pakistani / North Indian", LENTIL_LEGUME) {
        aliases("chana daal", "chane ki daal", "bengal gram dal", "chana dal fry")
        c("chana_dal", 250.0); c("onion", 100.0); c("tomato", 120.0); c("vegetable_oil", 35.0); c("ghee", 10.0)
        c("garlic", 10.0); c("ginger", 10.0); c("cumin", 4.0); c("turmeric", 2.0); c("chili_powder", 3.0); c("salt", 8.0)
        batch(1200.0); serving(250.0, 150.0, 350.0); cup(240.0); bowl(250.0)
        variability(0.3, "Water content and tempering fat vary")
        lookalikes("masoor_dal", "chana_masala")
        cues = "Golden split lentils that hold their shape, thicker than masoor dal"
    }
    dish("chana_masala", "Chana masala (chole)", "indian", "Punjabi", LENTIL_LEGUME) {
        aliases("chole", "chholay", "cholay", "chickpea curry", "chana masala", "pindi chole", "chana")
        c("chickpeas_cooked", 800.0); c("onion", 200.0); c("tomato", 250.0); c("vegetable_oil", 50.0); c("ginger", 15.0)
        c("garlic", 15.0); c("garam_masala", 8.0); c("chili_powder", 5.0); c("cumin", 4.0); c("turmeric", 2.0); c("salt", 10.0)
        batch(1300.0); serving(250.0, 150.0, 350.0); cup(240.0); bowl(250.0)
        variability(0.25, OIL_NOTE)
        lookalikes("chana_dal_curry", "rajma")
        cues = "Whole chickpeas in a brown, tomato-onion gravy"
    }
    dish("rajma", "Rajma (kidney bean curry)", "indian", "Punjabi / Kashmiri", LENTIL_LEGUME) {
        aliases("rajma masala", "kidney bean curry", "rajmah")
        c("kidney_beans", 700.0, cooked = true); c("onion", 150.0); c("tomato", 250.0); c("vegetable_oil", 40.0); c("butter", 10.0)
        c("ginger", 10.0); c("garlic", 10.0); c("garam_masala", 6.0); c("chili_powder", 4.0); c("cumin", 3.0); c("salt", 9.0)
        batch(1250.0); serving(250.0, 150.0, 350.0); cup(240.0)
        variability(0.25, OIL_NOTE)
        lookalikes("chana_masala")
        cues = "Dark red kidney beans in thick red-brown gravy"
    }
    dish("chicken_karahi", "Chicken karahi", "pakistani", "Peshawari / Lahori", CURRY_STEW) {
        aliases("karahi", "kadai chicken", "kadhai chicken", "chicken kadai", "peshawari karahi", "lahori karahi", "chicken karhai")
        c("chicken_curry_cut", 1000.0); c("tomato", 500.0); c("vegetable_oil", 100.0); c("ghee", 20.0); c("ginger", 25.0)
        c("garlic", 20.0); c("green_chili", 25.0); c("black_pepper", 4.0); c("cumin", 3.0); c("chili_powder", 5.0); c("salt", 12.0)
        c("cilantro", 10.0)
        batch(1200.0); serving(280.0, 180.0, 400.0); cup(220.0); plate(300.0)
        variability(0.3, "Restaurant karahi often uses far more oil or ghee")
        lookalikes("butter_chicken", "chicken_curry", "beef_nihari")
        cues = "Bone-in chicken in a thick tomato masala that clings to the meat, oil separating at the edges, julienned ginger, green chillies, served in a wok-like karahi"
        restaurantFactor = 1.3
    }
    dish("chicken_curry", "Chicken curry (salan)", "pakistani", "Home-style", CURRY_STEW) {
        aliases("chicken salan", "murgh salan", "chicken masala", "desi chicken curry", "chicken korma", "shorba")
        c("chicken_curry_cut", 1000.0); c("onion", 300.0); c("tomato", 300.0); c("yogurt", 150.0); c("vegetable_oil", 90.0)
        c("ginger", 20.0); c("garlic", 20.0); c("garam_masala", 8.0); c("chili_powder", 8.0); c("turmeric", 3.0); c("coriander_seed", 5.0)
        c("salt", 12.0); c("water", 300.0)
        batch(1900.0); serving(300.0, 200.0, 450.0); cup(235.0); bowl(300.0)
        variability(0.3, OIL_NOTE)
        lookalikes("chicken_karahi", "butter_chicken")
        cues = "Bone-in chicken in a thinner onion-tomato gravy with visible oil"
    }
    dish("butter_chicken", "Butter chicken (murgh makhani)", "indian", "Punjabi / Delhi", CURRY_STEW) {
        aliases("murgh makhani", "makhani chicken", "chicken makhani", "chicken tikka masala", "tikka masala")
        c("chicken_thigh", 700.0); c("yogurt", 150.0); c("butter", 60.0); c("cream_heavy", 150.0); c("tomato_canned", 400.0)
        c("ginger", 20.0); c("garlic", 20.0); c("garam_masala", 8.0); c("chili_powder", 6.0); c("fenugreek", 3.0); c("sugar", 10.0)
        c("salt", 12.0); c("vegetable_oil", 20.0); c("cashews", 30.0)
        batch(1400.0); serving(250.0, 180.0, 350.0); cup(240.0); bowl(250.0)
        variability(0.3, "Cream and butter quantities vary a lot, especially in restaurants")
        lookalikes("chicken_karahi", "chicken_curry")
        cues = "Boneless chicken pieces in a smooth orange creamy tomato sauce, often a swirl of cream"
        restaurantFactor = 1.3
    }
    dish("chicken_tikka", "Chicken tikka", "pakistani", "Punjabi", GRILLED_MEAT) {
        aliases("tikka", "chicken tikka boti", "tandoori chicken", "chicken boti", "malai boti")
        c("chicken_thigh", 800.0); c("yogurt", 200.0, consumed = 0.5); c("lemon_juice", 30.0); c("ginger", 15.0); c("garlic", 15.0)
        c("chili_powder", 8.0); c("garam_masala", 6.0); c("vegetable_oil", 20.0, consumed = 0.6); c("salt", 10.0)
        batch(700.0); serving(200.0, 120.0, 300.0); piece(30.0, "1 tikka piece")
        variability(0.15, "Leaner than curries; basting with butter adds energy")
        lookalikes("seekh_kebab", "chicken_shawarma")
        cues = "Charred orange-red boneless chunks on skewers or a sizzler, with onion rings and lemon"
    }
    dish("seekh_kebab", "Seekh kebab", "pakistani", "Lahori / Mughlai", GRILLED_MEAT) {
        aliases("seekh kabab", "sheekh kebab", "kebab", "kabab", "beef seekh", "chicken seekh kebab")
        c("beef_ground", 1000.0); c("onion", 150.0); c("green_chili", 20.0); c("ginger", 15.0); c("garlic", 15.0)
        c("cilantro", 15.0); c("garam_masala", 8.0); c("chili_powder", 6.0); c("salt", 14.0); c("vegetable_oil", 20.0, consumed = 0.5)
        batch(900.0); serving(180.0, 90.0, 300.0); piece(60.0, "1 kebab")
        variability(0.25, "Fat content of the mince is the main variable")
        lookalikes("kofta_kebab", "chicken_tikka")
        cues = "Long cylindrical minced-meat kebabs with char marks, flecked with herbs"
    }
    dish("chapati", "Chapati / roti", "pakistani", "South Asian", BREAD) {
        aliases("roti", "chapatti", "phulka", "chapati", "chapathi", "tandoori roti", "whole wheat roti")
        c("atta", 300.0); c("salt", 2.0)
        batch(400.0); serving(80.0, 40.0, 160.0); piece(40.0, "1 medium roti (~15 cm)")
        variability(0.1, "Larger rotis or ghee on top add energy (log ghee separately)")
        lookalikes("paratha", "naan")
        cues = "Thin round whole-wheat flatbread with brown spots, matte surface"
        restaurantFactor = 1.0
    }
    dish("paratha", "Paratha", "pakistani", "Punjabi", BREAD) {
        aliases("parantha", "plain paratha", "lachha paratha", "laccha paratha", "parotta")
        c("atta", 300.0); c("ghee", 60.0); c("salt", 3.0)
        batch(480.0); serving(80.0, 60.0, 160.0); piece(80.0, "1 paratha")
        variability(0.3, "Ghee or oil used for layering and frying varies")
        lookalikes("chapati", "naan")
        cues = "Flaky layered flatbread with a shiny, golden fried surface"
    }
    dish("naan", "Naan", "pakistani", "Tandoor (restaurant)", BREAD) {
        aliases("nan", "butter naan", "garlic naan", "plain naan", "tandoori naan", "roghni naan")
        c("flour", 500.0); c("yogurt", 100.0); c("milk_whole", 100.0); c("yeast", 5.0); c("sugar", 10.0); c("salt", 8.0)
        c("vegetable_oil", 20.0); c("butter", 30.0)
        batch(820.0); serving(100.0, 70.0, 200.0); piece(100.0, "1 naan")
        variability(0.2, "Size and butter brushing vary")
        lookalikes("chapati", "paratha")
        cues = "Thick, puffy, teardrop or oval leavened bread with large charred bubbles"
        restaurantFactor = 1.1
    }
    dish("raita", "Raita", "pakistani", "South Asian", SIDE) {
        aliases("cucumber raita", "mint raita", "dahi raita", "kheera raita", "boondi raita")
        c("yogurt", 400.0); c("cucumber", 150.0); c("mint", 5.0); c("cumin", 2.0); c("salt", 4.0)
        batch(550.0); serving(100.0, 50.0, 150.0); cup(240.0)
        variability(0.15, "Full-fat vs low-fat yogurt")
        lookalikes("toum")
        cues = "White yogurt side dish with grated cucumber or mint, sometimes cumin sprinkled"
        restaurantFactor = 1.0
    }
    dish("kachumber_salad", "Kachumber salad", "pakistani", "South Asian", SALAD) {
        aliases("salad", "kachumber", "desi salad", "onion tomato salad", "cucumber tomato salad", "shirazi salad")
        c("cucumber", 200.0); c("tomato", 200.0); c("onion", 100.0); c("lemon_juice", 20.0); c("green_chili", 5.0)
        c("cilantro", 5.0); c("salt", 3.0)
        batch(530.0); serving(100.0, 50.0, 200.0); cup(150.0)
        variability(0.1, "Low-energy unless dressed with oil")
        lookalikes("green_salad", "greek_salad")
        cues = "Diced cucumber, tomato and onion, no leafy greens"
        restaurantFactor = 1.0
    }
    dish("palak_paneer", "Palak paneer", "indian", "Punjabi", CURRY_STEW) {
        aliases("saag paneer", "spinach paneer", "palak paneer")
        c("spinach", 600.0); c("paneer", 250.0); c("onion", 100.0); c("tomato", 100.0); c("ghee", 20.0); c("vegetable_oil", 20.0)
        c("cream_heavy", 50.0); c("garlic", 10.0); c("ginger", 10.0); c("cumin", 3.0); c("garam_masala", 4.0); c("salt", 8.0)
        batch(950.0); serving(250.0, 150.0, 350.0); cup(240.0)
        variability(0.3, "Cream, butter and paneer quantity vary")
        lookalikes("sarson_ka_saag")
        cues = "Bright green puréed spinach with white paneer cubes"
    }
    dish("sarson_ka_saag", "Sarson ka saag", "pakistani", "Punjabi", CURRY_STEW) {
        aliases("saag", "sarson da saag", "mustard greens curry")
        c("mustard_greens", 700.0); c("spinach", 300.0); c("cornmeal", 40.0); c("ghee", 50.0); c("onion", 100.0)
        c("garlic", 15.0); c("ginger", 15.0); c("green_chili", 10.0); c("salt", 10.0)
        batch(950.0); serving(250.0, 150.0, 350.0); cup(240.0)
        variability(0.35, "Often finished with a large dollop of butter or ghee")
        lookalikes("palak_paneer")
        cues = "Dark green coarse purée, often with butter on top, served with makki roti"
    }
    dish("aloo_gobi", "Aloo gobi", "indian", "Punjabi", CURRY_STEW) {
        aliases("aloo gobhi", "potato cauliflower curry", "gobi aloo")
        c("potato", 400.0); c("cauliflower", 500.0); c("onion", 100.0); c("tomato", 100.0); c("vegetable_oil", 50.0)
        c("ginger", 10.0); c("garlic", 10.0); c("turmeric", 3.0); c("cumin", 4.0); c("chili_powder", 3.0); c("salt", 8.0); c("cilantro", 5.0)
        batch(1000.0); serving(200.0, 120.0, 300.0); cup(170.0)
        variability(0.3, OIL_NOTE)
        cues = "Dry yellow turmeric-coloured potato and cauliflower florets"
    }
    dish("samosa", "Samosa (potato)", "pakistani", "South Asian", FRIED_SNACK) {
        aliases("samosas", "aloo samosa", "vegetable samosa", "samoosa", "sambusa")
        c("flour", 250.0); c("vegetable_oil", 40.0); c("vegetable_oil", 400.0, consumed = 0.15); c("potato", 500.0); c("peas", 100.0)
        c("onion", 50.0); c("green_chili", 10.0); c("cumin", 3.0); c("garam_masala", 4.0); c("salt", 6.0); c("cilantro", 5.0)
        batch(900.0); serving(150.0, 75.0, 300.0); piece(75.0, "1 medium samosa")
        variability(0.3, "Oil absorbed during frying varies with temperature and size")
        lookalikes("pakora")
        cues = "Triangular deep-fried pastry with a blistered golden crust"
    }
    dish("pakora", "Pakora (vegetable fritters)", "pakistani", "South Asian", FRIED_SNACK) {
        aliases("pakoras", "pakoray", "bhajia", "bhaji", "onion bhaji", "pakode")
        c("besan", 200.0); c("onion", 200.0); c("potato", 150.0); c("spinach", 50.0); c("vegetable_oil", 400.0, consumed = 0.15)
        c("chili_powder", 3.0); c("salt", 5.0)
        batch(700.0); serving(100.0, 50.0, 200.0); piece(25.0, "1 pakora")
        variability(0.3, "Oil absorption varies")
        lookalikes("samosa", "falafel")
        cues = "Irregular, craggy golden fritters"
    }
    dish("dosa", "Dosa", "indian", "Tamil / Udupi", BREAD) {
        aliases("plain dosa", "masala dosa", "dosai", "dosa crepe")
        c("rice_white", 300.0); c("urad_dal", 100.0); c("fenugreek", 3.0); c("salt", 6.0); c("vegetable_oil", 50.0, consumed = 0.8)
        batch(760.0); serving(150.0, 75.0, 250.0); piece(76.0, "1 dosa (plain)")
        variability(0.25, "Ghee/oil on the griddle and potato filling vary")
        cues = "Thin, crisp golden crêpe, often rolled or folded, served with chutneys and sambar"
    }
    dish("kheer", "Kheer (rice pudding)", "pakistani", "South Asian", DESSERT) {
        aliases("rice kheer", "firni", "phirni", "payasam", "rice pudding", "sheer khurma")
        c("milk_whole", 1000.0); c("rice_white", 80.0); c("sugar", 100.0); c("cardamom", 2.0); c("almonds", 20.0); c("pistachios", 10.0)
        batch(900.0); serving(150.0, 100.0, 250.0); cup(240.0)
        variability(0.2, "Sugar and reduction level vary")
        cues = "Creamy white pudding with rice grains, nuts and cardamom"
    }

    // ============================================================ Middle East & North Africa
    dish("chicken_shawarma", "Chicken shawarma (meat)", "lebanese", "Levantine", GRILLED_MEAT) {
        aliases("shawarma", "shawerma", "chicken shawerma", "shwarma", "doner", "doner kebab", "chicken doner")
        c("chicken_thigh", 1000.0); c("yogurt", 100.0, consumed = 0.6); c("olive_oil", 40.0); c("lemon_juice", 30.0); c("garlic", 15.0)
        c("paprika", 5.0); c("cumin", 5.0); c("cinnamon", 2.0); c("black_pepper", 3.0); c("salt", 10.0)
        batch(750.0); serving(150.0, 100.0, 250.0); cup(140.0)
        variability(0.2, "Fat dripping from the spit and basting vary")
        lookalikes("chicken_tikka")
        cues = "Thin shaved slices of seasoned, browned chicken"
    }
    dish("toum", "Garlic sauce (toum)", "lebanese", "Levantine", SIDE) {
        aliases("toum", "garlic sauce", "white sauce", "shawarma sauce", "garlic mayo", "toom")
        c("garlic", 60.0); c("vegetable_oil", 400.0); c("lemon_juice", 60.0); c("salt", 5.0)
        batch(525.0); serving(30.0, 10.0, 60.0); portions += FoodPortion(QuantityUnit.TABLESPOON, 14.0, label = "1 tbsp")
        variability(0.25, "Some versions are yogurt- or mayo-based and much lighter")
        lookalikes("raita")
        cues = "Thick bright-white emulsified sauce"
        restaurantFactor = 1.0
    }
    dish("shawarma_wrap", "Chicken shawarma wrap", "lebanese", "Levantine", SANDWICH_WRAP) {
        aliases("shawarma sandwich", "shawarma roll", "chicken wrap", "doner wrap", "durum")
        c("pita", 90.0); c("chicken_thigh", 160.0); c("garlic", 4.0); c("vegetable_oil", 25.0); c("lemon_juice", 4.0)
        c("tomato", 30.0); c("lettuce", 20.0); food("usda:11937", 20.0)
        batch(310.0); serving(310.0, 220.0, 450.0); piece(310.0, "1 wrap")
        variability(0.3, "Garlic sauce quantity is the biggest variable")
        lookalikes("chicken_sandwich")
        cues = "Rolled flatbread, often toasted, filled with shaved chicken, pickles and garlic sauce"
        restaurantFactor = 1.2
    }
    dish("falafel", "Falafel", "egyptian", "Levantine / Egyptian (ta'ameya)", FRIED_SNACK) {
        aliases("felafel", "ta'ameya", "taameya", "falafel balls")
        c("chickpeas_dry", 500.0); c("onion", 100.0); c("garlic", 15.0); c("cilantro", 20.0); c("parsley", 20.0); c("cumin", 6.0)
        c("coriander_seed", 4.0); c("baking_soda", 3.0); c("salt", 8.0); c("vegetable_oil", 1000.0, consumed = 0.1)
        batch(1150.0); serving(100.0, 50.0, 200.0); piece(17.0, "1 falafel")
        variability(0.25, "Oil absorption varies")
        lookalikes("pakora", "kofta_kebab")
        cues = "Dark golden-brown fried balls or patties with a green interior"
    }
    dish("hummus", "Hummus", "lebanese", "Levantine", SIDE) {
        aliases("houmous", "hommus", "humus", "hummus bi tahini")
        c("chickpeas_cooked", 400.0); c("tahini", 60.0); c("olive_oil", 30.0); c("lemon_juice", 40.0); c("garlic", 6.0); c("salt", 5.0); c("water", 40.0)
        batch(570.0); serving(60.0, 30.0, 150.0); portions += FoodPortion(QuantityUnit.TABLESPOON, 15.0, label = "1 tbsp")
        variability(0.25, "Olive oil drizzled on top varies")
        cues = "Smooth beige dip, often with olive oil, paprika and whole chickpeas on top"
        restaurantFactor = 1.1
    }
    dish("tabbouleh", "Tabbouleh", "lebanese", "Levantine", SALAD) {
        aliases("tabouli", "tabouleh", "tabbouli")
        c("parsley", 150.0); c("bulgur", 50.0); c("tomato", 150.0); c("onion", 30.0); c("mint", 10.0); c("olive_oil", 40.0)
        c("lemon_juice", 40.0); c("salt", 3.0)
        batch(520.0); serving(120.0, 60.0, 200.0); cup(160.0)
        variability(0.25, "Olive oil quantity varies")
        lookalikes("kachumber_salad", "green_salad")
        cues = "Finely chopped green parsley salad flecked with tomato and bulgur"
    }
    dish("koshari", "Koshari", "egyptian", "Cairene", RICE_DISH) {
        aliases("koshary", "kushari")
        c("rice_white", 200.0); c("lentils_brown", 150.0); c("pasta", 150.0); c("chickpeas_cooked", 150.0); c("onion", 300.0)
        c("vegetable_oil", 150.0, consumed = 0.4); c("tomato_canned", 400.0); c("garlic", 10.0); c("vinegar", 30.0); c("cumin", 4.0)
        c("chili_powder", 2.0); c("salt", 10.0)
        batch(2000.0); serving(400.0, 250.0, 600.0); bowl(400.0)
        variability(0.25, "Amount of fried onions and their oil")
        cues = "Mix of rice, lentils, macaroni and chickpeas with tomato sauce and crispy fried onions"
    }
    dish("lamb_tagine", "Lamb tagine", "moroccan", "Marrakchi / Fassi", CURRY_STEW) {
        aliases("tajine", "tagine", "lamb tajine", "moroccan lamb")
        c("lamb", 800.0); c("onion", 250.0); c("tomato", 200.0); c("olive_oil", 40.0); c("garlic", 10.0); c("ginger", 10.0)
        c("cinnamon", 3.0); c("cumin", 4.0); c("paprika", 4.0); c("turmeric", 2.0); c("dates", 80.0); c("almonds", 30.0); c("salt", 10.0)
        c("cilantro", 10.0)
        batch(1300.0); serving(300.0, 200.0, 400.0); bowl(300.0)
        variability(0.25, "Cut of lamb and oil vary")
        cues = "Slow-cooked meat in a conical clay pot with dried fruit and almonds"
    }
    dish("vegetable_couscous", "Vegetable couscous", "moroccan", "Berber / Amazigh", BOWL) {
        aliases("couscous", "seksu", "couscous royale (vegetable)")
        c("couscous", 300.0); c("carrot", 200.0); c("zucchini", 200.0); c("chickpeas_cooked", 200.0); c("onion", 100.0)
        c("olive_oil", 30.0); c("butter", 20.0); c("cumin", 3.0); c("turmeric", 2.0); c("cinnamon", 1.0); c("salt", 10.0)
        batch(1450.0); serving(350.0, 250.0, 500.0); plate(400.0)
        variability(0.2, "Butter/smen worked into the grains varies")
        cues = "Fluffy fine grains piled with stewed vegetables and chickpeas"
    }
    dish("lentil_soup", "Red lentil soup (mercimek çorbası)", "turkish", "Anatolian", SOUP) {
        aliases("mercimek corbasi", "lentil soup", "shorbat adas", "red lentil soup")
        c("lentils_red", 250.0); c("onion", 100.0); c("carrot", 100.0); c("butter", 30.0); c("olive_oil", 15.0)
        c("tomato_paste", 20.0); c("cumin", 3.0); c("paprika", 3.0); c("salt", 8.0); c("water", 1500.0)
        batch(1900.0); serving(300.0, 200.0, 450.0); bowl(300.0); cup(245.0)
        variability(0.2, "Butter drizzle varies")
        lookalikes("masoor_dal", "misir_wat")
        cues = "Smooth orange purée soup, often with a lemon wedge and red pepper butter"
    }
    dish("kofta_kebab", "Kofta kebab", "turkish", "Turkish / Levantine", GRILLED_MEAT) {
        aliases("kofte", "köfte", "kufta", "adana kebab", "lamb kofta", "kafta")
        c("lamb_ground", 500.0); c("onion", 100.0); c("parsley", 20.0); c("cumin", 4.0); c("paprika", 4.0); c("salt", 8.0)
        batch(480.0); serving(180.0, 90.0, 300.0); piece(60.0, "1 kofta")
        variability(0.25, "Fat content of the mince")
        lookalikes("seekh_kebab", "falafel")
        cues = "Grilled oblong minced-meat skewers"
    }

    // ============================================================ Sub-Saharan Africa
    dish("jollof_rice", "Jollof rice", "nigerian", "Nigerian / Ghanaian", RICE_DISH) {
        aliases("jollof", "jolof", "benachin", "party jollof")
        c("rice_white", 500.0); c("tomato", 400.0); c("bell_pepper", 200.0); c("onion", 200.0); c("tomato_paste", 70.0)
        c("vegetable_oil", 100.0); c("green_chili", 10.0); c("garlic", 10.0); c("ginger", 10.0); c("bay_leaf", 1.0)
        c("garam_masala", 3.0); c("oregano", 2.0); c("salt", 10.0)
        batch(1900.0); serving(300.0, 200.0, 450.0); cup(200.0); plate(350.0)
        variability(0.25, OIL_NOTE)
        lookalikes("chicken_biryani", "kimchi_fried_rice")
        cues = "Uniformly orange-red tomato rice, sometimes with smoky bottom grains"
    }
    dish("injera", "Injera", "ethiopian", "Ethiopian / Eritrean", BREAD) {
        aliases("enjera", "teff flatbread")
        c("teff", 300.0); c("salt", 2.0)
        batch(720.0); serving(120.0, 60.0, 240.0); piece(120.0, "1 injera (large round)")
        variability(0.1, "Some injera blends wheat or barley flour with teff")
        cues = "Large spongy, porous sourdough flatbread used as a plate"
        restaurantFactor = 1.0
    }
    dish("doro_wat", "Doro wat", "ethiopian", "Amhara", CURRY_STEW) {
        aliases("doro wot", "doro wett", "ethiopian chicken stew")
        c("chicken_curry_cut", 1000.0); c("onion", 1000.0); c("ghee", 120.0); c("garam_masala", 30.0); c("garlic", 20.0)
        c("ginger", 20.0); c("egg", 300.0); c("salt", 10.0); c("lemon_juice", 20.0)
        batch(1800.0); serving(300.0, 200.0, 450.0); cup(240.0)
        variability(0.3, "Niter kibbeh (spiced butter) quantity varies")
        lookalikes("chicken_curry", "misir_wat")
        cues = "Very dark red-brown thick chicken stew with whole hard-boiled eggs, served on injera"
    }
    dish("misir_wat", "Misir wat", "ethiopian", "Ethiopian", LENTIL_LEGUME) {
        aliases("misir wot", "misir wot", "red lentil wat", "ethiopian lentils")
        c("lentils_red", 300.0); c("onion", 300.0); c("vegetable_oil", 60.0); c("garam_masala", 20.0); c("garlic", 15.0)
        c("ginger", 10.0); c("tomato_paste", 30.0); c("salt", 8.0)
        batch(1300.0); serving(200.0, 120.0, 300.0); cup(240.0)
        variability(0.3, OIL_NOTE)
        lookalikes("masoor_dal", "lentil_soup")
        cues = "Thick brick-red spiced lentil stew on injera"
    }
    dish("ugali", "Ugali", "kenyan", "East African", SIDE) {
        aliases("ugali", "sima", "nshima", "pap", "sadza", "posho", "fufu (maize)")
        c("cornmeal", 300.0); c("water", 900.0)
        batch(1100.0); serving(250.0, 150.0, 400.0)
        variability(0.1, "Stiffness (water) changes energy per gram")
        cues = "Dense white cake-like maize porridge"
        restaurantFactor = 1.0
    }
    dish("fried_plantain", "Fried plantain (dodo)", "nigerian", "West African / Caribbean", SIDE) {
        aliases("dodo", "plantain", "kelewele", "maduros", "tajadas", "fried plantains")
        c("plantain", 600.0); c("vegetable_oil", 300.0, consumed = 0.15); c("salt", 3.0)
        batch(520.0); serving(100.0, 60.0, 200.0); cup(140.0)
        variability(0.3, "Oil absorption varies with ripeness and frying")
        cues = "Caramelised golden-brown diagonal or cubed plantain pieces"
    }

    // ============================================================ East Asia
    dish("salmon_nigiri", "Salmon nigiri", "japanese", "Edomae (Tokyo)", SUSHI) {
        aliases("nigiri", "sake nigiri", "salmon sushi", "sushi")
        c("rice_glutinous", 220.0, cooked = true); c("vinegar", 10.0); c("sugar", 6.0); c("salt", 2.0); c("salmon", 130.0)
        batch(368.0); serving(222.0, 74.0, 370.0); piece(37.0, "1 piece")
        variability(0.1, "Rice ball size varies")
        lookalikes("california_roll")
        cues = "Oblong pressed rice topped with a slice of orange salmon"
        restaurantFactor = 1.0
    }
    dish("california_roll", "California roll", "japanese", "Japanese-American", SUSHI) {
        aliases("maki", "maki roll", "sushi roll", "uramaki", "california maki")
        c("rice_glutinous", 300.0, cooked = true); c("avocado", 60.0); c("cucumber", 40.0); c("shrimp", 60.0, cooked = true)
        c("mayonnaise", 15.0); c("sesame_seeds", 8.0); c("vinegar", 15.0); c("sugar", 10.0); food("usda:11446", 6.0)
        batch(514.0); serving(256.0, 128.0, 400.0); piece(32.0, "1 piece")
        variability(0.15, "Spicy mayo or tempura variants are much richer (imitation crab approximated as shrimp)")
        lookalikes("salmon_nigiri")
        cues = "Inside-out rolls with rice outside coated in sesame, avocado and crab inside"
    }
    dish("ramen", "Ramen (shoyu, chicken)", "japanese", "Tokyo-style shoyu", NOODLE_SOUP) {
        aliases("ramen", "shoyu ramen", "chicken ramen", "ramen noodles bowl", "tonkotsu ramen", "miso ramen")
        c("egg_noodles", 230.0, cooked = true); c("water", 450.0); c("soy_sauce", 30.0); c("chicken_thigh", 80.0); c("egg", 50.0)
        c("scallion", 10.0); c("sesame_oil", 5.0); c("vegetable_oil", 10.0); c("bok_choy", 40.0); c("mushroom", 20.0)
        c("ginger", 3.0); c("garlic", 3.0)
        batch(900.0); serving(900.0, 600.0, 1100.0); bowl(900.0)
        variability(0.35, "Rich pork-bone (tonkotsu) broths and extra fat can add 200+ kcal per bowl")
        lookalikes("pho_bo", "chow_mein")
        cues = "Deep bowl of wheat noodles in broth with soft-boiled egg, sliced meat, spring onions"
        halalCaution = "Many ramen broths and chashu toppings are pork-based"
    }
    dish("chicken_teriyaki", "Chicken teriyaki", "japanese", "Japanese", GRILLED_MEAT) {
        aliases("teriyaki chicken", "teriyaki")
        c("chicken_thigh", 600.0); c("soy_sauce", 60.0); c("sugar", 40.0); c("white_wine", 30.0); c("ginger", 5.0); c("vegetable_oil", 10.0)
        batch(520.0); serving(200.0, 120.0, 300.0)
        variability(0.2, "Glaze quantity varies")
        cues = "Glossy dark-glazed chicken slices"
        halalCaution = "Traditional teriyaki uses mirin and sake (alcohol)"
    }
    dish("miso_soup", "Miso soup", "japanese", "Japanese", SOUP) {
        aliases("misoshiru", "miso shiru", "miso")
        c("water", 800.0); c("miso", 60.0); c("tofu_firm", 100.0); c("scallion", 10.0); food("usda:11446", 3.0)
        batch(950.0); serving(240.0, 150.0, 350.0); bowl(240.0)
        variability(0.15, "Miso amount")
        cues = "Cloudy light-brown broth with small tofu cubes and seaweed"
        restaurantFactor = 1.0
        halalCaution = "Dashi stock is fish-based (bonito)"
    }
    dish("bibimbap", "Bibimbap", "korean", "Jeonju (Jeolla)", BOWL) {
        aliases("bibimbab", "dolsot bibimbap", "korean rice bowl")
        c("rice_glutinous", 250.0, cooked = true); c("beef_stew", 80.0); c("spinach", 50.0); c("bean_sprouts", 50.0); c("carrot", 40.0)
        c("zucchini", 40.0); c("mushroom", 30.0); c("egg", 50.0); c("gochujang", 20.0); c("sesame_oil", 10.0); c("soy_sauce", 10.0)
        c("vegetable_oil", 10.0); c("sugar", 5.0); c("garlic", 3.0)
        batch(620.0); serving(620.0, 450.0, 750.0); bowl(620.0)
        variability(0.2, "Oil from sautéed vegetables and the sesame oil")
        lookalikes("chicken_rice_bowl", "kimchi_fried_rice")
        cues = "Rice topped with neat sections of vegetables, beef and a fried egg, red gochujang"
    }
    dish("bulgogi", "Beef bulgogi", "korean", "Seoul", GRILLED_MEAT) {
        aliases("bulgogi", "korean bbq beef", "beef bulgogi")
        c("beef_stew", 600.0); c("soy_sauce", 60.0); c("brown_sugar", 30.0); c("sesame_oil", 15.0); c("garlic", 15.0); c("onion", 100.0)
        c("scallion", 20.0); c("apple", 60.0); c("sesame_seeds", 5.0); c("black_pepper", 2.0); c("vegetable_oil", 15.0)
        batch(650.0); serving(200.0, 120.0, 300.0)
        variability(0.25, "Cut of beef (ribeye vs sirloin)")
        cues = "Thin caramelised beef slices with onion and sesame"
    }
    dish("kimchi_fried_rice", "Kimchi fried rice", "korean", "Korean home-style", RICE_DISH) {
        aliases("kimchi bokkeumbap", "kimchi bokkeum bap")
        c("rice_glutinous", 400.0, cooked = true); c("kimchi", 150.0); c("egg", 100.0); c("vegetable_oil", 20.0); c("gochujang", 20.0)
        c("sesame_oil", 5.0); c("scallion", 15.0)
        batch(700.0); serving(350.0, 250.0, 450.0); plate(350.0)
        variability(0.25, OIL_NOTE)
        lookalikes("chicken_fried_rice", "jollof_rice")
        cues = "Reddish-orange fried rice with chopped kimchi and a fried egg"
    }
    dish("chicken_fried_rice", "Chicken fried rice", "chinese", "Cantonese-style", RICE_DISH) {
        aliases("fried rice", "egg fried rice", "chinese fried rice", "nasi goreng", "khao pad")
        c("rice_white", 600.0, cooked = true); c("chicken_breast", 200.0); c("egg", 100.0); c("peas", 80.0); c("carrot", 60.0)
        c("scallion", 20.0); c("soy_sauce", 30.0); c("vegetable_oil", 40.0); c("garlic", 10.0); c("sesame_oil", 5.0)
        batch(1050.0); serving(350.0, 250.0, 500.0); cup(150.0); plate(350.0)
        variability(0.3, "Takeaway fried rice is often much oilier")
        lookalikes("chicken_pulao", "chicken_biryani", "kimchi_fried_rice")
        cues = "Separate light-brown wok-fried rice grains with egg pieces, peas, carrots, spring onion"
        restaurantFactor = 1.3
    }
    dish("mapo_tofu", "Mapo tofu", "chinese", "Sichuan", CURRY_STEW) {
        aliases("mapo doufu", "ma po tofu")
        c("tofu_firm", 500.0); c("beef_ground", 150.0); c("soy_sauce", 20.0); c("chili_powder", 5.0); c("vegetable_oil", 40.0)
        c("garlic", 10.0); c("ginger", 10.0); c("scallion", 20.0); c("black_pepper", 2.0); food("usda:20027", 10.0); c("water", 200.0)
        batch(900.0); serving(300.0, 200.0, 400.0); bowl(300.0)
        variability(0.3, "Chilli oil quantity varies")
        cues = "Soft tofu cubes in glossy red chilli-oil sauce with minced meat"
        halalCaution = "Traditionally made with minced pork"
    }
    dish("kung_pao_chicken", "Kung pao chicken", "chinese", "Sichuan", CURRY_STEW) {
        aliases("gong bao chicken", "kung po chicken", "kungpao chicken")
        c("chicken_thigh", 500.0); c("peanuts", 60.0); c("bell_pepper", 100.0); c("scallion", 30.0); c("vegetable_oil", 45.0)
        c("soy_sauce", 30.0); c("vinegar", 15.0); c("sugar", 15.0); c("garlic", 10.0); c("ginger", 10.0); c("chili_powder", 3.0)
        batch(700.0); serving(250.0, 150.0, 350.0); cup(160.0)
        variability(0.3, "Takeaway versions are often oilier and sweeter")
        cues = "Diced chicken with peanuts and dried chillies in a glossy dark sauce"
        restaurantFactor = 1.3
    }
    dish("chow_mein", "Chicken chow mein", "chinese", "Cantonese / takeaway", NOODLES_PASTA) {
        aliases("chowmein", "lo mein", "fried noodles", "stir-fried noodles", "hakka noodles", "yakisoba")
        c("egg_noodles", 250.0); c("chicken_breast", 200.0); c("cabbage", 150.0); c("carrot", 60.0); c("bean_sprouts", 80.0)
        c("vegetable_oil", 45.0); c("soy_sauce", 40.0); c("sesame_oil", 5.0); c("garlic", 10.0)
        batch(1150.0); serving(400.0, 250.0, 550.0); plate(400.0)
        variability(0.3, "Oil used in stir-frying varies")
        lookalikes("pad_thai", "spaghetti_bolognese")
        cues = "Stir-fried yellow wheat noodles with shredded vegetables and chicken"
        restaurantFactor = 1.3
    }
    dish("jiaozi", "Jiaozi (pork & cabbage dumplings)", "chinese", "Northern (Dongbei)", DUMPLING) {
        aliases("dumplings", "potstickers", "gyoza", "mandu", "momo", "steamed dumplings", "guotie")
        c("flour", 250.0); c("water", 120.0); c("pork_shoulder", 300.0); c("cabbage", 200.0); c("scallion", 20.0); c("ginger", 10.0)
        c("soy_sauce", 20.0); c("sesame_oil", 10.0)
        batch(880.0); serving(220.0, 110.0, 330.0); piece(22.0, "1 dumpling")
        variability(0.2, "Pan-fried potstickers absorb extra oil")
        cues = "Pleated crescent-shaped dumplings, steamed or pan-fried"
        halalCaution = "Usually pork; chicken, beef or vegetable fillings exist"
    }

    // ============================================================ Southeast Asia
    dish("pho_bo", "Phở bò (beef pho)", "vietnamese", "Northern (Hanoi)", NOODLE_SOUP) {
        aliases("pho", "beef pho", "pho bo", "pho ga", "vietnamese noodle soup")
        c("rice_noodles", 250.0, cooked = true); c("beef_stew", 100.0); c("water", 500.0); c("fish_sauce", 15.0); c("onion", 30.0)
        c("ginger", 5.0); c("cinnamon", 1.0); c("bean_sprouts", 50.0); c("basil", 5.0); c("cilantro", 3.0); c("lime", 10.0)
        c("scallion", 10.0); c("sugar", 5.0)
        batch(950.0); serving(950.0, 650.0, 1200.0); bowl(950.0)
        variability(0.2, "Broth fat and meat portion vary")
        lookalikes("ramen")
        cues = "Clear amber broth with flat white rice noodles, thin beef slices, herbs and lime on the side"
        restaurantFactor = 1.05
    }
    dish("pad_thai", "Pad Thai", "thai", "Central", NOODLES_PASTA) {
        aliases("phad thai", "pad thai shrimp", "pad thai chicken", "phat thai")
        c("rice_noodles", 200.0); c("shrimp", 150.0); c("egg", 100.0); c("bean_sprouts", 100.0); c("peanuts", 40.0)
        c("vegetable_oil", 45.0); c("fish_sauce", 30.0); c("tamarind", 30.0); c("brown_sugar", 30.0); c("scallion", 20.0)
        c("garlic", 10.0); c("lime", 20.0); c("chili_powder", 2.0)
        batch(1000.0); serving(450.0, 300.0, 600.0); plate(450.0)
        variability(0.3, "Restaurant portions are often oilier and sweeter")
        lookalikes("chow_mein")
        cues = "Flat stir-fried rice noodles with egg, bean sprouts, crushed peanuts and a lime wedge"
        restaurantFactor = 1.25
    }
    dish("green_curry_chicken", "Thai green curry with chicken", "thai", "Central", CURRY_STEW) {
        aliases("green curry", "gaeng keow wan", "thai curry", "red curry", "panang curry")
        c("chicken_thigh", 500.0); c("coconut_milk", 400.0); c("green_chili", 20.0); c("garlic", 10.0); c("lemongrass", 15.0)
        c("fish_sauce", 20.0); c("brown_sugar", 15.0); c("eggplant", 150.0); c("basil", 10.0); c("vegetable_oil", 15.0)
        batch(1100.0); serving(300.0, 200.0, 400.0); bowl(300.0)
        variability(0.25, "Coconut milk vs light coconut milk")
        lookalikes("chicken_curry", "beef_rendang")
        cues = "Pale green coconut curry with chicken, Thai aubergine and basil"
    }
    dish("nasi_lemak", "Nasi lemak", "malaysian", "Malay", RICE_DISH) {
        aliases("nasi lemak", "coconut rice with sambal")
        c("rice_white", 300.0); c("coconut_milk", 250.0); c("green_chili", 10.0); c("onion", 100.0); c("vegetable_oil", 40.0)
        c("tamarind", 20.0); c("sugar", 20.0); c("peanuts", 40.0); c("egg", 100.0); c("cucumber", 80.0); c("salt", 6.0)
        batch(1300.0); serving(430.0, 300.0, 600.0); plate(430.0)
        variability(0.3, "Sambal oil, fried anchovies and fried chicken add-ons vary")
        cues = "Mound of coconut rice with red sambal, peanuts, egg and cucumber"
    }
    dish("beef_rendang", "Beef rendang", "indonesian", "Padang (Minangkabau)", CURRY_STEW) {
        aliases("rendang", "rendang daging", "beef rendang")
        c("beef_stew", 1000.0); c("coconut_milk", 400.0); c("coconut_desiccated", 50.0); c("onion", 150.0); c("garlic", 20.0)
        c("ginger", 20.0); c("lemongrass", 40.0); c("chili_powder", 15.0); c("vegetable_oil", 40.0); c("brown_sugar", 20.0)
        c("salt", 10.0); c("tamarind", 15.0); c("turmeric", 3.0)
        batch(1200.0); serving(200.0, 120.0, 300.0); cup(200.0)
        variability(0.2, "Reduction level")
        lookalikes("beef_nihari", "green_curry_chicken")
        cues = "Dark brown, almost dry caramelised beef curry"
    }
    dish("chicken_adobo", "Chicken adobo", "filipino", "Tagalog", CURRY_STEW) {
        aliases("adobo", "adobong manok", "pork adobo")
        c("chicken_curry_cut", 1000.0); c("soy_sauce", 100.0); c("vinegar", 100.0); c("garlic", 30.0); c("bay_leaf", 1.0)
        c("black_pepper", 4.0); c("vegetable_oil", 20.0); c("brown_sugar", 10.0); c("water", 150.0)
        batch(1000.0); serving(250.0, 150.0, 350.0)
        variability(0.25, "Skin-on chicken and pork versions are richer")
        cues = "Dark soy-braised bone-in chicken with garlic and bay leaves"
        halalCaution = "Pork adobo is equally common"
    }

    // ============================================================ Latin America & Caribbean
    dish("tacos_carne_asada", "Tacos de carne asada", "mexican", "Northern (Norteño)", SANDWICH_WRAP) {
        aliases("tacos", "taco", "beef tacos", "street tacos", "carne asada tacos", "tacos al pastor")
        c("tortilla_corn", 78.0); c("beef_stew", 120.0); c("onion", 20.0); c("cilantro", 4.0); c("lime", 10.0); c("tomato", 40.0)
        c("green_chili", 5.0); c("vegetable_oil", 5.0)
        batch(250.0); serving(250.0, 83.0, 420.0); piece(83.0, "1 taco")
        variability(0.25, "Cheese, crema or guacamole toppings add energy")
        lookalikes("chicken_enchiladas")
        cues = "Small soft corn tortillas topped with chopped grilled meat, onion and coriander"
        halalCaution = "Al pastor tacos are pork"
    }
    dish("chicken_enchiladas", "Chicken enchiladas", "mexican", "Central / Tex-Mex", CURRY_STEW) {
        aliases("enchiladas", "enchilada", "enchiladas rojas", "enchiladas verdes")
        c("tortilla_corn", 260.0); c("chicken_breast", 500.0); c("tomato_canned", 400.0); c("chili_powder", 8.0); c("onion", 100.0)
        c("cheddar", 150.0); c("vegetable_oil", 30.0); c("garlic", 10.0)
        batch(1300.0); serving(260.0, 130.0, 400.0); piece(130.0, "1 enchilada")
        variability(0.3, "Cheese and crema quantity vary")
        lookalikes("tacos_carne_asada")
        cues = "Rolled tortillas covered in red or green sauce and melted cheese"
    }
    dish("guacamole", "Guacamole", "mexican", "Central", SIDE) {
        aliases("guac", "avocado dip")
        c("avocado", 450.0); c("onion", 40.0); c("tomato", 80.0); c("lime", 20.0); c("cilantro", 8.0); c("green_chili", 8.0); c("salt", 3.0)
        batch(600.0); serving(60.0, 30.0, 150.0); portions += FoodPortion(QuantityUnit.TABLESPOON, 15.0, label = "1 tbsp")
        variability(0.1, "Close to reference values")
        cues = "Chunky green avocado dip"
        restaurantFactor = 1.0
    }
    dish("rice_and_peas", "Rice and peas", "caribbean", "Jamaican", RICE_DISH) {
        aliases("rice & peas", "rice and beans", "arroz con frijoles", "gallo pinto", "moros y cristianos")
        c("rice_white", 400.0); c("kidney_beans", 250.0, cooked = true); c("coconut_milk", 200.0); c("scallion", 20.0); c("garlic", 5.0)
        c("oregano", 2.0); c("salt", 8.0); c("water", 400.0)
        batch(1500.0); serving(250.0, 150.0, 400.0); cup(200.0)
        variability(0.15, "Coconut milk quantity")
        lookalikes("chicken_pulao", "jollof_rice")
        cues = "Rice speckled with red kidney beans, lightly tinted by coconut milk"
    }
    dish("jerk_chicken", "Jerk chicken", "caribbean", "Jamaican", GRILLED_MEAT) {
        aliases("jerk", "jamaican jerk chicken")
        c("chicken_curry_cut", 1000.0); c("scallion", 50.0); c("green_chili", 15.0); c("garlic", 15.0); c("ginger", 10.0)
        c("soy_sauce", 30.0); c("vegetable_oil", 30.0, consumed = 0.7); c("brown_sugar", 15.0); c("lime", 20.0); c("cinnamon", 2.0)
        c("black_pepper", 3.0); c("oregano", 2.0); c("salt", 8.0)
        batch(800.0); serving(250.0, 150.0, 350.0); piece(125.0, "1 leg quarter (with bone)")
        variability(0.25, "Skin-on pieces add fat")
        lookalikes("chicken_tikka")
        cues = "Deeply charred, dark-spiced bone-in chicken"
    }
    dish("ceviche", "Ceviche", "peruvian", "Coastal (Lima)", SALAD) {
        aliases("cebiche", "seviche", "ceviche de pescado")
        c("white_fish", 500.0); c("lime", 200.0, consumed = 0.5); c("onion", 150.0); c("green_chili", 15.0); c("cilantro", 10.0); c("salt", 6.0)
        batch(800.0); serving(200.0, 120.0, 300.0); cup(200.0)
        variability(0.1, "Lean; served with sweet potato and corn (log separately)")
        cues = "Cubes of white fish cured in lime with red onion slivers"
        restaurantFactor = 1.0
    }
    dish("lomo_saltado", "Lomo saltado", "peruvian", "Chifa (Peruvian-Chinese)", CURRY_STEW) {
        aliases("lomo saltado", "beef stir fry with fries")
        c("beef_stew", 500.0); c("onion", 200.0); c("tomato", 200.0); c("potato", 400.0); c("vegetable_oil", 300.0, consumed = 0.15)
        c("soy_sauce", 40.0); c("vinegar", 20.0); c("vegetable_oil", 30.0); c("garlic", 10.0); c("cilantro", 5.0); c("green_chili", 10.0)
        batch(1300.0); serving(400.0, 250.0, 550.0); plate(400.0)
        variability(0.3, "Fried potatoes absorb variable oil")
        cues = "Stir-fried beef strips with onion, tomato and French fries, often with rice"
    }
    dish("feijoada", "Feijoada", "brazilian", "Carioca / national", CURRY_STEW) {
        aliases("feijoada completa", "black bean stew")
        c("black_beans", 500.0); c("pork_shoulder", 400.0); c("bacon", 150.0); c("beef_stew", 300.0); c("onion", 200.0); c("garlic", 20.0)
        c("bay_leaf", 1.0); c("vegetable_oil", 20.0); c("salt", 12.0)
        batch(3000.0); serving(350.0, 250.0, 500.0); bowl(350.0)
        variability(0.3, "Cuts of pork and sausage vary")
        cues = "Dark black bean stew with chunks of pork and sausage"
    }

    // ============================================================ Europe & North America
    dish("spaghetti_bolognese", "Spaghetti bolognese", "italian", "Emilian (ragù) / international", NOODLES_PASTA) {
        aliases("bolognese", "spag bol", "spaghetti with meat sauce", "pasta bolognese", "ragu", "spaghetti")
        c("pasta", 400.0); c("beef_ground", 500.0); c("onion", 100.0); c("carrot", 80.0); c("celery", 50.0); c("tomato_canned", 800.0)
        c("tomato_paste", 30.0); c("olive_oil", 30.0); c("garlic", 10.0); c("milk_whole", 100.0); c("white_wine", 100.0)
        c("parmesan", 30.0); c("salt", 10.0)
        batch(2300.0); serving(450.0, 300.0, 600.0); plate(450.0)
        variability(0.2, "Mince fat content and pasta-to-sauce ratio")
        lookalikes("penne_arrabbiata", "chow_mein")
        cues = "Long pasta with a thick brown-red minced-meat sauce, grated cheese"
        halalCaution = "Traditional ragù uses wine; some versions include pancetta"
    }
    dish("penne_arrabbiata", "Penne arrabbiata", "italian", "Roman / Lazio", NOODLES_PASTA) {
        aliases("arrabbiata", "penne arrabiata", "pasta arrabbiata", "tomato pasta", "pasta pomodoro", "marinara pasta")
        c("pasta", 400.0); c("tomato_canned", 800.0); c("olive_oil", 50.0); c("garlic", 15.0); c("chili_powder", 3.0); c("parsley", 10.0); c("salt", 10.0)
        batch(1650.0); serving(400.0, 250.0, 550.0); plate(400.0)
        variability(0.2, "Olive oil quantity")
        lookalikes("spaghetti_bolognese")
        cues = "Tube pasta in a smooth red tomato sauce without meat"
    }
    dish("pizza_margherita", "Pizza margherita", "italian", "Neapolitan", PIZZA) {
        aliases("pizza", "margherita", "cheese pizza", "neapolitan pizza", "margarita pizza")
        c("flour", 300.0); c("water", 190.0); c("yeast", 3.0); c("salt", 7.0); c("olive_oil", 15.0); c("tomato_canned", 200.0)
        c("mozzarella", 250.0); c("basil", 5.0)
        batch(900.0); serving(240.0, 120.0, 450.0); slice(60.0, "1 slice (1/8 of a 12-in pizza)")
        variability(0.3, "Crust thickness and cheese vary; takeaway pizzas are often richer")
        cues = "Round flatbread with red tomato, white melted mozzarella patches, basil"
        restaurantFactor = 1.25
        halalCaution = "Pepperoni and many cheese toppings need checking"
    }
    dish("beef_burger", "Beef burger", "american", "Classic", SANDWICH_WRAP) {
        aliases("burger", "hamburger", "cheeseburger", "beef burger", "smash burger")
        c("bread_white", 75.0); c("beef_ground", 113.0); c("cheddar", 20.0); c("lettuce", 15.0); c("tomato", 25.0); c("onion", 15.0)
        c("ketchup", 15.0); c("mayonnaise", 10.0)
        batch(260.0); serving(260.0, 180.0, 400.0); piece(260.0, "1 burger")
        variability(0.3, "Patty size, sauces and double patties vary widely")
        lookalikes("chicken_sandwich")
        cues = "Round bun with a grilled beef patty, cheese, lettuce, tomato"
        restaurantFactor = 1.2
    }
    dish("chicken_sandwich", "Chicken sandwich", "american", "Classic", SANDWICH_WRAP) {
        aliases("chicken sandwich", "grilled chicken sandwich", "club sandwich", "chicken sub")
        c("bread_whole_wheat", 64.0); c("chicken_breast", 80.0, cooked = true); c("lettuce", 15.0); c("tomato", 25.0); c("mayonnaise", 15.0)
        batch(200.0); serving(200.0, 150.0, 300.0); piece(200.0, "1 sandwich")
        variability(0.3, "Fried chicken and extra sauces add a lot")
        lookalikes("beef_burger", "shawarma_wrap")
        cues = "Two slices of bread with sliced chicken and salad"
    }
    dish("green_salad", "Green salad", "international", null, SALAD) {
        aliases("garden salad", "side salad", "mixed salad", "lettuce salad", "salad")
        c("lettuce", 100.0); c("cucumber", 50.0); c("tomato", 50.0); c("olive_oil", 10.0); c("vinegar", 10.0); c("salt", 1.0)
        batch(220.0); serving(150.0, 80.0, 300.0); bowl(150.0)
        variability(0.5, "Dressing is the main source of energy and varies widely")
        lookalikes("kachumber_salad", "greek_salad", "caesar_salad")
        cues = "Leafy greens with cucumber and tomato"
        restaurantFactor = 1.2
    }
    dish("greek_salad", "Greek salad (horiatiki)", "greek", "National", SALAD) {
        aliases("horiatiki", "greek salad", "village salad")
        c("tomato", 200.0); c("cucumber", 150.0); c("onion", 40.0); c("bell_pepper", 60.0); c("feta", 80.0); food("usda:09193", 30.0)
        c("olive_oil", 30.0); c("oregano", 1.0); c("vinegar", 10.0)
        batch(600.0); serving(280.0, 150.0, 400.0); bowl(280.0)
        variability(0.3, "Olive oil and feta quantities")
        lookalikes("kachumber_salad", "green_salad")
        cues = "Chunky tomato, cucumber, onion and olives with a slab or cubes of white feta"
    }
    dish("caesar_salad", "Caesar salad", "american", "Classic", SALAD) {
        aliases("caesar", "chicken caesar salad", "caesar salad")
        c("lettuce", 200.0); c("parmesan", 30.0); c("bread_white", 30.0); c("olive_oil", 10.0); c("mayonnaise", 40.0)
        c("lemon_juice", 10.0); c("garlic", 3.0)
        batch(320.0); serving(250.0, 150.0, 400.0); bowl(250.0)
        variability(0.4, "Dressing quantity varies; add chicken separately")
        lookalikes("green_salad")
        cues = "Romaine with croutons, shaved parmesan and creamy dressing"
        halalCaution = "Classic dressing contains anchovies; some contain bacon"
    }
    dish("chicken_rice_bowl", "Chicken & rice bowl", "international", null, BOWL) {
        aliases("rice bowl", "chicken bowl", "teriyaki bowl", "burrito bowl", "poke bowl", "grain bowl", "meal prep bowl")
        c("rice_white", 250.0, cooked = true); c("chicken_breast", 150.0); c("broccoli", 80.0); c("carrot", 40.0); c("soy_sauce", 15.0)
        c("sesame_oil", 5.0); c("vegetable_oil", 10.0); c("sesame_seeds", 3.0)
        batch(520.0); serving(520.0, 350.0, 700.0); bowl(520.0)
        variability(0.25, "Sauces and dressings vary")
        lookalikes("bibimbap", "chicken_fried_rice")
        cues = "Bowl with separate sections of rice, sliced chicken and vegetables"
    }
    dish("omelette", "Omelette (2 eggs)", "international", null, BREAKFAST) {
        aliases("omelet", "masala omelette", "anda omelette", "egg omelette", "scrambled eggs", "fried eggs", "anda")
        c("egg", 100.0); c("milk_whole", 20.0); c("butter", 7.0); c("salt", 1.0); c("onion", 20.0); c("tomato", 20.0); c("green_chili", 3.0)
        batch(160.0); serving(160.0, 80.0, 250.0); piece(160.0, "1 two-egg omelette")
        variability(0.3, "Butter or oil in the pan varies")
        cues = "Folded or flat golden egg, sometimes with chopped onion, tomato and chilli"
        restaurantFactor = 1.3
    }
    dish("oatmeal", "Oatmeal / porridge", "international", null, BREAKFAST) {
        aliases("porridge", "oats", "oatmeal", "overnight oats", "daliya")
        c("oats", 40.0); c("milk_low_fat", 240.0); c("honey", 10.0)
        batch(290.0); serving(290.0, 200.0, 400.0); bowl(290.0)
        variability(0.25, "Toppings (nuts, fruit, sugar) vary")
        cues = "Creamy beige porridge in a bowl, often topped with fruit"
        restaurantFactor = 1.0
    }
    dish("pancakes", "Pancakes", "american", "Classic", BREAKFAST) {
        aliases("pancake", "hotcakes", "flapjacks")
        c("flour", 125.0); c("milk_whole", 250.0); c("egg", 50.0); c("butter", 30.0); c("sugar", 25.0); c("baking_powder", 8.0)
        batch(450.0); serving(225.0, 75.0, 375.0); piece(75.0, "1 pancake (15 cm)")
        variability(0.2, "Syrup and butter on top (log separately)")
        cues = "Stack of round fluffy golden pancakes"
    }
    dish("french_fries", "French fries", "american", "Fast food", FRIED_SNACK) {
        aliases("fries", "chips", "chips (uk)", "potato fries", "frites")
        c("potato", 600.0); c("vegetable_oil", 1000.0, consumed = 0.045); c("salt", 3.0)
        batch(340.0); serving(115.0, 70.0, 180.0); cup(50.0)
        variability(0.3, "Oil absorption differs by cut and fryer temperature")
        cues = "Golden potato sticks"
        restaurantFactor = 1.2
    }
    dish("brownie", "Chocolate brownie", "american", "Classic", DESSERT) {
        aliases("brownies", "chocolate brownie", "fudge brownie")
        c("butter", 115.0); c("sugar", 200.0); c("egg", 100.0); c("flour", 65.0); c("cocoa", 40.0); c("vanilla", 5.0); c("salt", 1.0)
        batch(470.0); serving(60.0, 30.0, 100.0); piece(30.0, "1 small square (5 cm)")
        variability(0.2, "Size and add-ins (nuts, chocolate chips)")
        cues = "Dense dark chocolate square with a crackly top"
    }
    dish("vanilla_ice_cream", "Vanilla ice cream", "international", null, DESSERT) {
        aliases("ice cream", "vanilla ice cream", "kulfi", "gelato")
        food("usda:19095", 100.0)
        batch(100.0); serving(66.0, 33.0, 150.0); portions += FoodPortion(QuantityUnit.SCOOP, 66.0, label = "1 scoop (1/2 cup)")
        variability(0.25, "Premium ice creams are richer; kulfi and gelato differ")
        cues = "Scoops of pale cream-coloured ice cream"
        restaurantFactor = 1.0
    }
}.items
