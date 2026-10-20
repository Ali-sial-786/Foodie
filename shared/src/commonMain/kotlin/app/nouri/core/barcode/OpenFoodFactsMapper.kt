package app.nouri.core.barcode

import app.nouri.core.fooddb.FoodReference
import app.nouri.core.label.NutrientFields
import app.nouri.core.model.DataQuality
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.ontology.Allergen
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.QuantityUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Maps an Open Food Facts API v2 product response (`/api/v2/product/{code}`) to a
 * [BarcodeLookupResult]. Pure: the HTTP call is made by the app.
 *
 * Rules:
 * - `status` 0 (or no `product`) → [BarcodeLookupResult.NotFound]; unparseable JSON →
 *   [BarcodeLookupResult.Failed], retryable because it is usually a truncated or error page.
 * - Numbers may be JSON numbers or strings ("12.5", "12,5"). Negative or physically impossible
 *   values (a macronutrient above 100 g per 100 g, more than 950 kcal, more sodium than salt)
 *   are treated as missing rather than passed on.
 * - Energy: `energy-kcal_100g`, else `energy-kj_100g` / 4.184, else `energy_100g` (kJ) / 4.184.
 *   Sodium: `sodium_100g` (grams) × 1000, else `salt_100g` × 400. When a `_100g` value is absent
 *   but the `_serving` value and the serving weight are known, it is scaled to 100 g.
 * - Carbohydrate: OFF stores it as printed. EU/UK (and AU/NZ) labels exclude fibre, so when
 *   every country the product is sold in uses that convention, fibre is added to give total
 *   carbohydrate; otherwise the value is passed through unchanged.
 * - Liquids: OFF files per-100 mL values under the `_100g` keys; they are used as per 100 g
 *   (an error of a few percent for most drinks).
 * - Any missing core value (energy, protein, carbohydrate, fat) → `food = null`, with every
 *   missing nutrient listed so the app can offer label scanning. Otherwise the record is
 *   [NutritionSource.BRANDED_DATABASE] with [DataQuality.MEDIUM] (crowd-sourced) and a SERVING
 *   portion when the serving weight is known.
 * - Allergens come from `allergens_tags` only ("may contain" traces are ignored).
 */
object OpenFoodFactsMapper {

    const val ATTRIBUTION = "Data from Open Food Facts (openfoodfacts.org), ODbL"

    private const val KJ_PER_KCAL = 4.184
    private const val SODIUM_MG_PER_SALT_G = 400.0

    private val allergenTags = mapOf(
        "milk" to Allergen.MILK,
        "eggs" to Allergen.EGG,
        "peanuts" to Allergen.PEANUT,
        "nuts" to Allergen.TREE_NUT,
        "soybeans" to Allergen.SOY,
        "gluten" to Allergen.GLUTEN,
        "fish" to Allergen.FISH,
        "crustaceans" to Allergen.CRUSTACEAN,
        "molluscs" to Allergen.MOLLUSC,
        "sesame-seeds" to Allergen.SESAME,
        "mustard" to Allergen.MUSTARD,
        "sulphur-dioxide-and-sulphites" to Allergen.SULPHITES,
    )
    private val wheatWord = Regex("""(?<![a-z])wheat(?![a-z])""", RegexOption.IGNORE_CASE)

    /** Countries whose labels print carbohydrate excluding fibre (EU/EEA, UK, CH, AU, NZ). */
    private val fibreExcludedCountries = setOf(
        "united-kingdom", "ireland", "france", "germany", "spain", "italy", "portugal", "belgium",
        "netherlands", "luxembourg", "austria", "switzerland", "denmark", "sweden", "finland",
        "norway", "iceland", "poland", "czech-republic", "slovakia", "hungary", "slovenia",
        "croatia", "romania", "bulgaria", "greece", "cyprus", "malta", "estonia", "latvia",
        "lithuania", "australia", "new-zealand",
    ).map { "en:$it" }.toSet()

