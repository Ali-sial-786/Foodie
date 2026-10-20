package app.nouri.core.units

import kotlinx.serialization.Serializable

@Serializable
enum class UnitKind { MASS, VOLUME, COUNT }

/**
 * Cup/spoon conventions differ by region. US customary cups are 236.6 mL; metric
 * cups (Canada, UK, Australia, most of South Asia's modern recipes) are 250 mL.
 * Nutrition labels in the US use 240 mL per cup for labelling purposes.
 */
@Serializable
enum class MeasurementSystem { US_CUSTOMARY, METRIC }

@Serializable
enum class PortionSize { SMALL, MEDIUM, LARGE }

/**
 * Units a user, recipe, label or AI model might express a quantity in.
 * [massGrams] and [volumeMl] give fixed conversion factors where they exist.
 */
@Serializable
enum class QuantityUnit(
    val kind: UnitKind,
    val symbol: String,
    val massGrams: Double? = null,
    private val volumeMlUs: Double? = null,
    private val volumeMlMetric: Double? = null,
) {
    GRAM(UnitKind.MASS, "g", massGrams = 1.0),
    KILOGRAM(UnitKind.MASS, "kg", massGrams = 1000.0),
    MILLIGRAM(UnitKind.MASS, "mg", massGrams = 0.001),
    OUNCE(UnitKind.MASS, "oz", massGrams = 28.349523125),
    POUND(UnitKind.MASS, "lb", massGrams = 453.59237),

    MILLILITRE(UnitKind.VOLUME, "ml", volumeMlUs = 1.0, volumeMlMetric = 1.0),
    LITRE(UnitKind.VOLUME, "l", volumeMlUs = 1000.0, volumeMlMetric = 1000.0),
    TEASPOON(UnitKind.VOLUME, "tsp", volumeMlUs = 4.92892159375, volumeMlMetric = 5.0),
    TABLESPOON(UnitKind.VOLUME, "tbsp", volumeMlUs = 14.78676478125, volumeMlMetric = 15.0),
    CUP(UnitKind.VOLUME, "cup", volumeMlUs = 236.5882365, volumeMlMetric = 250.0),
    FLUID_OUNCE(UnitKind.VOLUME, "fl oz", volumeMlUs = 29.5735295625, volumeMlMetric = 29.5735295625),
    PINCH(UnitKind.VOLUME, "pinch", volumeMlUs = 0.31, volumeMlMetric = 0.31),

    PIECE(UnitKind.COUNT, "piece"),
    SLICE(UnitKind.COUNT, "slice"),
    CLOVE(UnitKind.COUNT, "clove"),
    SERVING(UnitKind.COUNT, "serving"),
    HANDFUL(UnitKind.COUNT, "handful"),
    BOWL(UnitKind.COUNT, "bowl"),
    PLATE(UnitKind.COUNT, "plate"),
    CAN(UnitKind.COUNT, "can"),
    PACKAGE(UnitKind.COUNT, "package"),
    BUNCH(UnitKind.COUNT, "bunch"),
    STICK(UnitKind.COUNT, "stick"),
    SCOOP(UnitKind.COUNT, "scoop"),
    ;

    fun volumeMl(system: MeasurementSystem): Double? =
        if (system == MeasurementSystem.US_CUSTOMARY) volumeMlUs else volumeMlMetric

    companion object {
        private val aliasMap: Map<String, QuantityUnit> = buildMap {
            fun add(unit: QuantityUnit, vararg names: String) = names.forEach { put(it, unit) }
            add(GRAM, "g", "gr", "gm", "gms", "gram", "grams", "gramme", "grammes")
            add(KILOGRAM, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
            add(MILLIGRAM, "mg", "milligram", "milligrams")
            add(OUNCE, "oz", "ounce", "ounces")
            add(POUND, "lb", "lbs", "pound", "pounds")
            add(MILLILITRE, "ml", "mls", "millilitre", "millilitres", "milliliter", "milliliters", "cc")
            add(LITRE, "l", "litre", "litres", "liter", "liters", "ltr")
            add(TEASPOON, "tsp", "tsps", "teaspoon", "teaspoons", "t", "chammach", "chamach")
            add(TABLESPOON, "tbsp", "tbsps", "tbs", "tbl", "tablespoon", "tablespoons", "T")
            add(CUP, "cup", "cups", "c", "katori")
            add(FLUID_OUNCE, "fl oz", "floz", "fluid ounce", "fluid ounces")
            add(PINCH, "pinch", "pinches", "dash", "dashes", "chutki")
            add(PIECE, "piece", "pieces", "pc", "pcs", "whole", "unit", "units", "item", "items", "nos", "no")
            add(SLICE, "slice", "slices")
            add(CLOVE, "clove", "cloves", "pod", "pods")
            add(SERVING, "serving", "servings", "portion", "portions", "serve", "serves")
            add(HANDFUL, "handful", "handfuls", "mutthi")
            add(BOWL, "bowl", "bowls")
            add(PLATE, "plate", "plates", "plateful")
            add(CAN, "can", "cans", "tin", "tins")
            add(PACKAGE, "package", "packages", "pack", "packs", "packet", "packets", "bag", "bags", "box", "boxes")
            add(BUNCH, "bunch", "bunches", "bundle")
            add(STICK, "stick", "sticks")
            add(SCOOP, "scoop", "scoops")
        }

        /** Parses a unit token. Case-sensitive only for "T" (tablespoon) vs "t" (teaspoon). */
        fun parse(token: String): QuantityUnit? {
            val trimmed = token.trim().trimEnd('.')
            if (trimmed == "T" || trimmed == "Tbsp" || trimmed == "TBSP") return TABLESPOON
            return aliasMap[trimmed.lowercase()]
        }
    }
}

/** A quantity exactly as entered, kept alongside its normalised gram value. */
@Serializable
data class Quantity(val amount: Double, val unit: QuantityUnit, val size: PortionSize? = null) {
    init {
        require(amount.isFinite() && amount >= 0) { "Quantity amount must be a non-negative number" }
    }

    fun scaled(factor: Double) = copy(amount = amount * factor)

    override fun toString(): String = "${formatAmount(amount)} ${unit.symbol}"

    companion object {
        fun grams(amount: Double) = Quantity(amount, QuantityUnit.GRAM)
    }
}

/** Food-specific household measure, e.g. "1 medium onion" = 110 g. */
@Serializable
data class FoodPortion(
    val unit: QuantityUnit,
    val grams: Double,
    val size: PortionSize? = null,
    val label: String = "",
) {
    init {
        require(grams > 0) { "Portion weight must be positive" }
    }
}

/** How a gram value was obtained; drives uncertainty and UI explanations. */
@Serializable
enum class ConversionMethod {
    /** The user/recipe gave a mass directly. */
    DIRECT_MASS,

    /** Volume converted with a density known for this food. */
    FOOD_DENSITY,

    /** Count converted with a household measure known for this food. */
    FOOD_PORTION,

    /** Volume converted assuming the density of water. */
    ASSUMED_WATER_DENSITY,

    /** A generic household measure unrelated to this specific food. */
    GENERIC_PORTION,
}

@Serializable
data class GramConversion(
    val grams: Double,
    val method: ConversionMethod,
    /** Plain-language assumption shown to the user when not exact. */
    val assumption: String? = null,
) {
    val isExact: Boolean get() = method == ConversionMethod.DIRECT_MASS
}

/** Everything known about a food that helps convert household units. */
data class PortionContext(
    val densityGPerMl: Double? = null,
    val portions: List<FoodPortion> = emptyList(),
    /** Grams in one labelled serving, when known. */
    val servingGrams: Double? = null,
    val foodName: String = "food",
)

sealed interface ConversionOutcome {
    data class Success(val conversion: GramConversion) : ConversionOutcome
    data class Failure(val reason: String) : ConversionOutcome
}

/**
 * Converts household quantities to grams. Pure and deterministic; never guesses
 * silently — every non-exact conversion carries an [GramConversion.assumption].
 */
object UnitConverter {

    /** Generic last-resort weights for count units without food-specific data. */
    private val genericCountWeights: Map<QuantityUnit, Pair<Double, String>> = mapOf(
        QuantityUnit.HANDFUL to (30.0 to "a handful is assumed to be about 30 g"),
        QuantityUnit.BOWL to (300.0 to "a bowl is assumed to hold about 300 g"),
        QuantityUnit.PLATE to (400.0 to "a plate is assumed to hold about 400 g"),
        QuantityUnit.SCOOP to (30.0 to "a scoop is assumed to be about 30 g"),
    )

    fun toGrams(
        quantity: Quantity,
        context: PortionContext = PortionContext(),
        system: MeasurementSystem = MeasurementSystem.METRIC,
    ): ConversionOutcome {
        val unit = quantity.unit
        val amount = quantity.amount
        return when (unit.kind) {
            UnitKind.MASS -> ConversionOutcome.Success(
                GramConversion(amount * unit.massGrams!!, ConversionMethod.DIRECT_MASS),
            )

            UnitKind.VOLUME -> volumeToGrams(amount, unit, context, system)
            UnitKind.COUNT -> countToGrams(quantity, context)
        }
    }

    private fun volumeToGrams(
        amount: Double,
        unit: QuantityUnit,
        context: PortionContext,
        system: MeasurementSystem,
    ): ConversionOutcome {
        // A food-specific household volume (e.g. "1 cup, chopped" = 160 g) beats density.
        // Reference portions are recorded in US customary measures.
        context.portions.firstOrNull { it.unit == unit && it.size == null }?.let { portion ->
            val factor = unit.volumeMl(system)!! / unit.volumeMl(MeasurementSystem.US_CUSTOMARY)!!
            return ConversionOutcome.Success(
                GramConversion(
                    amount * portion.grams * factor,
                    ConversionMethod.FOOD_PORTION,
                    assumption = portion.label.takeIf { it.isNotBlank() }?.let { "1 ${unit.symbol} of ${context.foodName} ≈ ${formatAmount(portion.grams * factor)} g ($it)" },
                ),
            )
        }
        val ml = amount * unit.volumeMl(system)!!
        val density = context.densityGPerMl
        return if (density != null) {
            ConversionOutcome.Success(GramConversion(ml * density, ConversionMethod.FOOD_DENSITY))
        } else {
            ConversionOutcome.Success(
                GramConversion(
                    ml,
                    ConversionMethod.ASSUMED_WATER_DENSITY,
                    assumption = "${context.foodName} measured by volume; assumed to weigh about the same as water",
                ),
            )
        }
    }

    private fun countToGrams(quantity: Quantity, context: PortionContext): ConversionOutcome {
        val unit = quantity.unit
        val amount = quantity.amount
        if (unit == QuantityUnit.SERVING) {
            context.servingGrams?.let {
                return ConversionOutcome.Success(GramConversion(amount * it, ConversionMethod.FOOD_PORTION))
            }
        }
        val candidates = context.portions.filter { it.unit == unit }
        val portion = candidates.firstOrNull { it.size == quantity.size }
            ?: candidates.firstOrNull { it.size == PortionSize.MEDIUM }
            ?: candidates.firstOrNull { it.size == null }
            ?: candidates.firstOrNull()
            // A "piece" of something with a "serving" measure is a reasonable fallback the other way round.
            ?: if (unit == QuantityUnit.SERVING) context.portions.firstOrNull { it.unit == QuantityUnit.PIECE } else null
        if (portion != null) {
            val assumption = when {
                quantity.size != null && portion.size != quantity.size ->
                    "no ${quantity.size.name.lowercase()} size on record; used ${portion.label.ifBlank { "standard" }} (${formatAmount(portion.grams)} g)"
                portion.label.isNotBlank() -> "1 ${unit.symbol} = ${portion.label} (${formatAmount(portion.grams)} g)"
                else -> null
            }
            return ConversionOutcome.Success(
                GramConversion(amount * portion.grams, ConversionMethod.FOOD_PORTION, assumption),
            )
        }
        genericCountWeights[unit]?.let { (grams, text) ->
            return ConversionOutcome.Success(GramConversion(amount * grams, ConversionMethod.GENERIC_PORTION, text))
        }
        return ConversionOutcome.Failure("No weight is known for 1 ${unit.symbol} of ${context.foodName}; enter grams instead")
    }

    fun gramsToOunces(grams: Double): Double = grams / QuantityUnit.OUNCE.massGrams!!
    fun ouncesToGrams(oz: Double): Double = oz * QuantityUnit.OUNCE.massGrams!!
    fun kgToLb(kg: Double): Double = kg * 1000.0 / QuantityUnit.POUND.massGrams!!
    fun lbToKg(lb: Double): Double = lb * QuantityUnit.POUND.massGrams!! / 1000.0
}

/** Formats amounts compactly: 2, 2.5, 0.33, 1/2-friendly decimals. */
fun formatAmount(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = when {
        value >= 100 -> kotlin.math.round(value)
        value >= 10 -> kotlin.math.round(value * 10) / 10
        else -> kotlin.math.round(value * 100) / 100
    }
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
}
