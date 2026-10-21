package app.nouri.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2: dish families on saved recipes, and personal star ratings. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recipes` ADD COLUMN `family` TEXT")
        db.execSQL("ALTER TABLE `recipe_usage` ADD COLUMN `rating` INTEGER")
    }
}

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
    version = 2,
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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

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
