package app.nouri.core

import app.nouri.core.fooddb.InMemoryFoodRepository
import app.nouri.core.fooddb.UsdaSr28
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NouriCoreTest {
    private var n = 0

    @Test
    fun searchRanksFriendlyResultsFirst() {
        val core = NouriCore(newId = { "x${n++}" })
        val results = core.search("biryani")
        assertEquals("dish:chicken_biryani", results.first().food.id)
        val chicken = core.search("chicken breast")
        assertTrue(chicken.any { it.title == "Chicken breast, cooked" && it.food.id == "usda:05064" })
        assertTrue(core.search("a").isEmpty())
    }

    @Test
    fun extendedDatabaseIsSearchedAndUsedForMatching() {
        val line = "09999\t0900\tKumquats, raw\t71\t1.88\t0.86\t15.9\t6.5\t9.36\t10\t0.103\t19\t1 fruit without refuse\t\t\t7"
        val extended = InMemoryFoodRepository(listOfNotNull(UsdaSr28.parseTsvLine(line)))
        val core = NouriCore(extendedFoods = extended, newId = { "x${n++}" })
        assertTrue(core.search("kumquat").any { it.food.id == "usda:09999" })
        assertEquals("usda:09999", core.matcher.match("kumquats")?.food?.id)
    }

    @Test
    fun wiringProducesWorkingEstimator() {
        val core = NouriCore(newId = { "x${n++}" })
        val r = core.estimator.estimate(app.nouri.core.ai.mock.MockScenarios.meals.getValue("biryani_plate"), app.nouri.core.scan.EstimationContext("s"))
        assertTrue(r.total.energyKcal > 300)
        assertTrue(NouriCore.ingredientCount > 150 && NouriCore.dishCount > 70)
    }
}
