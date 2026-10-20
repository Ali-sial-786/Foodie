package app.nouri.core.scan

import app.nouri.core.ai.ClarifyingQuestion
import app.nouri.core.ai.DetectedFoodItem
import app.nouri.core.ai.Likelihood
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.PortionHints
import app.nouri.core.dishes.DishCatalog
import app.nouri.core.fooddb.CoreFoods
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.nutrition.NutritionEngine
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.units.ConversionOutcome
import app.nouri.core.units.Quantity
import app.nouri.core.units.UnitConverter
import app.nouri.core.units.formatAmount
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EstimationContext(
    val scanId: String,
    val hints: PortionHints = PortionHints(),
    val imageCount: Int = 1,
    val simulated: Boolean = false,
    val memory: CorrectionMemory = CorrectionMemory(),
)

/**
 * Turns a provider's identification into an editable, deterministic estimate.
 * The provider never supplies calories: grams × reference nutrients does.
 */
class MealEstimator(
    private val matcher: FoodMatcher,
    private val foods: FoodRepository = CoreFoods.repository,
    private val engine: NutritionEngine = NutritionEngine(),
    private val newId: () -> String,
) {

    fun estimate(analysis: MealAnalysis, context: EstimationContext): MealScanResult {
        var items = analysis.items.map { buildItem(it, analysis, context) }
        context.hints.totalWeightGrams?.takeIf { it > 0 }?.let { total -> items = distributeWeighedTotal(items, total) }
        val question = analysis.suggestedQuestion?.takeIf { it.options.size >= 2 } ?: deriveQuestion(items)
        return assemble(context.scanId, items, question, context.simulated, analysis.imageQuality, analysis.unidentified, emptyList())
    }

    /** Applies one correction and recalculates everything deterministically. */
    fun apply(result: MealScanResult, edit: ScanEdit): MealScanResult {
        if (edit is ScanEdit.Answer) return answer(result, edit.option)
        if (edit is ScanEdit.AddItem) {
            val portion = PortionEstimate.exact(edit.grams, PortionSource.USER_ADJUSTED, edit.quantity)
            val item = compute(
                ScanItem(
                    id = newId(), detected = null, match = edit.match, alternatives = emptyList(), portion = portion,
                    preparation = Preparation(), nutrients = Nutrients.ZERO, energyRange = EstimateRange.exact(0.0),
                    confidence = ItemConfidence(ConfidenceLevel.HIGHER, ConfidenceLevel.MODERATE, ConfidenceLevel.MODERATE),
                    userEdited = true,
                ),
            )
            return reassemble(result, result.items + item)
        }
        val items = result.items.mapNotNull { item ->
            if (item.id != edit.itemId) return@mapNotNull item
            when (edit) {
                is ScanEdit.Remove -> null
                is ScanEdit.SetIncluded -> item.copy(included = edit.included)
                is ScanEdit.ChangeFood -> compute(
                    item.copy(
                        match = edit.match.copy(method = if (edit.match.method == MatchMethod.UNMATCHED) MatchMethod.USER_SELECTED else edit.match.method),
                        alternatives = (listOfNotNull(item.match) + item.alternatives).filter { it.food.id != edit.match.food.id }.distinctBy { it.food.id },
                        portion = keepPortionOrTypical(item, edit.match),
                        userEdited = true,
                    ),
                )
                is ScanEdit.SetGrams -> compute(
                    item.copy(
                        portion = PortionEstimate.exact(edit.grams.coerceAtLeast(0.0), if (edit.weighed) PortionSource.USER_WEIGHED else PortionSource.USER_ADJUSTED),
                        userEdited = true,
                    ),
                )
                is ScanEdit.ScalePortion -> {
                    val f = edit.factor.coerceIn(0.0, 10.0)
                    val p = item.portion
                    compute(item.copy(portion = PortionEstimate(p.grams * f, p.lowGrams * f, p.highGrams * f, PortionSource.USER_ADJUSTED, p.quantity?.scaled(f), p.householdText), userEdited = true))
                }
                is ScanEdit.SetQuantity -> {
                    val grams = gramsFor(item.match, edit.quantity) ?: return@mapNotNull item.copy(notes = item.notes + "Couldn't convert ${edit.quantity}; enter grams instead")
                    val source = if (edit.quantity.unit.kind == app.nouri.core.units.UnitKind.MASS) PortionSource.USER_WEIGHED else PortionSource.USER_ADJUSTED
                    compute(item.copy(portion = PortionEstimate.exact(grams, source, edit.quantity), userEdited = true))
                }
                is ScanEdit.SetPreparation -> compute(item.copy(preparation = item.preparation.copy(context = edit.context), userEdited = true))
                is ScanEdit.AddFat -> compute(item.copy(preparation = item.preparation.copy(addedFats = item.preparation.addedFats + edit.fat), userEdited = true))
                is ScanEdit.ClearAddedFats -> compute(item.copy(preparation = item.preparation.copy(addedFats = emptyList()), userEdited = true))
                is ScanEdit.AddItem, is ScanEdit.Answer -> item
            }
        }
        return reassemble(result, items)
    }

    // ------------------------------------------------------------------ Stage 2/3

    private fun buildItem(det: DetectedFoodItem, analysis: MealAnalysis, ctx: EstimationContext): ScanItem {
        val ranked = (det.candidates.map { it.name to it.likelihood } + (det.label to Likelihood.MEDIUM))
            .sortedBy { it.second.ordinal }
        val candidates = matcher.candidates(ranked)
        val remembered = ctx.memory.renameFor(det.label)?.let { r ->
            matcher.byReferenceId(r.foodRefId, MatchMethod.USER_CORRECTION_MEMORY)?.copy(label = r.label)
                ?: matcher.match(r.label)?.copy(method = MatchMethod.USER_CORRECTION_MEMORY)
        }
        val primary = remembered ?: candidates.firstOrNull()?.first
        val alternatives = buildList {
            addAll(candidates.map { it.first })
            primary?.dishId?.let { addAll(matcher.lookalikes(it)) }
        }.distinctBy { it.food.id }.filter { it.food.id != primary?.food?.id }.take(5)

        val notes = buildList {
            if (remembered != null) add("Using ${remembered.label}, as you corrected before")
            if (det.partiallyHidden) add("Partly hidden in the photo")
            if (primary == null) add("Not matched to nutrition data — choose the food")
            if (det.visibleFat == "heavy") add("Looks oily; the estimate allows for extra fat")
        }
        val base = ScanItem(
            id = newId(),
            detected = det,
            match = primary,
            alternatives = alternatives,
            portion = estimatePortion(det, primary, analysis, ctx),
            preparation = Preparation(
                context = when (ctx.hints.homemade) { true -> PrepContext.HOMEMADE; false -> PrepContext.RESTAURANT; null -> PrepContext.UNKNOWN },
                cookingMethod = det.cookingMethod,
            ),
            nutrients = Nutrients.ZERO,
            energyRange = EstimateRange.exact(0.0),
            confidence = ItemConfidence(ConfidenceLevel.LOW, ConfidenceLevel.LOW, ConfidenceLevel.LOW),
            notes = notes,
            originalMatchId = candidates.firstOrNull()?.first?.food?.id,
        )
        return compute(base)
    }

    private fun estimatePortion(det: DetectedFoodItem, match: FoodMatch?, analysis: MealAnalysis, ctx: EstimationContext): PortionEstimate {
        val learned = match?.let { ctx.memory.usualPortion(it.food.id) }
        det.estimatedGrams?.takeIf { it.isValid() }?.let { ai ->
            val source = when {
                ctx.imageCount > 1 -> PortionSource.MULTI_ANGLE
                ctx.hints.referenceObject != null || ctx.hints.plateDiameterCm != null || ctx.hints.bowlVolumeMl != null || analysis.referenceObjects.isNotEmpty() -> PortionSource.REFERENCE_OBJECT
                else -> PortionSource.AI_ESTIMATE
            }
            val central = if (learned != null) (0.7 * ai.typical + 0.3 * learned.meanGrams).coerceIn(ai.low, ai.high) else ai.typical
            return PortionEstimate(central, ai.low, ai.high, source, householdText = det.householdEstimate, basis = det.portionBasis)
        }
        if (learned != null) {
            val g = learned.meanGrams
            return PortionEstimate(g, g * 0.8, g * 1.2, PortionSource.LEARNED_PREFERENCE)
        }
        val dish = match?.dishId?.let { DishCatalog[it] }
        if (dish != null) return PortionEstimate(dish.servingGrams, dish.servingLowGrams, dish.servingHighGrams, PortionSource.TYPICAL_SERVING, basis = "Typical serving")
        val typical = match?.food?.servingGrams ?: 100.0
        return PortionEstimate(typical, typical * 0.5, typical * 1.5, PortionSource.TYPICAL_SERVING, basis = "Typical serving")
    }

    private fun keepPortionOrTypical(item: ScanItem, newMatch: FoodMatch): PortionEstimate =
        if (item.detected?.estimatedGrams != null || item.portion.source != PortionSource.TYPICAL_SERVING) item.portion
        else {
            val dish = newMatch.dishId?.let { DishCatalog[it] }
            if (dish != null) PortionEstimate(dish.servingGrams, dish.servingLowGrams, dish.servingHighGrams, PortionSource.TYPICAL_SERVING)
            else item.portion
        }

    private fun gramsFor(match: FoodMatch?, quantity: Quantity): Double? {
        val food = match?.food
        val ctx = food?.portionContext()?.let { base ->
            val ing = match.ingredientId?.let(IngredientCatalog::get)
            if (ing == null) base else base.copy(densityGPerMl = ing.densityGPerMl ?: base.densityGPerMl, portions = ing.portions + base.portions)
        } ?: app.nouri.core.units.PortionContext()
        return (UnitConverter.toGrams(quantity, ctx) as? ConversionOutcome.Success)?.conversion?.grams
    }

    private fun distributeWeighedTotal(items: List<ScanItem>, totalGrams: Double): List<ScanItem> {
        val sum = items.sumOf { it.portion.grams }
        if (sum <= 0) return items
        return items.map { item ->
            val g = item.portion.grams / sum * totalGrams
            compute(item.copy(portion = PortionEstimate(g, g * 0.9, g * 1.1, PortionSource.USER_WEIGHED, basis = "Shared out from the weighed total by the photo's proportions")))
        }
    }

    // ------------------------------------------------------------------ Stage 4/5

    private val oil by lazy { foods.get("usda:04582") }

    /** Recomputes nutrients, range and confidence for an item from its current fields. */
    fun compute(item: ScanItem): ScanItem {
        val match = item.match
            ?: return item.copy(
                nutrients = Nutrients.ZERO,
                energyRange = EstimateRange.exact(0.0),
                confidence = ItemConfidence(ConfidenceLevel.LOW, portionConfidence(item), ConfidenceLevel.LOW),
            )
        val p = item.portion
        val per100 = match.food.per100g
        val base = match.food.nutrientsFor(p.grams)
        val notes = item.notes.filterNot { it.startsWith(RESTAURANT_NOTE) }.toMutableList()

        val restaurantExtra = if (item.preparation.context == PrepContext.RESTAURANT && match.restaurantEnergyFactor > 1.0 && oil != null) {
            val oilGrams = base.energyKcal * (match.restaurantEnergyFactor - 1.0) / (oil!!.per100g.energyKcal / 100.0)
            notes += "$RESTAURANT_NOTE: about ${formatAmount(oilGrams)} g extra cooking fat"
            oil!!.nutrientsFor(oilGrams)
        } else Nutrients.ZERO
        val fats = Nutrients.sum(
            item.preparation.addedFats.mapNotNull { fat ->
                engine.calculateLine(RecipeIngredient(displayName = fat.label, ingredientId = fat.ingredientId, quantity = Quantity.grams(fat.grams))).nutrients
            },
        )
        val total = base + restaurantExtra + fats

        val contextKnown = item.preparation.context != PrepContext.UNKNOWN
        val variability = match.energyVariability * if (contextKnown) 0.7 else 1.0
        val heavyFat = if (item.detected?.visibleFat == "heavy" && item.preparation.addedFats.isEmpty()) 0.15 else 0.0
        val kcalPerGram = per100.energyKcal / 100.0
        val extrasKcal = restaurantExtra.energyKcal + fats.energyKcal
        val low = (p.lowGrams * kcalPerGram * (1 - variability)) + extrasKcal * 0.8
        val high = (p.highGrams * kcalPerGram * (1 + variability + heavyFat)) + extrasKcal * 1.2
        val central = total.energyKcal
        val range = EstimateRange(minOf(low, central), central, maxOf(high, central))

        return item.copy(
            nutrients = total,
            energyRange = range,
            confidence = ItemConfidence(identificationConfidence(item, match), portionConfidence(item), dataConfidence(match)),
            notes = notes.distinct(),
        )
    }

    private fun identificationConfidence(item: ScanItem, match: FoodMatch): ConfidenceLevel {
        if (match.method in setOf(MatchMethod.USER_SELECTED, MatchMethod.USER_CORRECTION_MEMORY, MatchMethod.SAVED_FOOD)) return ConfidenceLevel.HIGHER
        if (item.detected == null) return ConfidenceLevel.HIGHER
        if (match.method == MatchMethod.DATABASE_SEARCH) return ConfidenceLevel.LOW
        val c = item.detected.candidates
        val top = c.firstOrNull()?.likelihood ?: Likelihood.MEDIUM
        val competing = c.drop(1).any { it.likelihood != Likelihood.LOW }
        return when {
            top == Likelihood.HIGH && !competing -> ConfidenceLevel.HIGHER
            top == Likelihood.LOW -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.MODERATE
        }
    }

    private fun portionConfidence(item: ScanItem): ConfidenceLevel {
        val p = item.portion
        val base = when (p.source) {
            PortionSource.USER_WEIGHED, PortionSource.LABEL_SERVING -> ConfidenceLevel.HIGHER
            PortionSource.USER_ADJUSTED, PortionSource.MULTI_ANGLE, PortionSource.REFERENCE_OBJECT, PortionSource.LEARNED_PREFERENCE -> ConfidenceLevel.MODERATE
            PortionSource.AI_ESTIMATE -> if (p.grams > 0 && (p.highGrams - p.lowGrams) / 2 / p.grams <= 0.2) ConfidenceLevel.MODERATE else ConfidenceLevel.LOW
            PortionSource.TYPICAL_SERVING -> ConfidenceLevel.LOW
        }
        return if (item.detected?.partiallyHidden == true && base == ConfidenceLevel.HIGHER) ConfidenceLevel.MODERATE
        else if (item.detected?.partiallyHidden == true && base == ConfidenceLevel.MODERATE && !item.userEdited) ConfidenceLevel.LOW
        else base
    }

    private fun dataConfidence(match: FoodMatch): ConfidenceLevel = when {
        match.food.quality == DataQuality.APPROXIMATE -> ConfidenceLevel.LOW
        match.food.source in setOf(NutritionSource.MANUFACTURER_LABEL, NutritionSource.USER_ENTERED, NutritionSource.SAVED_RECIPE, NutritionSource.VERIFIED_DATABASE) -> ConfidenceLevel.HIGHER
        match.food.source in setOf(NutritionSource.REFERENCE_RECIPE, NutritionSource.BRANDED_DATABASE) -> ConfidenceLevel.MODERATE
        else -> ConfidenceLevel.LOW
    }

    // ------------------------------------------------------------------ assembly & questions

    private fun reassemble(prev: MealScanResult, items: List<ScanItem>) =
        assemble(prev.scanId, items, prev.question, prev.simulated, prev.imageQuality, prev.unidentified, prev.warnings)

    private fun assemble(
        scanId: String,
        items: List<ScanItem>,
        question: ClarifyingQuestion?,
        simulated: Boolean,
        imageQuality: String,
        unidentified: List<String>,
        warnings: List<String>,
    ): MealScanResult {
        val included = items.filter { it.included && it.match != null }
        val total = Nutrients.sum(included.map { it.nutrients })
        val central = total.energyKcal
        // Independent item uncertainties combine as root-sum-square, not a straight sum.
        val down = sqrt(included.sumOf { (it.energyRange.central - it.energyRange.low).let { d -> d * d } })
        val up = sqrt(included.sumOf { (it.energyRange.high - it.energyRange.central).let { d -> d * d } })
        val range = EstimateRange((central - down).coerceAtLeast(0.0), central, central + up)
        val overall = if (included.isEmpty()) ConfidenceLevel.LOW else ConfidenceLevel.min(*included.map { it.confidence.overall }.toTypedArray())
        return MealScanResult(
            scanId = scanId,
            items = items,
            question = question,
            total = total,
            totalEnergyRange = range,
            overallConfidence = overall,
            explanation = explain(included, range),
            simulated = simulated,
            imageQuality = imageQuality,
            warnings = warnings + buildList {
                if (items.any { it.included && it.match == null }) add("Some foods aren't matched yet and aren't counted")
                if (imageQuality in setOf("blurry", "dark", "partial")) add("Photo quality is $imageQuality; the estimate is less reliable")
            },
            unidentified = unidentified,
        )
    }

    private fun explain(items: List<ScanItem>, range: EstimateRange): String {
        if (items.isEmpty()) return "Add or match a food to see an estimate."
        if (items.all { it.confidence.overall == ConfidenceLevel.HIGHER }) {
            return "About ${range.central.roundToInt()} kcal, based on your portions and reference data."
        }
        val head = "Estimated ${range.format()}."
        val totalWidth = items.sumOf { it.energyRange.width }.takeIf { it > 0 } ?: return head
        val drivers = items.sortedByDescending { it.energyRange.width }
            .filter { it.energyRange.width / totalWidth >= 0.2 }
            .take(2)
        if (drivers.isEmpty()) return head
        val phrases = drivers.map { item ->
            val name = item.displayName.lowercase()
            val p = item.portion
            val portionSpread = if (p.grams > 0) (p.highGrams - p.lowGrams) / 2 / p.grams else 0.0
            val prepSpread = item.match?.energyVariability ?: 0.0
            if (portionSpread >= prepSpread) "the amount of $name" else "how the $name was prepared (${(item.match?.variabilityNote ?: "oil, butter or cream").lowercase().substringBefore(';')})"
        }
        val verb = if (phrases.size == 1) "is" else "are"
        val noun = if (phrases.size == 1) "uncertainty" else "uncertainties"
        val subject = phrases.joinToString(" and ").replaceFirstChar { it.uppercase() }
        return "$head $subject $verb the main $noun."
    }

    private fun deriveQuestion(items: List<ScanItem>): ClarifyingQuestion? {
        items.forEachIndexed { index, item ->
            val det = item.detected ?: return@forEachIndexed
            val plausible = det.candidates.filter { it.likelihood != Likelihood.LOW }.take(2)
            if (plausible.size == 2) {
                val a = matcher.match(plausible[0].name)
                val b = matcher.match(plausible[1].name)
                if (a != null && b != null && a.food.id != b.food.id) {
                    val ea = a.food.per100g.energyKcal
                    val eb = b.food.per100g.energyKcal
                    if (maxOf(ea, eb) > 0 && kotlin.math.abs(ea - eb) / maxOf(ea, eb) > 0.15) {
                        return ClarifyingQuestion("Is this ${a.label.lowercase()} or ${b.label.lowercase()}?", listOf(a.label, b.label, NOT_SURE), index)
                    }
                }
            }
        }
        val mealEnergy = items.filter { it.included }.sumOf { it.nutrients.energyKcal }
        items.forEachIndexed { index, item ->
            val m = item.match ?: return@forEachIndexed
            if (m.restaurantEnergyFactor >= 1.2 && item.preparation.context == PrepContext.UNKNOWN && mealEnergy > 0 && item.nutrients.energyKcal / mealEnergy >= 0.4) {
                return ClarifyingQuestion("Was the ${m.label.lowercase()} homemade or from a restaurant?", listOf(HOMEMADE, RESTAURANT, NOT_SURE), index)
            }
        }
        return null
    }

    private fun answer(result: MealScanResult, option: String): MealScanResult {
        val q = result.question ?: return result
        val cleared = result.copy(question = null)
        if (option.equals(NOT_SURE, ignoreCase = true)) return cleared
        val target = q.itemIndex?.let { result.items.getOrNull(it) }
        val lower = option.lowercase()
        if ("restaurant" in lower || "takeaway" in lower || "homemade" in lower || "home-made" in lower) {
            val ctx = if ("homemade" in lower || "home-made" in lower) PrepContext.HOMEMADE else PrepContext.RESTAURANT
            val ids = target?.let { setOf(it.id) } ?: result.items.map { it.id }.toSet()
            return ids.fold(cleared) { acc, id -> apply(acc, ScanEdit.SetPreparation(id, ctx)) }
        }
        if (target != null) {
            val match = (target.alternatives + listOfNotNull(target.match)).firstOrNull { it.label.equals(option, ignoreCase = true) }
                ?: matcher.match(option)
            if (match != null) return apply(cleared, ScanEdit.ChangeFood(target.id, match.copy(method = MatchMethod.USER_SELECTED)))
            val noted = target.copy(notes = target.notes + "You said: $option")
            return reassemble(cleared, result.items.map { if (it.id == target.id) noted else it })
        }
        return cleared
    }

    companion object {
        const val NOT_SURE = "Not sure"
        const val HOMEMADE = "Homemade"
        const val RESTAURANT = "Restaurant"
        private const val RESTAURANT_NOTE = "Restaurant preparation"
    }
}
