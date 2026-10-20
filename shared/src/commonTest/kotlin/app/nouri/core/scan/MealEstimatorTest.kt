package app.nouri.core.scan

import app.nouri.core.ai.DetectedFoodItem
import app.nouri.core.ai.DishCandidate
import app.nouri.core.ai.GramRange
import app.nouri.core.ai.Likelihood
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.PortionHints
import app.nouri.core.ai.mock.MockScenarios
import app.nouri.core.diary.MealType
import app.nouri.core.dishes.DishFoodRepository
import app.nouri.core.fooddb.CompositeFoodRepository
import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.NutritionSource
import app.nouri.core.nutrition.Precision
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealEstimatorTest {
    private val foods = CompositeFoodRepository(listOf(CoreFoods.repository, DishFoodRepository()))
    private val matcher = FoodMatcher(foods)
    private var n = 0
    private val estimator = MealEstimator(matcher, foods, newId = { "id${n++}" })

    private fun estimate(key: String, hints: PortionHints = PortionHints(), memory: CorrectionMemory = CorrectionMemory(), images: Int = 1) =
        estimator.estimate(MockScenarios.meals.getValue(key), EstimationContext("scan-1", hints, images, simulated = true, memory = memory))

    @Test
    fun biryaniPlateIsRecognisedAsDistinctDishes() {
        val r = estimate("biryani_plate")
        assertEquals(listOf("chicken_biryani", "raita", "kachumber_salad"), r.items.map { it.match?.dishId })
        val biryani = r.items[0]
        assertEquals(MatchMethod.DISH_REFERENCE, biryani.match!!.method)
        assertEquals(360.0, biryani.portion.grams)
        // Nutrition is reference-recipe energy density × grams, not a model number.
        assertEquals(biryani.match!!.food.per100g.energyKcal * 3.6, biryani.nutrients.energyKcal, 1e-6)
        assertTrue(biryani.alternatives.any { it.dishId == "chicken_pulao" })
        assertTrue(r.totalEnergyRange.low < r.total.energyKcal && r.total.energyKcal < r.totalEnergyRange.high)
        assertTrue(r.explanation.startsWith("Estimated"), r.explanation)
        assertTrue(r.simulated)
        // Reference object (fork) present → portion from reference object, never "higher".
        assertEquals(PortionSource.REFERENCE_OBJECT, biryani.portion.source)
        assertTrue(biryani.confidence.overall != ConfidenceLevel.HIGHER)
    }

    @Test
    fun ambiguousRiceAsksAndAnswerSwitchesDish() {
        val r = estimate("ambiguous_rice")
        val q = assertNotNull(r.question)
        assertEquals(listOf("Biryani", "Pulao", "Not sure"), q.options)
        val before = r.items[0].match!!.dishId
        val answered = estimator.apply(r, ScanEdit.Answer("Biryani"))
        assertNull(answered.question)
        assertEquals("chicken_biryani", answered.items[0].match!!.dishId)
        assertEquals(ConfidenceLevel.HIGHER, answered.items[0].confidence.identification)
        assertTrue(before != null)
    }

    @Test
    fun restaurantAnswerAddsCookingFat() {
        val r = estimate("karahi_naan")
        val homemade = estimator.apply(r, ScanEdit.Answer("Homemade"))
        val restaurant = estimator.apply(r, ScanEdit.Answer("Restaurant"))
        val h = homemade.items[0].nutrients.energyKcal
        val rr = restaurant.items[0].nutrients.energyKcal
        assertTrue(rr > h * 1.25, "restaurant karahi should be richer ($rr vs $h)")
        assertTrue(restaurant.items[0].notes.any { it.startsWith("Restaurant preparation") })
        assertEquals(h, r.items[0].nutrients.energyKcal, 1e-9, "unknown context uses the reference recipe")
    }

    @Test
    fun portionEditsRecalculateDeterministically() {
        val r = estimate("biryani_plate")
        val id = r.items[0].id
        val edited = estimator.apply(r, ScanEdit.SetGrams(id, 300.0, weighed = true))
        val item = edited.items[0]
        assertEquals(300.0, item.portion.grams)
        assertEquals(PortionSource.USER_WEIGHED, item.portion.source)
        assertEquals(ConfidenceLevel.HIGHER, item.confidence.portion)
        assertEquals(item.match!!.food.per100g.energyKcal * 3.0, item.nutrients.energyKcal, 1e-6)
        assertEquals(estimator.apply(r, ScanEdit.SetGrams(id, 300.0, weighed = true)).total, edited.total)

        val halved = estimator.apply(r, ScanEdit.ScalePortion(id, 0.5))
        assertEquals(180.0, halved.items[0].portion.grams, 1e-9)
    }

    @Test
    fun removeExcludeAndAddItems() {
        val r = estimate("biryani_plate")
        val removed = estimator.apply(r, ScanEdit.Remove(r.items[2].id))
        assertEquals(2, removed.items.size)
        val excluded = estimator.apply(r, ScanEdit.SetIncluded(r.items[1].id, false))
        assertTrue(excluded.total.energyKcal < r.total.energyKcal)
        val naan = matcher.match("naan")!!
        val added = estimator.apply(r, ScanEdit.AddItem(naan, 100.0, Quantity(1.0, QuantityUnit.PIECE)))
        assertEquals(4, added.items.size)
        assertEquals(r.total.energyKcal + naan.food.per100g.energyKcal, added.total.energyKcal, 1e-6)
    }

    @Test
    fun householdQuantityUsesDishPortions() {
        val r = estimate("karahi_naan")
        val naanId = r.items[1].id
        val two = estimator.apply(r, ScanEdit.SetQuantity(naanId, Quantity(2.0, QuantityUnit.PIECE)))
        assertEquals(200.0, two.items[1].portion.grams, 1e-9)
    }

    @Test
    fun addedGheeIsCounted() {
        val r = estimate("breakfast_paratha")
        val id = r.items[1].id
        val withGhee = estimator.apply(r, ScanEdit.AddFat(id, AddedFat("ghee", 10.0, "Ghee on top")))
        assertEquals(r.items[1].nutrients.energyKcal + 87.6, withGhee.items[1].nutrients.energyKcal, 0.5)
    }

    @Test
    fun weighedTotalIsSharedByProportion() {
        val r = estimate("biryani_plate", PortionHints(totalWeightGrams = 600.0))
        assertEquals(600.0, r.items.sumOf { it.portion.grams }, 1e-6)
        assertTrue(r.items.all { it.portion.source == PortionSource.USER_WEIGHED })
    }

    @Test
    fun multiplePhotosImprovePortionSource() {
        val r = estimate("burger_fries", images = 2)
        assertTrue(r.items.all { it.portion.source == PortionSource.MULTI_ANGLE })
    }

    @Test
    fun correctionMemoryAppliesNextTime() {
        val first = estimate("ambiguous_rice")
        val corrected = estimator.apply(first, ScanEdit.ChangeFood(first.items[0].id, matcher.match("chicken biryani")!!))
        val memory = CorrectionMemory().learn(corrected.items[0])
        val second = estimate("ambiguous_rice", memory = memory)
        assertEquals("chicken_biryani", second.items[0].match!!.dishId)
        assertEquals(MatchMethod.USER_CORRECTION_MEMORY, second.items[0].match!!.method)

        var m = CorrectionMemory()
        repeat(3) { m = m.learn(corrected.items[0].copy(portion = PortionEstimate.exact(250.0, PortionSource.USER_WEIGHED))) }
        val usual = m.usualPortion(corrected.items[0].match!!.food.id)
        assertEquals(250.0, usual!!.meanGrams, 1e-9)
    }

    @Test
    fun unmatchedItemsAreFlaggedNotInvented() {
        val analysis = MealAnalysis(listOf(DetectedFoodItem("Glorbnak stew", listOf(DishCandidate("glorbnak", Likelihood.MEDIUM)), estimatedGrams = GramRange(100.0, 200.0, 300.0))))
        val r = estimator.estimate(analysis, EstimationContext("s"))
        assertTrue(r.items.single().isUnmatched)
        assertEquals(0.0, r.total.energyKcal)
        assertTrue(r.warnings.any { "aren't matched" in it })
        assertEquals(ConfidenceLevel.LOW, r.overallConfidence)
    }

    @Test
    fun loggingFreezesSnapshotsWithProvenance() {
        val r = estimate("biryani_plate")
        val entries = ScanLogging.toDiaryEntries(r, LocalDate(2026, 10, 20), MealType.LUNCH, 1_000L, "Asia/Karachi") { "e${n++}" }
        assertEquals(3, entries.size)
        val e = entries.first()
        assertEquals(NutritionSource.AI_ASSISTED_ESTIMATE, e.snapshot.provenance.source)
        assertEquals(Precision.ESTIMATED, e.snapshot.precision)
        assertNotNull(e.snapshot.energyRange)
        assertEquals("scan:scan-1:${r.items[0].id}", e.idempotencyKey)
        assertTrue(e.snapshot.provenance.detail!!.contains("demo scan"))
    }

    @Test
    fun ingredientChipsNeverPreselectUncertainItems() {
        val chips = IngredientScanMapper.chips(MockScenarios.ingredients.getValue("fridge"), IngredientNormalizer()) { "c${n++}" }
        val chutney = chips.first { it.name.contains("chutney", ignoreCase = true) }
        assertFalse(chutney.selected)
        val tomato = chips.first { it.ingredientId == "tomato" }
        assertTrue(tomato.selected)
        assertEquals(Quantity(4.0, QuantityUnit.PIECE), tomato.quantity)
        assertTrue(chips.any { it.ingredientId == "chicken_thigh" })
    }
}
