package app.nouri.data.db

import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodState
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.StorageLocation
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.pantry.PantryItem
import app.nouri.core.pantry.PantrySource
import app.nouri.core.progress.BodyMeasurement
import app.nouri.core.progress.WeightEntry
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeIngredient
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeStep
import app.nouri.core.units.FoodPortion
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val dbJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private val stringList = ListSerializer(String.serializer())
internal fun List<String>.toJson(): String = dbJson.encodeToString(stringList, this)
internal fun String.toStringList(): List<String> = runCatching { dbJson.decodeFromString(stringList, this) }.getOrDefault(emptyList())

private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
    value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

// ------------------------------------------------------------------ diary

fun DiaryEntry.toEntity(now: Long): DiaryEntryEntity {
    val s = snapshot
    val n = s.nutrients
    return DiaryEntryEntity(
        id = id, date = date.toString(), mealType = mealType.name, loggedAtEpochMs = loggedAtEpochMs, timeZoneId = timeZoneId,
        name = name, quantityAmount = quantity?.amount, quantityUnit = quantity?.unit?.name, quantitySize = quantity?.size?.name,
        foodRefId = foodRefId, recipeId = recipeId, recipeFraction = recipeFraction,
        energyKcal = n.energyKcal, proteinG = n.proteinG, carbsG = n.carbsG, fatG = n.fatG, fiberG = n.fiberG, sugarG = n.sugarG,
        sodiumMg = n.sodiumMg, saturatedFatG = n.saturatedFatG, grams = s.grams,
        provenanceSource = s.provenance.source.name, provenanceRef = s.provenance.referenceId, provenanceDetail = s.provenance.detail,
        datasetVersion = s.provenance.datasetVersion, confidence = s.confidence.name, precision = s.precision.name,
        rangeLowKcal = s.energyRange?.low, rangeHighKcal = s.energyRange?.high, snapshotAtEpochMs = s.capturedAtEpochMs,
        snapshotNotesJson = s.notes.toJson(), origin = origin.name, scanId = scanId, idempotencyKey = idempotencyKey, note = note,
        updatedAtEpochMs = now,
    )
}

fun DiaryEntryEntity.toDomain(): DiaryEntry {
    val nutrients = Nutrients(energyKcal, proteinG, carbsG, fatG, fiberG, sugarG, sodiumMg, saturatedFatG)
    val range = if (rangeLowKcal != null && rangeHighKcal != null && rangeLowKcal <= energyKcal && energyKcal <= rangeHighKcal) {
        EstimateRange(rangeLowKcal, energyKcal, rangeHighKcal)
    } else null
    return DiaryEntry(
        id = id,
        date = LocalDate.parse(date),
        mealType = enumOr(mealType, MealType.SNACK),
        loggedAtEpochMs = loggedAtEpochMs,
        timeZoneId = timeZoneId,
        name = name,
        quantity = if (quantityAmount != null && quantityUnit != null) {
            Quantity(quantityAmount, enumOr(quantityUnit, QuantityUnit.GRAM), quantitySize?.let { enumOr(it, PortionSize.MEDIUM) })
        } else null,
        foodRefId = foodRefId,
        recipeId = recipeId,
        recipeFraction = recipeFraction,
        snapshot = NutritionSnapshot(
            nutrients = nutrients,
            grams = grams,
            provenance = Provenance(enumOr(provenanceSource, NutritionSource.APPROXIMATE_FALLBACK), provenanceRef, provenanceDetail, datasetVersion),
            confidence = enumOr(confidence, ConfidenceLevel.MODERATE),
            precision = enumOr(precision, Precision.CALCULATED),
            energyRange = range,
            capturedAtEpochMs = snapshotAtEpochMs,
            notes = snapshotNotesJson.toStringList(),
        ),
        origin = enumOr(origin, EntryOrigin.QUICK_ADD),
        scanId = scanId,
        idempotencyKey = idempotencyKey,
        note = note,
    )
}

