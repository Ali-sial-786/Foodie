package app.nouri.core.ontology

import app.nouri.core.ontology.Allergen.CRUSTACEAN
import app.nouri.core.ontology.Allergen.EGG
import app.nouri.core.ontology.Allergen.FISH
import app.nouri.core.ontology.Allergen.GLUTEN
import app.nouri.core.ontology.Allergen.MILK
import app.nouri.core.ontology.Allergen.MUSTARD
import app.nouri.core.ontology.Allergen.PEANUT
import app.nouri.core.ontology.Allergen.SESAME
import app.nouri.core.ontology.Allergen.SOY
import app.nouri.core.ontology.Allergen.SULPHITES
import app.nouri.core.ontology.Allergen.TREE_NUT
import app.nouri.core.ontology.Allergen.WHEAT
import app.nouri.core.ontology.DietGoal.DAIRY_FREE
import app.nouri.core.ontology.DietGoal.GLUTEN_FREE
import app.nouri.core.ontology.DietGoal.HALAL
import app.nouri.core.ontology.DietGoal.HIGHER_PROTEIN
import app.nouri.core.ontology.DietGoal.LOWER_CALORIE
import app.nouri.core.ontology.DietGoal.VEGAN
import app.nouri.core.ontology.DietGoal.VEGETARIAN
import app.nouri.core.ontology.IngredientCategory.BAKING
import app.nouri.core.ontology.IngredientCategory.BEVERAGE
import app.nouri.core.ontology.IngredientCategory.BREAD
import app.nouri.core.ontology.IngredientCategory.CONDIMENT
import app.nouri.core.ontology.IngredientCategory.DAIRY
import app.nouri.core.ontology.IngredientCategory.FAT_OIL
import app.nouri.core.ontology.IngredientCategory.FRUIT
import app.nouri.core.ontology.IngredientCategory.GRAIN
import app.nouri.core.ontology.IngredientCategory.HERB
import app.nouri.core.ontology.IngredientCategory.LEGUME
import app.nouri.core.ontology.IngredientCategory.NUT_SEED
import app.nouri.core.ontology.IngredientCategory.PASTA_NOODLE
import app.nouri.core.ontology.IngredientCategory.PLANT_PROTEIN
import app.nouri.core.ontology.IngredientCategory.PORK
import app.nouri.core.ontology.IngredientCategory.POULTRY
import app.nouri.core.ontology.IngredientCategory.RED_MEAT
import app.nouri.core.ontology.IngredientCategory.SEAFOOD
import app.nouri.core.ontology.IngredientCategory.SPICE
import app.nouri.core.ontology.IngredientCategory.SWEETENER
import app.nouri.core.ontology.IngredientCategory.VEGETABLE
import app.nouri.core.ontology.StorageLocation.FREEZER
import app.nouri.core.ontology.StorageLocation.FRIDGE
import app.nouri.core.ontology.StorageLocation.PANTRY
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.PortionSize
import app.nouri.core.units.QuantityUnit

/**
 * The normalised ingredient ontology. Every alias, transliteration and regional
 * name resolves to one canonical id. USDA NDB numbers refer to SR28 records that
 * scripts/generate_food_data.py compiles into [app.nouri.core.fooddb.UsdaCoreFoods];
 * tests verify that every referenced record exists.
 *
 * Portion weights are typical edible weights compiled from USDA household measures
 * where available; they are defaults that users can override per food.
 */
object IngredientCatalog {

    val all: List<CanonicalIngredient> by lazy { buildCatalog() }

    val byId: Map<String, CanonicalIngredient> by lazy { all.associateBy { it.id } }

    operator fun get(id: String): CanonicalIngredient? = byId[id]

    fun require(id: String): CanonicalIngredient =
        byId[id] ?: throw IllegalArgumentException("Unknown ingredient id: $id")
}

private class IngredientBuilder(
    val id: String,
    val name: String,
    val category: IngredientCategory,
    val rawNdb: String?,
    val cookedNdb: String?,
    aliases: String,
) {
    val aliases = aliases.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableList()
    var fallbackRef: String? = null
    var density: Double? = null
    val portions = mutableListOf<FoodPortion>()
    val allergens = mutableSetOf<Allergen>()
    var origin: AnimalOrigin = AnimalOrigin.NONE
    var halal: HalalStatus = HalalStatus.PERMISSIBLE
    var storage: StorageLocation = PANTRY
    var shelfLife: Int? = null
    var staple = false
    var edible = 1.0
    var cookedYield: Double? = null
    val subs = mutableListOf<Substitution>()

    fun piece(grams: Double, label: String, size: PortionSize? = PortionSize.MEDIUM) {
        portions += FoodPortion(QuantityUnit.PIECE, grams, size, label)
    }

    fun portion(unit: QuantityUnit, grams: Double, label: String, size: PortionSize? = null) {
        portions += FoodPortion(unit, grams, size, label)
    }

    fun allergen(vararg a: Allergen) {
        allergens += a
    }

    fun store(location: StorageLocation, days: Int?) {
        storage = location
        shelfLife = days
    }

    fun sub(replacementId: String, vararg goals: DietGoal, ratio: Double = 1.0, note: String = "") {
        subs += Substitution(replacementId, ratio, goals.toSet(), note)
    }

    fun build() = CanonicalIngredient(
        id = id, name = name, category = category, aliases = aliases.distinct(),
        rawNdb = rawNdb, cookedNdb = cookedNdb, fallbackRef = fallbackRef,
        densityGPerMl = density, portions = portions.toList(), allergens = allergens.toSet(),
        animalOrigin = origin, halal = halal, storage = storage, shelfLifeDays = shelfLife,
        pantryStaple = staple, edibleFraction = edible, substitutions = subs.toList(),
        cookedYield = cookedYield,
    )
}

private class CatalogBuilder {
    val items = mutableListOf<CanonicalIngredient>()

    fun ing(
        id: String,
        name: String,
        category: IngredientCategory,
        ndb: String? = null,
        cookedNdb: String? = null,
        aliases: String = "",
        block: IngredientBuilder.() -> Unit = {},
    ) {
        val b = IngredientBuilder(id, name, category, ndb, cookedNdb, aliases)
        b.aliases += name.lowercase()
        b.block()
        items += b.build()
    }

    // Shared configuration helpers --------------------------------------------------
    fun IngredientBuilder.meat(origin: AnimalOrigin, days: Int = 2) {
        this.origin = origin
        halal = if (origin == AnimalOrigin.PORK) HalalStatus.NOT_PERMISSIBLE else HalalStatus.REQUIRES_HALAL_SOURCE
        store(FRIDGE, days)
        cookedYield = 0.75
    }

    fun IngredientBuilder.dairy(days: Int?) {
        origin = AnimalOrigin.DAIRY
        allergen(MILK)
        store(FRIDGE, days)
    }

    fun IngredientBuilder.produce(days: Int, location: StorageLocation = FRIDGE) = store(location, days)

    fun IngredientBuilder.spice() {
        staple = true
        store(PANTRY, 365)
    }
}

