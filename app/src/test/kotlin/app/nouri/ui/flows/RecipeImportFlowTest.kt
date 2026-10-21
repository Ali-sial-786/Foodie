package app.nouri.ui.flows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.testing.testContainer
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.navigation.NouriApp
import app.nouri.ui.recipes.SharedRecipeContent
import app.nouri.ui.theme.NouriTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Text shared into Nouri from another app opens the import screen, is read on the phone
 * (demo mode, no AI), previewed and saved as an imported recipe. Simulated (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class RecipeImportFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val recipe = """
        Masoor Dal
        Recipe by Auntie Noor
        Serves 4

        Ingredients
        1 cup red lentils, rinsed
        3 cups water
        1 tsp turmeric
        1 tsp salt
        2 tbsp ghee
        1 onion, sliced

        Method
        1. Boil the lentils with water, turmeric and salt for 20 minutes until soft.
        2. Fry the onion in ghee until golden and pour over the dal.
    """.trimIndent()

    @Test
    fun sharedTextIsImportedReviewedAndSaved() {
        val c = testContainer()
        c.recipeInbox.offer(SharedRecipeContent(recipe, emptyList()))
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                NouriTheme { NouriApp(onboardingComplete = true, navController = rememberNavController()) }
            }
        }

        rule.waitUntil(10_000) { rule.onAllNodesWithText("Import a recipe").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Masoor Dal", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Import").performScrollTo().performClick()

        rule.waitUntil(10_000) { rule.onAllNodesWithText("Save recipe").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("From Auntie Noor").assertExists()
        rule.onNodeWithText("Save recipe").performScrollTo().performClick()

        rule.waitUntil(10_000) {
            rule.waitForIdle()
            runBlocking { c.recipes.savedRecipes() }.isNotEmpty()
        }
        val saved = runBlocking { c.recipes.savedRecipes() }.single()
        assertEquals("Masoor Dal", saved.name)
        assertEquals(RecipeOrigin.IMPORTED, saved.origin)
        assertEquals("From Auntie Noor", saved.attribution)
        assertEquals(4, saved.servings)
        assertEquals(6, saved.ingredients.size)
        assertEquals(2, saved.steps.size)
        assertTrue(saved.ingredients.first().ingredientId != null)
    }
}
