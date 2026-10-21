package app.nouri.core.ontology

import app.nouri.core.ontology.Allergen.CRUSTACEAN
import app.nouri.core.ontology.Allergen.EGG
import app.nouri.core.ontology.Allergen.FISH
import app.nouri.core.ontology.Allergen.GLUTEN
import app.nouri.core.ontology.Allergen.MILK
import app.nouri.core.ontology.Allergen.MOLLUSC
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
import app.nouri.core.ontology.DietGoal.NUT_FREE
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
        aliases = "chicken,chicken pieces,bone-in chicken,curry cut chicken,murgh,murghi,karahi chicken,chicken with bone,pollo") {
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
    ing("chicken_whole", "Chicken, whole (with skin and bone)", POULTRY, ndb = "05006", cookedNdb = "05009",
        aliases = "whole chicken,whole bird,roasting chicken,roaster chicken,spatchcock chicken,spatchcocked chicken,pollo entero,poulet entier") {
        meat(AnimalOrigin.POULTRY)
        edible = 0.68 // SR28 05006: bone is 32% of a ready-to-cook bird
        piece(1500.0, "1 whole chicken (~1.5 kg)")
        sub("chicken_curry_cut", LOWER_CALORIE, note = "Skinless bone-in pieces")
        sub("chickpeas_cooked", VEGETARIAN, VEGAN)
    }
    ing("chicken_liver", "Chicken liver", POULTRY, ndb = "05027", cookedNdb = "05028",
        aliases = "chicken livers,liver,livers,kaleji,higado de pollo,higaditos de pollo,foie de volaille,fegatini,atay ng manok") {
        meat(AnimalOrigin.POULTRY, 1); piece(44.0, "1 liver")
    }
    ing("turkey_breast", "Turkey breast (skinless)", POULTRY, ndb = "05219", cookedNdb = "05220",
        aliases = "turkey breasts,turkey breast fillet,turkey cutlets,turkey steaks,turkey escalopes,pechuga de pavo,blanc de dinde,petto di tacchino") {
        meat(AnimalOrigin.POULTRY)
        sub("tofu_firm", VEGETARIAN, VEGAN)
    }
    // Whole ducks and legs are sold bone-in; the SR28 record is meat and skin. Weigh boneless breast as eaten.
    ing("duck", "Duck, whole or bone-in (with skin)", POULTRY, ndb = "05139", cookedNdb = "05140",
        aliases = "whole duck,duck legs,duck leg,duck pieces,pato,canard,anatra,bebek,itik") {
        meat(AnimalOrigin.POULTRY)
        edible = 0.72 // SR28 05139: bone is 28%
        sub("chicken_whole", LOWER_CALORIE, note = "Much less fat under the skin")
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
    ing("beef_steak", "Beef steak (sirloin)", RED_MEAT, ndb = "13929", cookedNdb = "13930",
        aliases = "steak,sirloin,beef sirloin,sirloin steak,flank steak,skirt steak,ribeye,rib eye,beef strips,bulgogi beef,thinly sliced beef,arrachera") {
        meat(AnimalOrigin.RED_MEAT, 3)
        sub("chicken_breast", LOWER_CALORIE)
        sub("tofu_firm", VEGETARIAN, VEGAN)
    }
    ing("lamb", "Lamb (leg/shoulder)", RED_MEAT, ndb = "17011", cookedNdb = "17012",
        aliases = "lamb leg,lamb shoulder,lamb cubes,mutton,maaz,cordero,agneau") {
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
    ing("lamb_chops", "Lamb chops (loin, bone-in)", RED_MEAT, ndb = "17023", cookedNdb = "17024",
        aliases = "lamb chops,lamb chop,lamb loin chops,lamb cutlets,mutton chops,chuletas de cordero,cotelettes d'agneau,kuzu pirzola") {
        meat(AnimalOrigin.RED_MEAT, 3)
        edible = 0.79 // SR28 17023: bone is 21%
        piece(120.0, "1 loin chop, with bone (~120 g)")
        sub("chicken_thigh", LOWER_CALORIE)
    }
    ing("beef_brisket", "Beef brisket", RED_MEAT, ndb = "13803", cookedNdb = "13804",
        aliases = "brisket,beef brisket,brisket flat,brisket point,chadolbaegi,pecho de res,poitrine de boeuf") {
        meat(AnimalOrigin.RED_MEAT, 3)
        sub("chicken_thigh", LOWER_CALORIE)
    }
    ing("beef_short_ribs", "Beef short ribs, bone-in", RED_MEAT, ndb = "13147", cookedNdb = "13148",
        aliases = "short ribs,beef short ribs,short rib,galbi,kalbi,flanken ribs,flanken short ribs,costillas de res,asado de tira,tira de asado") {
        meat(AnimalOrigin.RED_MEAT, 3)
        edible = 0.73 // SR28 13147: bone is 27%
        sub("beef_stew", LOWER_CALORIE, ratio = 0.73, note = "Boneless chuck; use 73% of the weight")
    }
    ing("beef_sausage", "Beef sausage / frankfurter", RED_MEAT, ndb = "07022", cookedNdb = "07945",
        aliases = "beef sausage,beef sausages,beef frankfurter,beef frankfurters,beef franks,beef hot dog,beef hot dogs,all beef hot dogs,halal sausage,halal sausages,halal hot dogs") {
        meat(AnimalOrigin.RED_MEAT, 7)
        cookedYield = 0.95 // sold ready to eat; heating loses little weight
        piece(50.0, "1 frankfurter (~50 g)")
    }
    ing("pork_shoulder", "Pork shoulder", PORK, ndb = "10070", aliases = "pork,pork butt,boston butt,cerdo,pork mince") {
        meat(AnimalOrigin.PORK, 3)
        sub("chicken_thigh", HALAL, note = "Halal-certified chicken")
        sub("lamb", HALAL, note = "Halal-certified lamb")
    }
    ing("bacon", "Bacon", PORK, ndb = "10123", cookedNdb = "10862", aliases = "streaky bacon,pancetta,lardons") {
        meat(AnimalOrigin.PORK, 7)
        sub("turkey_ground", HALAL, LOWER_CALORIE, note = "Use halal turkey bacon")
    }
    ing("pork_belly", "Pork belly", PORK, ndb = "10005",
        aliases = "pork belly,samgyeopsal,samgyupsal,liempo,wu hua rou,wuhua rou,buta bara,panceta,poitrine de porc") {
        meat(AnimalOrigin.PORK, 3)
        sub("beef_brisket", HALAL, note = "Halal-certified beef brisket")
    }
    ing("ham", "Ham, cooked", PORK, ndb = "10182", cookedNdb = "10136",
        aliases = "ham,cooked ham,sliced ham,deli ham,leg ham,gammon,jamon york,jamon cocido,jambon blanc,jambon de paris,prosciutto cotto") {
        meat(AnimalOrigin.PORK, 7)
        cookedYield = 0.9 // sold cooked; reheating loses little weight
        portion(QuantityUnit.SLICE, 28.0, "1 slice (~28 g)")
        sub("turkey_breast", HALAL, note = "Halal turkey ham or sliced turkey breast")
    }
    ing("chorizo", "Chorizo", PORK, ndb = "07019",
        aliases = "chorizo sausage,spanish chorizo,mexican chorizo,chouriço,chourico,chorizo de bilbao") {
        meat(AnimalOrigin.PORK, 21)
        piece(60.0, "1 link (4 in)")
        sub("beef_sausage", HALAL, note = "Halal beef sausage with smoked paprika")
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
    // Molluscs and crabs: as with shrimp, permissibility differs between schools, so halal is VERIFY.
    ing("squid", "Squid / calamari", SEAFOOD, ndb = "15175",
        aliases = "calamari,calamares,calamar,calamaro,squid rings,squid tubes,baby squid,pusit,sotong,ojingeo,youyu,you yu,kalamar") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
    }
    ing("cuttlefish", "Cuttlefish", SEAFOOD, ndb = "15163", cookedNdb = "15229", aliases = "sepia,seppia,seiche") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
    }
    ing("octopus", "Octopus", SEAFOOD, ndb = "15166", cookedNdb = "15230",
        aliases = "baby octopus,octopus tentacles,pulpo,polpo,poulpe,nakji,muneo,zhangyu,ahtapot,pugita,pweza") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
        cookedYield = 0.6 // octopus shrinks a lot when boiled or braised (editorial estimate)
    }
    // Mussels and clams are bought live in the shell; the SR28 records are for the meat alone.
    ing("mussels", "Mussels (in shell)", SEAFOOD, ndb = "15164", cookedNdb = "15165",
        aliases = "mussel,blue mussels,green lipped mussels,green mussels,moules,mejillones,cozze,midye,tahong,kupang") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
        edible = 0.3 // meat is roughly 30% of the live weight (editorial estimate)
        piece(20.0, "1 mussel, in shell")
    }
    ing("clams", "Clams (in shell)", SEAFOOD, ndb = "15157", cookedNdb = "15159",
        aliases = "clam,littleneck clams,manila clams,vongole,almejas,palourdes,cockles,halaan,tulya,kerang,asari,hamaguri,jogae") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
        edible = 0.2 // meat is roughly 15–25% of the live weight (editorial estimate)
        piece(20.0, "1 small clam, in shell")
    }
    // Values are for the meat; a whole crab is mostly shell, so weigh the picked meat.
    ing("crab", "Crab meat", SEAFOOD, ndb = "15139", cookedNdb = "15140",
        aliases = "crab,crabmeat,lump crab meat,lump crabmeat,picked crab meat,cangrejo,jaiba,crabe,granchio,kepiting,ketam,alimango") {
        origin = AnimalOrigin.SHELLFISH; allergen(CRUSTACEAN); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
        portion(QuantityUnit.CUP, 118.0, "1 cup, flaked")
    }
    ing("imitation_crab", "Imitation crab (surimi)", SEAFOOD, ndb = "15138",
        aliases = "imitation crab meat,crab sticks,crab stick,seafood sticks,kani,kanikama,surimi,krab") {
        // Most brands add crab extract, egg white and wheat starch, and some add mirin.
        origin = AnimalOrigin.FISH; allergen(FISH, CRUSTACEAN, EGG, WHEAT, GLUTEN); halal = HalalStatus.VERIFY
        store(FRIDGE, 14); piece(17.0, "1 stick")
    }
    ing("scallops", "Scallops", SEAFOOD, ndb = "15172", cookedNdb = "90240",
        aliases = "scallop,sea scallops,bay scallops,vieiras,coquilles saint jacques,coquille saint jacques,noix de saint jacques,capesante,hotate") {
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC); halal = HalalStatus.VERIFY; store(FRIDGE, 2)
        piece(15.0, "1 large scallop")
    }
    ing("sardines", "Sardines, canned in oil", SEAFOOD, ndb = "15088",
        aliases = "sardine,canned sardines,tinned sardines,sardines in oil,pilchards,sardinas") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(PANTRY, 1000)
        portion(QuantityUnit.CAN, 92.0, "1 can (3.75 oz), drained"); piece(12.0, "1 sardine")
    }
    ing("anchovies", "Anchovies, canned in oil", SEAFOOD, ndb = "15002",
        aliases = "anchovy,anchovy fillets,anchovy fillet,canned anchovies,anchoas,acciughe,alici,anchois") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(PANTRY, 730); piece(4.0, "1 anchovy fillet")
    }
    ing("mackerel", "Mackerel", SEAFOOD, ndb = "15046", cookedNdb = "15047",
        aliases = "mackerel fillet,atlantic mackerel,caballa,maquereau,sgombro,uskumru,saba fish,godeungeo,bangda,bangude,ikan kembung") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(112.0, "1 fillet")
    }
    ing("tuna_fresh", "Tuna, fresh (yellowfin)", SEAFOOD, ndb = "15127", cookedNdb = "15221",
        aliases = "fresh tuna,tuna steak,tuna steaks,tuna loin,ahi,ahi tuna,yellowfin tuna,yellowfin,sashimi tuna,sushi grade tuna,maguro,atun fresco,thon frais") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(170.0, "1 steak")
    }
    ing("trout", "Trout", SEAFOOD, ndb = "15240", cookedNdb = "15241",
        aliases = "rainbow trout,trout fillet,steelhead,steelhead trout,trucha,truite,trota,forelle") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(79.0, "1 fillet")
    }
    // Weights are as bought (dry, salted). Soaking removes most of the salt, so sodium is overstated.
    ing("cod_salted", "Salt cod (bacalhau)", SEAFOOD, ndb = "15018",
        aliases = "salt cod,salted cod,dried salt cod,saltfish,salt fish,bacalhau,bacalao,baccala,morue,klippfisk") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 180)
    }
    ing("smoked_salmon", "Smoked salmon (lox)", SEAFOOD, ndb = "15179",
        aliases = "smoked salmon,lox,nova lox,cold smoked salmon,gravlax,gravadlax,salmon ahumado,saumon fume,salmone affumicato") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 7); portion(QuantityUnit.SLICE, 20.0, "1 slice (~20 g)")
    }
    ing("catfish", "Catfish", SEAFOOD, ndb = "15234", cookedNdb = "15235",
        aliases = "catfish fillet,catfish fillets,basa fillet,basa fish,pangasius,swai,hito,ikan keli,magur,bagre") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(159.0, "1 fillet")
        halal = HalalStatus.VERIFY // fish without scales are not permissible in some schools
    }
    ing("snapper", "Snapper", SEAFOOD, ndb = "15101", cookedNdb = "15102",
        aliases = "red snapper,snapper fillet,huachinango,pargo,pargo rojo,vivaneau,kakap merah,maya maya") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(170.0, "1 fillet portion")
    }
    ing("sea_bass", "Sea bass", SEAFOOD, ndb = "15091", cookedNdb = "15092",
        aliases = "seabass,sea bass fillet,branzino,bronzino,loup de mer,lubina,robalo,spigola,levrek,barramundi,siakap") {
        origin = AnimalOrigin.FISH; allergen(FISH); store(FRIDGE, 2); piece(129.0, "1 fillet")
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
    ing("sour_cream", "Sour cream", DAIRY, ndb = "01056", aliases = "soured cream,crema,crema agria,smetana") {
        dairy(14); density = 1.0; portion(QuantityUnit.TABLESPOON, 12.0, "1 tbsp")
        sub("greek_yogurt_nonfat", LOWER_CALORIE, HIGHER_PROTEIN)
    }
    ing("cheddar", "Cheddar cheese", DAIRY, ndb = "01009", aliases = "cheese,cheddar,yellow cheese,queso,monterey jack,colby jack,pepper jack,colby") {
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
    ing("ricotta", "Ricotta", DAIRY, ndb = "01036", aliases = "ricotta cheese,whole milk ricotta,requeson") {
        dairy(7); portion(QuantityUnit.CUP, 246.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 15.5, "1 tbsp")
    }
    ing("goat_cheese", "Goat cheese, soft (chèvre)", DAIRY, ndb = "01159",
        aliases = "goat cheese,goat's cheese,goats cheese,soft goat cheese,chevre,chèvre,queso de cabra,fromage de chevre") {
        dairy(14); halal = HalalStatus.VERIFY
    }
    ing("gruyere", "Gruyère", DAIRY, ndb = "01023", aliases = "gruyere cheese,gruyère cheese,comte,comté,beaufort cheese") {
        dairy(60); halal = HalalStatus.VERIFY; portion(QuantityUnit.CUP, 108.0, "1 cup, shredded")
    }
    ing("swiss_cheese", "Swiss cheese (Emmental)", DAIRY, ndb = "01040", aliases = "emmental,emmentaler,emmenthal,jarlsberg") {
        dairy(30); halal = HalalStatus.VERIFY
        portion(QuantityUnit.SLICE, 28.0, "1 slice"); portion(QuantityUnit.CUP, 108.0, "1 cup, shredded")
    }
    ing("queso_fresco", "Queso fresco", DAIRY, ndb = "01228",
        aliases = "queso blanco,fresh cheese,queso panela,panela cheese,queijo fresco,queijo minas") {
        dairy(10); halal = HalalStatus.VERIFY; portion(QuantityUnit.CUP, 122.0, "1 cup, crumbled")
    }
    ing("blue_cheese", "Blue cheese", DAIRY, ndb = "01004",
        aliases = "bleu cheese,gorgonzola,roquefort,stilton,danish blue,cabrales,dolcelatte") {
        dairy(30); halal = HalalStatus.VERIFY; portion(QuantityUnit.CUP, 135.0, "1 cup, crumbled")
    }
    ing("buttermilk", "Buttermilk (cultured, low-fat)", DAIRY, ndb = "01088",
        aliases = "buttermilk,cultured buttermilk,low fat buttermilk,chaas,chhaas,chaach,chhach,mattha,ayran,babeurre") {
        dairy(14); density = 1.03; portion(QuantityUnit.CUP, 245.0, "1 cup")
    }
    ing("evaporated_milk", "Evaporated milk", DAIRY, ndb = "01096",
        aliases = "evap milk,evaporated whole milk,carnation milk,leche evaporada,lait concentre non sucre") {
        dairy(7); store(PANTRY, 365); density = 1.07
        portion(QuantityUnit.CAN, 378.0, "1 can (12 fl oz)"); portion(QuantityUnit.CUP, 252.0, "1 cup")
    }
    ing("condensed_milk", "Sweetened condensed milk", DAIRY, ndb = "01095",
        aliases = "condensed milk,leche condensada,lait concentre sucre,leite condensado,milkmaid") {
        dairy(7); store(PANTRY, 365); density = 1.29
        portion(QuantityUnit.CAN, 397.0, "1 can (14 oz)"); portion(QuantityUnit.TABLESPOON, 19.0, "1 tbsp")
    }
    ing("half_and_half", "Half-and-half", DAIRY, ndb = "01049", aliases = "half & half,half n half,half and half cream") {
        dairy(10); density = 1.02; portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
        sub("milk_whole", LOWER_CALORIE)
    }
    ing("cream_light", "Cream, light (single)", DAIRY, ndb = "01050",
        aliases = "single cream,light cream,table cream,coffee cream,cooking cream,media crema,crema de mesa") {
        dairy(10); density = 1.01; portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
        sub("milk_whole", LOWER_CALORIE, note = "Thinner sauce")
        sub("coconut_milk", DAIRY_FREE, VEGAN)
    }

    // ---------------------------------------------------------------- Plant protein & legumes
    ing("tofu_firm", "Tofu, firm", PLANT_PROTEIN, ndb = "16126", aliases = "tofu,bean curd,firm tofu,dubu") {
        allergen(SOY); store(FRIDGE, 5); portion(QuantityUnit.PACKAGE, 400.0, "1 block (~400 g)")
    }
    ing("tofu_silken", "Tofu, silken/soft", PLANT_PROTEIN, ndb = "16127",
        aliases = "silken tofu,soft tofu,sundubu,soondubu,kinugoshi,kinugoshi tofu") {
        allergen(SOY); store(FRIDGE, 5); portion(QuantityUnit.PACKAGE, 350.0, "1 package (~350 g)")
    }
    ing("tempeh", "Tempeh", PLANT_PROTEIN, ndb = "16114", cookedNdb = "16174", aliases = "tempe") {
        allergen(SOY); store(FRIDGE, 10)
        portion(QuantityUnit.PACKAGE, 227.0, "1 block (8 oz)"); portion(QuantityUnit.CUP, 166.0, "1 cup")
    }
    ing("edamame", "Edamame, shelled", PLANT_PROTEIN, ndb = "11211", cookedNdb = "11212",
        aliases = "edamame beans,shelled edamame,mukimame,green soybeans") {
        allergen(SOY); store(FREEZER, 365); portion(QuantityUnit.CUP, 118.0, "1 cup, shelled")
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
        aliases = "moong,moong dal,mung dal,mash ki daal,yellow moong") {
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
    ing("white_beans", "White beans (cannellini), canned", LEGUME, cookedNdb = "16051",
        aliases = "cannellini,cannellini beans,white kidney beans,haricot beans,navy beans,great northern beans,fasolia,alubias blancas") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 240.0, "1 can, drained (~240 g)")
    }
    ing("black_eyed_peas", "Black-eyed peas", LEGUME, ndb = "16062", cookedNdb = "16063",
        aliases = "lobia,cowpeas,black eyed beans,niebe,ewa") {
        store(PANTRY, 365)
    }
    ing("split_peas", "Split peas", LEGUME, ndb = "16085", cookedNdb = "16086", aliases = "yellow split peas,green split peas,matar dal") {
        store(PANTRY, 365); cookedYield = 2.3
    }
    ing("mung_beans", "Mung beans, whole", LEGUME, ndb = "16080", cookedNdb = "16081",
        aliases = "mung beans,green gram,whole moong,sabut moong,green moong,whole mung beans,munggo,monggo,ndengu,choroko,nokdu,lu dou,kacang hijau") {
        store(PANTRY, 365); density = 0.85; cookedYield = 2.5; portion(QuantityUnit.CUP, 207.0, "1 cup, dried")
    }
    ing("pinto_beans", "Pinto beans", LEGUME, ndb = "16042", cookedNdb = "16043",
        aliases = "pinto,frijoles pintos,borlotti,borlotti beans,cranberry beans,roman beans") {
        store(PANTRY, 365); density = 0.82; cookedYield = 2.4; portion(QuantityUnit.CUP, 193.0, "1 cup, dried")
    }
    ing("fava_beans", "Fava beans, dried (ful)", LEGUME, ndb = "16052", cookedNdb = "16053",
        aliases = "fava beans,dried fava beans,dried broad beans,ful medames,foul medames,ful mudammas,habas secas,split fava beans") {
        store(PANTRY, 365); density = 0.63; cookedYield = 2.3; portion(QuantityUnit.CUP, 150.0, "1 cup, dried")
    }
    ing("butter_beans", "Butter beans / lima beans", LEGUME, ndb = "16071", cookedNdb = "16072",
        aliases = "butter beans,lima beans,large lima beans,dried lima beans,gigantes,gigandes,judiones,pallares") {
        store(PANTRY, 365); density = 0.75; cookedYield = 2.4; portion(QuantityUnit.CUP, 178.0, "1 cup, dried")
    }
    ing("adzuki_beans", "Adzuki beans", LEGUME, ndb = "16001", cookedNdb = "16002",
        aliases = "azuki beans,aduki beans,adzuki,azuki,hong dou") {
        store(PANTRY, 365); density = 0.83; cookedYield = 2.4; portion(QuantityUnit.CUP, 197.0, "1 cup, dried")
    }
    // SR28 has no anko record; sweetened canned adzuki beans (tsubuan style) are the same food.
    ing("red_bean_paste", "Sweet red bean paste (anko)", LEGUME, cookedNdb = "16003",
        aliases = "red bean paste,sweet red bean paste,anko,tsubuan,koshian,adzuki bean paste,azuki bean paste,sweet bean paste,dou sha,hong dou sha,sweetened red beans,sweetened adzuki beans") {
        store(FRIDGE, 14); portion(QuantityUnit.TABLESPOON, 18.5, "1 tbsp"); portion(QuantityUnit.CUP, 296.0, "1 cup")
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
        aliases = "sticky rice,glutinous rice,sweet rice,khao niao,malagkit,pulut,mochigome,chapssal") {
        store(PANTRY, 730); density = 0.85; cookedYield = 2.4
    }
    // Arborio, bomba and Calrose are medium-grain; Japanese rice is short-grain. SR28 values are near-identical.
    ing("rice_short_grain", "Rice, short/medium-grain (sushi, risotto, paella)", GRAIN, ndb = "20050", cookedNdb = "20051",
        aliases = "sushi rice,short grain rice,japanese rice,calrose,medium grain rice,arborio,arborio rice,carnaroli,carnaroli rice," +
            "vialone nano,risotto rice,paella rice,bomba,bomba rice,calasparra,pudding rice,egyptian rice,korean rice,japonica rice") {
        store(PANTRY, 730); density = 0.82; cookedYield = 2.4
        portion(QuantityUnit.CUP, 195.0, "1 cup, uncooked")
        sub("rice_brown", HIGHER_PROTEIN, note = "Longer cooking time")
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
    ing("cornmeal", "Cornmeal / masa", GRAIN, ndb = "20022", aliases = "cornmeal,polenta,maize flour,makki ka atta,corn flour") {
        store(PANTRY, 365); portion(QuantityUnit.CUP, 157.0, "1 cup")
    }
    ing("teff", "Teff", GRAIN, ndb = "20142", cookedNdb = "20143", aliases = "teff flour,tef,injera flour") {
        store(PANTRY, 365)
    }
    // Masarepa (arepa flour) is precooked rather than nixtamalised, but its nutrition is near-identical.
    ing("masa_harina", "Masa harina (nixtamalised corn flour)", GRAIN, ndb = "20017",
        aliases = "masa harina,masa,masa flour,instant corn masa flour,maseca,masarepa,harina pan,arepa flour,precooked corn flour") {
        store(PANTRY, 365); density = 0.48; portion(QuantityUnit.CUP, 114.0, "1 cup")
    }
    ing("barley", "Barley, pearled", GRAIN, ndb = "20005", cookedNdb = "20006",
        aliases = "pearl barley,pearled barley,jau,cebada,orge,arpa") {
        allergen(GLUTEN); store(PANTRY, 365); density = 0.85; cookedYield = 2.8
        portion(QuantityUnit.CUP, 200.0, "1 cup, uncooked")
        sub("rice_brown", GLUTEN_FREE)
    }
    ing("buckwheat", "Buckwheat groats (kasha)", GRAIN, ndb = "20009", cookedNdb = "20010",
        aliases = "buckwheat groats,kasha,roasted buckwheat,grechka,kuttu,sarrasin,trigo sarraceno,memil") {
        store(PANTRY, 365); density = 0.69; cookedYield = 3.2; portion(QuantityUnit.CUP, 164.0, "1 cup, uncooked")
    }
    ing("millet", "Millet", GRAIN, ndb = "20031", cookedNdb = "20032",
        aliases = "pearl millet,bajra,proso millet,foxtail millet,millet grains") {
        store(PANTRY, 365); density = 0.85; cookedYield = 3.2; portion(QuantityUnit.CUP, 200.0, "1 cup, uncooked")
    }
    ing("tapioca_pearls", "Tapioca pearls (sago)", GRAIN, ndb = "20068",
        aliases = "tapioca,sago,sago pearls,sabudana,sabu dana,pearl tapioca,boba,boba pearls,sagu") {
        store(PANTRY, 730); density = 0.64; portion(QuantityUnit.CUP, 152.0, "1 cup, dry")
    }
    ing("bread_white", "Bread, white", BREAD, ndb = "18069",
        aliases = "white bread,sandwich bread,toast,double roti,pan de molde,fresh breadcrumbs,soft breadcrumbs,fresh bread crumbs") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); portion(QuantityUnit.SLICE, 25.0, "1 slice")
        sub("bread_whole_wheat", HIGHER_PROTEIN)
    }
    ing("bread_whole_wheat", "Bread, whole-wheat", BREAD, ndb = "18075", aliases = "whole wheat bread,brown bread,wholemeal bread,whole grain bread") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); portion(QuantityUnit.SLICE, 32.0, "1 slice")
    }
    ing("pita", "Pita bread", BREAD, ndb = "18041", aliases = "pita,pitta,arabic bread,khubz,lebanese bread") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 5); piece(60.0, "1 pita (6.5 in)")
    }
    ing("phyllo", "Phyllo (filo) pastry", BREAD, ndb = "18338", aliases = "filo,filo pastry,phyllo dough,fillo,yufka,warqa") {
        allergen(WHEAT, GLUTEN); store(FRIDGE, 14); portion(QuantityUnit.SLICE, 19.0, "1 sheet (~19 g)")
    }
    ing("tortilla_flour", "Flour tortilla", BREAD, ndb = "18364", aliases = "flour tortilla,tortilla,wrap,burrito wrap") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 14); piece(45.0, "1 tortilla (8 in)")
    }
    ing("tortilla_corn", "Corn tortilla", BREAD, ndb = "18363", aliases = "corn tortilla,tortilla de maiz,taco shell") {
        store(PANTRY, 14); piece(26.0, "1 tortilla (6 in)")
    }
    ing("bread_rye", "Bread, rye", BREAD, ndb = "18060",
        aliases = "rye bread,light rye,dark rye,seeded rye,deli rye,pumpernickel,roggenbrot") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 7); portion(QuantityUnit.SLICE, 32.0, "1 slice")
    }
    ing("baguette", "French bread / baguette", BREAD, ndb = "18029",
        aliases = "baguettes,french bread,french baguette,french stick,sourdough,sourdough bread,crusty bread,country bread,pain de campagne,ciabatta,italian bread,vienna bread,pan frances") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 3)
        piece(250.0, "1 baguette (~250 g)"); portion(QuantityUnit.SLICE, 25.0, "1 slice (~2 cm)")
    }
    // The SR28 record is made with shortening; all-butter puff pastry also contains milk, so check the label.
    ing("puff_pastry", "Puff pastry", BREAD, ndb = "18337", cookedNdb = "18211",
        aliases = "puff pastry sheet,puff pastry sheets,ready rolled puff pastry,rough puff pastry,pate feuilletee,pâte feuilletée,hojaldre,masa de hojaldre,pasta sfoglia") {
        allergen(WHEAT, GLUTEN); store(FREEZER, 180); piece(245.0, "1 sheet (~245 g)")
    }
    // Wonton and egg-roll wrappers contain egg; many gyoza and dumpling wrappers do not.
    ing("wonton_wrappers", "Wonton / dumpling wrappers", BREAD, ndb = "18368",
        aliases = "wonton wrapper,wonton skins,won ton wrappers,dumpling wrappers,dumpling skins,gyoza wrappers,potsticker wrappers," +
            "jiaozi wrappers,mandu wrappers,egg roll wrappers,spring roll wrappers,spring roll pastry,lumpia wrappers,siomai wrappers") {
        origin = AnimalOrigin.EGG; allergen(WHEAT, GLUTEN, EGG); store(FRIDGE, 14)
        piece(8.0, "1 wonton wrapper (3½ in square)"); piece(32.0, "1 egg roll wrapper (7 in square)", PortionSize.LARGE)
    }
    // SR28 has no rice-paper record; dry rice noodles are the same rice-and-tapioca dough, dried (~360 kcal/100 g).
    ing("rice_paper", "Rice paper wrappers", BREAD, ndb = "20133",
        aliases = "rice paper,rice paper sheets,rice wrappers,spring roll rice paper,summer roll wrappers,banh trang,bánh tráng") {
        store(PANTRY, 730); piece(9.0, "1 round sheet (22 cm)")
    }
    ing("breadcrumbs", "Breadcrumbs, dry", BREAD, ndb = "18079",
        aliases = "bread crumbs,dry breadcrumbs,dried breadcrumbs,plain breadcrumbs,panko,panko breadcrumbs,japanese breadcrumbs,pan rallado,chapelure,pangrattato") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 180); density = 0.46
        portion(QuantityUnit.CUP, 108.0, "1 cup (panko weighs about half)"); portion(QuantityUnit.TABLESPOON, 7.0, "1 tbsp")
    }
    ing("pasta", "Pasta, dry", PASTA_NOODLE, ndb = "20120", cookedNdb = "20121",
        aliases = "pasta,spaghetti,penne,macaroni,fusilli,linguine,fettuccine,noodles (pasta),rigatoni,farfalle,tagliatelle,pappardelle," +
            "orecchiette,conchiglie,ditalini,orzo,risoni,lasagne sheets,lasagna noodles,vermicelli,wheat vermicelli,seviyan,sewaiyan," +
            "sevaiyan,semiya,fideo,fideos,shehriyeh") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 730); cookedYield = 2.25
        portion(QuantityUnit.CUP, 91.0, "1 cup, dry penne")
        sub("rice_noodles", GLUTEN_FREE)
    }
    ing("egg_noodles", "Egg noodles", PASTA_NOODLE, ndb = "20109", cookedNdb = "20110", aliases = "egg noodles,chow mein noodles,lo mein noodles") {
        origin = AnimalOrigin.EGG; allergen(WHEAT, GLUTEN, EGG); store(PANTRY, 365); cookedYield = 2.4
    }
    ing("rice_noodles", "Rice noodles", PASTA_NOODLE, ndb = "20133", cookedNdb = "20134",
        aliases = "rice noodles,rice vermicelli,banh pho,pho noodles,pad thai noodles,sen lek,bihon") {
        store(PANTRY, 730); cookedYield = 2.3
    }
    // SR28 has mung-bean cellophane noodles only; Korean dangmyeon (sweet-potato starch) is nutritionally very close (almost pure starch).
    ing("glass_noodles", "Glass noodles (cellophane)", PASTA_NOODLE, ndb = "16082",
        aliases = "cellophane noodles,mung bean noodles,bean thread noodles,dangmyeon,sweet potato noodles,japchae noodles,fensi,woon sen") {
        store(PANTRY, 730); cookedYield = 3.0
    }
    ing("ramen_noodles", "Instant ramen noodles", PASTA_NOODLE, ndb = "06583", aliases = "instant noodles,ramen,maggi,indomie,instant ramen") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); portion(QuantityUnit.PACKAGE, 85.0, "1 package (85 g)")
    }
    // Most soba is part wheat flour (100% buckwheat juwari soba is the exception), so it is flagged for wheat.
    ing("soba", "Soba (buckwheat noodles)", PASTA_NOODLE, ndb = "20114", cookedNdb = "20115",
        aliases = "soba noodles,buckwheat noodles,naengmyeon noodles,memil guksu") {
        allergen(WHEAT, GLUTEN); store(PANTRY, 365); cookedYield = 2.5
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
        produce(60, PANTRY); piece(3.0, "1 clove"); portion(QuantityUnit.CLOVE, 3.0, "1 clove")
        portion(QuantityUnit.TEASPOON, 2.8, "1 tsp, minced"); portion(QuantityUnit.TABLESPOON, 8.5, "1 tbsp, minced/paste")
    }
    ing("ginger", "Ginger", VEGETABLE, ndb = "11216", aliases = "ginger,adrak,adrakh,ginger root,jengibre,gingembre,ginger paste") {
        produce(21); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp, grated"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp, grated/paste")
        piece(11.0, "1 in (2.5 cm) piece")
    }
    ing("green_chili", "Green chili", VEGETABLE, ndb = "11670",
        aliases = "green chilli,green chillies,hari mirch,chili pepper,serrano,jalapeno,thai chili,bird's eye chili,chile verde,jalapeño," +
            "jalapeños,jalapeno pepper,jalapeño pepper,green chile,serrano pepper,siling haba,cabe hijau,pilipili," +
            "bird's eye chillies,bird's eye chilli,green bird's eye chillies,thai chillies,thai chilies,fresh green chillies," +
            "scotch bonnet,scotch bonnets,scotch bonnet pepper,scotch bonnet chillies,habanero,habaneros,habanero pepper,ata rodo") {
        // Scotch bonnet and habanero follow the built-in recipes, which use this id; the red record is nutritionally identical.
        produce(10); piece(5.0, "1 green chili")
    }
    ing("bell_pepper", "Bell pepper", VEGETABLE, ndb = "11821",
        aliases = "capsicum,shimla mirch,red pepper,green pepper,yellow pepper,sweet pepper,pimiento,poivron,poblano,poblanos," +
            "poblano pepper,chile poblano,anaheim,anaheim pepper,anaheim chile,hatch chile,new mexico chile,cubanelle," +
            "cubanelle pepper,italian frying pepper,banana pepper") {
        // Poblano, Anaheim and cubanelle are mild, bell-pepper-sized fresh chiles; this record and piece weight fit them better than green_chili.
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
    ing("mushroom", "Mushrooms", VEGETABLE, ndb = "11260",
        aliases = "mushroom,button mushrooms,champignons,khumbi,setas,shiitake,cremini,chestnut mushrooms,brown mushrooms,baby bella," +
            "portobello,portobello mushrooms,oyster mushrooms,king oyster mushrooms,enoki,enoki mushrooms,champinones,hongos") {
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
    ing("bean_sprouts", "Bean sprouts", VEGETABLE, ndb = "11043", aliases = "mung bean sprouts,sprouts,moyashi,sukju,taugeh") {
        produce(3); portion(QuantityUnit.CUP, 104.0, "1 cup")
    }
    // Most kimchi is seasoned with fish sauce or salted shrimp, so it isn't counted as vegetarian.
    ing("kimchi", "Kimchi", CONDIMENT, ndb = "11118", aliases = "kimchee,baechu kimchi") {
        origin = AnimalOrigin.FISH; allergen(FISH, CRUSTACEAN)
        store(FRIDGE, 60); portion(QuantityUnit.CUP, 150.0, "1 cup")
    }
    ing("wakame", "Wakame seaweed (rehydrated)", VEGETABLE, ndb = "11669", aliases = "seaweed,wakame seaweed,miyeok,dried wakame") {
        store(PANTRY, 365); portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp, rehydrated")
    }
    ing("celery", "Celery", VEGETABLE, ndb = "11143", aliases = "celery sticks,apio") {
        produce(14); piece(40.0, "1 medium stalk")
    }
    ing("radish", "Radish / mooli", VEGETABLE, ndb = "11429", aliases = "mooli,muli,daikon,radishes,rabano,daikon radish,labanos,lobak,luo bo") {
        produce(10)
    }
    ing("beetroot", "Beetroot", VEGETABLE, ndb = "11080", aliases = "beet,beets,chukandar,remolacha") {
        produce(14); piece(82.0, "1 beet")
    }
    ing("pumpkin", "Pumpkin / squash", VEGETABLE, ndb = "11422", aliases = "kaddu,squash,calabaza,kabocha") {
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
    ing("leek", "Leek", VEGETABLE, ndb = "11246", cookedNdb = "11247",
        aliases = "leeks,poireau,poireaux,puerro,puerros,porro,porri,daepa,negi,naganegi") {
        produce(14); piece(89.0, "1 leek, trimmed (white and pale green)"); portion(QuantityUnit.CUP, 89.0, "1 cup, sliced")
    }
    ing("kale", "Kale", VEGETABLE, ndb = "11233", cookedNdb = "11234",
        aliases = "curly kale,lacinato kale,tuscan kale,cavolo nero,black kale,dinosaur kale,col rizada") {
        produce(7); portion(QuantityUnit.CUP, 16.0, "1 cup, loosely packed pieces")
        portion(QuantityUnit.BUNCH, 200.0, "1 bunch, stems removed (~200 g)")
    }
    ing("swiss_chard", "Swiss chard", VEGETABLE, ndb = "11147", cookedNdb = "11148",
        aliases = "chard,rainbow chard,ruby chard,silverbeet,silver beet,acelga,acelgas,blette,blettes,bietola,silq") {
        produce(5); portion(QuantityUnit.CUP, 36.0, "1 cup, chopped"); piece(48.0, "1 leaf")
    }
    ing("collard_greens", "Collard greens", VEGETABLE, ndb = "11161", cookedNdb = "11162",
        aliases = "collards,collard,collard leaves,sukuma wiki,sukuma,haak,haakh,couve,couve mineira,gomen") {
        produce(5); portion(QuantityUnit.CUP, 36.0, "1 cup, chopped")
    }
    ing("arugula", "Arugula / rocket", VEGETABLE, ndb = "11959",
        aliases = "arugula,rocket,wild rocket,baby rocket,roquette,rucola,rucula,rúcula,jarjeer,gargeer,roka") {
        produce(4); portion(QuantityUnit.CUP, 20.0, "1 cup"); portion(QuantityUnit.HANDFUL, 20.0, "1 handful")
    }
    ing("watercress", "Watercress", VEGETABLE, ndb = "11591", aliases = "cress,cresson,crescione,xi yang cai,selada air") {
        produce(4); portion(QuantityUnit.CUP, 34.0, "1 cup, chopped")
    }
    ing("fennel_bulb", "Fennel bulb", VEGETABLE, ndb = "11957",
        aliases = "fennel bulbs,florence fennel,bulb fennel,finocchio,hinojo,fenouil") {
        produce(10); piece(234.0, "1 medium bulb"); portion(QuantityUnit.CUP, 87.0, "1 cup, sliced")
    }
    ing("asparagus", "Asparagus", VEGETABLE, ndb = "11011", cookedNdb = "11012",
        aliases = "asparagus spears,green asparagus,white asparagus,esparragos,espárragos,asperges,asparagi") {
        produce(4); piece(16.0, "1 medium spear"); portion(QuantityUnit.BUNCH, 250.0, "1 bunch, trimmed (~250 g)")
        portion(QuantityUnit.CUP, 134.0, "1 cup, pieces")
    }
    ing("artichoke", "Artichoke (globe), edible part", VEGETABLE, ndb = "11007", cookedNdb = "11008",
        aliases = "artichoke,artichokes,globe artichoke,artichoke hearts,alcachofa,alcachofas,carciofi,carciofo,artichaut,enginar") {
        produce(7); piece(128.0, "1 medium artichoke, edible part")
        portion(QuantityUnit.CAN, 240.0, "1 can of hearts, drained (~240 g)")
    }
    ing("turnip", "Turnip", VEGETABLE, ndb = "11564", cookedNdb = "11565",
        aliases = "turnips,shalgam,shaljam,nabo,navet,rapa") {
        produce(21); piece(122.0, "1 medium turnip"); portion(QuantityUnit.CUP, 130.0, "1 cup, cubed")
    }
    ing("parsnip", "Parsnip", VEGETABLE, ndb = "11298", cookedNdb = "11299", aliases = "parsnips,chirivia,chirivía,panais") {
        produce(21); piece(170.0, "1 medium parsnip"); portion(QuantityUnit.CUP, 133.0, "1 cup, sliced")
    }
    ing("butternut_squash", "Butternut squash", VEGETABLE, ndb = "11485", cookedNdb = "11486",
        aliases = "butternut,butternut pumpkin,zapallo anco,courge butternut") {
        produce(60, PANTRY); piece(840.0, "1 medium squash, peeled and seeded (~1 kg whole)")
        portion(QuantityUnit.CUP, 140.0, "1 cup, cubed")
    }
    ing("chayote", "Chayote", VEGETABLE, ndb = "11149", cookedNdb = "11150",
        aliases = "chayote squash,christophene,chocho,cho cho,mirliton,sayote,chuchu,xuxu") {
        produce(14); piece(203.0, "1 chayote"); portion(QuantityUnit.CUP, 132.0, "1 cup, pieces")
    }
    ing("taro", "Taro", VEGETABLE, ndb = "11518", cookedNdb = "11519",
        aliases = "taro root,arbi,arvi,colocasia,eddoe,eddoes,dasheen,gabi,satoimo,qolqas,kolokasi,cocoyam,toran,yu tou") {
        produce(14, PANTRY); portion(QuantityUnit.CUP, 104.0, "1 cup, sliced")
    }
    ing("lotus_root", "Lotus root", VEGETABLE, ndb = "11254", cookedNdb = "11255",
        aliases = "lotus stem,kamal kakdi,bhein,bhee,nadru,renkon,lian ou,yeongeun") {
        produce(14); piece(115.0, "1 root segment"); portion(QuantityUnit.CUP, 120.0, "1 cup, sliced")
    }
    ing("bamboo_shoots", "Bamboo shoots, canned", VEGETABLE, cookedNdb = "11028",
        aliases = "bamboo shoots,bamboo shoot,canned bamboo shoots,sliced bamboo shoots,takenoko,rebung,labong") {
        store(PANTRY, 730); portion(QuantityUnit.CAN, 262.0, "1 can, drained (~260 g)"); portion(QuantityUnit.CUP, 131.0, "1 cup, sliced")
    }
    // Raw record; canned water chestnuts are a little lower in energy.
    ing("water_chestnuts", "Water chestnuts", VEGETABLE, ndb = "11588",
        aliases = "water chestnut,canned water chestnuts,singhara,singhada,matai,ma tai,apulid") {
        store(PANTRY, 730); piece(9.0, "1 water chestnut"); portion(QuantityUnit.CAN, 140.0, "1 can (8 oz), drained (~140 g)")
    }
    ing("shallot", "Shallot", VEGETABLE, ndb = "11677",
        aliases = "shallots,eschalot,eschalots,echalote,échalote,chalota,chalote,scalogno,banana shallots,french shallots,asian shallots," +
            "thai shallots,red shallots,bawang merah,sambar onion,sambar onions,sibuyas tagalog,hom daeng") {
        produce(30, PANTRY); piece(25.0, "1 shallot, peeled"); portion(QuantityUnit.TABLESPOON, 10.0, "1 tbsp, chopped")
    }
    ing("tomatillo", "Tomatillo", VEGETABLE, ndb = "11954", aliases = "tomatillos,tomate verde,tomates verdes,miltomate,tomate de cascara") {
        produce(14); piece(34.0, "1 medium tomatillo"); portion(QuantityUnit.CUP, 132.0, "1 cup, chopped")
    }
    ing("broad_beans", "Broad beans, fresh/frozen", VEGETABLE, ndb = "11088", cookedNdb = "11089",
        aliases = "broad beans,fresh broad beans,frozen broad beans,baby broad beans,fresh fava beans,frozen fava beans,green fava beans,habas,habas verdes,feves,fèves,bakla") {
        store(FREEZER, 240); portion(QuantityUnit.CUP, 109.0, "1 cup, shelled")
    }
    ing("red_chili", "Red chili, fresh", VEGETABLE, ndb = "11819",
        aliases = "red chili,red chilli,red chilies,red chillies,red chile,fresh red chili,fresh red chilli,fresh red chillies," +
            "red chili pepper,red chilli pepper,fresno chili,fresno chile,red bird's eye chili,red thai chili,cabe merah,cili merah") {
        produce(10); piece(8.0, "1 red chilli")
    }
    ing("sun_dried_tomatoes", "Sun-dried tomatoes", VEGETABLE, ndb = "11955",
        aliases = "sun-dried tomatoes,sun dried tomatoes,sundried tomatoes,dried tomatoes,pomodori secchi,tomates secos") {
        store(PANTRY, 365); piece(2.0, "1 piece"); portion(QuantityUnit.CUP, 54.0, "1 cup")
    }
    ing("sun_dried_tomatoes_oil", "Sun-dried tomatoes in oil, drained", VEGETABLE, ndb = "11956",
        aliases = "sun-dried tomatoes in oil,sun dried tomatoes in oil,oil-packed sun-dried tomatoes,sundried tomatoes in oil") {
        store(FRIDGE, 30); piece(3.0, "1 piece"); portion(QuantityUnit.CUP, 110.0, "1 cup")
    }
    // SR28's laver record is moist; a dried sheet has more energy per gram but weighs under 3 g, so totals change by a few kcal.
    ing("nori", "Nori (seaweed sheets)", VEGETABLE, ndb = "11446",
        aliases = "nori sheets,laver,seaweed sheets,sushi nori,roasted seaweed,kimbap seaweed,gim,zicai,zi cai") {
        store(PANTRY, 365); piece(2.6, "1 sheet")
    }
    ing("shiitake_dried", "Shiitake mushrooms, dried", VEGETABLE, ndb = "11268",
        aliases = "dried shiitake,dried shiitake mushrooms,dried shiitakes,dried chinese mushrooms,chinese black mushrooms,dong gu,donggu,dried mushrooms,dried porcini,dried porcini mushrooms") {
        store(PANTRY, 365); piece(3.6, "1 dried mushroom")
    }
    ing("olives_green", "Olives, green", CONDIMENT, ndb = "09195",
        aliases = "olives,olive,green olives,green olive,pitted green olives,manzanilla olives,queen olives,castelvetrano olives," +
            "stuffed olives,aceitunas,aceitunas verdes,olives vertes,zaitoon,zeytin") {
        store(PANTRY, 365); piece(2.7, "1 olive")
    }
    ing("olives_black", "Olives, black (ripe)", CONDIMENT, ndb = "09193",
        aliases = "black olives,black olive,ripe olives,sliced black olives,canned black olives,aceitunas negras,olives noires") {
        store(PANTRY, 365); piece(4.4, "1 large olive"); portion(QuantityUnit.TABLESPOON, 8.4, "1 tbsp, sliced")
    }
    ing("capers", "Capers", CONDIMENT, ndb = "02054", aliases = "caper,nonpareil capers,alcaparras,capres,câpres,capperi") {
        store(FRIDGE, 365); portion(QuantityUnit.TABLESPOON, 8.6, "1 tbsp, drained")
    }
    ing("pickled_cucumber", "Pickled cucumbers (dill pickles)", CONDIMENT, ndb = "11937",
        aliases = "dill pickles,dill pickle,gherkins,gherkin,cornichons,cornichon,pickled cucumbers,pickled cucumber,kosher dill pickles,sour pickles,pepinillos") {
        store(FRIDGE, 90); piece(65.0, "1 medium dill pickle"); portion(QuantityUnit.SLICE, 7.0, "1 slice")
    }
    ing("sauerkraut", "Sauerkraut", CONDIMENT, ndb = "11439", aliases = "choucroute,kiseli kupus,kapusta kiszona,sour cabbage") {
        store(FRIDGE, 60); portion(QuantityUnit.CUP, 142.0, "1 cup, drained")
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
    ing("coconut_milk", "Coconut milk, canned", CONDIMENT, ndb = "12118", aliases = "coconut milk,santan,gata,leche de coco,nariyal doodh") {
        store(PANTRY, 730); density = 0.97; portion(QuantityUnit.CAN, 400.0, "1 can (400 ml)")
        sub("milk_low_fat", LOWER_CALORIE, note = "Lighter but less rich")
    }
    ing("coconut_desiccated", "Coconut, desiccated", NUT_SEED, ndb = "12108", aliases = "desiccated coconut,shredded coconut,nariyal,coconut flakes") {
        store(PANTRY, 180); portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp")
    }
    ing("coconut_cream", "Coconut cream", CONDIMENT, ndb = "12115",
        aliases = "thick coconut milk,first press coconut milk,kakang gata,santan kental,hua kati") {
        store(PANTRY, 730); density = 1.0
        portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp"); portion(QuantityUnit.CAN, 400.0, "1 can (400 ml)")
        sub("coconut_milk", LOWER_CALORIE, note = "Thinner and lighter")
    }
    ing("coconut_fresh", "Coconut, fresh (meat)", NUT_SEED, ndb = "12104",
        aliases = "fresh coconut,coconut meat,fresh grated coconut,freshly grated coconut,grated fresh coconut,frozen grated coconut,thengai,thenga,niyog") {
        produce(7); portion(QuantityUnit.CUP, 80.0, "1 cup, grated")
    }
    ing("peach", "Peach", FRUIT, ndb = "09236",
        aliases = "peaches,nectarine,nectarines,durazno,duraznos,melocoton,melocotón,peche,pesche,aadu,aadoo,shaftalu") {
        produce(5, PANTRY); piece(150.0, "1 medium peach"); portion(QuantityUnit.CUP, 154.0, "1 cup, sliced")
    }
    ing("pear", "Pear", FRUIT, ndb = "09252", aliases = "pears,nashpati,nashpaati,poire,poires,armut,asian pear") {
        produce(14); piece(178.0, "1 medium pear"); portion(QuantityUnit.CUP, 140.0, "1 cup, sliced")
    }
    ing("plum", "Plum", FRUIT, ndb = "09279", aliases = "plums,ciruela,ciruelas,prugna,prugne") {
        produce(5); piece(66.0, "1 plum"); portion(QuantityUnit.CUP, 165.0, "1 cup, sliced")
    }
    ing("cherries", "Cherries", FRUIT, ndb = "09070",
        aliases = "cherry,sweet cherries,sour cherries,morello cherries,cereza,cerezas,cerise,cerises,ciliegie,kiraz,albaloo,visne") {
        produce(5); portion(QuantityUnit.CUP, 154.0, "1 cup, pitted"); piece(8.0, "1 cherry")
    }
    ing("raspberries", "Raspberries", FRUIT, ndb = "09302", aliases = "raspberry,frambuesa,frambuesas,framboise,framboises,lamponi") {
        produce(3); portion(QuantityUnit.CUP, 123.0, "1 cup")
    }
    ing("cranberries_dried", "Cranberries, dried (sweetened)", FRUIT, ndb = "09079",
        aliases = "dried cranberries,dried cranberry,craisins,sweetened dried cranberries") {
        store(PANTRY, 365); portion(QuantityUnit.CUP, 160.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 10.0, "1 tbsp")
    }
    ing("apricot", "Apricot", FRUIT, ndb = "09021",
        aliases = "apricots,fresh apricots,albaricoque,albaricoques,chabacano,abricot,abricots,albicocca,albicocche,mishmish") {
        produce(5); piece(35.0, "1 apricot"); portion(QuantityUnit.CUP, 155.0, "1 cup, halves")
    }
    ing("apricots_dried", "Apricots, dried", FRUIT, ndb = "09032",
        aliases = "dried apricots,dried apricot,dried apricot halves,chopped dried apricots,diced dried apricots,turkish apricots,khubani,khumani,orejones") {
        allergen(SULPHITES) // the SR28 record is sulphured, as most dried apricots are
        store(PANTRY, 365); piece(7.0, "1 dried apricot (2 halves)"); portion(QuantityUnit.CUP, 130.0, "1 cup, halves")
    }
    ing("prunes", "Prunes (dried plums)", FRUIT, ndb = "09291",
        aliases = "prunes,dried plums,pitted prunes,aloo bukhara,alu bukhara,ciruelas pasas,pruneaux") {
        store(PANTRY, 365); piece(9.5, "1 prune, pitted"); portion(QuantityUnit.CUP, 174.0, "1 cup, pitted")
    }
    ing("fig", "Fig, fresh", FRUIT, ndb = "09089", aliases = "figs,fresh figs,higo,higos,figue,figues") {
        produce(5); piece(50.0, "1 medium fig")
    }
    ing("figs_dried", "Figs, dried", FRUIT, ndb = "09094",
        aliases = "dried figs,dried fig,chopped dried figs,anjeer,anjir,higos secos,figues seches,fichi secchi") {
        store(PANTRY, 365); piece(8.4, "1 dried fig"); portion(QuantityUnit.CUP, 149.0, "1 cup")
    }
    ing("kiwi", "Kiwifruit", FRUIT, ndb = "09148", aliases = "kiwi,kiwis,kiwi fruit,green kiwi,chinese gooseberry") {
        produce(14); piece(69.0, "1 kiwi, peeled"); portion(QuantityUnit.CUP, 180.0, "1 cup, sliced")
    }
    ing("guava", "Guava", FRUIT, ndb = "09139",
        aliases = "guavas,amrood,amrud,guayaba,guayabas,goiaba,goyave,bayabas,mapera,jambu batu") {
        produce(5, PANTRY); piece(55.0, "1 guava"); portion(QuantityUnit.CUP, 165.0, "1 cup")
    }
    ing("lychee", "Lychee", FRUIT, ndb = "09164", aliases = "lychees,litchi,litchis,lichi,leechee,lizhi") {
        produce(7); piece(9.6, "1 lychee, peeled"); portion(QuantityUnit.CUP, 190.0, "1 cup")
    }
    ing("passion_fruit", "Passion fruit", FRUIT, ndb = "09231",
        aliases = "passionfruit,passion fruit pulp,maracuya,maracuyá,maracuja,maracujá,granadilla,lilikoi,chinola,parchita,fruit de la passion") {
        produce(7, PANTRY); piece(18.0, "1 fruit (pulp and seeds)"); portion(QuantityUnit.CUP, 236.0, "1 cup, pulp")
    }
    ing("rhubarb", "Rhubarb", FRUIT, ndb = "09307", aliases = "rhubarb stalks,ruibarbo,rhubarbe") {
        produce(7); piece(51.0, "1 stalk"); portion(QuantityUnit.CUP, 122.0, "1 cup, diced")
    }
    // Ripe jackfruit; young green jackfruit (used as a meat substitute) is less sweet and lower in energy.
    ing("jackfruit", "Jackfruit", FRUIT, ndb = "09144",
        aliases = "kathal,kathhal,langka,nangka,khanun,chakka,fenesi") {
        produce(5, PANTRY); portion(QuantityUnit.CUP, 165.0, "1 cup, sliced")
    }
    // "zest lemon" / "zest orange" catch "zest of 1 lemon" once the count and "of" are stripped as descriptors.
    ing("lemon_zest", "Lemon zest", FRUIT, ndb = "09156",
        aliases = "lemon peel,grated lemon zest,lemon rind,grated lemon rind,zest of lemon,zest lemon,cascara de limon") {
        produce(7); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp")
        piece(6.0, "zest of 1 lemon (~1 tbsp)", null)
    }
    ing("orange_zest", "Orange zest", FRUIT, ndb = "09216",
        aliases = "orange peel,grated orange zest,orange rind,grated orange rind,zest of orange,zest orange,cascara de naranja") {
        produce(7); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp")
        piece(9.0, "zest of 1 orange (~1½ tbsp)", null)
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
    ing("peanut_oil", "Peanut (groundnut) oil", FAT_OIL, ndb = "04042",
        aliases = "peanut oil,groundnut oil,arachis oil,huile d'arachide,aceite de cacahuete,moongphali ka tel") {
        spice(); allergen(PEANUT); density = 0.91; portion(QuantityUnit.TABLESPOON, 13.5, "1 tbsp")
        sub("vegetable_oil", NUT_FREE)
    }
    // Red palm oil is a defining flavour in West African and Bahian cooking, so it is not treated as a pantry staple.
    ing("palm_oil", "Palm oil (red)", FAT_OIL, ndb = "04055",
        aliases = "palm oil,red palm oil,zomi,dende oil,azeite de dende,azeite de dendê,epo pupa") {
        store(PANTRY, 365); density = 0.92; portion(QuantityUnit.TABLESPOON, 13.6, "1 tbsp")
    }
    ing("shortening", "Vegetable shortening", FAT_OIL, ndb = "04615",
        aliases = "shortening,crisco,vanaspati,banaspati,dalda,vegetable ghee") {
        store(PANTRY, 365); density = 0.87
        portion(QuantityUnit.TABLESPOON, 12.8, "1 tbsp"); portion(QuantityUnit.CUP, 205.0, "1 cup")
    }
    ing("margarine", "Margarine (80% fat)", FAT_OIL, ndb = "04610", aliases = "margarine,marge,margarina,stick margarine,baking margarine") {
        store(FRIDGE, 120); density = 0.96; portion(QuantityUnit.TABLESPOON, 14.2, "1 tbsp")
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
    ing("pine_nuts", "Pine nuts", NUT_SEED, ndb = "12147",
        aliases = "pine nut,pignoli,pinoli,pinon,pinones,piñones,chilgoza,chilghoza,snobar,snoubar") {
        allergen(TREE_NUT); store(FRIDGE, 90)
        portion(QuantityUnit.TABLESPOON, 8.5, "1 tbsp"); portion(QuantityUnit.CUP, 135.0, "1 cup")
    }
    ing("hazelnuts", "Hazelnuts", NUT_SEED, ndb = "12120",
        aliases = "hazelnut,filberts,filbert,avellanas,avellana,noisettes,nocciole,findik") {
        allergen(TREE_NUT); store(PANTRY, 180)
        portion(QuantityUnit.CUP, 115.0, "1 cup, chopped"); portion(QuantityUnit.HANDFUL, 28.0, "1 handful (~20 nuts)")
    }
    ing("pecans", "Pecans", NUT_SEED, ndb = "12142", aliases = "pecan,pecan halves,pecan nuts,nuez pecana,nueces pecanas,noix de pecan") {
        allergen(TREE_NUT); store(PANTRY, 180); portion(QuantityUnit.CUP, 99.0, "1 cup, halves")
    }
    ing("macadamia", "Macadamia nuts", NUT_SEED, ndb = "12131", aliases = "macadamias,macadamia nut") {
        allergen(TREE_NUT); store(PANTRY, 180); portion(QuantityUnit.CUP, 134.0, "1 cup")
    }
    // Almond flour is finely ground blanched almonds, so the blanched-almond record applies directly.
    ing("almond_flour", "Almond flour", NUT_SEED, ndb = "12062",
        aliases = "almond meal,ground almonds,blanched almond flour,harina de almendra,poudre d'amande,badam powder,badam flour") {
        allergen(TREE_NUT); store(PANTRY, 180); density = 0.41
        portion(QuantityUnit.CUP, 96.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp")
    }
    ing("flaxseed", "Flaxseed (linseed)", NUT_SEED, ndb = "12220",
        aliases = "flaxseeds,flax seeds,flax seed,linseed,linseeds,ground flaxseed,milled flaxseed,flax meal,alsi seeds,linaza") {
        store(PANTRY, 365)
        portion(QuantityUnit.TABLESPOON, 10.3, "1 tbsp, whole (ground is ~7 g)"); portion(QuantityUnit.CUP, 168.0, "1 cup, whole")
    }
    ing("pumpkin_seeds", "Pumpkin seeds (pepitas)", NUT_SEED, ndb = "12014",
        aliases = "pumpkin seeds,pepitas,pepita,squash seeds,shelled pumpkin seeds,pipas de calabaza,kaddu ke beej") {
        store(PANTRY, 180); portion(QuantityUnit.CUP, 129.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 8.0, "1 tbsp")
    }
    ing("sunflower_seeds", "Sunflower seeds", NUT_SEED, ndb = "12036",
        aliases = "sunflower seed,sunflower seed kernels,shelled sunflower seeds,semillas de girasol,pipas") {
        store(PANTRY, 180); portion(QuantityUnit.CUP, 140.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 9.0, "1 tbsp")
    }
    ing("poppy_seeds", "Poppy seeds", NUT_SEED, ndb = "02033",
        aliases = "poppy seed,khus khus,khaskhas,khas khas,posto,semillas de amapola,blue poppy seeds") {
        spice(); portion(QuantityUnit.TEASPOON, 2.8, "1 tsp"); portion(QuantityUnit.TABLESPOON, 8.8, "1 tbsp")
    }
    // Egusi comes from Citrullus melons, the same species as the watermelon in SR28's dried seed-kernel record.
    ing("egusi", "Egusi (melon seeds)", NUT_SEED, ndb = "12174",
        aliases = "egusi seeds,ground egusi,agusi,melon seeds,watermelon seeds,magaz") {
        store(PANTRY, 180); portion(QuantityUnit.CUP, 108.0, "1 cup")
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
        aliases = "lal mirch,lal mirch powder,red chilli powder,kashmiri chili,chile en polvo,gochugaru,chili flakes,red pepper flakes," +
            "chilli powder,chile powder,red chile powder,kashmiri chilli powder,chilli flakes,red chilli flakes,red chili flakes," +
            "chile flakes,crushed red pepper,crushed red pepper flakes,crushed red chili,crushed red chilli,crushed chillies," +
            "crushed chilli,aleppo pepper,pul biber,taco seasoning,fajita seasoning") {
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
    ing("allspice", "Allspice", SPICE, ndb = "02001", aliases = "pimento,jamaican pepper,allspice berries,ground allspice,bahar") {
        spice(); portion(QuantityUnit.TEASPOON, 1.9, "1 tsp, ground"); piece(0.2, "1 berry", null)
    }
    // SR28 has no star anise record; anise seed (02002) is the closest spice profile. Recipes use a few grams, so the effect on totals is negligible.
    ing("star_anise", "Star anise", SPICE, ndb = "02002", aliases = "star aniseed,badian,chakra phool,bunga lawang,hoi") {
        spice(); piece(0.7, "1 whole star", null); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp, ground")
    }
    ing("bay_leaf", "Bay leaf", SPICE, ndb = "02004", aliases = "tej patta,tez patta,bay leaves,laurel") {
        spice(); piece(0.2, "1 leaf", null)
    }
    ing("fenugreek", "Fenugreek (methi)", SPICE, ndb = "02019", aliases = "methi,methi seeds,kasuri methi,fenugreek seeds,dried fenugreek leaves,hilbeh") {
        spice(); portion(QuantityUnit.TEASPOON, 3.7, "1 tsp, seeds")
    }
    ing("mustard_seed", "Mustard seeds", SPICE, ndb = "02024",
        aliases = "rai,sarson seeds,black mustard seeds,mustard seed,dry mustard,mustard powder,ground mustard,mustard flour,english mustard powder") {
        spice(); allergen(MUSTARD); portion(QuantityUnit.TEASPOON, 3.3, "1 tsp")
    }
    ing("fennel_seed", "Fennel seeds", SPICE, ndb = "02018", aliases = "saunf,sonf,fennel,aniseed") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp")
    }
    ing("oregano", "Oregano, dried", SPICE, ndb = "02027", aliases = "dried oregano,mixed herbs,italian seasoning") {
        spice(); portion(QuantityUnit.TEASPOON, 1.0, "1 tsp")
    }
    ing("saffron", "Saffron", SPICE, ndb = "02037", aliases = "zafran,kesar,azafran") {
        spice(); portion(QuantityUnit.PINCH, 0.1, "1 pinch (~20 threads)")
    }
    ing("thyme", "Thyme, dried", SPICE, ndb = "02042", aliases = "dried thyme,ground thyme,tomillo") {
        spice(); portion(QuantityUnit.TEASPOON, 1.0, "1 tsp, leaves"); portion(QuantityUnit.TABLESPOON, 2.7, "1 tbsp, leaves")
    }
    ing("thyme_fresh", "Thyme, fresh", HERB, ndb = "02049",
        aliases = "fresh thyme,thyme sprigs,thyme sprig,sprigs of thyme,sprig of thyme,sprigs thyme,thyme leaves,fresh thyme leaves,lemon thyme,tomillo fresco") {
        produce(10); piece(0.5, "1 sprig"); portion(QuantityUnit.TEASPOON, 0.8, "1 tsp, leaves")
    }
    ing("rosemary", "Rosemary, dried", SPICE, ndb = "02036", aliases = "dried rosemary,romero,romarin,rosmarino") {
        spice(); portion(QuantityUnit.TEASPOON, 1.2, "1 tsp"); portion(QuantityUnit.TABLESPOON, 3.3, "1 tbsp")
    }
    ing("rosemary_fresh", "Rosemary, fresh", HERB, ndb = "02063",
        aliases = "fresh rosemary,rosemary sprigs,rosemary sprig,sprigs of rosemary,sprig of rosemary,sprigs rosemary,fresh rosemary leaves") {
        produce(10); piece(1.0, "1 sprig"); portion(QuantityUnit.TEASPOON, 0.7, "1 tsp, chopped")
        portion(QuantityUnit.TABLESPOON, 1.7, "1 tbsp, chopped")
    }
    ing("dill", "Dill, fresh", HERB, ndb = "02045",
        aliases = "fresh dill,dill weed,dill fronds,dill sprigs,eneldo,aneth,aneto,shepu,suva,ukrop,shevid,shibit") {
        produce(7); portion(QuantityUnit.CUP, 8.9, "1 cup, sprigs"); portion(QuantityUnit.TABLESPOON, 1.0, "1 tbsp, chopped")
        piece(0.2, "1 sprig")
    }
    ing("dill_dried", "Dill, dried", SPICE, ndb = "02017", aliases = "dried dill,dried dill weed") {
        spice(); portion(QuantityUnit.TEASPOON, 1.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 3.1, "1 tbsp")
    }
    // SR28 has ground sage only. Fresh leaves weigh well under a gram each, so using it for them changes totals by a few kcal.
    ing("sage", "Sage", SPICE, ndb = "02038", aliases = "dried sage,ground sage,rubbed sage,fresh sage,sage leaves,salvia,sauge") {
        spice(); portion(QuantityUnit.TEASPOON, 0.7, "1 tsp, ground"); piece(0.5, "1 fresh leaf", null)
    }
    // Dried record; fresh tarragon is used by the sprig, so the difference is negligible.
    ing("tarragon", "Tarragon", SPICE, ndb = "02041", aliases = "dried tarragon,fresh tarragon,estragon,estragón,dragoncello,tarkhun") {
        spice(); portion(QuantityUnit.TEASPOON, 0.6, "1 tsp, leaves"); portion(QuantityUnit.TABLESPOON, 1.8, "1 tbsp, leaves")
    }
    ing("marjoram", "Marjoram, dried", SPICE, ndb = "02023", aliases = "dried marjoram,mejorana,marjolaine,maggiorana") {
        spice(); portion(QuantityUnit.TEASPOON, 0.6, "1 tsp")
    }
    ing("chives", "Chives", HERB, ndb = "11156",
        aliases = "chive,fresh chives,snipped chives,cebollino,ciboulette,erba cipollina,garlic chives,chinese chives,jiu cai,nira,buchu") {
        produce(7); portion(QuantityUnit.TABLESPOON, 3.0, "1 tbsp, chopped"); portion(QuantityUnit.TEASPOON, 1.0, "1 tsp, chopped")
    }
    ing("nutmeg", "Nutmeg", SPICE, ndb = "02025", aliases = "ground nutmeg,whole nutmeg,jaiphal,jaifal,nuez moscada,muscade,noce moscata") {
        spice(); portion(QuantityUnit.TEASPOON, 2.2, "1 tsp, ground")
    }
    ing("mace", "Mace", SPICE, ndb = "02022", aliases = "ground mace,blade mace,javitri,javatri,macis") {
        spice(); portion(QuantityUnit.TEASPOON, 1.7, "1 tsp, ground")
    }
    ing("cayenne", "Cayenne pepper", SPICE, ndb = "02031", aliases = "cayenne,ground cayenne,cayenne powder,pimienta de cayena") {
        spice(); portion(QuantityUnit.TEASPOON, 1.8, "1 tsp"); portion(QuantityUnit.TABLESPOON, 5.3, "1 tbsp")
    }
    ing("white_pepper", "White pepper", SPICE, ndb = "02032",
        aliases = "ground white pepper,white peppercorns,safed mirch,pimienta blanca,poivre blanc") {
        spice(); portion(QuantityUnit.TEASPOON, 2.4, "1 tsp, ground")
    }
    ing("caraway", "Caraway seeds", SPICE, ndb = "02005", aliases = "caraway,caraway seed,shahi jeera,shah jeera,alcaravea,carvi,kummel") {
        spice(); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp")
    }
    ing("celery_seed", "Celery seed", SPICE, ndb = "02007", aliases = "celery seeds,ajmod") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp")
    }
    ing("garlic_powder", "Garlic powder", SPICE, ndb = "02020",
        aliases = "granulated garlic,garlic granules,lehsan powder,ajo en polvo") {
        spice(); portion(QuantityUnit.TEASPOON, 3.1, "1 tsp"); portion(QuantityUnit.TABLESPOON, 9.7, "1 tbsp")
    }
    ing("onion_powder", "Onion powder", SPICE, ndb = "02026", aliases = "granulated onion,onion granules,cebolla en polvo") {
        spice(); portion(QuantityUnit.TEASPOON, 2.4, "1 tsp"); portion(QuantityUnit.TABLESPOON, 6.9, "1 tbsp")
    }
    ing("ginger_ground", "Ginger, ground (dry)", SPICE, ndb = "02021",
        aliases = "ground ginger,ginger powder,dry ginger,dried ginger,dry ginger powder,sonth,saunth,sukku,jengibre molido") {
        spice(); portion(QuantityUnit.TEASPOON, 1.8, "1 tsp"); portion(QuantityUnit.TABLESPOON, 5.2, "1 tbsp")
    }
    ing("dried_chilies", "Dried red chillies, whole", SPICE, ndb = "11962",
        aliases = "dried chilies,dried chillies,dried chilis,dried red chilies,dried red chillies,dried red chili,dried red chilli," +
            "dry red chillies,dry red chilies,whole dried chillies,whole dried red chillies,whole dried red chilies," +
            "whole red chillies,whole red chilies,sabut lal mirch,sukhi lal mirch,sookhi lal mirch,kashmiri chillies," +
            "whole kashmiri chillies,kashmiri red chillies,byadgi chillies,chile de arbol,chiles de arbol,arbol chiles,arbol chilies," +
            "dried thai chilies,dried bird's eye chillies,gan la jiao,chiles secos") {
        spice(); piece(1.0, "1 whole dried chilli (~1 g)", null)
    }
    ing("ancho_chili", "Ancho chile, dried", SPICE, ndb = "11978",
        aliases = "ancho,ancho chile,ancho chiles,ancho chili,ancho chilies,ancho chillies,ancho pepper,ancho peppers,dried ancho,chile ancho,mulato,mulato chile,mulato chiles,chile mulato") {
        store(PANTRY, 365); piece(17.0, "1 dried ancho")
    }
    // SR28 has no guajillo record; pasilla is a dried chile of similar size and composition.
    ing("pasilla_chili", "Pasilla / guajillo chile, dried", SPICE, ndb = "11982",
        aliases = "pasilla,pasilla chile,pasilla chiles,pasilla chili,chile pasilla,chile negro,guajillo,guajillo chile,guajillo chiles," +
            "guajillo chili,guajillo chilies,guajillo peppers,chile guajillo,chiles guajillo") {
        store(PANTRY, 365); piece(7.0, "1 dried chile")
    }
    // Stand-ins below: SR28 has no record for these. Each is used by the pinch or teaspoon, so a
    // botanically or culinarily close spice record changes recipe totals by only a few kcal.
    // Ajwain (carom) is an Apiaceae seed like caraway.
    ing("ajwain", "Ajwain (carom seeds)", SPICE, ndb = "02005", aliases = "ajowan,carom seeds,carom,omum,ajwain seeds,bishop's weed") {
        spice(); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp")
    }
    // Nigella: cumin seed is the closest whole-seed spice record.
    ing("nigella", "Nigella seeds (kalonji)", SPICE, ndb = "02014",
        aliases = "nigella seeds,kalonji,black seed,black seeds,charnushka,habba sauda,kalo jeera,onion seeds") {
        spice(); portion(QuantityUnit.TEASPOON, 2.1, "1 tsp")
    }
    // Asafoetida is sold compounded with flour; garlic powder is a similar pungent dried powder.
    ing("asafoetida", "Asafoetida (hing)", SPICE, ndb = "02020", aliases = "asafetida,hing,heeng,perungayam,inguva") {
        spice(); allergen(WHEAT, GLUTEN) // most compounded hing is cut with wheat flour; rice-flour versions exist
        portion(QuantityUnit.PINCH, 0.2, "1 pinch"); portion(QuantityUnit.TEASPOON, 3.0, "1 tsp")
    }
    // Sichuan peppercorns: black pepper is the closest dried peppercorn record.
    ing("sichuan_pepper", "Sichuan peppercorns", SPICE, ndb = "02030",
        aliases = "sichuan pepper,sichuan peppercorn,szechuan pepper,szechuan peppercorns,huajiao,hua jiao,timur,sansho,chopi") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp")
    }
    // Five-spice is led by star anise, which star_anise also maps to anise seed.
    ing("five_spice", "Chinese five-spice", SPICE, ndb = "02002",
        aliases = "five spice,five spice powder,chinese five spice,chinese five spice powder,wu xiang fen") {
        spice(); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp")
    }
    // Sumac is a ground dried red berry; paprika is the closest ground dried-fruit spice record.
    ing("sumac", "Sumac", SPICE, ndb = "02028", aliases = "sumaq,sumak,summaq,somagh,ground sumac") {
        spice(); portion(QuantityUnit.TEASPOON, 2.3, "1 tsp")
    }
    // Za'atar is mostly thyme/oregano with sumac and toasted sesame; flagged for sesame.
    ing("zaatar", "Za'atar", SPICE, ndb = "02042", aliases = "zaatar,zahtar,zatar,za atar,zaatar spice") {
        spice(); allergen(SESAME); portion(QuantityUnit.TEASPOON, 1.7, "1 tsp"); portion(QuantityUnit.TABLESPOON, 5.0, "1 tbsp")
    }
    // Curry leaves are used a sprig at a time (~1 g); fresh basil is the closest fresh-leaf herb record.
    ing("curry_leaves", "Curry leaves", HERB, ndb = "02044",
        aliases = "curry leaf,kadi patta,kari patta,kadhi patta,karivepaku,karuveppilai,meetha neem,daun kari") {
        produce(14); piece(0.1, "1 leaf", null)
    }
    // Makrut lime leaves are used whole for aroma, like bay leaves.
    ing("kaffir_lime_leaves", "Makrut (kaffir) lime leaves", SPICE, ndb = "02004",
        aliases = "kaffir lime leaves,kaffir lime leaf,makrut lime leaves,makrut lime leaf,lime leaves,bai makrut,daun jeruk,daun limau purut") {
        store(FREEZER, 180); piece(0.3, "1 leaf", null)
    }
    // Galangal is a ginger-family rhizome used the same way as fresh ginger.
    ing("galangal", "Galangal", VEGETABLE, ndb = "11216", aliases = "galanga,greater galangal,blue ginger,thai ginger,laos,lengkuas,kha") {
        produce(21); piece(11.0, "1 in (2.5 cm) piece"); portion(QuantityUnit.TABLESPOON, 6.0, "1 tbsp, sliced")
    }
    ing("salt", "Salt", SPICE, ndb = "02047", aliases = "namak,sea salt,table salt,kosher salt,sal,sel,milh") {
        spice(); density = 1.2; portion(QuantityUnit.TEASPOON, 6.0, "1 tsp"); portion(QuantityUnit.PINCH, 0.4, "1 pinch")
    }

    // ---------------------------------------------------------------- Sauces, condiments, sweeteners
    ing("soy_sauce", "Soy sauce", CONDIMENT, ndb = "16123",
        aliases = "soya sauce,shoyu,light soy sauce,dark soy sauce,ganjang,kecap,salsa de soya,salsa de soja,sauce soja,jiang you,toyo,kicap") {
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
    ing("vinegar", "Vinegar", CONDIMENT, ndb = "02053",
        aliases = "white vinegar,sirka,rice vinegar,vinagre,rice wine vinegar,distilled vinegar,white distilled vinegar,spirit vinegar,cane vinegar,coconut vinegar,palm vinegar,sukang maasim,suka") {
        store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
    }
    ing("apple_cider_vinegar", "Apple cider vinegar", CONDIMENT, ndb = "02048",
        aliases = "cider vinegar,acv,apple vinegar,vinagre de manzana,vinaigre de cidre") {
        store(PANTRY, 730); density = 1.01; portion(QuantityUnit.TABLESPOON, 14.9, "1 tbsp")
    }
    // Wine vinegars and balsamic (which is made with wine vinegar) are VERIFY, as Ingredient.kt describes.
    ing("red_wine_vinegar", "Red wine vinegar", CONDIMENT, ndb = "02068",
        aliases = "white wine vinegar,wine vinegar,sherry vinegar,champagne vinegar,vinagre de vino,vinagre de jerez,vinaigre de vin,aceto di vino") {
        halal = HalalStatus.VERIFY; allergen(SULPHITES); store(PANTRY, 730); density = 1.01
        portion(QuantityUnit.TABLESPOON, 14.9, "1 tbsp")
        sub("apple_cider_vinegar", HALAL)
    }
    ing("balsamic", "Balsamic vinegar", CONDIMENT, ndb = "02069",
        aliases = "balsamic,aceto balsamico,vinagre balsamico,vinagre balsámico,vinaigre balsamique") {
        halal = HalalStatus.VERIFY; allergen(SULPHITES); store(PANTRY, 730); density = 1.08
        portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp")
        sub("apple_cider_vinegar", HALAL, note = "Add a little honey or pomegranate molasses for sweetness")
    }
    ing("oyster_sauce", "Oyster sauce", CONDIMENT, ndb = "06176", aliases = "hao you,haoyou,saus tiram") {
        // Most brands thicken it with wheat flour or starch.
        origin = AnimalOrigin.SHELLFISH; allergen(MOLLUSC, WHEAT, GLUTEN); halal = HalalStatus.VERIFY
        store(FRIDGE, 180); density = 1.22; portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp")
        sub("soy_sauce", VEGETARIAN, VEGAN, ratio = 0.7, note = "Or a mushroom-based vegetarian oyster sauce")
    }
    ing("hoisin", "Hoisin sauce", CONDIMENT, ndb = "06175", aliases = "hoisin,haixian jiang,hai xian jiang") {
        allergen(SOY, WHEAT, GLUTEN, SESAME) // most brands contain wheat and sesame
        store(FRIDGE, 180); density = 1.08; portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp")
    }
    ing("worcestershire", "Worcestershire sauce", CONDIMENT, ndb = "06971",
        aliases = "worcestershire,worcester sauce,lea & perrins,lea and perrins,salsa inglesa") {
        // Contains anchovies; UK-style recipes use barley malt vinegar.
        origin = AnimalOrigin.FISH; allergen(FISH, GLUTEN)
        store(PANTRY, 730); density = 1.16; portion(QuantityUnit.TABLESPOON, 17.0, "1 tbsp")
    }
    ing("hot_sauce", "Hot sauce (chili pepper sauce)", CONDIMENT, ndb = "06961",
        aliases = "hot sauce,pepper sauce,hot pepper sauce,louisiana hot sauce,tabasco,tabasco sauce,salsa picante,peri peri sauce,piri piri sauce") {
        store(PANTRY, 730); density = 1.04; portion(QuantityUnit.TEASPOON, 5.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
    }
    ing("sriracha", "Sriracha", CONDIMENT, ndb = "06631",
        aliases = "sriracha sauce,siracha,sriracha chili sauce,tuong ot,tuong ot sriracha,chili garlic sauce") {
        store(PANTRY, 365); density = 1.1; portion(QuantityUnit.TEASPOON, 6.5, "1 tsp")
    }
    ing("mustard", "Mustard (prepared)", CONDIMENT, ndb = "02046",
        aliases = "prepared mustard,yellow mustard,american mustard,dijon,dijon mustard,english mustard,wholegrain mustard," +
            "whole grain mustard,grainy mustard,stone ground mustard,brown mustard,moutarde,mostaza,senape,senf,hardal") {
        allergen(MUSTARD); store(FRIDGE, 365); density = 1.05
        portion(QuantityUnit.TEASPOON, 5.0, "1 tsp"); portion(QuantityUnit.TABLESPOON, 15.0, "1 tbsp")
    }
    ing("salsa", "Salsa (tomato, jarred)", CONDIMENT, ndb = "06164",
        aliases = "tomato salsa,jarred salsa,salsa roja,chunky salsa,pico de gallo,salsa fresca,salsa mexicana") {
        store(FRIDGE, 14); density = 1.1; portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp"); portion(QuantityUnit.CUP, 260.0, "1 cup")
    }
    ing("pesto", "Pesto (basil)", CONDIMENT, ndb = "06626",
        aliases = "basil pesto,pesto genovese,pesto alla genovese,green pesto") {
        origin = AnimalOrigin.DAIRY; allergen(MILK, TREE_NUT); halal = HalalStatus.VERIFY // Parmesan is made with animal rennet
        store(FRIDGE, 14); portion(QuantityUnit.TABLESPOON, 16.0, "1 tbsp"); portion(QuantityUnit.CUP, 252.0, "1 cup")
    }
    ing("tamarind", "Tamarind", CONDIMENT, ndb = "09322", aliases = "imli,tamarind paste,tamarindo,tamarind pulp,assam jawa") {
        store(PANTRY, 365); portion(QuantityUnit.TABLESPOON, 18.0, "1 tbsp, pulp")
    }
    ing("chicken_broth", "Chicken stock / broth", CONDIMENT, ndb = "06194",
        aliases = "chicken stock,chicken broth,stock,broth,yakhni,caldo de pollo,chicken bone broth") {
        origin = AnimalOrigin.POULTRY; halal = HalalStatus.REQUIRES_HALAL_SOURCE
        store(PANTRY, 730); density = 1.0; portion(QuantityUnit.CUP, 240.0, "1 cup")
        sub("vegetable_broth", VEGETARIAN, VEGAN)
    }
    ing("beef_broth", "Beef or lamb stock / broth", CONDIMENT, ndb = "06008",
        aliases = "beef stock,beef broth,lamb stock,lamb broth,mutton stock,bone broth,consomme,caldo de res") {
        origin = AnimalOrigin.RED_MEAT; halal = HalalStatus.REQUIRES_HALAL_SOURCE
        store(PANTRY, 730); density = 1.0; portion(QuantityUnit.CUP, 240.0, "1 cup")
        sub("vegetable_broth", VEGETARIAN, VEGAN)
    }
    ing("fish_stock", "Fish stock", CONDIMENT, ndb = "06174", aliases = "fish stock,fish broth,fumet,dashi") {
        origin = AnimalOrigin.FISH; allergen(FISH)
        store(FRIDGE, 3); density = 1.0; portion(QuantityUnit.CUP, 233.0, "1 cup")
        sub("vegetable_broth", VEGETARIAN, VEGAN)
    }
    // Dry cubes and powder are concentrated: one 4.8 g cube makes about a cup of stock.
    ing("stock_cube", "Stock cube / bouillon", CONDIMENT, ndb = "06081",
        aliases = "stock cube,bouillon cube,bouillon,chicken cube,chicken stock cube,stock powder,bouillon powder,knorr cube,maggi cube") {
        origin = AnimalOrigin.POULTRY; halal = HalalStatus.REQUIRES_HALAL_SOURCE
        store(PANTRY, 730); piece(4.8, "1 cube"); portion(QuantityUnit.TEASPOON, 2.0, "1 tsp, powder")
    }
    ing("vegetable_broth", "Vegetable stock / broth", CONDIMENT, ndb = "06700",
        aliases = "vegetable stock,veg stock,vegetable broth,veggie stock,vegetable bouillon") {
        store(PANTRY, 730); density = 1.0; portion(QuantityUnit.CUP, 240.0, "1 cup")
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
    // Fruit molasses (pomegranate, date, grape) are concentrated sugar syrups of about the same energy density.
    ing("molasses", "Molasses / treacle", SWEETENER, ndb = "19304",
        aliases = "black treacle,treacle,pomegranate molasses,dibs rumman,nar eksisi,date syrup,date molasses,grape molasses,pekmez") {
        store(PANTRY, 730); density = 1.42; portion(QuantityUnit.TABLESPOON, 20.0, "1 tbsp")
    }
    ing("corn_syrup", "Corn syrup", SWEETENER, ndb = "19350",
        aliases = "light corn syrup,dark corn syrup,karo,glucose syrup,liquid glucose") {
        store(PANTRY, 730); density = 1.44; portion(QuantityUnit.TABLESPOON, 21.0, "1 tbsp")
    }
    // Golden syrup is a cane refiner's syrup; SR28's corn/refiner/sugar table blend matches it closely (~320 kcal/100 g).
    ing("golden_syrup", "Golden syrup", SWEETENER, ndb = "19362", aliases = "lyle's golden syrup,light treacle,refiner's syrup") {
        store(PANTRY, 730); density = 1.34; portion(QuantityUnit.TABLESPOON, 20.0, "1 tbsp")
    }
    ing("powdered_sugar", "Powdered sugar (icing sugar)", SWEETENER, ndb = "19336",
        aliases = "icing sugar,confectioners sugar,confectioners' sugar,confectioner's sugar,10x sugar,azucar glas,azúcar glas,azucar impalpable,sucre glace,zucchero a velo") {
        store(PANTRY, 730); density = 0.51
        portion(QuantityUnit.CUP, 120.0, "1 cup, unsifted"); portion(QuantityUnit.TABLESPOON, 7.5, "1 tbsp")
    }
    ing("cocoa", "Cocoa powder", BAKING, ndb = "19165", aliases = "cocoa,cacao powder,unsweetened cocoa") {
        store(PANTRY, 730); portion(QuantityUnit.TABLESPOON, 5.4, "1 tbsp")
    }
    ing("dark_chocolate", "Dark chocolate (70–85%)", SWEETENER, ndb = "19904", aliases = "dark chocolate,chocolate,bittersweet chocolate") {
        store(PANTRY, 365); portion(QuantityUnit.PIECE, 10.0, "1 square (~10 g)")
        halal = HalalStatus.VERIFY
    }
    // Most semisweet chips contain milk fat and soy lecithin.
    ing("chocolate_chips", "Chocolate chips, semisweet", BAKING, ndb = "19080",
        aliases = "chocolate chips,semisweet chocolate chips,semi-sweet chocolate chips,chocolate morsels,choc chips,semisweet chocolate," +
            "semi-sweet chocolate,chocolate chunks,dark chocolate chips,choco chips") {
        origin = AnimalOrigin.DAIRY; allergen(MILK, SOY); halal = HalalStatus.VERIFY; store(PANTRY, 365)
        portion(QuantityUnit.CUP, 170.0, "1 cup"); portion(QuantityUnit.TABLESPOON, 10.6, "1 tbsp")
    }
    ing("white_chocolate", "White chocolate", BAKING, ndb = "19087",
        aliases = "white chocolate chips,white chocolate chunks,chocolate blanco,chocolat blanc,cioccolato bianco") {
        origin = AnimalOrigin.DAIRY; allergen(MILK, SOY); halal = HalalStatus.VERIFY; store(PANTRY, 365)
        portion(QuantityUnit.CUP, 170.0, "1 cup, chips"); portion(QuantityUnit.TABLESPOON, 14.0, "1 tbsp")
    }
    ing("baking_powder", "Baking powder", BAKING, ndb = "18369", aliases = "baking powder,polvo de hornear") {
        spice(); portion(QuantityUnit.TEASPOON, 4.6, "1 tsp")
    }
    ing("baking_soda", "Baking soda", BAKING, ndb = "18372", aliases = "bicarbonate of soda,meetha soda,bicarbonato") {
        spice(); portion(QuantityUnit.TEASPOON, 4.6, "1 tsp")
    }
    ing("cornstarch", "Cornstarch", BAKING, ndb = "20027", aliases = "corn starch,cornflour,maize starch,maizena,katakuriko,potato starch") {
        spice(); density = 0.54; portion(QuantityUnit.TABLESPOON, 8.0, "1 tbsp"); portion(QuantityUnit.TEASPOON, 2.7, "1 tsp")
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
    ing("white_wine", "Wine, white", BEVERAGE, ndb = "14106",
        aliases = "white wine,cooking wine,mirin,sake,shaoxing wine,rice wine,sherry,dry sherry,cooking sherry") {
        halal = HalalStatus.NOT_PERMISSIBLE; allergen(SULPHITES); store(PANTRY, 365)
        sub("water", HALAL, note = "Use stock with a splash of vinegar or lemon instead")
    }
    ing("red_wine", "Wine, red", BEVERAGE, ndb = "14096",
        aliases = "red wine,dry red wine,cooking red wine,vino tinto,vin rouge,vino rosso") {
        halal = HalalStatus.NOT_PERMISSIBLE; allergen(SULPHITES); store(PANTRY, 365); density = 0.99
        sub("vegetable_broth", HALAL, note = "Use stock with a splash of vinegar or pomegranate molasses instead")
    }
    ing("beer", "Beer", BEVERAGE, ndb = "14003",
        aliases = "lager,pale ale,brown ale,stout,pilsner,ipa,dark beer,cerveza,biere,bière,birra") {
        halal = HalalStatus.NOT_PERMISSIBLE; allergen(GLUTEN); store(PANTRY, 180); density = 1.01
        portion(QuantityUnit.CAN, 356.0, "1 can (12 fl oz)")
        sub("vegetable_broth", HALAL, note = "Use stock, or an alcohol-free malt drink in batters")
    }
    ing("coconut_water", "Coconut water", BEVERAGE, ndb = "12119",
        aliases = "agua de coco,buko juice,nariyal pani,madafu") {
        store(PANTRY, 365); density = 1.01; portion(QuantityUnit.CUP, 240.0, "1 cup")
    }
    // SR28 lists a cup of this record as 262 g; 240 g matches its 1.0 g/ml density.
    ing("almond_milk", "Almond milk, unsweetened", BEVERAGE, ndb = "14091",
        aliases = "almond milk,unsweetened almond milk,almond drink,almond beverage,leche de almendras,lait d'amande") {
        allergen(TREE_NUT); store(FRIDGE, 7); density = 1.01; portion(QuantityUnit.CUP, 240.0, "1 cup")
        sub("soy_milk", NUT_FREE)
    }
}.items
