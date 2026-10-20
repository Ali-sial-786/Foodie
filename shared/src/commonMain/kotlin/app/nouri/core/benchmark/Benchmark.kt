package app.nouri.core.benchmark

import app.nouri.core.ai.AiResult
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.dishes.DishNutrition
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.model.Nutrients
import app.nouri.core.scan.EstimationContext
import app.nouri.core.scan.MealEstimator
import app.nouri.core.scan.MealScanResult
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.TimeSource

@Serializable
enum class BenchmarkCategory {
    SINGLE_INGREDIENT, MIXED_PLATE, PACKAGED, SOUTH_ASIAN, EAST_ASIAN, MIDDLE_EASTERN, WESTERN,
    AFRICAN, LATIN_AMERICAN, RESTAURANT, PARTIALLY_OBSCURED, LOOKALIKE, DIFFICULT_PORTION,
}

/** How the ground truth was established; only weighed/label/recipe truths are used for error metrics. */
@Serializable
enum class TruthSource { WEIGHED, LABEL, KNOWN_RECIPE, ESTIMATED_BY_ANNOTATOR }

@Serializable
data class TruthItem(
    /** Food reference id ("dish:chicken_biryani", "usda:09040", "off:…"). */
    val foodRefId: String,
    val grams: Double,
    /** Other ids that count as a correct identification (e.g. a regional variant). */
    val acceptableIds: List<String> = emptyList(),
)

@Serializable
data class BenchmarkCase(
    val id: String,
    val categories: Set<BenchmarkCategory>,
    val description: String,
    val truth: List<TruthItem>,
    val truthSource: TruthSource,
    /** Path to a photo for real-provider runs (relative to the dataset directory). */
    val imagePath: String? = null,
    /** Recorded or synthetic provider output for deterministic runs of the downstream pipeline. */
    val providerFixture: MealAnalysis? = null,
)

data class CaseResult(
    val case: BenchmarkCase,
    val succeeded: Boolean,
    val failure: String?,
    val identifiedCorrectly: Int,
    val truthItems: Int,
    /** Detected items not in the truth (false positives) — each would need removing. */
    val extraItems: Int,
    val portionAbsPctErrors: List<Double>,
    val truthEnergyKcal: Double,
    val estimatedEnergyKcal: Double,
    val truthNutrients: Nutrients,
    val estimatedNutrients: Nutrients,
    val withinRange: Boolean,
    /** Half-width of the shown calorie range relative to the estimate (0.25 = ±25%). */
    val rangeRelativeHalfWidth: Double,
    val correctionsNeeded: Int,
    val latencyMs: Long,
) {
    val energyAbsPctError: Double get() = if (truthEnergyKcal > 0) abs(estimatedEnergyKcal - truthEnergyKcal) / truthEnergyKcal * 100 else 0.0
}

data class BenchmarkSummary(
    val mode: String,
    val cases: Int,
    val failures: Int,
    val identificationAccuracyPct: Double,
    val meanPortionAbsPctError: Double,
    val meanEnergyAbsPctError: Double,
    val medianEnergyAbsPctError: Double,
    val meanProteinAbsError: Double,
    val meanCarbsAbsError: Double,
    val meanFatAbsError: Double,
    val truthWithinRangePct: Double,
    /** Context for [truthWithinRangePct]: wide ranges trivially contain the truth. */
    val meanRangeHalfWidthPct: Double,
    val meanCorrectionsPerMeal: Double,
    val meanLatencyMs: Double,
    val byCategory: Map<BenchmarkCategory, Double>,
    val results: List<CaseResult>,
)

/**
 * Runs benchmark cases through an analysis source and the real estimation
 * pipeline. With fixtures as the source it measures everything downstream of
 * vision (matching, portions, nutrition, ranges); with a live provider and photos
 * it measures the whole system.
 */
