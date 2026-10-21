package app.nouri.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Room schema (version 1). Nutrition values are stored as typed columns — never as
 * free text — and every diary row carries the frozen snapshot and provenance used
 * at logging time. Dates are ISO-8601 strings (yyyy-MM-dd), which sort correctly.
 */

@Entity(
    tableName = "diary_entries",
    indices = [Index("date"), Index(value = ["idempotencyKey"], unique = true), Index("scanId"), Index("foodRefId")],
)
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val mealType: String,
    val loggedAtEpochMs: Long,
    val timeZoneId: String,
    val name: String,
    val quantityAmount: Double?,
    val quantityUnit: String?,
    val quantitySize: String?,
    val foodRefId: String?,
    val recipeId: String?,
    val recipeFraction: Double?,
    // Frozen nutrition snapshot
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double?,
    val sugarG: Double?,
    val sodiumMg: Double?,
    val saturatedFatG: Double?,
    val grams: Double?,
    val provenanceSource: String,
    val provenanceRef: String?,
    val provenanceDetail: String?,
    val datasetVersion: String?,
    val confidence: String,
    val precision: String,
    val rangeLowKcal: Double?,
    val rangeHighKcal: Double?,
    val snapshotAtEpochMs: Long,
    val snapshotNotesJson: String,
    val origin: String,
    val scanId: String?,
    val idempotencyKey: String,
    val note: String?,
    /** Soft delete so "Undo" works and history edits are recoverable. */
    val deleted: Boolean = false,
    val updatedAtEpochMs: Long,
)

data class NameCount(val name: String, val count: Int)

@Entity(tableName = "weight_entries", indices = [Index("date")])
data class WeightEntryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val minuteOfDay: Int?,
    val weightKg: Double,
    val note: String?,
)

@Entity(tableName = "body_measurements", indices = [Index("date")])
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    val date: String,
    val waistCm: Double?,
    val hipCm: Double?,
    val chestCm: Double?,
    val note: String?,
)

@Entity(tableName = "recipes", indices = [Index("cuisineId"), Index("variantOf")])
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cuisineId: String,
    val region: String?,
    val description: String,
    val servings: Int,
    val prepMinutes: Int,
    val cookMinutes: Int,
    val difficulty: String,
    val origin: String,
    val variantOf: String?,
    val variantSummary: String?,
    val attribution: String?,
    val storage: String?,
    val reheating: String?,
    val cookedYieldGrams: Double?,
    val equipmentJson: String,
    val tagsJson: String,
    val foodSafetyJson: String,
    val notesJson: String,
    val declaredDietsJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Added in v2. */
    val family: String? = null,
)

@Entity(
    tableName = "recipe_ingredients",
    primaryKeys = ["recipeId", "position"],
    foreignKeys = [androidx.room.ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = androidx.room.ForeignKey.CASCADE)],
)
data class RecipeIngredientEntity(
    val recipeId: String,
    val position: Int,
    val displayName: String,
    val ingredientId: String?,
    val amount: Double,
    val unit: String,
    val size: String?,
    val preparation: String?,
    val optional: Boolean,
    val groupName: String?,
    val consumedFraction: Double,
    val foodRefId: String?,
    val measuredCooked: Boolean,
    val gramsOverride: Double?,
)

@Entity(
    tableName = "recipe_steps",
    primaryKeys = ["recipeId", "position"],
    foreignKeys = [androidx.room.ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = androidx.room.ForeignKey.CASCADE)],
)
data class RecipeStepEntity(
    val recipeId: String,
    val position: Int,
    val text: String,
    val timerSeconds: Int?,
    val temperatureC: Int?,
)

data class RecipeWithDetails(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipeId") val ingredients: List<RecipeIngredientEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipeId") val steps: List<RecipeStepEntity>,
)

/** Favourites and cooking history for both built-in and saved recipes. */
@Entity(tableName = "recipe_usage")
data class RecipeUsageEntity(
    @PrimaryKey val recipeId: String,
    val favourite: Boolean,
    val cookedCount: Int,
    val lastCookedAtEpochMs: Long?,
    /** The user's own 1–5 stars; null until rated. Added in v2. */
    val rating: Int? = null,
)

@Entity(tableName = "pantry_items", indices = [Index("ingredientId"), Index("expiresOn")])
data class PantryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ingredientId: String?,
    val quantityAmount: Double?,
    val quantityUnit: String?,
    val location: String,
    val addedOn: String,
    val expiresOn: String?,
    val opened: Boolean,
    val source: String,
    val barcode: String?,
    val note: String?,
)

@Entity(tableName = "grocery_items")
data class GroceryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ingredientId: String?,
    val quantityAmount: Double?,
    val quantityUnit: String?,
    val category: String,
    val checked: Boolean,
    val forRecipeIdsJson: String,
    val addedAtEpochMs: Long,
)

/** Custom foods, nutrition labels and cached barcode products. */
@Entity(tableName = "user_foods", indices = [Index("barcode"), Index("name")])
data class UserFoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double?,
    val sugarG: Double?,
    val sodiumMg: Double?,
    val saturatedFatG: Double?,
    val servingGrams: Double?,
    val servingText: String?,
    val densityGPerMl: Double?,
    val source: String,
    val quality: String,
    val sourceDetail: String?,
    val portionsJson: String,
    val allergensJson: String,
    val updatedAtEpochMs: Long,
)

/** A photo analysis kept for corrections, history and benchmarking. Images are optional. */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMs: Long,
    val kind: String,
    val providerKind: String,
    val simulated: Boolean,
    val analysisJson: String,
    val imagePathsJson: String,
    val loggedEntryIdsJson: String,
)

/** A group of foods saved to log again in one tap ("My usual breakfast"). */
@Entity(tableName = "saved_meals")
data class SavedMealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val itemsJson: String,
    val createdAtEpochMs: Long,
)
