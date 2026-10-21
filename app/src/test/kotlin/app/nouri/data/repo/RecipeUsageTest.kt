package app.nouri.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.testing.testContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeUsageTest {
    private val c = testContainer()

    @Test
    fun ratingsSurviveFavouritesAndCookingAndCanBeCleared() = runBlocking {
        c.recipes.setRating("butter_chicken", 4)
        c.recipes.setFavourite("butter_chicken", true)
        c.recipes.markCooked("butter_chicken")
        val usage = c.recipes.usage.first().getValue("butter_chicken")
        assertEquals(4, usage.rating)
        assertTrue(usage.favourite)
        assertEquals(1, usage.cookedCount)
        c.recipes.setRating("butter_chicken", null)
        assertNull(c.recipes.usage.first().getValue("butter_chicken").rating)
    }
}
