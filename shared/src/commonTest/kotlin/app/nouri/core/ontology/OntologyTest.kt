package app.nouri.core.ontology

import app.nouri.core.fooddb.CoreFoods
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class OntologyTest {
    private val normalizer = IngredientNormalizer()

    @Test
    fun idsAreUnique() {
        val ids = IngredientCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate ingredient ids")
    }

    @Test
    fun everyReferenceResolvesToNutritionData() {
        for (ing in IngredientCatalog.all) {
            val refs = listOfNotNull(ing.primaryRef, ing.cookedRef)
            if (refs.isEmpty()) fail("${ing.id} has no nutrition reference")
            for (ref in refs) assertNotNull(CoreFoods.repository.get(ref), "${ing.id} → $ref missing")
        }
    }

    @Test
    fun substitutionsPointToKnownIngredients() {
        for (ing in IngredientCatalog.all) for (s in ing.substitutions) {
            assertNotNull(IngredientCatalog[s.replacementId], "${ing.id} substitutes unknown ${s.replacementId}")
            assertTrue(s.ratio > 0)
        }
    }

    private fun resolveId(text: String): String? = normalizer.resolve(text)?.ingredient?.id

    @Test
    fun regionalAliasesResolve() {
        assertEquals("cilantro", resolveId("coriander"))
        assertEquals("cilantro", resolveId("Hara Dhaniya"))
        assertEquals("eggplant", resolveId("aubergine"))
        assertEquals("eggplant", resolveId("baingan"))
        assertEquals("yogurt", resolveId("dahi"))
        assertEquals("atta", resolveId("atta"))
        assertEquals("besan", resolveId("besan"))
        assertEquals("ghee", resolveId("Desi ghee"))
        assertEquals("okra", resolveId("bhindi"))
        assertEquals("onion", resolveId("pyaz"))
        assertEquals("lentils_red", resolveId("masoor dal"))
        assertEquals("bok_choy", resolveId("pak choi"))
        assertEquals("fish_sauce", resolveId("nam pla"))
    }

    @Test
    fun worldCuisineAliasesResolve() {
        val expected = mapOf(
            "calamari" to "squid",
            "pulpo" to "octopus",
            "moules" to "mussels",
            "vongole" to "clams",
            "bacalhau" to "cod_salted",
            "saltfish" to "cod_salted",
            "lox" to "smoked_salmon",
            "fresh tuna" to "tuna_fresh",
            "galbi" to "beef_short_ribs",
            "samgyeopsal" to "pork_belly",
            "kaleji" to "chicken_liver",
            "whole chicken" to "chicken_whole",
            "lamb chops" to "lamb_chops",
            "chèvre" to "goat_cheese",
            "queso blanco" to "queso_fresco",
            "chaas" to "buttermilk",
            "sweetened condensed milk" to "condensed_milk",
            "single cream" to "cream_light",
            "arborio rice" to "rice_short_grain",
            "sushi rice" to "rice_short_grain",
            "sticky rice" to "rice_glutinous",
            "masa harina" to "masa_harina",
            "kasha" to "buckwheat",
            "sabudana" to "tapioca_pearls",
            "panko" to "breadcrumbs",
            "sourdough" to "baguette",
            "seviyan" to "pasta",
            "gyoza wrappers" to "wonton_wrappers",
            "ful medames" to "fava_beans",
            "fresh fava beans" to "broad_beans",
            "anko" to "red_bean_paste",
            "sundubu" to "tofu_silken",
            "arbi" to "taro",
            "renkon" to "lotus_root",
            "christophene" to "chayote",
            "sukuma wiki" to "collard_greens",
            "rocket" to "arugula",
            "bawang merah" to "shallot",
            "jalapeño" to "green_chili",
            "poblano peppers" to "bell_pepper",
            "scotch bonnet" to "green_chili",
            "whole dried red chillies" to "dried_chilies",
            "guajillo chiles" to "pasilla_chili",
            "gherkins" to "pickled_cucumber",
            "nori sheets" to "nori",
            "kathal" to "jackfruit",
            "amrood" to "guava",
            "maracuyá" to "passion_fruit",
            "anjeer" to "figs_dried",
            "fresh figs" to "fig",
            "egusi" to "egusi",
            "chilgoza" to "pine_nuts",
            "khus khus" to "poppy_seeds",
            "ground almonds" to "almond_flour",
            "fresh thyme" to "thyme_fresh",
            "dried thyme" to "thyme",
            "kalonji" to "nigella",
            "hing" to "asafoetida",
            "za'atar" to "zaatar",
            "kadi patta" to "curry_leaves",
            "lengkuas" to "galangal",
            "red palm oil" to "palm_oil",
            "vanaspati" to "shortening",
            "icing sugar" to "powdered_sugar",
            "pomegranate molasses" to "molasses",
            "salsa inglesa" to "worcestershire",
            "dijon mustard" to "mustard",
            "cerveza" to "beer",
            "nariyal pani" to "coconut_water",
        )
        for ((text, id) in expected) assertEquals(id, resolveId(text), "\"$text\"")
    }

    @Test
    fun specificNamesBeatTheirGenericParts() {
        // Wine vinegars and sherry must not read as the (non-halal) wine they contain in their names.
        assertEquals("red_wine_vinegar", resolveId("white wine vinegar"))
        assertEquals("vinegar", resolveId("rice wine vinegar"))
        assertEquals("white_wine", resolveId("dry sherry"))
        assertEquals("red_wine", resolveId("red wine"))
        assertEquals("chili_powder", resolveId("red chilli flakes"))
        assertEquals("chili_powder", resolveId("crushed red pepper"))
        assertEquals("chili_powder", resolveId("kashmiri red chilli powder"))
        assertEquals("sun_dried_tomatoes_oil", resolveId("sun-dried tomatoes in oil, drained"))
        assertEquals("apricots_dried", resolveId("chopped dried apricots"))
        assertEquals("bread_white", resolveId("fresh breadcrumbs"))
        assertEquals("olive_oil", resolveId("extra virgin olive oil"))
        assertEquals("tuna_canned", resolveId("tuna"))
        assertEquals("cayenne", resolveId("cayenne pepper"))
        assertEquals("mustard_seed", resolveId("dry mustard"))
    }

    @Test
    fun newMeatAndAlcoholHaveHalalStatus() {
        for (id in listOf("pork_belly", "ham", "chorizo", "beer", "red_wine")) {
            assertEquals(HalalStatus.NOT_PERMISSIBLE, IngredientCatalog.require(id).halal, id)
        }
        for (id in listOf("duck", "turkey_breast", "chicken_whole", "chicken_liver", "beef_brisket", "beef_short_ribs", "beef_sausage", "lamb_chops")) {
            assertEquals(HalalStatus.REQUIRES_HALAL_SOURCE, IngredientCatalog.require(id).halal, id)
        }
        assertEquals(HalalStatus.VERIFY, IngredientCatalog.require("squid").halal)
        assertEquals(HalalStatus.VERIFY, IngredientCatalog.require("red_wine_vinegar").halal)
    }

    @Test
    fun seafoodCarriesItsAllergens() {
        val fish = listOf("sardines", "anchovies", "mackerel", "tuna_fresh", "trout", "cod_salted", "smoked_salmon", "catfish", "snapper", "sea_bass", "worcestershire")
        for (id in fish) {
            val ing = IngredientCatalog.require(id)
            assertTrue(Allergen.FISH in ing.allergens, "$id should list fish")
            assertEquals(AnimalOrigin.FISH, ing.animalOrigin, id)
        }
        val molluscs = listOf("squid", "cuttlefish", "octopus", "mussels", "clams", "scallops", "oyster_sauce")
        for (id in molluscs) {
            val ing = IngredientCatalog.require(id)
            assertTrue(Allergen.MOLLUSC in ing.allergens, "$id should list molluscs")
            assertEquals(AnimalOrigin.SHELLFISH, ing.animalOrigin, id)
        }
        assertTrue(Allergen.CRUSTACEAN in IngredientCatalog.require("crab").allergens)
        assertTrue(Allergen.SESAME in IngredientCatalog.require("zaatar").allergens)
        assertTrue(Allergen.PEANUT in IngredientCatalog.require("peanut_oil").allergens)
        assertTrue(!IngredientCatalog.require("worcestershire").isVegetarian)
    }

    @Test
    fun shellOnAndBoneInItemsExcludeInediblePart() {
        for (id in listOf("mussels", "clams", "chicken_whole", "duck", "beef_short_ribs", "lamb_chops")) {
            assertTrue(IngredientCatalog.require(id).edibleFraction < 1.0, id)
        }
        // Dry grains and pulses swell when cooked, which the pot-weight estimate relies on.
        for (id in listOf("barley", "buckwheat", "millet", "rice_short_grain", "soba", "pinto_beans", "fava_beans", "butter_beans", "mung_beans", "adzuki_beans")) {
            assertTrue((IngredientCatalog.require(id).cookedYield ?: 0.0) > 1.0, id)
        }
    }

    @Test
    fun stocksKeepTheirAnimalOrigin() {
        // Diet and halal flags depend on this: fish or beef stock must not read as chicken.
        assertEquals("fish_stock", resolveId("fish stock"))
        assertEquals("beef_broth", resolveId("beef stock"))
        assertEquals("beef_broth", resolveId("lamb stock"))
        assertEquals("vegetable_broth", resolveId("vegetable stock"))
        assertEquals("stock_cube", resolveId("1 chicken stock cube"))
        assertEquals("stock_cube", resolveId("bouillon cube"))
        assertEquals(AnimalOrigin.FISH, IngredientCatalog["fish_stock"]?.animalOrigin)
    }

    @Test
    fun descriptorsAndPluralsAreIgnored() {
        assertEquals("onion", resolveId("2 large onions, finely chopped"))
        assertEquals("tomato", resolveId("ripe tomatoes"))
        assertEquals("chicken_breast", resolveId("Boneless skinless chicken breasts"))
        assertEquals("potato", resolveId("potatoes"))
        assertEquals("garlic", resolveId("garlic cloves, minced"))
    }

    @Test
    fun compoundNamesBeatTheirParts() {
        assertEquals("tomato_paste", resolveId("tomato paste"))
        assertEquals("beef_ground", resolveId("ground beef"))
        assertEquals("cumin", resolveId("ground cumin"))
        assertEquals("coconut_milk", resolveId("coconut milk"))
        assertEquals("greek_yogurt_nonfat", resolveId("greek yogurt"))
    }

    @Test
    fun genericChickenMeansBoneInCurryCut() {
        assertEquals("chicken_curry_cut", resolveId("chicken"))
        assertEquals("chicken_thigh", resolveId("chicken thighs"))
    }

    @Test
    fun fuzzyMatchingToleratesTypos() {
        val match = normalizer.resolve("tomatos")
        assertEquals("tomato", match?.ingredient?.id)
        val typo = normalizer.resolve("cardamon")
        assertEquals("cardamom", typo?.ingredient?.id)
        assertEquals(MatchKind.FUZZY, typo?.kind)
    }

    @Test
    fun nonsenseDoesNotMatch() {
        assertNull(normalizer.resolve("xqzvw"))
        assertNull(normalizer.resolve(""))
    }

    @Test
    fun halalStatusIsIngredientLevel() {
        assertEquals(HalalStatus.NOT_PERMISSIBLE, IngredientCatalog.require("pork_shoulder").halal)
        assertEquals(HalalStatus.NOT_PERMISSIBLE, IngredientCatalog.require("white_wine").halal)
        assertEquals(HalalStatus.REQUIRES_HALAL_SOURCE, IngredientCatalog.require("chicken_breast").halal)
        assertEquals(HalalStatus.VERIFY, IngredientCatalog.require("gelatin").halal)
        assertEquals(HalalStatus.PERMISSIBLE, IngredientCatalog.require("lentils_red").halal)
    }

    @Test
    fun dietFlagsFollowAnimalOrigin() {
        assertTrue(IngredientCatalog.require("paneer").isVegetarian)
        assertTrue(!IngredientCatalog.require("paneer").isVegan)
        assertTrue(IngredientCatalog.require("tofu_firm").isVegan)
        assertTrue(!IngredientCatalog.require("fish_sauce").isVegetarian)
        assertTrue(!IngredientCatalog.require("mayonnaise").isVegan)
        assertTrue(!IngredientCatalog.require("honey").isVegan)
        assertTrue(!IngredientCatalog.require("gelatin").isVegetarian)
        assertTrue(IngredientCatalog.require("atta").containsGluten)
    }

    @Test
    fun suggestionsMatchPrefixes() {
        val s = normalizer.suggest("chick")
        assertTrue(s.any { it.id.startsWith("chicken") || it.id.startsWith("chickpea") })
    }
}
