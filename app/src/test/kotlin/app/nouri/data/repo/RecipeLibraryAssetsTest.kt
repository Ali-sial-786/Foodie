package app.nouri.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.recipes.RecipeCatalog
import app.nouri.testing.testContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The bundled library asset decodes on the device side and holds every recipe in `data/recipes/`. */
@RunWith(AndroidJUnit4::class)
class RecipeLibraryAssetsTest {
    private val c = testContainer()

    private val sourceCount: Int = File("../data/recipes").listFiles { f -> f.extension == "json" }.orEmpty()
        .sumOf { Json.parseToJsonElement(it.readText()).jsonObject.getValue("recipes").jsonArray.size }

    @Test
    fun everyLibraryRecipeLoadsAndIsFindable() = runBlocking {
        val library = c.recipeLibrary.all()
        assertEquals(sourceCount, library.size)
        library.firstOrNull()?.let { assertNotNull(c.recipes.get(it.id)) }
        val all = c.recipes.all.first()
        assertEquals(RecipeCatalog.all.size + sourceCount, all.size)
        assertTrue(all.map { it.id }.toSet().size == all.size)
    }
}