    fun map(json: String): BarcodeLookupResult {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: IllegalArgumentException) {
            return BarcodeLookupResult.Failed("Product data could not be read", retryable = true)
        }
        if (root !is JsonObject) return BarcodeLookupResult.Failed("Product data could not be read", retryable = true)
        val status = root.text("status")?.lowercase()
        if (status == "0" || status == "0.0" || status == "failure") return BarcodeLookupResult.NotFound
        val product = root["product"] as? JsonObject ?: return BarcodeLookupResult.NotFound
        val code = root.text("code") ?: product.text("code") ?: product.text("_id")
            ?: return BarcodeLookupResult.Failed("Product data has no barcode", retryable = false)
        val gtin = Gtin.normalize(code) ?: code
        return BarcodeLookupResult.Found(mapProduct(gtin, product))
    }

    private fun mapProduct(gtin: String, product: JsonObject): BrandedProduct {
        val brand = product.text("brands")?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        val name = product.text("product_name_en") ?: product.text("product_name")
            ?: product.text("generic_name_en") ?: product.text("generic_name")
            ?: brand?.let { "$it product" } ?: "Product $gtin"
        val quantityText = product.text("quantity")
        val servingText = product.text("serving_size")
        val servingGrams = servingGrams(product, servingText)

        val nutriments = product["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
        fun per100(key: String): Double? = nutriments.number("${key}_100g")
            ?: servingGrams?.let { grams -> nutriments.number("${key}_serving")?.times(100.0 / grams) }

        val energy = (per100("energy-kcal") ?: per100("energy-kj")?.div(KJ_PER_KCAL) ?: per100("energy")?.div(KJ_PER_KCAL))
            ?.takeIf { it <= 950.0 }
        val protein = per100("proteins")?.takeIf { it <= 100.0 }
        val fat = per100("fat")?.takeIf { it <= 100.0 }
        val fibre = per100("fiber")?.takeIf { it <= 100.0 }
        val printedCarbs = per100("carbohydrates")?.takeIf { it <= 100.0 }
        val carbs = printedCarbs?.let { if (excludesFibre(product)) it + (fibre ?: 0.0) else it }
        val sugars = per100("sugars")?.takeIf { it <= 100.0 }
        val saturated = per100("saturated-fat")?.takeIf { it <= 100.0 }
        val sodium = (per100("sodium")?.times(1000.0) ?: per100("salt")?.times(SODIUM_MG_PER_SALT_G))
            ?.takeIf { it <= 40_000.0 }

        val missing = buildList {
            if (energy == null) add(NutrientFields.ENERGY_KCAL)
            if (protein == null) add(NutrientFields.PROTEIN_G)
            if (carbs == null) add(NutrientFields.CARBS_G)
            if (fat == null) add(NutrientFields.FAT_G)
            if (fibre == null) add(NutrientFields.FIBER_G)
            if (sugars == null) add(NutrientFields.SUGAR_G)
            if (sodium == null) add(NutrientFields.SODIUM_MG)
            if (saturated == null) add(NutrientFields.SATURATED_FAT_G)
        }
        val food = if (energy != null && protein != null && carbs != null && fat != null) {
            FoodReference(
                id = "off:$gtin",
                name = name,
                per100g = Nutrients(
                    energyKcal = energy,
                    proteinG = protein,
                    carbsG = carbs,
                    fatG = fat,
                    fiberG = fibre,
                    sugarG = sugars,
                    sodiumMg = sodium,
                    saturatedFatG = saturated,
                ),
                source = NutritionSource.BRANDED_DATABASE,
                quality = DataQuality.MEDIUM,
                portions = listOfNotNull(servingGrams?.let { FoodPortion(QuantityUnit.SERVING, it, label = servingText ?: "1 serving") }),
                servingGrams = servingGrams,
                sourceDetail = ATTRIBUTION,
            )
        } else {
            null
        }
        return BrandedProduct(
            gtin = gtin,
            name = name,
            brand = brand,
            packageQuantityText = quantityText,
            packageGrams = QuantityText.packageGrams(quantityText),
            servingSizeText = servingText,
            servingGrams = servingGrams,
            food = food,
            allergens = allergens(product),
            missingNutrients = missing,
            attribution = ATTRIBUTION,
        )
    }

    /**
     * `serving_quantity` is in the unit of `serving_quantity_unit` when present; older records
     * have no unit, so a serving described only by volume ("1 cup (240 ml)") has no gram weight.
     */
    private fun servingGrams(product: JsonObject, servingText: String?): Double? {
        val quantity = product.number("serving_quantity")
        val unit = product.text("serving_quantity_unit")?.lowercase()
        val grams = when {
            unit != null -> quantity.takeIf { unit == "g" }
            servingText != null && QuantityText.massGrams(servingText) != null -> quantity ?: QuantityText.massGrams(servingText)
            servingText != null && QuantityText.hasVolume(servingText) -> null
            else -> quantity
        }
        return grams?.takeIf { it > 0.0 }
    }

    private fun excludesFibre(product: JsonObject): Boolean {
        val countries = product.textList("countries_tags")
        return countries.isNotEmpty() && countries.all { it in fibreExcludedCountries }
    }

    private fun allergens(product: JsonObject): Set<Allergen> {
        val tags = product.textList("allergens_tags").ifEmpty { product.textList("allergens_hierarchy") }
        val found = tags.mapNotNull { tag -> allergenTags[tag.substringAfter(':').trim().lowercase()] }.toMutableSet()
        if (Allergen.GLUTEN in found) {
            val ingredients = listOfNotNull(product.text("ingredients_text_en"), product.text("ingredients_text"))
            if (ingredients.any { wheatWord.containsMatchIn(it) }) found += Allergen.WHEAT
        }
        return found.sortedBy { it.ordinal }.toSet()
    }

    // --- JSON helpers: values arrive as numbers, strings or null. ---------------------------

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        val value = if (primitive.isString) {
            primitive.content.trim().replace(',', '.').toDoubleOrNull()
        } else {
            primitive.doubleOrNull
        }
        return value?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun JsonObject.textList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
}

