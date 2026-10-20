package app.nouri.core.scan

import app.nouri.core.ai.IngredientAnalysis
import app.nouri.core.ai.IngredientCertainty
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.ontology.IngredientNormalizer
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import kotlinx.datetime.LocalDate

object ScanLogging {
    private val photoPortions = setOf(
        PortionSource.AI_ESTIMATE, PortionSource.MULTI_ANGLE, PortionSource.REFERENCE_OBJECT,
        PortionSource.TYPICAL_SERVING, PortionSource.LEARNED_PREFERENCE,
    )

    /** One diary entry per included, matched item, all linked by the scan id. */
    fun toDiaryEntries(
        result: MealScanResult,
        date: LocalDate,
        meal: MealType,
        nowEpochMs: Long,
        timeZoneId: String,
        newId: () -> String,
    ): List<DiaryEntry> = result.items.filter { it.included && it.match != null }.map { item ->
        val match = item.match!!
        val estimatedPortion = item.portion.source in photoPortions
        val provenance = Provenance(
            source = if (estimatedPortion) NutritionSource.AI_ASSISTED_ESTIMATE else match.food.source,
            referenceId = match.food.id,
            detail = buildString {
                append(item.portion.source.label)
                append(" (${formatAmount(item.portion.grams)} g); nutrition: ")
                append(match.food.sourceDetail ?: match.food.name)
                if (result.simulated) append(" — demo scan")
            },
            datasetVersion = match.food.datasetVersion,
        )
        val precision = when {
            item.portion.source == PortionSource.USER_WEIGHED && item.confidence.data == ConfidenceLevel.HIGHER -> Precision.MEASURED
            estimatedPortion -> Precision.ESTIMATED
            else -> Precision.CALCULATED
        }
        DiaryEntry(
            id = newId(),
            date = date,
            mealType = meal,
            loggedAtEpochMs = nowEpochMs,
            timeZoneId = timeZoneId,
            name = item.displayName,
            quantity = item.portion.quantity ?: Quantity.grams(item.portion.grams),
            foodRefId = match.food.id,
            snapshot = NutritionSnapshot(
                nutrients = item.nutrients,
                grams = item.portion.grams,
                provenance = provenance,
                confidence = item.confidence.overall,
                precision = precision,
                energyRange = item.energyRange.takeIf { precision == Precision.ESTIMATED },
                capturedAtEpochMs = nowEpochMs,
                notes = item.notes,
            ),
            origin = EntryOrigin.PHOTO_SCAN,
            scanId = result.scanId,
            idempotencyKey = "scan:${result.scanId}:${item.id}",
        )
    }
}

/** An editable ingredient chip from an ingredient photo. */
data class IngredientChip(
    val id: String,
    val name: String,
    val ingredientId: String?,
    val quantity: Quantity?,
    val certainty: IngredientCertainty,
    val packaged: Boolean,
    /** Uncertain ("possible") items start unselected; nothing is assumed present without confirmation. */
    val selected: Boolean,
    val note: String? = null,
)

object IngredientScanMapper {
    fun chips(analysis: IngredientAnalysis, normalizer: IngredientNormalizer, newId: () -> String): List<IngredientChip> =
        analysis.items.map { item ->
            val match = normalizer.resolve(item.name)
            val unit = item.unit?.let { QuantityUnit.parse(it) } ?: if (item.quantity != null) QuantityUnit.PIECE else null
            IngredientChip(
                id = newId(),
                name = match?.ingredient?.name ?: item.name.replaceFirstChar { it.uppercase() },
                ingredientId = match?.ingredient?.id,
                quantity = if (item.quantity != null && unit != null) Quantity(item.quantity, unit) else null,
                certainty = item.certainty,
                packaged = item.packaged,
                selected = item.certainty != IngredientCertainty.POSSIBLE,
                note = item.notes,
            )
        }.distinctBy { it.ingredientId ?: it.name.lowercase() }
}
