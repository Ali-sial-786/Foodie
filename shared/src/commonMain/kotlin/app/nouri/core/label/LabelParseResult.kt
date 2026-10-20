package app.nouri.core.label

import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.HouseholdMeasureParser
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit

/**
 * Nutrition label conventions the parser understands. The region decides how values are read:
 * US and Canadian panels are per serving and print energy as bare "Calories"; EU/UK tables are
 * per 100 g (optionally plus a serving column), print energy in kJ and kcal, declare salt rather
 * than sodium and exclude fibre from "Carbohydrate".
 */
enum class LabelRegion { US, CANADA, EU, UNKNOWN }

/**
 * The serving a label's values refer to, as printed ("2/3 cup (55g)", "1 cup (250 mL)").
 *
 * [grams] and [millilitres] are only what the label states (ounces are converted, nothing else
 * is assumed). [householdText] is the household part ("2/3 cup", "1 bar"), or null when the
 * serving is given only as a mass or volume.
 */
data class ServingSize(
    val text: String,
    val grams: Double?,
    val millilitres: Double?,
    val householdText: String?,
)

/**
 * Names used in [LabelParseResult.missingFields] and in the barcode module's missing-nutrient
 * lists. Nutrient entries match the [Nutrients] property names.
 */
object NutrientFields {
    const val ENERGY_KCAL = "energyKcal"
    const val PROTEIN_G = "proteinG"
    const val CARBS_G = "carbsG"
    const val FAT_G = "fatG"
    const val FIBER_G = "fiberG"
    const val SUGAR_G = "sugarG"
    const val SODIUM_MG = "sodiumMg"
    const val SATURATED_FAT_G = "saturatedFatG"
    const val SERVING_SIZE = "servingSize"
    const val SERVING_GRAMS = "servingGrams"

    /** Energy and the three macronutrients: without all four a record is incomplete. */
    val CORE: List<String> = listOf(ENERGY_KCAL, PROTEIN_G, CARBS_G, FAT_G)

    /** Every nutrient a label or product record can provide, in display order. */
    val ALL_NUTRIENTS: List<String> =
        CORE + listOf(FIBER_G, SUGAR_G, SODIUM_MG, SATURATED_FAT_G)
}

/**
 * What [NutritionLabelParser] could read from one label.
 *
 * [perServing] and [per100g] hold only what the label prints for that basis; per-100 g values are
 * derived from a serving only in [toFoodReference]. [Nutrients] cannot express an unknown core
 * value, so a core field that could not be read is 0.0 there and is always listed in
 * [missingFields] (relative to the primary basis: per 100 g when printed, otherwise per serving).
 * Callers must check [missingFields] before trusting a zero.
 *
 * Carbohydrate is total carbohydrate, as everywhere in the app: for EU/UK labels, which print
 * fibre separately from "Carbohydrate", fibre is added back in. Sodium comes from the sodium row,
 * or from salt × 400 when only salt is declared.
 *
 * [warnings] lists every assumption and every failed plausibility check in plain language;
 * any warning caps [confidence] at [ConfidenceLevel.MODERATE].
 */
data class LabelParseResult(
    val region: LabelRegion,
    val servingSize: ServingSize?,
    val servingsPerContainer: Double?,
    val perServing: Nutrients?,
    val per100g: Nutrients?,
    val productName: String?,
    val confidence: ConfidenceLevel,
    val missingFields: List<String>,
    val warnings: List<String>,
) {
    /**
     * Converts the label into a per-100 g [FoodReference], or null when that is impossible
     * without inventing data:
     * - energy could not be read (a record without energy would silently log as 0 kcal);
     * - only per-serving values exist and the serving weight is unknown — in particular a serving
     *   given only in millilitres is never converted with an assumed density
     *   (the parse result already carries a warning explaining this).
     *
     * Quality is [DataQuality.HIGH] when energy, protein, carbohydrate and fat were all read,
     * otherwise [DataQuality.MEDIUM]. When the serving weight is known, a SERVING portion (and a
     * household portion such as "2/3 cup", when recognisable) is included.
     */
    fun toFoodReference(id: String, name: String): FoodReference? {
        if (NutrientFields.ENERGY_KCAL in missingFields) return null
        val serving = servingSize
        val grams = serving?.grams?.takeIf { it > 0.0 }
        val base = per100g ?: perServing?.let { values -> grams?.let { values * (100.0 / it) } } ?: return null
        val complete = NutrientFields.CORE.none { it in missingFields }
        val portions = buildList {
            if (serving != null && grams != null) {
                add(FoodPortion(QuantityUnit.SERVING, grams, label = serving.text))
                // Household volume measures are stored as US measures; Canadian/EU cups and spoons are metric,
                // so only US labels contribute them (the gram serving is always kept).
                householdPortion(serving.householdText, grams)
                    ?.takeIf { region == LabelRegion.US || it.unit.kind != app.nouri.core.units.UnitKind.VOLUME }
                    ?.let(::add)
            }
        }
        val density = serving?.millilitres?.takeIf { it > 0.0 }?.let { ml -> grams?.div(ml) }
            ?.takeIf { it in 0.2..3.0 }
        return FoodReference(
            id = id,
            name = name,
            per100g = base.clampNonNegative(),
            source = NutritionSource.MANUFACTURER_LABEL,
            quality = if (complete) DataQuality.HIGH else DataQuality.MEDIUM,
            densityGPerMl = density,
            portions = portions,
            servingGrams = grams,
            sourceDetail = "Nutrition label (${regionLabel(region)})",
        )
    }

    private companion object {
        private val householdAmount = Regex("""^\s*(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?|[½⅓⅔¼¾])\s*(\S.*)$""")

        fun regionLabel(region: LabelRegion): String = when (region) {
            LabelRegion.US -> "US format"
            LabelRegion.CANADA -> "Canadian format"
            LabelRegion.EU -> "EU/UK format"
            LabelRegion.UNKNOWN -> "format not recognised"
        }

        /** "2/3 cup" + 55 g → 1 cup = 82.5 g; mass, volume-unit and "serving" measures add nothing. */
        fun householdPortion(text: String?, servingGrams: Double): FoodPortion? {
            val match = text?.let { householdAmount.find(it) } ?: return null
            val amount = parseCount(match.groupValues[1]) ?: return null
            val portion = HouseholdMeasureParser.parse("$amount ${match.groupValues[2]}", servingGrams) ?: return null
            if (portion.unit == QuantityUnit.SERVING || portion.unit == QuantityUnit.MILLILITRE || portion.unit == QuantityUnit.LITRE) {
                return null
            }
            return portion.copy(label = text.trim())
        }

        fun parseCount(text: String): Double? {
            val t = text.trim()
            when (t) {
                "½" -> return 0.5
                "⅓" -> return 1.0 / 3.0
                "⅔" -> return 2.0 / 3.0
                "¼" -> return 0.25
                "¾" -> return 0.75
            }
            val parts = t.split(Regex("\\s+"))
            var total = 0.0
            for (part in parts) {
                total += if ('/' in part) {
                    val (n, d) = part.split('/').map { it.toDoubleOrNull() ?: return null }
                    if (d == 0.0) return null
                    n / d
                } else {
                    part.replace(',', '.').toDoubleOrNull() ?: return null
                }
            }
            return total.takeIf { it > 0.0 }
        }
    }
}