/** Parses package and serving quantity text such as "400 g", "1,5 kg", "2 x 150 g", "500 ml". */
internal object QuantityText {
    private const val OUNCE_G = 28.349523125
    private const val POUND_G = 453.59237

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val mass = Regex("""(?<![\d.,])$NUMBER\s*(kg|kilograms?|g|gr|grams?|grammes?|mg|oz|lbs?)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
    private val multipack = Regex(
        """(?<![\d.,])(\d+)\s*[x×*]\s*$NUMBER\s*(kg|kilograms?|g|gr|grams?|grammes?|oz|lbs?)(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE,
    )
    private val volume = Regex("""(?<![\d.,])$NUMBER\s*(ml|cl|dl|l|litres?|liters?|fl\.?\s*oz)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
    private val fluidOunce = Regex("""fl\.?\s*oz""", RegexOption.IGNORE_CASE)

    /** Net mass of a package: multipacks are multiplied out; volume-only quantities give null. */
    fun packageGrams(text: String?): Double? {
        if (text == null) return null
        val pack = multipack.find(text)
        if (pack != null) {
            val count = pack.groupValues[1].toDouble()
            val each = grams(pack.groupValues[2], pack.groupValues[3])
            if (each != null && count > 0.0) return count * each
        }
        return massGrams(text)
    }

    /** First metric mass in [text], else the first imperial one; null when none is stated. */
    fun massGrams(text: String): Double? {
        val masses = mass.findAll(fluidOunce.replace(text, " ")).mapNotNull { m ->
            val unit = m.groupValues[2].lowercase()
            grams(m.groupValues[1], unit)?.let { (unit.startsWith("oz") || unit.startsWith("lb")) to it }
        }.toList()
        val grams = masses.firstOrNull { !it.first }?.second ?: masses.firstOrNull()?.second
        return grams?.takeIf { it > 0.0 }
    }

    fun hasVolume(text: String): Boolean = volume.containsMatchIn(text)

    private fun grams(number: String, unit: String): Double? {
        val value = number.replace(',', '.').toDoubleOrNull() ?: return null
        val u = unit.lowercase()
        return when {
            u == "mg" -> value / 1000.0
            u.startsWith("k") -> value * 1000.0
            u == "oz" -> value * OUNCE_G
            u.startsWith("lb") -> value * POUND_G
            else -> value
        }
    }
}