class BenchmarkRunner(
    private val estimator: MealEstimator,
    private val foods: FoodRepository,
    private val dishNutrition: DishNutrition = DishNutrition.shared,
) {
    suspend fun run(cases: List<BenchmarkCase>, mode: String, analyze: suspend (BenchmarkCase) -> AiResult<MealAnalysis>): BenchmarkSummary {
        val results = cases.map { runCase(it, analyze) }
        return summarize(mode, results)
    }

    private suspend fun runCase(case: BenchmarkCase, analyze: suspend (BenchmarkCase) -> AiResult<MealAnalysis>): CaseResult {
        val truthNutrients = Nutrients.sum(case.truth.mapNotNull { t -> reference(t.foodRefId)?.nutrientsFor(t.grams) })
        val start = TimeSource.Monotonic.markNow()
        val outcome = analyze(case)
        val latency = start.elapsedNow().inWholeMilliseconds
        if (outcome is AiResult.Failure) {
            return CaseResult(case, false, outcome.error.message, 0, case.truth.size, 0, emptyList(), truthNutrients.energyKcal, 0.0, truthNutrients, Nutrients.ZERO, false, 0.0, case.truth.size, latency)
        }
        val analysis = (outcome as AiResult.Success).value
        val result: MealScanResult = estimator.estimate(analysis, EstimationContext(scanId = "bench-${case.id}"))

        val unmatchedTruth = case.truth.toMutableList()
        var correct = 0
        var extra = 0
        val portionErrors = mutableListOf<Double>()
        for (item in result.items) {
            val id = item.match?.food?.id
            val truth = unmatchedTruth.firstOrNull { t -> id != null && (t.foodRefId == id || id in t.acceptableIds) }
            if (truth != null) {
                correct++
                unmatchedTruth.remove(truth)
                portionErrors += abs(item.portion.grams - truth.grams) / truth.grams * 100
            } else {
                extra++
            }
        }
        val missed = unmatchedTruth.size
        val r = result.totalEnergyRange
        return CaseResult(
            case = case,
            succeeded = true,
            failure = null,
            identifiedCorrectly = correct,
            truthItems = case.truth.size,
            extraItems = extra,
            portionAbsPctErrors = portionErrors,
            truthEnergyKcal = truthNutrients.energyKcal,
            estimatedEnergyKcal = result.total.energyKcal,
            truthNutrients = truthNutrients,
            estimatedNutrients = result.total,
            withinRange = truthNutrients.energyKcal in r.low..r.high,
            rangeRelativeHalfWidth = r.relativeSpread,
            // Each wrong item needs a rename (or removal) and each missed item an addition.
            correctionsNeeded = extra + missed,
            latencyMs = latency,
        )
    }

    private fun reference(id: String) =
        if (id.startsWith(DishNutrition.PREFIX)) dishNutrition.reference(id.removePrefix(DishNutrition.PREFIX)) else foods.get(id)

    private fun summarize(mode: String, results: List<CaseResult>): BenchmarkSummary {
        val ok = results.filter { it.succeeded }
        val metricCases = ok.filter { it.case.truthSource != TruthSource.ESTIMATED_BY_ANNOTATOR }
        fun mean(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size
        val energyErrors = metricCases.map { it.energyAbsPctError }.sorted()
        val totalTruth = ok.sumOf { it.truthItems }
        val byCategory = BenchmarkCategory.entries.associateWith { cat ->
            mean(metricCases.filter { cat in it.case.categories }.map { it.energyAbsPctError })
        }.filterValues { it > 0.0 }
        return BenchmarkSummary(
            mode = mode,
            cases = results.size,
            failures = results.count { !it.succeeded },
            identificationAccuracyPct = if (totalTruth == 0) 0.0 else ok.sumOf { it.identifiedCorrectly } * 100.0 / totalTruth,
            meanPortionAbsPctError = mean(metricCases.flatMap { it.portionAbsPctErrors }),
            meanEnergyAbsPctError = mean(energyErrors),
            medianEnergyAbsPctError = if (energyErrors.isEmpty()) 0.0 else energyErrors[energyErrors.size / 2],
            meanProteinAbsError = mean(metricCases.map { abs(it.estimatedNutrients.proteinG - it.truthNutrients.proteinG) }),
            meanCarbsAbsError = mean(metricCases.map { abs(it.estimatedNutrients.carbsG - it.truthNutrients.carbsG) }),
            meanFatAbsError = mean(metricCases.map { abs(it.estimatedNutrients.fatG - it.truthNutrients.fatG) }),
            truthWithinRangePct = if (metricCases.isEmpty()) 0.0 else metricCases.count { it.withinRange } * 100.0 / metricCases.size,
            meanRangeHalfWidthPct = mean(metricCases.map { it.rangeRelativeHalfWidth * 100 }),
            meanCorrectionsPerMeal = mean(ok.map { it.correctionsNeeded.toDouble() }),
            meanLatencyMs = mean(results.map { it.latencyMs.toDouble() }),
            byCategory = byCategory,
            results = results,
        )
    }

    companion object {
        fun markdown(summary: BenchmarkSummary): String = buildString {
            fun f(v: Double) = ((v * 10).toLong() / 10.0).toString()
            appendLine("# Nouri estimation benchmark — ${summary.mode}")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| Cases (failures) | ${summary.cases} (${summary.failures}) |")
            appendLine("| Identification accuracy | ${f(summary.identificationAccuracyPct)}% |")
            appendLine("| Mean portion error | ${f(summary.meanPortionAbsPctError)}% |")
            appendLine("| Mean / median meal energy error | ${f(summary.meanEnergyAbsPctError)}% / ${f(summary.medianEnergyAbsPctError)}% |")
            appendLine("| Mean protein / carbs / fat error | ${f(summary.meanProteinAbsError)} g / ${f(summary.meanCarbsAbsError)} g / ${f(summary.meanFatAbsError)} g |")
            appendLine("| Truth inside shown range | ${f(summary.truthWithinRangePct)}% (mean range ±${f(summary.meanRangeHalfWidthPct)}%) |")
            appendLine("| Corrections needed per meal | ${f(summary.meanCorrectionsPerMeal)} |")
            appendLine("| Mean latency | ${f(summary.meanLatencyMs)} ms |")
            appendLine()
            appendLine("| Case | Truth kcal | Estimate kcal | Error | In range | Corrections |")
            appendLine("|---|---|---|---|---|---|")
            for (r in summary.results) {
                appendLine("| ${r.case.id} | ${r.truthEnergyKcal.toLong()} | ${r.estimatedEnergyKcal.toLong()} | ${f(r.energyAbsPctError)}% | ${if (r.withinRange) "yes" else "no"} | ${r.correctionsNeeded}${r.failure?.let { " (failed: $it)" } ?: ""} |")
            }
        }
    }
}
