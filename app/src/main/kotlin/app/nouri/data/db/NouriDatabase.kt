package app.nouri.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DiaryEntryEntity::class,
        WeightEntryEntity::class,
        BodyMeasurementEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        RecipeStepEntity::class,
        RecipeUsageEntity::class,
        PantryItemEntity::class,
        GroceryItemEntity::class,
        UserFoodEntity::class,
        ScanSessionEntity::class,
        SavedMealEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NouriDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun weightDao(): WeightDao
    abstract fun recipeDao(): RecipeDao
    abstract fun pantryDao(): PantryDao
    abstract fun userFoodDao(): UserFoodDao
    abstract fun scanDao(): ScanDao

    companion object {
        const val NAME = "nouri.db"

        /**
         * Explicit migrations only — user data is never dropped. Add a Migration for
         * every schema version bump (see ARCHITECTURE.md, "Database migrations").
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        fun build(context: Context): NouriDatabase =
            Room.databaseBuilder(context.applicationContext, NouriDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): NouriDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, NouriDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
