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
