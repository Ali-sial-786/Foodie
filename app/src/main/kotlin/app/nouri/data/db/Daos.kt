package app.nouri.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE date = :date AND deleted = 0 ORDER BY loggedAtEpochMs")
    fun observeDay(date: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :start AND :end AND deleted = 0 ORDER BY date, loggedAtEpochMs")
    fun observeRange(start: String, end: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :start AND :end AND deleted = 0 ORDER BY date, loggedAtEpochMs")
    suspend fun range(start: String, end: String): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun get(id: String): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE idempotencyKey IN (:keys)")
    suspend fun byIdempotencyKeys(keys: List<String>): List<DiaryEntryEntity>

    /** IGNORE + unique idempotency key = a retried log action can never create duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<DiaryEntryEntity>): List<Long>

    @Upsert
    suspend fun upsert(entry: DiaryEntryEntity)

    @Query("UPDATE diary_entries SET deleted = 1, updatedAtEpochMs = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    @Query("UPDATE diary_entries SET deleted = 0, updatedAtEpochMs = :now WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>, now: Long)

    @Query("DELETE FROM diary_entries WHERE deleted = 1 AND updatedAtEpochMs < :before")
    suspend fun purgeDeleted(before: Long)

    @Query(
        "SELECT * FROM diary_entries WHERE deleted = 0 AND id IN (" +
            "SELECT id FROM diary_entries d WHERE deleted = 0 AND loggedAtEpochMs = " +
            "(SELECT MAX(loggedAtEpochMs) FROM diary_entries d2 WHERE d2.name = d.name AND d2.deleted = 0)) " +
            "ORDER BY loggedAtEpochMs DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<DiaryEntryEntity>>

    @Query("SELECT name, COUNT(*) AS count FROM diary_entries WHERE deleted = 0 GROUP BY name ORDER BY count DESC LIMIT :limit")
    fun observeFrequentNames(limit: Int): Flow<List<NameCount>>

    @Query("SELECT * FROM diary_entries WHERE deleted = 0 ORDER BY date, loggedAtEpochMs")
    suspend fun all(): List<DiaryEntryEntity>

    @Query("SELECT DISTINCT date FROM diary_entries WHERE deleted = 0 ORDER BY date DESC LIMIT :limit")
    suspend fun recentDates(limit: Int): List<String>
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY date, minuteOfDay")
    fun observeAll(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries ORDER BY date, minuteOfDay")
    suspend fun all(): List<WeightEntryEntity>

    @Upsert
    suspend fun upsert(entry: WeightEntryEntity)

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM body_measurements ORDER BY date")
    fun observeMeasurements(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements ORDER BY date")
    suspend fun allMeasurements(): List<BodyMeasurementEntity>

    @Upsert
    suspend fun upsertMeasurement(entry: BodyMeasurementEntity)

    @Query("DELETE FROM body_measurements WHERE id = :id")
    suspend fun deleteMeasurement(id: String)
}

@Dao
abstract class RecipeDao {
    @Transaction
    @Query("SELECT * FROM recipes ORDER BY updatedAtEpochMs DESC")
    abstract fun observeAll(): Flow<List<RecipeWithDetails>>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun get(id: String): RecipeWithDetails?

    @Transaction
    @Query("SELECT * FROM recipes")
    abstract suspend fun all(): List<RecipeWithDetails>

    @Upsert
    abstract suspend fun upsertRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertIngredients(items: List<RecipeIngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSteps(items: List<RecipeStepEntity>)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :id")
    abstract suspend fun deleteIngredients(id: String)

    @Query("DELETE FROM recipe_steps WHERE recipeId = :id")
    abstract suspend fun deleteSteps(id: String)

    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun delete(id: String)

    /** Replaces a recipe and its children atomically. */
    @Transaction
    open suspend fun save(recipe: RecipeEntity, ingredients: List<RecipeIngredientEntity>, steps: List<RecipeStepEntity>) {
        upsertRecipe(recipe)
        deleteIngredients(recipe.id)
        deleteSteps(recipe.id)
        insertIngredients(ingredients)
        insertSteps(steps)
    }

    @Query("SELECT * FROM recipe_usage")
    abstract fun observeUsage(): Flow<List<RecipeUsageEntity>>

    @Query("SELECT * FROM recipe_usage WHERE recipeId = :id")
    abstract suspend fun usage(id: String): RecipeUsageEntity?

    @Upsert
    abstract suspend fun upsertUsage(usage: RecipeUsageEntity)
}

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry_items ORDER BY CASE WHEN expiresOn IS NULL THEN 1 ELSE 0 END, expiresOn, name")
    fun observePantry(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items")
    suspend fun pantry(): List<PantryItemEntity>

    @Upsert
    suspend fun upsertPantry(items: List<PantryItemEntity>)

    @Query("DELETE FROM pantry_items WHERE id IN (:ids)")
    suspend fun deletePantry(ids: List<String>)

    @Query("SELECT * FROM grocery_items ORDER BY checked, category, name")
    fun observeGrocery(): Flow<List<GroceryItemEntity>>

    @Query("SELECT * FROM grocery_items")
    suspend fun grocery(): List<GroceryItemEntity>

    @Upsert
    suspend fun upsertGrocery(items: List<GroceryItemEntity>)

    @Query("DELETE FROM grocery_items WHERE id IN (:ids)")
    suspend fun deleteGrocery(ids: List<String>)

    @Query("DELETE FROM grocery_items WHERE checked = 1")
    suspend fun clearCheckedGrocery()
}

@Dao
interface UserFoodDao {
    @Query("SELECT * FROM user_foods ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<UserFoodEntity>>

    @Query("SELECT * FROM user_foods WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): UserFoodEntity?

    @Upsert
    suspend fun upsert(food: UserFoodEntity)

    @Query("DELETE FROM user_foods WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScanDao {
    @Upsert
    suspend fun upsert(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun get(id: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY createdAtEpochMs DESC")
    suspend fun all(): List<ScanSessionEntity>

    @Query("UPDATE scan_sessions SET imagePathsJson = '[]' WHERE id IN (:ids)")
    suspend fun clearImages(ids: List<String>)

    @Query("SELECT * FROM saved_meals ORDER BY createdAtEpochMs DESC")
    fun observeSavedMeals(): Flow<List<SavedMealEntity>>

    @Upsert
    suspend fun upsertSavedMeal(meal: SavedMealEntity)

    @Query("DELETE FROM saved_meals WHERE id = :id")
    suspend fun deleteSavedMeal(id: String)
}