// ------------------------------------------------------------------ weight

fun WeightEntry.toEntity() = WeightEntryEntity(id, date.toString(), minuteOfDay, weightKg, note)
fun WeightEntryEntity.toDomain() = WeightEntry(id, LocalDate.parse(date), minuteOfDay, weightKg, note)
fun BodyMeasurement.toEntity() = BodyMeasurementEntity(id, date.toString(), waistCm, hipCm, chestCm, note)
fun BodyMeasurementEntity.toDomain() = BodyMeasurement(id, LocalDate.parse(date), waistCm, hipCm, chestCm, note)

// ------------------------------------------------------------------ recipes

fun Recipe.toEntities(now: Long): Triple<RecipeEntity, List<RecipeIngredientEntity>, List<RecipeStepEntity>> {
    val entity = RecipeEntity(
        id = id, name = name, cuisineId = cuisineId, region = region, description = description, servings = servings,
        prepMinutes = prepMinutes, cookMinutes = cookMinutes, difficulty = difficulty.name, origin = origin.name,
        variantOf = variantOf, variantSummary = variantSummary, attribution = attribution, storage = storage,
        reheating = reheating, cookedYieldGrams = cookedYieldGrams, equipmentJson = equipment.toJson(),
        tagsJson = tags.toList().toJson(), foodSafetyJson = foodSafety.toJson(), notesJson = notes.toJson(),
        declaredDietsJson = declaredDiets.map { it.name }.toJson(),
        createdAtEpochMs = if (createdAtEpochMs > 0) createdAtEpochMs else now, updatedAtEpochMs = now,
        family = family,
    )
    val ingredients = ingredients.mapIndexed { i, l ->
        RecipeIngredientEntity(
            recipeId = id, position = i, displayName = l.displayName, ingredientId = l.ingredientId, amount = l.quantity.amount,
            unit = l.quantity.unit.name, size = l.quantity.size?.name, preparation = l.preparation, optional = l.optional,
            groupName = l.group, consumedFraction = l.consumedFraction, foodRefId = l.foodRefId, measuredCooked = l.measuredCooked,
            gramsOverride = l.gramsOverride,
        )
    }
    val steps = steps.mapIndexed { i, s -> RecipeStepEntity(id, i, s.text, s.timerSeconds, s.temperatureC) }
    return Triple(entity, ingredients, steps)
}

fun RecipeWithDetails.toDomain(): Recipe {
    val r = recipe
    return Recipe(
        id = r.id, name = r.name, cuisineId = r.cuisineId, region = r.region, description = r.description,
        servings = r.servings.coerceAtLeast(1),
        ingredients = ingredients.sortedBy { it.position }.map { l ->
            RecipeIngredient(
                displayName = l.displayName, ingredientId = l.ingredientId,
                quantity = Quantity(l.amount, enumOr(l.unit, QuantityUnit.GRAM), l.size?.let { enumOr(it, PortionSize.MEDIUM) }),
                preparation = l.preparation, optional = l.optional, group = l.groupName,
                consumedFraction = l.consumedFraction.coerceIn(0.0, 1.0), foodRefId = l.foodRefId, measuredCooked = l.measuredCooked,
                gramsOverride = l.gramsOverride,
            )
        },
        steps = steps.sortedBy { it.position }.map { RecipeStep(it.text, it.timerSeconds, it.temperatureC) },
        prepMinutes = r.prepMinutes, cookMinutes = r.cookMinutes, difficulty = enumOr(r.difficulty, Difficulty.MEDIUM),
        equipment = r.equipmentJson.toStringList(), tags = r.tagsJson.toStringList().toSet(),
        origin = enumOr(r.origin, RecipeOrigin.USER).let { if (it == RecipeOrigin.EXTERNAL_LICENSED && r.attribution.isNullOrBlank()) RecipeOrigin.USER else it },
        variantOf = r.variantOf, variantSummary = r.variantSummary, family = r.family, attribution = r.attribution, storage = r.storage,
        reheating = r.reheating, cookedYieldGrams = r.cookedYieldGrams, foodSafety = r.foodSafetyJson.toStringList(),
        notes = r.notesJson.toStringList(),
        declaredDiets = r.declaredDietsJson.toStringList().mapNotNull { n -> DietGoal.entries.firstOrNull { it.name == n } }.toSet(),
        createdAtEpochMs = r.createdAtEpochMs,
    )
}

