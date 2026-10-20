package app.nouri.core.fooddb

import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.PortionContext
import app.nouri.core.units.PortionSize
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.UnitKind
import kotlinx.serialization.Serializable

@Serializable
enum class FoodState { RAW, COOKED, AS_SOLD, PREPARED }

/**
 * A nutrition reference record: nutrients per 100 g of the *edible portion*, plus
 * everything needed to convert household measures. Identification ("this is
 * chicken biryani") and nutrition ("100 g of it contains…") are separate
 * responsibilities; this type is only the latter.
 */
@Serializable
data class FoodReference(
    /** Namespaced id: "usda:05064", "dish:chicken_biryani", "off:0737628064502", "user:…", "approx:paneer". */
    val id: String,
    val name: String,
    val per100g: Nutrients,
    val source: NutritionSource,
    val quality: DataQuality,
    val state: FoodState = FoodState.AS_SOLD,
    val densityGPerMl: Double? = null,
    val portions: List<FoodPortion> = emptyList(),
    /** Fraction of the as-purchased weight that is inedible (bone, peel, shell). 0.12 for eggs. */
    val refuseFraction: Double = 0.0,
    val servingGrams: Double? = null,
    val foodGroup: String? = null,
    val sourceDetail: String? = null,
    val datasetVersion: String? = null,
) {
    init {
        require(refuseFraction in 0.0..0.95) { "Refuse fraction out of range for $id" }
        require(per100g.isNonNegative()) { "Negative nutrients for $id" }
    }

    fun provenance(): Provenance = Provenance(
        source = source,
        referenceId = id,
        detail = sourceDetail ?: name,
        datasetVersion = datasetVersion,
    )

    fun portionContext(): PortionContext = PortionContext(
        densityGPerMl = densityGPerMl,
        portions = portions,
        servingGrams = servingGrams,
        foodName = name.lowercase(),
    )

    /** Nutrients for [grams] of the edible portion. */
    fun nutrientsFor(grams: Double): Nutrients = per100g * (grams / 100.0)
}

/**
 * Turns USDA household measure descriptions ("1 cup, chopped", ".5 fillet",
 * "1 large", "1 tbsp") into structured [FoodPortion]s. Mass descriptions such as
 * "3 oz" are ignored because they carry no extra information.
 */
object HouseholdMeasureParser {
    private val amountRegex = Regex("""^\s*(\d*\.?\d+)\s*(.*)$""")
    private val servingWords = setOf("serving", "nlea", "container")
    private val pieceWords = setOf(
        "fillet", "breast", "thigh", "drumstick", "wing", "egg", "fruit", "pepper", "patty",
        "link", "sausage", "bar", "cookie", "muffin", "bagel", "roll", "bun", "tortilla",
        "pita", "potato", "onion", "tomato", "carrot", "banana", "apple", "orange", "leg",
        "chop", "steak", "cake", "head", "stalk", "leaf", "ear", "spear", "date", "fig",
        "olive", "nut", "kernel", "shrimp", "biscuit", "pancake", "waffle", "taco", "burrito",
    )

    fun parse(description: String, grams: Double): FoodPortion? {
        if (grams <= 0.0 || description.isBlank()) return null
        val match = amountRegex.find(description.trim()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null
        val rest = match.groupValues[2].trim().lowercase()
        val head = rest.substringBefore(',').substringBefore('(').trim()
        val words = head.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val first = words.first()

        val size = when (first) {
            "small" -> PortionSize.SMALL
            "medium" -> PortionSize.MEDIUM
            "large", "extra" -> PortionSize.LARGE
            else -> null
        }
        val unit: QuantityUnit = when {
            size != null -> QuantityUnit.PIECE
            first in servingWords -> QuantityUnit.SERVING
            first in pieceWords -> QuantityUnit.PIECE
            else -> QuantityUnit.parse(first)?.takeIf { it.kind != UnitKind.MASS } ?: return null
        }
        val perUnit = grams / amount
        return FoodPortion(unit = unit, grams = perUnit, size = size, label = description.trim())
    }

    /** Density from a "1 cup" measure (US cup), used for spoon conversions. */
    fun densityFrom(portions: List<FoodPortion>): Double? =
        portions.firstOrNull { it.unit == QuantityUnit.CUP }?.let { it.grams / 236.5882365 }
            ?: portions.firstOrNull { it.unit == QuantityUnit.TABLESPOON }?.let { it.grams / 14.78676478125 }
            ?: portions.firstOrNull { it.unit == QuantityUnit.FLUID_OUNCE }?.let { it.grams / 29.5735295625 }
}

/** Factory for records from USDA National Nutrient Database for Standard Reference, Release 28. */
object UsdaSr28 {
    const val DATASET = "USDA SR28 (2016)"

    fun food(
        ndb: String,
        name: String,
        group: String,
        kcal: Double,
        protein: Double,
        fat: Double,
        carbs: Double,
        fiber: Double?,
        sugar: Double?,
        sodiumMg: Double?,
        satFat: Double?,
        weight1: Double?,
        measure1: String?,
        weight2: Double?,
        measure2: String?,
        refusePct: Double?,
    ): FoodReference {
        val portions = buildList {
            if (weight1 != null && measure1 != null) HouseholdMeasureParser.parse(measure1, weight1)?.let(::add)
            if (weight2 != null && measure2 != null) HouseholdMeasureParser.parse(measure2, weight2)?.let(::add)
        }
        val lower = name.lowercase()
        val state = when {
            "raw" in lower -> FoodState.RAW
            listOf("cooked", "boiled", "roasted", "baked", "fried", "braised", "grilled", "steamed", "broiled", "stewed").any { it in lower } -> FoodState.COOKED
            else -> FoodState.AS_SOLD
        }
        return FoodReference(
            id = "usda:$ndb",
            name = name,
            per100g = Nutrients(
                energyKcal = kcal,
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                fiberG = fiber,
                sugarG = sugar,
                sodiumMg = sodiumMg,
                saturatedFatG = satFat,
            ),
            source = NutritionSource.VERIFIED_DATABASE,
            quality = DataQuality.HIGH,
            state = state,
            densityGPerMl = HouseholdMeasureParser.densityFrom(portions),
            portions = portions,
            refuseFraction = ((refusePct ?: 0.0) / 100.0).coerceIn(0.0, 0.95),
            foodGroup = group,
            sourceDetail = "USDA SR28 #$ndb — $name",
            datasetVersion = DATASET,
        )
    }

    /**
     * Parses one line of the compact TSV produced by scripts/generate_food_data.py:
     * ndb, group, name, kcal, protein, fat, carbs, fiber, sugar, sodium, satFat,
     * weight1, measure1, weight2, measure2, refusePct. Empty fields are unknown.
     */
    fun parseTsvLine(line: String): FoodReference? {
        val f = line.split('\t')
        if (f.size < 16 || f[0].startsWith("#")) return null
        fun d(i: Int) = f[i].takeIf { it.isNotBlank() }?.toDoubleOrNull()
        return runCatching {
            food(
                ndb = f[0], group = f[1], name = f[2],
                kcal = d(3) ?: return null, protein = d(4) ?: 0.0, fat = d(5) ?: 0.0, carbs = d(6) ?: 0.0,
                fiber = d(7), sugar = d(8), sodiumMg = d(9), satFat = d(10),
                weight1 = d(11), measure1 = f[12].ifBlank { null },
                weight2 = d(13), measure2 = f[14].ifBlank { null },
                refusePct = d(15),
            )
        }.getOrNull()
    }
}