private fun buildCatalog(): List<CanonicalIngredient> = CatalogBuilder().apply {
    // ---------------------------------------------------------------- Poultry
    ing("chicken_breast", "Chicken breast", POULTRY, ndb = "05062", cookedNdb = "05064",
        aliases = "chicken breasts,boneless chicken breast,skinless chicken breast,chicken fillet,murgh breast,pechuga de pollo") {
        meat(AnimalOrigin.POULTRY); piece(174.0, "1 boneless breast")
        sub("tofu_firm", VEGETARIAN, VEGAN, note = "Press tofu well; cook time is shorter")
        sub("chickpeas_cooked", VEGETARIAN, VEGAN, note = "Lower protein than chicken")
    }
    ing("chicken_thigh", "Chicken thigh, boneless", POULTRY, ndb = "05096", cookedNdb = "05098",
        aliases = "chicken thighs,boneless chicken thigh,thigh fillet") {
        meat(AnimalOrigin.POULTRY); piece(116.0, "1 boneless thigh")
        sub("chicken_breast", LOWER_CALORIE, note = "Leaner; cook a little less to keep it moist")
        sub("tofu_firm", VEGETARIAN, VEGAN)
    }
    ing("chicken_curry_cut", "Chicken, bone-in pieces (skinless)", POULTRY, ndb = "05011", cookedNdb = "05013",
        aliases = "chicken,whole chicken,chicken pieces,bone-in chicken,curry cut chicken,murgh,murghi,karahi chicken,chicken with bone,pollo") {
        meat(AnimalOrigin.POULTRY)
        edible = 0.72 // bone-in skinless pieces: ~28% bone by weight (editorial estimate)
        piece(90.0, "1 curry-cut piece with bone")
        sub("chicken_thigh", ratio = 0.72, goals = arrayOf(LOWER_CALORIE), note = "Boneless thigh; use 72% of the weight")
        sub("chickpeas_cooked", VEGETARIAN, VEGAN)
    }
    ing("chicken_drumstick", "Chicken drumstick", POULTRY, ndb = "05071", cookedNdb = "05073",
        aliases = "drumsticks,chicken legs,leg piece") {
        meat(AnimalOrigin.POULTRY); edible = 0.68; piece(122.0, "1 drumstick, with bone")
    }
    ing("chicken_wing", "Chicken wings", POULTRY, ndb = "05100", aliases = "wings,chicken wing") {
        meat(AnimalOrigin.POULTRY); edible = 0.54; piece(90.0, "1 whole wing, with bone")
    }
    ing("chicken_ground", "Ground chicken", POULTRY, ndb = "05332", cookedNdb = "05333",
        aliases = "minced chicken,chicken mince,chicken keema,keema murgh") {
        meat(AnimalOrigin.POULTRY)
    }
    ing("turkey_ground", "Ground turkey", POULTRY, ndb = "05305", aliases = "minced turkey,turkey mince") {
        meat(AnimalOrigin.POULTRY)
    }

    // ---------------------------------------------------------------- Red meat
    ing("beef_stew", "Beef, stewing (chuck)", RED_MEAT, ndb = "23093", cookedNdb = "23090",
        aliases = "beef,beef cubes,stew beef,beef chuck,gosht,bade ka gosht,beef shank,nalli,carne de res,boeuf") {
        meat(AnimalOrigin.RED_MEAT, 3)
        sub("chicken_thigh", LOWER_CALORIE)
        sub("chickpeas_cooked", VEGETARIAN, VEGAN)
    }
    ing("beef_ground", "Ground beef (80% lean)", RED_MEAT, ndb = "23572", cookedNdb = "23573",
        aliases = "minced beef,beef mince,keema,qeema,hamburger meat,carne molida,kofta mince") {
        meat(AnimalOrigin.RED_MEAT, 2); piece(113.0, "1 patty (4 oz raw)")
        sub("beef_ground_lean", LOWER_CALORIE, note = "90% lean")
        sub("turkey_ground", LOWER_CALORIE)
        sub("lentils_brown", VEGETARIAN, VEGAN, ratio = 0.5, note = "Use half the weight of dry lentils")
    }
    ing("beef_ground_lean", "Ground beef (90% lean)", RED_MEAT, ndb = "23562", cookedNdb = "23563",
        aliases = "lean ground beef,lean mince,extra lean ground beef") {
        meat(AnimalOrigin.RED_MEAT, 2)
    }
    ing("lamb", "Lamb (leg/shoulder)", RED_MEAT, ndb = "17011", cookedNdb = "17012",
        aliases = "lamb leg,lamb shoulder,lamb cubes,lamb chops,mutton,maaz,cordero,agneau") {
        meat(AnimalOrigin.RED_MEAT, 3)
        sub("chicken_thigh", LOWER_CALORIE)
    }
    ing("lamb_ground", "Ground lamb", RED_MEAT, ndb = "17224", aliases = "minced lamb,lamb mince,lamb keema") {
        meat(AnimalOrigin.RED_MEAT, 2)
    }
    ing("goat", "Goat meat", RED_MEAT, ndb = "17168", cookedNdb = "17169",
        aliases = "goat,mutton,bakra,bakre ka gosht,chevon,cabrito,goat curry meat") {
        meat(AnimalOrigin.RED_MEAT, 3); edible = 0.8
    }
    ing("pork_shoulder", "Pork shoulder", PORK, ndb = "10070", aliases = "pork,pork butt,boston butt,pork belly,cerdo,pork mince") {
        meat(AnimalOrigin.PORK, 3)
        sub("chicken_thigh", HALAL, note = "Halal-certified chicken")
        sub("lamb", HALAL, note = "Halal-certified lamb")
    }
    ing("bacon", "Bacon", PORK, ndb = "10123", cookedNdb = "10862", aliases = "streaky bacon,pancetta,lardons") {
        meat(AnimalOrigin.PORK, 7)
        sub("turkey_ground", HALAL, LOWER_CALORIE, note = "Use halal turkey bacon")
    }

    // ---------------------------------------------------------------- Seafood
    ing("salmon", "Salmon", SEAFOOD, ndb = "15236", cookedNdb = "15237",
        aliases = "salmon fillet,atlantic salmon,sake (fish),saumon") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(170.0, "1 fillet portion")
    }
    ing("white_fish", "White fish (cod)", SEAFOOD, ndb = "15015", cookedNdb = "15016",
        aliases = "cod,haddock,pollock,white fish fillet,machli,fish fillet,pescado") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(150.0, "1 fillet portion")
    }
    ing("tilapia", "Tilapia", SEAFOOD, ndb = "15261", cookedNdb = "15262", aliases = "tilapia fillet") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(116.0, "1 fillet")
    }
    ing("tuna_canned", "Tuna, canned in water", SEAFOOD, ndb = "15121",
        aliases = "canned tuna,tinned tuna,tuna,atun") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(PANTRY, 1000)
        portion(QuantityUnit.CAN, 120.0, "1 can, drained (~120 g)")
    }
    ing("shrimp", "Shrimp / prawns", SEAFOOD, ndb = "15149", cookedNdb = "15151",
        aliases = "prawns,shrimps,jhinga,camarones,gambas,king prawns") {
        origin = AnimalOrigin.SHELLFISH; allergen(CRUSTACEAN); store(FRIDGE, 2)
        halal = HalalStatus.VERIFY // permissibility of shrimp differs between schools
        piece(6.0, "1 medium shrimp")
    }

    // ---------------------------------------------------------------- Eggs & dairy
    ing("egg", "Egg", IngredientCategory.EGG, ndb = "01123", cookedNdb = "01129",
        aliases = "eggs,anda,ande,whole egg,huevo,huevos,oeuf,large egg") {
        origin = AnimalOrigin.EGG; allergen(EGG); store(FRIDGE, 28)
        piece(50.0, "1 large egg (without shell)", PortionSize.LARGE)
        piece(44.0, "1 medium egg (without shell)", PortionSize.MEDIUM)
        piece(38.0, "1 small egg (without shell)", PortionSize.SMALL)
        sub("tofu_firm", VEGAN, ratio = 1.0, note = "Scrambles only; not for baking")
    }
    ing("egg_white", "Egg white", IngredientCategory.EGG, ndb = "01124", aliases = "egg whites,albumen") {
        origin = AnimalOrigin.EGG; allergen(EGG); store(FRIDGE, 4); piece(33.0, "1 large egg white")
    }
    ing("milk_whole", "Milk, whole", DAIRY, ndb = "01077", aliases = "milk,doodh,full cream milk,whole milk,leche,lait") {
        dairy(7); density = 1.03
        sub("milk_low_fat", LOWER_CALORIE); sub("soy_milk", VEGAN, DAIRY_FREE)
    }
    ing("milk_low_fat", "Milk, 2%", DAIRY, ndb = "01079", aliases = "2% milk,semi-skimmed milk,low fat milk,reduced fat milk") {
        dairy(7); density = 1.03; sub("soy_milk", VEGAN, DAIRY_FREE)
    }
    ing("milk_skim", "Milk, skim", DAIRY, ndb = "01151", aliases = "skim milk,skimmed milk,fat free milk,nonfat milk") {
        dairy(7); density = 1.03
    }
    ing("soy_milk", "Soy milk", PLANT_PROTEIN, ndb = "16139", aliases = "soya milk,soymilk,plant milk") {
        allergen(SOY); store(FRIDGE, 7); density = 1.03
    }
    ing("yogurt", "Yogurt, plain (whole milk)", DAIRY, ndb = "01116",
        aliases = "yoghurt,plain yogurt,dahi,curd,curds,laban,mast,yogur,natural yogurt") {
        dairy(14); density = 1.04
        sub("greek_yogurt_nonfat", LOWER_CALORIE, HIGHER_PROTEIN)
        sub("coconut_milk", VEGAN, DAIRY_FREE, ratio = 0.8, note = "Changes flavour; add lemon for tang")
    }
    ing("greek_yogurt_nonfat", "Greek yogurt, nonfat", DAIRY, ndb = "01256",
        aliases = "greek yogurt,fat free greek yogurt,0% greek yogurt,skyr,strained yogurt") {
        dairy(14); density = 1.04; portion(QuantityUnit.PACKAGE, 170.0, "1 container (170 g)")
    }
    ing("greek_yogurt_whole", "Greek yogurt, whole milk", DAIRY, ndb = "01293",
        aliases = "full fat greek yogurt,labneh,hung curd") {
        dairy(14); density = 1.04
    }
    ing("butter", "Butter", FAT_OIL, ndb = "01145", aliases = "unsalted butter,makhan,mantequilla,beurre,salted butter") {
        dairy(60); density = 0.96
        portion(QuantityUnit.TABLESPOON, 14.2, "1 tbsp butter")
        sub("olive_oil", DAIRY_FREE, VEGAN, ratio = 0.8)
    }
    ing("ghee", "Ghee (clarified butter)", FAT_OIL, ndb = "01003",
        aliases = "desi ghee,clarified butter,butter oil,samna,niter kibbeh") {
        dairy(180); store(PANTRY, 180); density = 0.91
        portion(QuantityUnit.TABLESPOON, 12.8, "1 tbsp ghee")
        sub("vegetable_oil", DAIRY_FREE, VEGAN, note = "Neutral oil; less nutty flavour")
        sub("vegetable_oil", LOWER_CALORIE, ratio = 0.5, note = "Use half the amount of oil")
    }
    ing("cream_heavy", "Cream, heavy", DAIRY, ndb = "01053", aliases = "heavy cream,double cream,whipping cream,malai,cream,nata") {
        dairy(10); density = 1.0
        sub("milk_whole", LOWER_CALORIE, note = "Thinner sauce")
        sub("coconut_milk", DAIRY_FREE, VEGAN)
    }
    ing("cheddar", "Cheddar cheese", DAIRY, ndb = "01009", aliases = "cheese,cheddar,yellow cheese,queso") {
        dairy(30); portion(QuantityUnit.SLICE, 28.0, "1 slice"); portion(QuantityUnit.CUP, 113.0, "1 cup, shredded")
        halal = HalalStatus.VERIFY
    }
    ing("mozzarella", "Mozzarella", DAIRY, ndb = "01026", aliases = "mozzarella cheese,pizza cheese,fresh mozzarella") {
        dairy(14); halal = HalalStatus.VERIFY; portion(QuantityUnit.CUP, 112.0, "1 cup, shredded")
    }
    ing("feta", "Feta cheese", DAIRY, ndb = "01019", aliases = "feta,white cheese,beyaz peynir,jibneh") {
        dairy(21); halal = HalalStatus.VERIFY; portion(QuantityUnit.CUP, 150.0, "1 cup, crumbled")
    }
    ing("parmesan", "Parmesan", DAIRY, ndb = "01032", aliases = "parmesan cheese,parmigiano,grana padano") {
        dairy(60); halal = HalalStatus.VERIFY; portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp, grated")
    }
    ing("paneer", "Paneer", DAIRY, aliases = "panir,cottage cheese (indian),chhena") {
        fallbackRef = "approx:paneer"; dairy(5); piece(15.0, "1 cube (~15 g)")
        sub("tofu_firm", VEGAN, DAIRY_FREE, LOWER_CALORIE)
    }
    ing("cottage_cheese", "Cottage cheese", DAIRY, ndb = "01012", aliases = "cottage cheese,curd cheese") {
        dairy(10); portion(QuantityUnit.CUP, 210.0, "1 cup")
    }
    ing("cream_cheese", "Cream cheese", DAIRY, ndb = "01017", aliases = "philadelphia,soft cheese") {
        dairy(14); portion(QuantityUnit.TABLESPOON, 14.5, "1 tbsp")
    }

    // ---------------------------------------------------------------- Plant protein & legumes
    ing("tofu_firm", "Tofu, firm", PLANT_PROTEIN, ndb = "16126", aliases = "tofu,bean curd,firm tofu,dubu") {
        allergen(SOY); store(FRIDGE, 5); portion(QuantityUnit.PACKAGE, 400.0, "1 block (~400 g)")
    }
    ing("lentils_red", "Red lentils (masoor dal)", LEGUME, ndb = "16144",
        aliases = "masoor,masoor dal,masoor daal,red lentils,split red lentils,lal masoor,mercimek,kirmizi mercimek") {
        store(PANTRY, 365); density = 0.82; cookedYield = 2.5
    }
    ing("lentils_brown", "Lentils, brown/green", LEGUME, ndb = "16069", cookedNdb = "16070",
        aliases = "lentils,green lentils,brown lentils,sabut masoor,lentejas,adas") {
        store(PANTRY, 365); density = 0.81; cookedYield = 2.5
    }
    ing("chana_dal", "Split chickpeas (chana dal)", LEGUME, ndb = "16056",
        aliases = "chana dal,chana daal,bengal gram,split bengal gram,chanay ki daal") {
        store(PANTRY, 365); density = 0.84; cookedYield = 2.4
    }
    ing("moong_dal", "Mung beans / moong dal", LEGUME, ndb = "16080", cookedNdb = "16081",
        aliases = "moong,moong dal,mung dal,mung beans,green gram,mash ki daal,yellow moong") {
        store(PANTRY, 365); density = 0.85; cookedYield = 2.5
    }
    ing("urad_dal", "Black gram (urad/maash dal)", LEGUME, ndb = "16083", cookedNdb = "16084",
        aliases = "urad,urad dal,maash,maash ki daal,black gram,black lentils,kali dal") {
        store(PANTRY, 365); density = 0.85; cookedYield = 2.5
    }
    ing("chickpeas_dry", "Chickpeas, dried", LEGUME, ndb = "16056", cookedNdb = "16057",
        aliases = "dried chickpeas,kabuli chana,safed chana,garbanzo beans dried,nohut,hummus beans") {
        store(PANTRY, 365); density = 0.84; cookedYield = 2.2
    }
    ing("chickpeas_cooked", "Chickpeas, cooked/canned", LEGUME, cookedNdb = "16057",
        aliases = "chickpeas,canned chickpeas,garbanzo beans,chole,chana,cooked chickpeas,boiled chana") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 240.0, "1 can, drained (~240 g)"); portion(QuantityUnit.CUP, 164.0, "1 cup")
    }
    ing("kidney_beans", "Kidney beans, cooked/canned", LEGUME, ndb = "16027", cookedNdb = "16033",
        aliases = "kidney beans,rajma,red beans,red kidney beans,frijoles rojos") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 240.0, "1 can, drained (~240 g)")
    }
    ing("black_beans", "Black beans", LEGUME, ndb = "16014", cookedNdb = "16015",
        aliases = "black beans,frijoles negros,turtle beans") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 240.0, "1 can, drained (~240 g)")
    }
    ing("black_eyed_peas", "Black-eyed peas", LEGUME, ndb = "16062", cookedNdb = "16063",
        aliases = "lobia,cowpeas,black eyed beans,niebe,ewa") {
        store(PANTRY, 365)
    }
    ing("split_peas", "Split peas", LEGUME, ndb = "16085", cookedNdb = "16086", aliases = "yellow split peas,green split peas,matar dal") {
        store(PANTRY, 365); cookedYield = 2.3
    }
    ing("peanuts", "Peanuts", NUT_SEED, ndb = "16087", aliases = "groundnuts,moongphali,mani,cacahuetes") {
        allergen(PEANUT); store(PANTRY, 180); portion(QuantityUnit.CUP, 146.0, "1 cup")
        portion(QuantityUnit.HANDFUL, 28.0, "1 handful (~28 g)")
    }
    ing("peanut_butter", "Peanut butter", NUT_SEED, ndb = "16098", aliases = "peanut paste,pb") {
        allergen(PEANUT); store(PANTRY, 180); portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp")
    }

    // ---------------------------------------------------------------- Grains, flours, breads, pasta
    ing("rice_white", "Rice, white (basmati/long grain)", GRAIN, ndb = "20044", cookedNdb = "20045",
        aliases = "rice,white rice,basmati,basmati rice,long grain rice,chawal,chaawal,sella rice,jasmine rice,arroz,pilau rice,riz") {
        store(PANTRY, 730); density = 0.78; cookedYield = 3.0
        portion(QuantityUnit.CUP, 185.0, "1 cup, uncooked")
        sub("rice_brown", HIGHER_PROTEIN, note = "Longer cooking time")
        sub("cauliflower", LOWER_CALORIE, ratio = 3.0, note = "Riced cauliflower replaces cooked rice weight-for-weight")
    }
    ing("rice_brown", "Rice, brown", GRAIN, ndb = "20036", cookedNdb = "20037", aliases = "brown rice,whole grain rice") {
        store(PANTRY, 180); density = 0.81; cookedYield = 2.6
    }
    ing("rice_glutinous", "Rice, short-grain/sushi", GRAIN, ndb = "20052", cookedNdb = "20053",
        aliases = "sushi rice,short grain rice,japanese rice,calrose,sticky rice") {
        store(PANTRY, 730); density = 0.85; cookedYield = 2.4
    }
    ing("atta", "Whole-wheat flour (atta)", GRAIN, ndb = "20080",
        aliases = "atta,chakki atta,whole wheat flour,wholemeal flour,whole-wheat flour,gehun ka atta") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 180); density = 0.51
        portion(QuantityUnit.CUP, 120.0, "1 cup")
    }
    ing("flour", "All-purpose flour (maida)", GRAIN, ndb = "20081",
        aliases = "flour,all purpose flour,plain flour,maida,white flour,harina,farine") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); density = 0.53
        portion(QuantityUnit.CUP, 125.0, "1 cup")
        sub("rice_flour", GLUTEN_FREE)
    }
    ing("rice_flour", "Rice flour", GRAIN, ndb = "20061", aliases = "rice flour,chawal ka atta") {
        store(PANTRY, 365); portion(QuantityUnit.CUP, 158.0, "1 cup")
    }
    ing("besan", "Chickpea flour (besan)", GRAIN, ndb = "16157", aliases = "besan,gram flour,chickpea flour,garbanzo flour,chana flour") {
        store(PANTRY, 180); portion(QuantityUnit.CUP, 92.0, "1 cup")
    }
    ing("semolina", "Semolina (sooji)", GRAIN, ndb = "20466", aliases = "sooji,suji,rava,semolina,cream of wheat,durum semolina") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); portion(QuantityUnit.CUP, 167.0, "1 cup")
    }
    ing("oats", "Oats, rolled", GRAIN, ndb = "08120", aliases = "rolled oats,oatmeal,porridge oats,jai,avena") {
        allergen(GLUTEN); store(PANTRY, 365); portion(QuantityUnit.CUP, 81.0, "1 cup, dry")
    }
    ing("quinoa", "Quinoa", GRAIN, ndb = "20035", cookedNdb = "20137", aliases = "quinoa,kinwa") {
        store(PANTRY, 730); portion(QuantityUnit.CUP, 170.0, "1 cup, uncooked"); cookedYield = 2.7
    }
    ing("couscous", "Couscous", GRAIN, ndb = "20028", cookedNdb = "20029", aliases = "couscous,kuskus,seksu") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); portion(QuantityUnit.CUP, 173.0, "1 cup, dry"); cookedYield = 2.4
    }
    ing("bulgur", "Bulgur", GRAIN, ndb = "20012", cookedNdb = "20013", aliases = "bulgur wheat,burghul,cracked wheat,daliya,dalia") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); portion(QuantityUnit.CUP, 140.0, "1 cup, dry"); cookedYield = 2.6
    }
    ing("cornmeal", "Cornmeal / masa", GRAIN, ndb = "20022", aliases = "cornmeal,polenta,masa harina,maize flour,makki ka atta,corn flour") {
        store(PANTRY, 365); portion(QuantityUnit.CUP, 157.0, "1 cup")
    }
    ing("teff", "Teff", GRAIN, ndb = "20142", cookedNdb = "20143", aliases = "teff flour,tef,injera flour") {
        store(PANTRY, 365)
    }
    ing("bread_white", "Bread, white", BREAD, ndb = "18069", aliases = "white bread,sandwich bread,toast,double roti,pan de molde") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); portion(QuantityUnit.SLICE, 25.0, "1 slice")
        sub("bread_whole_wheat", HIGHER_PROTEIN)
    }
    ing("bread_whole_wheat", "Bread, whole-wheat", BREAD, ndb = "18075", aliases = "whole wheat bread,brown bread,wholemeal bread,whole grain bread") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); portion(QuantityUnit.SLICE, 32.0, "1 slice")
    }
    ing("pita", "Pita bread", BREAD, ndb = "18041", aliases = "pita,pitta,arabic bread,khubz,lebanese bread") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); piece(60.0, "1 pita (6.5 in)")
    }
    ing("tortilla_flour", "Flour tortilla", BREAD, ndb = "18364", aliases = "flour tortilla,tortilla,wrap,burrito wrap") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 14); piece(45.0, "1 tortilla (8 in)")
    }
    ing("tortilla_corn", "Corn tortilla", BREAD, ndb = "18363", aliases = "corn tortilla,tortilla de maiz,taco shell") {
        store(PANTRY, 14); piece(26.0, "1 tortilla (6 in)")
    }
    ing("pasta", "Pasta, dry", PASTA_NOODLE, ndb = "20120", cookedNdb = "20121",
        aliases = "pasta,spaghetti,penne,macaroni,fusilli,linguine,fettuccine,noodles (pasta)") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 730); cookedYield = 2.25
        portion(QuantityUnit.CUP, 91.0, "1 cup, dry penne")
        sub("rice_noodles", GLUTEN_FREE)
    }
    ing("egg_noodles", "Egg noodles", PASTA_NOODLE, ndb = "20109", cookedNdb = "20110", aliases = "egg noodles,chow mein noodles,lo mein noodles") {
        allergen(WHEAT, GLUTEN, EGG); store(PANTRY, 365); cookedYield = 2.4
    }
    ing("rice_noodles", "Rice noodles", PASTA_NOODLE, ndb = "20133", cookedNdb = "20134",
        aliases = "rice noodles,rice vermicelli,banh pho,pho noodles,pad thai noodles,sen lek,bihon") {
        store(PANTRY, 730); cookedYield = 2.3
    }
    ing("ramen_noodles", "Instant ramen noodles", PASTA_NOODLE, ndb = "06583", aliases = "instant noodles,ramen,maggi,indomie,instant ramen") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); portion(QuantityUnit.PACKAGE, 85.0, "1 package (85 g)")
    }

    // ---------------------------------------------------------------- Vegetables
    ing("onion", "Onion", VEGETABLE, ndb = "11282",
        aliases = "onions,pyaz,piaz,piyaz,red onion,yellow onion,brown onion,white onion,cebolla,oignon,basal") {
        produce(30, PANTRY); piece(110.0, "1 medium onion")
        piece(70.0, "1 small onion", PortionSize.SMALL); piece(150.0, "1 large onion", PortionSize.LARGE)
        portion(QuantityUnit.CUP, 160.0, "1 cup, chopped"); edible = 0.9
    }
    ing("tomato", "Tomato", VEGETABLE, ndb = "11529",
        aliases = "tomatoes,tamatar,tamatar,tomate,tomates,roma tomato,cherry tomatoes,banadoura") {
        produce(7, PANTRY); piece(123.0, "1 medium tomato"); piece(91.0, "1 small tomato", PortionSize.SMALL)
        piece(182.0, "1 large tomato", PortionSize.LARGE); portion(QuantityUnit.CUP, 180.0, "1 cup, chopped")
    }
    ing("tomato_canned", "Tomatoes, canned", VEGETABLE, ndb = "11531",
        aliases = "canned tomatoes,chopped tomatoes,diced tomatoes,tinned tomatoes,crushed tomatoes,tomato puree") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 400.0, "1 can (400 g)")
    }
    ing("tomato_paste", "Tomato paste", CONDIMENT, ndb = "11546", aliases = "tomato paste,tomato concentrate,salça") {
        store(FRIDGE, 30); portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp")
    }
    ing("garlic", "Garlic", VEGETABLE, ndb = "11215", aliases = "garlic,lehsan,lehsun,lasan,ajo,ail,thoom,garlic cloves,garlic paste") {
        spice(); piece(3.0, "1 clove"); portion(QuantityUnit.CLOVE, 3.0, "1 clove")
        portion(QuantityUnit.TEASPOON, 2.8, "1 tsp, minced"); portion(QuantityUnit.TABLESPOON, 8.5, "1 tbsp, minced/paste")
    }
    ing("ginger", "Ginger", VEGETABLE, ndb = "11216", aliases = "ginger,adrak,adrakh,ginger root,jengibre,gingembre,ginger paste") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp, grated"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp, grated/paste")
        piece(11.0, "1 in (2.5 cm) piece")
    }
    ing("green_chili", "Green chili", VEGETABLE, ndb = "11670",
        aliases = "green chilli,green chillies,hari mirch,chili pepper,serrano,jalapeno,thai chili,bird's eye chili,chile verde") {
        produce(10); piece(5.0, "1 green chili")
    }
    ing("bell_pepper", "Bell pepper", VEGETABLE, ndb = "11821",
        aliases = "capsicum,shimla mirch,red pepper,green pepper,yellow pepper,sweet pepper,pimiento,poivron") {
        produce(10); piece(119.0, "1 medium pepper"); portion(QuantityUnit.CUP, 149.0, "1 cup, chopped")
    }
    ing("potato", "Potato", VEGETABLE, ndb = "11352", cookedNdb = "11367",
        aliases = "potatoes,aloo,alu,batata,patata,papas,pomme de terre,russet,yukon gold") {
        produce(30, PANTRY); piece(213.0, "1 medium potato"); piece(170.0, "1 small potato", PortionSize.SMALL)
        piece(299.0, "1 large potato", PortionSize.LARGE); portion(QuantityUnit.CUP, 150.0, "1 cup, diced")
        sub("cauliflower", LOWER_CALORIE)
    }
    ing("sweet_potato", "Sweet potato", VEGETABLE, ndb = "11507", cookedNdb = "11510",
        aliases = "sweet potatoes,shakarkandi,camote,boniato,kumara,yam (us)") {
        produce(21, PANTRY); piece(130.0, "1 medium sweet potato")
    }
    ing("carrot", "Carrot", VEGETABLE, ndb = "11124", aliases = "carrots,gajar,zanahoria,carotte") {
        produce(21); piece(61.0, "1 medium carrot"); portion(QuantityUnit.CUP, 128.0, "1 cup, chopped")
    }
    ing("cucumber", "Cucumber", VEGETABLE, ndb = "11205", aliases = "cucumbers,kheera,khira,pepino,concombre,khiyar") {
        produce(7); piece(301.0, "1 cucumber (8 in)"); portion(QuantityUnit.CUP, 104.0, "1 cup, sliced")
    }
    ing("spinach", "Spinach", VEGETABLE, ndb = "11457", cookedNdb = "11458", aliases = "palak,saag,espinaca,epinard,baby spinach,sabanekh") {
        produce(5); portion(QuantityUnit.CUP, 30.0, "1 cup, raw"); portion(QuantityUnit.BUNCH, 340.0, "1 bunch")
    }
    ing("mustard_greens", "Mustard greens (sarson)", VEGETABLE, ndb = "11270", aliases = "sarson,sarson ka saag,mustard leaves,gai choy") {
        produce(5); portion(QuantityUnit.CUP, 56.0, "1 cup, chopped")
    }
    ing("cauliflower", "Cauliflower", VEGETABLE, ndb = "11135", aliases = "gobi,phool gobi,coliflor,chou-fleur,arnabeet,riced cauliflower") {
        produce(7); piece(588.0, "1 medium head"); portion(QuantityUnit.CUP, 107.0, "1 cup, chopped")
    }
    ing("cabbage", "Cabbage", VEGETABLE, ndb = "11109", aliases = "band gobi,bandh gobi,cabbage,repollo,col,napa cabbage,chinese cabbage") {
        produce(14); portion(QuantityUnit.CUP, 89.0, "1 cup, chopped")
    }
    ing("peas", "Green peas", VEGETABLE, ndb = "11312", aliases = "peas,matar,mattar,frozen peas,guisantes,petit pois") {
        store(FREEZER, 240); portion(QuantityUnit.CUP, 134.0, "1 cup, frozen")
    }
    ing("okra", "Okra", VEGETABLE, ndb = "11278", cookedNdb = "11279", aliases = "bhindi,lady finger,ladies fingers,bamia,quimbombo,gumbo") {
        produce(4); portion(QuantityUnit.CUP, 100.0, "1 cup"); piece(12.0, "1 pod")
    }
    ing("eggplant", "Eggplant / aubergine", VEGETABLE, ndb = "11209", cookedNdb = "11210",
        aliases = "aubergine,brinjal,baingan,bengan,berenjena,aubergines,eggplants,batinjan,nasu") {
        produce(7); piece(458.0, "1 medium eggplant"); portion(QuantityUnit.CUP, 82.0, "1 cup, cubed")
    }
    ing("zucchini", "Zucchini / courgette", VEGETABLE, ndb = "11477", aliases = "courgette,courgettes,zucchinis,kousa,calabacin,tori") {
        produce(7); piece(196.0, "1 medium zucchini")
    }
    ing("bottle_gourd", "Bottle gourd (lauki)", VEGETABLE, ndb = "11218", aliases = "lauki,ghiya,dudhi,calabash,opo squash") {
        produce(7)
    }
    ing("bitter_gourd", "Bitter gourd (karela)", VEGETABLE, ndb = "11024", aliases = "karela,bitter melon,ampalaya,goya") {
        produce(5); piece(100.0, "1 medium karela")
    }
    ing("mushroom", "Mushrooms", VEGETABLE, ndb = "11260", aliases = "mushroom,button mushrooms,champignons,khumbi,setas,shiitake,cremini") {
        produce(5); portion(QuantityUnit.CUP, 70.0, "1 cup, sliced")
    }
    ing("broccoli", "Broccoli", VEGETABLE, ndb = "11090", aliases = "broccoli florets,brocoli") {
        produce(5); portion(QuantityUnit.CUP, 91.0, "1 cup, chopped")
    }
    ing("lettuce", "Lettuce", VEGETABLE, ndb = "11251", aliases = "romaine,iceberg,salad leaves,salad greens,lechuga,mixed greens") {
        produce(5); portion(QuantityUnit.CUP, 47.0, "1 cup, shredded")
    }
    ing("cilantro", "Cilantro / coriander leaves", HERB, ndb = "11165",
        aliases = "coriander,coriander leaves,fresh coriander,hara dhaniya,dhania,dhaniya,cilantro,kothmir,kinza") {
        produce(7); portion(QuantityUnit.CUP, 16.0, "1 cup, leaves"); portion(QuantityUnit.TABLESPOON, 1.0, "1 tbsp, chopped")
        portion(QuantityUnit.HANDFUL, 8.0, "1 handful"); portion(QuantityUnit.BUNCH, 50.0, "1 bunch")
    }
    ing("mint", "Mint", HERB, ndb = "02065", aliases = "pudina,podina,fresh mint,spearmint,yerba buena,nana,menthe") {
        produce(7); portion(QuantityUnit.TABLESPOON, 1.5, "1 tbsp, chopped"); portion(QuantityUnit.HANDFUL, 8.0, "1 handful")
    }
    ing("parsley", "Parsley", HERB, ndb = "11297", aliases = "flat leaf parsley,perejil,baqdounis,maydanoz") {
        produce(7); portion(QuantityUnit.CUP, 60.0, "1 cup, chopped")
    }
    ing("basil", "Basil, fresh", HERB, ndb = "02044", aliases = "thai basil,sweet basil,albahaca,tulsi") {
        produce(5); portion(QuantityUnit.CUP, 24.0, "1 cup, leaves")
    }
    ing("lemongrass", "Lemongrass", HERB, ndb = "11972", aliases = "lemon grass,takrai,sereh,xa") {
        produce(14); piece(20.0, "1 stalk")
    }
    ing("scallion", "Spring onion / scallion", VEGETABLE, ndb = "11291",
        aliases = "green onion,green onions,spring onions,scallions,hara pyaz,cebollin,pa (korean)") {
        produce(7); piece(15.0, "1 stalk"); portion(QuantityUnit.CUP, 100.0, "1 cup, chopped")
    }
    ing("lemon", "Lemon", FRUIT, ndb = "09150", aliases = "lemons,nimbu,limon,citron,leemoon") {
        produce(21); piece(58.0, "1 lemon (flesh)")
    }
    ing("lemon_juice", "Lemon juice", CONDIMENT, ndb = "09152", aliases = "lemon juice,nimbu ras,jugo de limon") {
        store(FRIDGE, 3); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp"); density = 1.03
    }
    ing("lime", "Lime", FRUIT, ndb = "09159", aliases = "limes,lime juice,key lime,lima,makrut lime") {
        produce(21); piece(44.0, "1 lime (flesh)"); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp juice")
    }
    ing("avocado", "Avocado", FRUIT, ndb = "09037", aliases = "avocados,aguacate,palta,butter fruit") {
        produce(5, PANTRY); piece(150.0, "1 avocado (flesh)"); edible = 0.74
    }
    ing("corn", "Sweet corn", VEGETABLE, ndb = "11167", aliases = "corn,sweetcorn,maize,makai,bhutta,elote,corn kernels") {
        store(FREEZER, 240); portion(QuantityUnit.CUP, 145.0, "1 cup, kernels"); piece(90.0, "1 medium ear (kernels)")
    }
    ing("green_beans", "Green beans", VEGETABLE, ndb = "11052", aliases = "french beans,string beans,haricots verts,phali,judias verdes") {
        produce(7); portion(QuantityUnit.CUP, 100.0, "1 cup")
    }
    ing("bok_choy", "Bok choy", VEGETABLE, ndb = "11116", aliases = "pak choi,pak-choi,bok choi,chinese greens") {
        produce(5); portion(QuantityUnit.CUP, 70.0, "1 cup, shredded")
    }
    ing("bean_sprouts", "Bean sprouts", VEGETABLE, ndb = "11043", aliases = "mung bean sprouts,sprouts,moyashi,kongnamul,taugeh") {
        produce(3); portion(QuantityUnit.CUP, 104.0, "1 cup")
    }
    ing("kimchi", "Kimchi", CONDIMENT, ndb = "11118", aliases = "kimchee,baechu kimchi") {
        store(FRIDGE, 60); portion(QuantityUnit.CUP, 150.0, "1 cup")
    }
    ing("celery", "Celery", VEGETABLE, ndb = "11143", aliases = "celery sticks,apio") {
        produce(14); piece(40.0, "1 medium stalk")
    }
    ing("radish", "Radish / mooli", VEGETABLE, ndb = "11429", aliases = "mooli,muli,daikon,radishes,rabano") {
        produce(10)
    }
    ing("beetroot", "Beetroot", VEGETABLE, ndb = "11080", aliases = "beet,beets,chukandar,remolacha") {
        produce(14); piece(82.0, "1 beet")
    }
    ing("pumpkin", "Pumpkin / squash", VEGETABLE, ndb = "11422", aliases = "kaddu,butternut squash,squash,calabaza,kabocha") {
        produce(30, PANTRY)
    }
    ing("cassava", "Cassava", VEGETABLE, ndb = "11134", aliases = "yuca,manioc,tapioca root,mogo,garri") {
        produce(7, PANTRY)
    }
    ing("plantain", "Plantain", FRUIT, ndb = "09277", cookedNdb = "09278", aliases = "plantains,platano,platano macho,dodo,kela (raw)") {
        produce(7, PANTRY); piece(179.0, "1 medium plantain")
    }
    ing("yam", "Yam", VEGETABLE, ndb = "11601", cookedNdb = "11602", aliases = "african yam,name,ube,purple yam") {
        produce(21, PANTRY)
    }

    // ---------------------------------------------------------------- Fruit
    ing("banana", "Banana", FRUIT, ndb = "09040", aliases = "bananas,kela,platano (fruta),guineo") {
        produce(5, PANTRY); piece(118.0, "1 medium banana"); piece(101.0, "1 small banana", PortionSize.SMALL)
        piece(136.0, "1 large banana", PortionSize.LARGE)
    }
    ing("apple", "Apple", FRUIT, ndb = "09003", aliases = "apples,seb,saib,manzana,pomme") {
        produce(30); piece(182.0, "1 medium apple")
    }
    ing("orange", "Orange", FRUIT, ndb = "09200", aliases = "oranges,santra,kinnow,naranja,mandarin,clementine") {
        produce(14, PANTRY); piece(131.0, "1 medium orange")
    }
    ing("mango", "Mango", FRUIT, ndb = "09176", aliases = "mangoes,aam,mango,mangga") {
        produce(5, PANTRY); piece(207.0, "1 mango (flesh)"); portion(QuantityUnit.CUP, 165.0, "1 cup, pieces")
    }
    ing("grapes", "Grapes", FRUIT, ndb = "09132", aliases = "angoor,uvas,raisins (fresh)") {
        produce(7); portion(QuantityUnit.CUP, 151.0, "1 cup")
    }
    ing("strawberries", "Strawberries", FRUIT, ndb = "09316", aliases = "strawberry,fresas") {
        produce(4); portion(QuantityUnit.CUP, 152.0, "1 cup, halves")
    }
    ing("blueberries", "Blueberries", FRUIT, ndb = "09050", aliases = "blueberry,arandanos") {
        produce(7); portion(QuantityUnit.CUP, 148.0, "1 cup")
    }
    ing("dates", "Dates", FRUIT, ndb = "09421", aliases = "khajoor,khajur,medjool dates,tamar,datiles") {
        store(PANTRY, 180); piece(24.0, "1 medjool date (pitted)")
    }
    ing("pineapple", "Pineapple", FRUIT, ndb = "09266", aliases = "ananas,pina") {
        produce(5, PANTRY); portion(QuantityUnit.CUP, 165.0, "1 cup, chunks")
    }
    ing("watermelon", "Watermelon", FRUIT, ndb = "09326", aliases = "tarbooz,sandia,pastèque") {
        produce(7); portion(QuantityUnit.CUP, 152.0, "1 cup, diced")
    }
    ing("papaya", "Papaya", FRUIT, ndb = "09226", aliases = "papita,lechosa,pawpaw") {
        produce(5, PANTRY); portion(QuantityUnit.CUP, 145.0, "1 cup, cubes")
    }
    ing("pomegranate", "Pomegranate", FRUIT, ndb = "09286", aliases = "anar,pomegranate seeds,granada,rumman") {
        produce(30); portion(QuantityUnit.CUP, 174.0, "1 cup, arils")
    }
    ing("raisins", "Raisins", FRUIT, ndb = "09298", aliases = "kishmish,sultanas,pasas") {
        store(PANTRY, 365); portion(QuantityUnit.TABLESPOON, 9.0, "1 tbsp")
    }
    ing("coconut_milk", "Coconut milk, canned", CONDIMENT, ndb = "12118", aliases = "coconut milk,coconut cream,santan,gata,leche de coco,nariyal doodh") {
        store(PANTRY, 730); density = 0.97; portion(QuantityUnit.CAN, 400.0, "1 can (400 ml)")
        sub("milk_low_fat", LOWER_CALORIE, note = "Lighter but less rich")
    }
    ing("coconut_desiccated", "Coconut, desiccated", NUT_SEED, ndb = "12108", aliases = "desiccated coconut,shredded coconut,nariyal,coconut flakes") {
        store(PANTRY, 180); portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp")
    }

    // ---------------------------------------------------------------- Fats & oils
    ing("vegetable_oil", "Vegetable oil (canola)", FAT_OIL, ndb = "04582",
        aliases = "oil,cooking oil,canola oil,rapeseed oil,sunflower oil,vegetable oil,corn oil,tel,aceite,huile") {
        spice(); density = 0.92; portion(QuantityUnit.TABLESPOON, 14.0, "1 tbsp"); portion(QuantityUnit.TEASPOON, 4.5, "1 tsp")
    }
    ing("olive_oil", "Olive oil", FAT_OIL, ndb = "04053", aliases = "extra virgin olive oil,evoo,zaitoon ka tel,aceite de oliva,zeit zeitoun") {
        spice(); density = 0.91; portion(QuantityUnit.TABLESPOON, 13.5, "1 tbsp"); portion(QuantityUnit.TEASPOON, 4.5, "1 tsp")
    }
    ing("mustard_oil", "Mustard oil", FAT_OIL, ndb = "04583", aliases = "sarson ka tel,kachi ghani oil") {
        spice(); density = 0.92; portion(QuantityUnit.TABLESPOON, 14.0, "1 tbsp"); allergen(MUSTARD)
    }
    ing("coconut_oil", "Coconut oil", FAT_OIL, ndb = "04047", aliases = "coconut oil,nariyal tel,virgin coconut oil") {
        spice(); density = 0.92; portion(QuantityUnit.TABLESPOON, 13.6, "1 tbsp")
    }
    ing("sesame_oil", "Sesame oil", FAT_OIL, ndb = "04058", aliases = "toasted sesame oil,til ka tel,gingelly oil,chamgireum") {
        spice(); allergen(SESAME); density = 0.92; portion(QuantityUnit.TABLESPOON, 13.6, "1 tbsp")
    }
    ing("mayonnaise", "Mayonnaise", CONDIMENT, ndb = "04025", aliases = "mayo,mayonesa,kewpie") {
        origin = AnimalOrigin.EGG; allergen(EGG); store(FRIDGE, 60); portion(QuantityUnit.TABLESPOON, 13.8, "1 tbsp")
        sub("greek_yogurt_nonfat", LOWER_CALORIE)
    }

    // ---------------------------------------------------------------- Nuts & seeds
    ing("almonds", "Almonds", NUT_SEED, ndb = "12061", aliases = "badam,almendras,amandes,slivered almonds") {
        allergen(TREE_NUT); store(PANTRY, 270); portion(QuantityUnit.CUP, 143.0, "1 cup, whole")
        portion(QuantityUnit.HANDFUL, 28.0, "1 handful (~23 almonds)")
    }
    ing("cashews", "Cashews", NUT_SEED, ndb = "12087", aliases = "kaju,cashew nuts,anacardos") {
        allergen(TREE_NUT); store(PANTRY, 270); portion(QuantityUnit.HANDFUL, 28.0, "1 handful")
        portion(QuantityUnit.CUP, 137.0, "1 cup")
    }
    ing("walnuts", "Walnuts", NUT_SEED, ndb = "12155", aliases = "akhrot,nueces,noix") {
        allergen(TREE_NUT); store(PANTRY, 180); portion(QuantityUnit.CUP, 117.0, "1 cup, chopped")
    }
    ing("pistachios", "Pistachios", NUT_SEED, ndb = "12151", aliases = "pista,pistachos,fistuq") {
        allergen(TREE_NUT); store(PANTRY, 270); portion(QuantityUnit.TABLESPOON, 8.0, "1 tbsp, chopped")
    }
    ing("sesame_seeds", "Sesame seeds", NUT_SEED, ndb = "12023", aliases = "til,sesame,ajonjoli,simsim,goma") {
        spice(); allergen(SESAME); portion(QuantityUnit.TABLESPOON, 9.0, "1 tbsp")
    }
    ing("tahini", "Tahini", CONDIMENT, ndb = "12698", aliases = "tahina,sesame paste,tahin,til paste") {
        allergen(SESAME); store(PANTRY, 180); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
    }
    ing("chia_seeds", "Chia seeds", NUT_SEED, ndb = "12006", aliases = "chia,tukh malanga (substitute)") {
        store(PANTRY, 365); portion(QuantityUnit.TABLESPOON, 12.0, "1 tbsp")
    }

    // ---------------------------------------------------------------- Spices & herbs (dried)
    ing("cumin", "Cumin", SPICE, ndb = "02014", aliases = "cumin seeds,jeera,zeera,zira,comino,kammoun,ground cumin,bhuna jeera") {
        spice(); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp, seeds/ground"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp")
    }
    ing("coriander_seed", "Coriander, ground", SPICE, ndb = "02013", aliases = "dhaniya powder,coriander powder,ground coriander,sabut dhaniya,coriander seeds") {
        spice(); portion(QuantityUnit.TEASPOON, 1.8, "1 tsp"); portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp")
    }
    ing("turmeric", "Turmeric", SPICE, ndb = "02043", aliases = "haldi,haldee,curcuma,kurkum,turmeric powder") {
        spice(); portion(QuantityUnit.TEASPOON, 3.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 9.4, "1 tbsp")
    }
    ing("chili_powder", "Red chili powder", SPICE, ndb = "02009",
        aliases = "lal mirch,lal mirch powder,red chilli powder,kashmiri chili,cayenne,chile en polvo,gochugaru,chili flakes,red pepper flakes") {
        spice(); portion(QuantityUnit.TEASPOON, 2.7, "1 tsp"); portion(QuantityUnit.TABLESPOON, 8.0, "1 tbsp")
    }
    ing("paprika", "Paprika", SPICE, ndb = "02028", aliases = "smoked paprika,pimenton,deghi mirch") {
        spice(); portion(QuantityUnit.TEASPOON, 2.3, "1 tsp")
    }
    ing("garam_masala", "Garam masala", SPICE, ndb = "02015", aliases = "garam masala,biryani masala,curry powder,masala,baharat,berbere,ras el hanout,seven spice") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 6.3, "1 tbsp")
    }
    ing("black_pepper", "Black pepper", SPICE, ndb = "02030", aliases = "kali mirch,pepper,ground black pepper,pimienta,black peppercorns") {
        spice(); portion(QuantityUnit.TEASPOON, 2.3, "1 tsp")
    }
    ing("cinnamon", "Cinnamon", SPICE, ndb = "02010", aliases = "dalchini,darchini,canela,cinnamon stick,cannelle") {
        spice(); portion(QuantityUnit.TEASPOON, 2.6, "1 tsp, ground"); portion(QuantityUnit.STICK, 3.0, "1 stick")
    }
    ing("cardamom", "Cardamom", SPICE, ndb = "02006", aliases = "elaichi,ilaichi,green cardamom,cardamomo,hel,black cardamom,bari elaichi") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp, ground"); piece(0.2, "1 pod", null)
    }
    ing("cloves", "Cloves", SPICE, ndb = "02011", aliases = "laung,long,clavos,qaranful") {
        spice(); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp, ground"); piece(0.1, "1 clove (spice)", null)
    }
    ing("bay_leaf", "Bay leaf", SPICE, ndb = "02004", aliases = "tej patta,tez patta,bay leaves,laurel") {
        spice(); piece(0.2, "1 leaf", null)
    }
    ing("fenugreek", "Fenugreek (methi)", SPICE, ndb = "02019", aliases = "methi,methi seeds,kasuri methi,fenugreek seeds,dried fenugreek leaves,hilbeh") {
        spice(); portion(QuantityUnit.TEASPOON, 3.7, "1 tsp, seeds")
    }
    ing("mustard_seed", "Mustard seeds", SPICE, ndb = "02024", aliases = "rai,sarson seeds,black mustard seeds,mustard seed") {
        spice(); allergen(MUSTARD); portion(QuantityUnit.TEASPOON, 3.3, "1 tsp")
    }
    ing("fennel_seed", "Fennel seeds", SPICE, ndb = "02018", aliases = "saunf,sonf,fennel,aniseed") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp")
    }
    ing("oregano", "Oregano, dried", SPICE, ndb = "02027", aliases = "dried oregano,za'atar,zaatar,mixed herbs,italian seasoning,thyme,dried thyme") {
        spice(); portion(QuantityUnit.TEASPOON, 1.0, "1 tsp")
    }
    ing("saffron", "Saffron", SPICE, ndb = "02037", aliases = "zafran,kesar,azafran") {
        spice(); portion(QuantityUnit.PINCH, 0.1, "1 pinch (~20 threads)")
    }
    ing("salt", "Salt", SPICE, ndb = "02047", aliases = "namak,sea salt,table salt,kosher salt,sal,sel,milh") {
        spice(); density = 1.2; portion(QuantityUnit.TEASPOON, 6.0, "1 tsp"); portion(QuantityUnit.PINCH, 0.4, "1 pinch")
    }

    // ---------------------------------------------------------------- Sauces, condiments, sweeteners
    ing("soy_sauce", "Soy sauce", CONDIMENT, ndb = "16123", aliases = "soya sauce,shoyu,light soy sauce,dark soy sauce,ganjang,kecap") {
        allergen(SOY, WHEAT, GLUTEN); store(PANTRY, 730); density = 1.13; portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp")
        sub("tamari", GLUTEN_FREE, note = "Check the label: most tamari is wheat-free")
    }
    ing("tamari", "Tamari", CONDIMENT, ndb = "16124", aliases = "tamari soy sauce,gluten-free soy sauce") {
        allergen(SOY); store(PANTRY, 730); density = 1.13; portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp")
    }
    ing("fish_sauce", "Fish sauce", CONDIMENT, ndb = "06179", aliases = "nam pla,nuoc mam,patis,fish sauce") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp")
    }
    ing("miso", "Miso", CONDIMENT, ndb = "16112", aliases = "miso paste,white miso,doenjang,soybean paste") {
        allergen(SOY); store(FRIDGE, 365); portion(QuantityUnit.TABLESPOON, 17.0, "1 tbsp")
    }
    ing("gochujang", "Gochujang", CONDIMENT, aliases = "korean chili paste,gochujang paste,red pepper paste") {
        fallbackRef = "approx:gochujang"; allergen(SOY, WHEAT, GLUTEN); store(FRIDGE, 365)
        portion(QuantityUnit.TABLESPOON, 20.0, "1 tbsp")
    }
    ing("ketchup", "Ketchup", CONDIMENT, ndb = "11935", aliases = "tomato ketchup,catsup,tomato sauce (condiment)") {
        store(FRIDGE, 180); portion(QuantityUnit.TABLESPOON, 17.0, "1 tbsp")
    }
    ing("vinegar", "Vinegar", CONDIMENT, ndb = "02053", aliases = "white vinegar,sirka,rice vinegar,apple cider vinegar,vinagre") {
        store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
    }
    ing("tamarind", "Tamarind", CONDIMENT, ndb = "09322", aliases = "imli,tamarind paste,tamarindo,tamarind pulp,assam jawa") {
        store(PANTRY, 365); portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp, pulp")
    }
    ing("sugar", "Sugar", SWEETENER, ndb = "19335", aliases = "cheeni,chini,white sugar,granulated sugar,caster sugar,azucar,sucre") {
        spice(); density = 0.85; portion(QuantityUnit.TEASPOON, 4.2, "1 tsp"); portion(QuantityUnit.TABLESPOON, 12.5, "1 tbsp")
        portion(QuantityUnit.CUP, 200.0, "1 cup")
    }
    ing("brown_sugar", "Brown sugar / jaggery", SWEETENER, ndb = "19334", aliases = "brown sugar,jaggery,gur,shakkar,panela,piloncillo,palm sugar,demerara") {
        store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 13.8, "1 tbsp"); portion(QuantityUnit.TEASPOON, 4.6, "1 tsp")
    }
    ing("honey", "Honey", SWEETENER, ndb = "19296", aliases = "shahad,shehad,miel,asal") {
        origin = AnimalOrigin.HONEY; store(PANTRY, 730); density = 1.42; portion(QuantityUnit.TABLESPOON, 21.0, "1 tbsp")
        portion(QuantityUnit.TEASPOON, 7.0, "1 tsp")
    }
    ing("maple_syrup", "Maple syrup", SWEETENER, ndb = "19353", aliases = "maple syrup,syrup") {
        store(FRIDGE, 365); portion(QuantityUnit.TABLESPOON, 20.0, "1 tbsp")
    }
    ing("cocoa", "Cocoa powder", BAKING, ndb = "19165", aliases = "cocoa,cacao powder,unsweetened cocoa") {
        store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 5.4, "1 tbsp")
    }
    ing("dark_chocolate", "Dark chocolate (70–85%)", SWEETENER, ndb = "19904", aliases = "dark chocolate,chocolate,bittersweet chocolate") {
        store(PANTRY, 365); portion(QuantityUnit.PIECE, 10.0, "1 square (~10 g)")
        halal = HalalStatus.VERIFY
    }
    ing("baking_powder", "Baking powder", BAKING, ndb = "18369", aliases = "baking powder,polvo de hornear") {
        spice(); portion(QuantityUnit.TEASPOON, 4.6, "1 tsp")
    }
    ing("baking_soda", "Baking soda", BAKING, ndb = "18372", aliases = "bicarbonate of soda,meetha soda,bicarbonato") {
        spice(); portion(QuantityUnit.TEASPOON, 4.6, "1 tsp")
    }
    ing("yeast", "Yeast, dry", BAKING, ndb = "18375", aliases = "dry yeast,instant yeast,khameer,levadura") {
        store(PANTRY, 365); portion(QuantityUnit.TEASPOON, 4.0, "1 tsp")
    }
    ing("vanilla", "Vanilla extract", BAKING, ndb = "02050", aliases = "vanilla essence,vanilla,vainilla") {
        store(PANTRY, 730); portion(QuantityUnit.TEASPOON, 4.2, "1 tsp")
        halal = HalalStatus.VERIFY // extracts are usually alcohol-based; essences may not be
    }
    ing("gelatin", "Gelatin", BAKING, ndb = "19177", aliases = "gelatine,jelly powder") {
        origin = AnimalOrigin.GELATIN; halal = HalalStatus.VERIFY; store(PANTRY, 730)
        portion(QuantityUnit.TEASPOON, 3.0, "1 tsp")
    }

    // ---------------------------------------------------------------- Drinks
    ing("water", "Water", BEVERAGE, ndb = "14411", aliases = "pani,agua,eau,tap water,mineral water") {
        spice(); density = 1.0
    }
    ing("coffee", "Coffee, brewed", BEVERAGE, ndb = "14209", aliases = "coffee,black coffee,americano,filter coffee,cafe,drip coffee") {
        density = 1.0; portion(QuantityUnit.CUP, 237.0, "1 cup (8 fl oz)")
    }
    ing("tea", "Tea, brewed", BEVERAGE, ndb = "14355", aliases = "tea,black tea,chai patti,green tea,shai,cha") {
        density = 1.0; portion(QuantityUnit.CUP, 237.0, "1 cup (8 fl oz)")
    }
    ing("orange_juice", "Orange juice", BEVERAGE, ndb = "09206", aliases = "oj,juice,jugo de naranja") {
        store(FRIDGE, 7); density = 1.04; portion(QuantityUnit.CUP, 248.0, "1 cup")
    }
    ing("cola", "Cola soft drink", BEVERAGE, ndb = "14400", aliases = "coke,pepsi,soda,soft drink,fizzy drink") {
        store(PANTRY, 270); density = 1.04; portion(QuantityUnit.CAN, 368.0, "1 can (355 ml)")
    }
    ing("white_wine", "Wine, white", BEVERAGE, ndb = "14106", aliases = "white wine,cooking wine,mirin,sake,shaoxing wine,rice wine,red wine") {
        halal = HalalStatus.NOT_PERMISSIBLE; allergen(SULPHITES); store(PANTRY, 365)
        sub("water", HALAL, note = "Use stock with a splash of vinegar or lemon instead")
    }
}.items
