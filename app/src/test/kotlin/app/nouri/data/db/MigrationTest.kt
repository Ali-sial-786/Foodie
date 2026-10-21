package app.nouri.data.db

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds a real version-1 database from the committed v1 schema, fills it, and opens it
 * with the current Room database. Room validates every table after the migrations run,
 * so a migration that doesn't match the entities fails here rather than on a phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    private fun createVersion1() {
        val schema = Json.parseToJsonElement(File("schemas/app.nouri.data.db.NouriDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.insert("recipe_usage", null, ContentValues().apply {
                put("recipeId", "butter_chicken"); put("favourite", 1); put("cookedCount", 3); put("lastCookedAtEpochMs", 1_700_000_000_000L)
            })
            db.insert("recipes", null, ContentValues().apply {
                put("id", "user:1"); put("name", "My dal"); put("cuisineId", "pakistani"); put("description", "")
                put("servings", 4); put("prepMinutes", 10); put("cookMinutes", 30); put("difficulty", "EASY"); put("origin", "USER")
                put("variantOf", "dal_tadka")
                listOf("equipmentJson", "tagsJson", "foodSafetyJson", "notesJson", "declaredDietsJson").forEach { put(it, "[]") }
                put("createdAtEpochMs", 1L); put("updatedAtEpochMs", 1L)
            })
            db.version = 1
        }
    }

    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking {
        createVersion1()
        val room = Room.databaseBuilder(context, NouriDatabase::class.java, name)
            .addMigrations(*NouriDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val usage = checkNotNull(room.recipeDao().usage("butter_chicken")) { "usage row lost" }
            assertTrue(usage.favourite)
            assertEquals(3, usage.cookedCount)
            assertNull(usage.rating)
            val recipe = checkNotNull(room.recipeDao().get("user:1")) { "saved recipe lost" }.toDomain()
            assertEquals("dal_tadka", recipe.variantOf)
            assertNull(recipe.family)
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }
}
