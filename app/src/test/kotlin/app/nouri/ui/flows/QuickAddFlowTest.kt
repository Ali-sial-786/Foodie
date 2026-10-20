package app.nouri.ui.flows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.model.NutritionSource
import app.nouri.core.util.SystemTime
import app.nouri.testing.testContainer
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.navigation.NouriApp
import app.nouri.ui.navigation.Route
import app.nouri.ui.theme.NouriTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickAddFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun quickAddLogsExactlyWhatWasTyped() {
        val c = testContainer()
        val today = SystemTime.diaryToday()
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                NouriTheme {
                    val nav = rememberNavController()
                    NouriApp(onboardingComplete = true, navController = nav)
                    LaunchedEffect(Unit) { nav.navigate(Route.AddFood(today.toString(), MealType.LUNCH.name, mode = "QUICK")) }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Calories").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Name (optional)")).performTextInput("Samosa from the canteen")
        rule.onNode(hasSetTextAction() and hasText("Calories")).performTextInput("260")
        rule.onNode(hasSetTextAction() and hasText("Protein")).performTextInput("5")
        rule.onNodeWithText("Log").performClick()

        rule.waitUntil(10_000) { runBlocking { c.diary.observeDay(today).first() }.isNotEmpty() }
        val entry = runBlocking { c.diary.observeDay(today).first() }.single()
        assertEquals("Samosa from the canteen", entry.name)
        assertEquals(MealType.LUNCH, entry.mealType)
        assertEquals(260.0, entry.nutrients.energyKcal, 0.0)
        assertEquals(5.0, entry.nutrients.proteinG, 0.0)
        assertEquals(EntryOrigin.QUICK_ADD, entry.origin)
        assertEquals(NutritionSource.USER_ENTERED, entry.snapshot.provenance.source)
    }
}
