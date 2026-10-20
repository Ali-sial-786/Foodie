package app.nouri.core.benchmark

import app.nouri.core.ai.AiCallMeta
import app.nouri.core.ai.AiError
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.ProviderKind
import app.nouri.core.dishes.DishFoodRepository
import app.nouri.core.fooddb.CompositeFoodRepository
import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.scan.FoodMatcher
import app.nouri.core.scan.MealEstimator
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproducible benchmark over synthetic provider fixtures. Writes
 * shared/build/reports/benchmark/benchmark.md. Thresholds guard against
 * regressions in the downstream pipeline; they are not accuracy claims.
 */
class BenchmarkTest {
    private val foods = CompositeFoodRepository(listOf(CoreFoods.repository, DishFoodRepository()))
    private var n = 0
    private val runner = BenchmarkRunner(MealEstimator(FoodMatcher(foods), foods, newId = { "b${n++}" }), foods)

    @Test
    fun fixtureBenchmark() = runTest {
        val summary = runner.run(BenchmarkFixtures.cases, mode = "synthetic provider fixtures") { case ->
            case.providerFixture?.let { AiResult.Success(it, AiCallMeta(ProviderKind.MOCK, "fixture", 0, simulated = true)) }
                ?: AiResult.Failure(AiError.NotConfigured("no fixture"))
        }
        val report = BenchmarkRunner.markdown(summary)
        val dir = File(System.getProperty("nouri.repoRoot") ?: ".", "shared/build/reports/benchmark").apply { mkdirs() }
        File(dir, "benchmark.md").writeText(report)
        println(report)

        assertEquals(20, summary.cases)
        assertEquals(0, summary.failures)
        assertTrue(summary.identificationAccuracyPct >= 85.0, "identification ${summary.identificationAccuracyPct}")
        assertTrue(summary.medianEnergyAbsPctError <= 15.0, "median energy error ${summary.medianEnergyAbsPctError}")
        assertTrue(summary.truthWithinRangePct >= 70.0, "range coverage ${summary.truthWithinRangePct}")
        // The deliberately hard cases must show up as errors, not be hidden.
        val bigPasta = summary.results.first { it.case.id == "big_pasta" }
        assertTrue(bigPasta.energyAbsPctError > 25.0)
        val missedDrink = summary.results.first { it.case.id == "burger_missed_drink" }
        assertTrue(missedDrink.correctionsNeeded >= 1)
    }

    @Test
    fun failedAnalysesAreCountedNotDropped() = runTest {
        val summary = runner.run(BenchmarkFixtures.cases.take(3), mode = "failing provider") { AiResult.Failure(AiError.Network()) }
        assertEquals(3, summary.failures)
        assertEquals(0.0, summary.identificationAccuracyPct)
    }
}