// ------------------------------------------------------------------ pantry & grocery

fun PantryItem.toEntity() = PantryItemEntity(
    id, name, ingredientId, quantity?.amount, quantity?.unit?.name, location.name, addedOn.toString(), expiresOn?.toString(),
    opened, source.name, barcode, note,
)

fun PantryItemEntity.toDomain() = PantryItem(
    id = id, name = name, ingredientId = ingredientId,
    quantity = if (quantityAmount != null && quantityUnit != null) Quantity(quantityAmount, enumOr(quantityUnit, QuantityUnit.PIECE)) else null,
    location = enumOr(location, StorageLocation.PANTRY), addedOn = LocalDate.parse(addedOn), expiresOn = expiresOn?.let(LocalDate::parse),
    opened = opened, source = enumOr(source, PantrySource.MANUAL), barcode = barcode, note = note,
)

fun GroceryItem.toEntity() = GroceryItemEntity(
    id, name, ingredientId, quantity?.amount, quantity?.unit?.name, category, checked, forRecipeIds.toJson(), addedAtEpochMs,
)

fun GroceryItemEntity.toDomain() = GroceryItem(
    id = id, name = name, ingredientId = ingredientId,
    quantity = if (quantityAmount != null && quantityUnit != null) Quantity(quantityAmount, enumOr(quantityUnit, QuantityUnit.PIECE)) else null,
    category = category, checked = checked, forRecipeIds = forRecipeIdsJson.toStringList(), addedAtEpochMs = addedAtEpochMs,
)

// ------------------------------------------------------------------ user foods

private val portionList = ListSerializer(FoodPortion.serializer())

fun FoodReference.toUserFoodEntity(brand: String?, barcode: String?, allergens: Set<Allergen>, now: Long) = UserFoodEntity(
    id = id, name = name, brand = brand, barcode = barcode,
    energyKcal = per100g.energyKcal, proteinG = per100g.proteinG, carbsG = per100g.carbsG, fatG = per100g.fatG,
    fiberG = per100g.fiberG, sugarG = per100g.sugarG, sodiumMg = per100g.sodiumMg, saturatedFatG = per100g.saturatedFatG,
    servingGrams = servingGrams, servingText = portions.firstOrNull { it.unit == QuantityUnit.SERVING }?.label,
    densityGPerMl = densityGPerMl, source = source.name, quality = quality.name, sourceDetail = sourceDetail,
    portionsJson = dbJson.encodeToString(portionList, portions), allergensJson = allergens.map { it.name }.toJson(),
    updatedAtEpochMs = now,
)

fun UserFoodEntity.toFoodReference() = FoodReference(
    id = id,
    name = if (brand.isNullOrBlank()) name else "$name ($brand)",
    per100g = Nutrients(energyKcal, proteinG, carbsG, fatG, fiberG, sugarG, sodiumMg, saturatedFatG).clampNonNegative(),
    source = enumOr(source, NutritionSource.USER_ENTERED),
    quality = enumOr(quality, DataQuality.MEDIUM),
    state = FoodState.AS_SOLD,
    densityGPerMl = densityGPerMl,
    portions = runCatching { dbJson.decodeFromString(portionList, portionsJson) }.getOrDefault(emptyList()),
    servingGrams = servingGrams,
    sourceDetail = sourceDetail,
)
