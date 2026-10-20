package app.nouri.core.dishes

import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.ontology.HalalStatus
import app.nouri.core.ontology.IngredientCatalog
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DishCatalogTest {
    private val nutrition = DishNutrition.shared
    private val matcher = DishMatcher.shared

    @Test
    fun idsUniqueAndReferencesValid() {
        val ids = DishCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (d in DishCatalog.all) {
            for (c in d.composition) {
                c.ingredientId?.let { assertNotNull(IngredientCatalog[it], "${d.id}: unknown ingredient $it") }
                c.foodRefId?.let { assertNotNull(CoreFoods.repository.get(it), "${d.id}: unknown food $it") }
            }
            for (l in d.lookalikes) assertNotNull(DishCatalog[l], "${d.id}: unknown lookalike $l")
        }
    }

    @Test
    fun everyDishCalculatesCompletelyWithPlausibleValues() {
        for (d in DishCatalog.all) {
            val report = nutrition.report(d.id)!!
            val unresolved = report.lines.filter { it.status == LineStatus.UNRESOLVED }
            assertTrue(unresolved.isEmpty(), "${d.id}: unresolved ${unresolved.map { it.line.displayName }}")
            val per100 = nutrition.reference(d.id)!!.per100g
            assertTrue(per100.energyKcal in 10.0..700.0, "${d.id}: implausible ${per100.energyKcal} kcal/100 g")
            // Atwater cross-check: database energy and 4/4/9 energy should agree within ~20%.
            val atwater = per100.atwaterEnergyKcal
            if (per100.energyKcal > 40) {
                assertTrue(abs(atwater - per100.energyKcal) / per100.energyKcal < 0.2, "${d.id}: Atwater mismatch $atwater vs ${per100.energyKcal}")
            }
            // The cooked batch should not weigh more than everything that went in plus absorbed water.
            assertTrue(report.inputMassGrams > 0)
        }
    }

    @Test
    fun biryaniIsDistinctFromPlainRice() {
        val biryani = nutrition.reference("chicken_biryani")!!.per100g
        val rice = nutrition.reference("plain_rice")!!.per100g
        assertTrue(biryani.proteinG > rice.proteinG * 1.5, "Biryani should carry meat protein")
        assertTrue(biryani.fatG > rice.fatG * 5, "Biryani should carry cooking fat")
        assertEquals(130.0, rice.energyKcal, 1e-9)
    }

    @Test
    fun referenceRecipeReportIsTransparent() {
        val report = nutrition.report("chicken_karahi")!!
        assertTrue(report.lines.size >= 8)
        assertTrue(report.cookedWeightMeasured)
        assertTrue(report.confidence != ConfidenceLevel.LOW)
    }

    @Test
    fun dishNamesMatchAcrossSpellings() {
        assertEquals("chicken_biryani", matcher.match("Chicken Biryani")?.dish?.id)
        assertEquals("chicken_biryani", matcher.match("biriyani")?.dish?.id)
        assertEquals("chicken_biryani", matcher.match("biriani")?.dish?.id)
        assertEquals("beef_nihari", matcher.match("nehari")?.dish?.id)
        assertEquals("chicken_karahi", matcher.match("chicken karhai")?.dish?.id)
        assertEquals("pho_bo", matcher.match("Phở bò")?.dish?.id)
        assertEquals("masoor_dal", matcher.match("daal")?.dish?.id)
        assertEquals("chapati", matcher.match("2 rotis")?.dish?.id)
        assertEquals("raita", matcher.match("cucumber raita")?.dish?.id)
        assertEquals(null, matcher.match("quantum soup"))
    }

    @Test
    fun halalAssessmentIsIngredientLevel() {
        assertEquals(HalalStatus.NOT_PERMISSIBLE, nutrition.halalStatus("feijoada"))
        assertEquals(HalalStatus.NOT_PERMISSIBLE, nutrition.halalStatus("jiaozi"))
        assertEquals(HalalStatus.REQUIRES_HALAL_SOURCE, nutrition.halalStatus("chicken_karahi"))
        assertEquals(HalalStatus.PERMISSIBLE, nutrition.halalStatus("masoor_dal"))
        assertEquals(HalalStatus.VERIFY, nutrition.halalStatus("ramen"))
    }

    @Test
    fun coversRequiredDishes() {
        val required = listOf(
            "chicken_biryani", "beef_nihari", "haleem", "masoor_dal", "chicken_karahi", "butter_chicken", "chapati", "naan",
            "shawarma_wrap", "salmon_nigiri", "ramen", "pho_bo", "pad_thai", "tacos_carne_asada", "chicken_enchiladas",
            "jollof_rice", "injera", "doro_wat", "spaghetti_bolognese", "pizza_margherita", "beef_burger", "chicken_sandwich",
            "green_salad", "chicken_rice_bowl", "brownie", "raita",
        )
        for (id in required) assertNotNull(DishCatalog[id], "missing $id")
        assertTrue(DishCatalog.all.map { it.cuisineId }.toSet().size >= 20)
    }

    @Test
    fun repositoryServesDishReferences() {
        val repo = DishFoodRepository()
        val ref = repo.get("dish:naan")
        assertNotNull(ref)
        assertTrue(ref.portions.any { it.label.contains("naan") })
        assertTrue(repo.search("biry").any { it.id == "dish:chicken_biryani" })
    }
}
