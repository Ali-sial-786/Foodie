package app.nouri.ui.scan

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.ai.ImagePayload
import app.nouri.core.ai.ImageRole
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.nutrition.Precision
import app.nouri.core.util.SystemTime
import app.nouri.image.ProcessedImage
import app.nouri.testing.testContainer
import app.nouri.ui.LocalAppContainer
import app.nouri.ui.navigation.NouriApp
import app.nouri.ui.navigation.Route
import app.nouri.ui.theme.NouriTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end on the JVM (Robolectric): a captured photo goes through the simulated
 * provider, the deterministic estimator and the review UI into the diary.
 * This is simulated — it does not exercise a real camera or a real AI provider.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class MealScanFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val logButton = SemanticsMatcher("log button") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Log ") && it.text.contains("kcal") } == true
    }

    @Test
    fun demoScanIsReviewedAndLoggedAsEstimates() {
        val c = testContainer()
        val file = File(c.context.cacheDir, "fake.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = ProcessedImage(ImagePayload(byteArrayOf(1, 2, 3), widthPx = 1, heightPx = 1, role = ImageRole.TOP_DOWN, sha256Hex = "00"), file)
        c.scanSessions.put(ScanCapture("scan-1", ScanMode.MEAL, listOf(image), 0L, demoScenario = "biryani_plate"))

        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                NouriTheme {
                    val nav = rememberNavController()
                    NouriApp(onboardingComplete = true, navController = nav)
                    LaunchedEffect(Unit) { nav.navigate(Route.MealReview("scan-1")) }
                }
            }
        }

        rule.waitUntil(10_000) { rule.onAllNodesWithText("Chicken biryani", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Demo mode", substring = true).fetchSemanticsNodes().isNotEmpty() }

        rule.onNode(hasTestTag(MEAL_REVIEW_LIST)).performScrollToNode(logButton)
        rule.onNode(logButton).performClick()

        val today = SystemTime.diaryToday()
        // waitForIdle lets the main looper run the view model's coroutine between checks.
        rule.waitUntil(10_000) {
            rule.waitForIdle()
            runBlocking { c.diary.observeDay(today).first() }.isNotEmpty()
        }
        val logged = runBlocking { c.diary.observeDay(today).first() }
        assertTrue(logged.all { it.origin == EntryOrigin.PHOTO_SCAN && it.scanId == "scan-1" })
        assertTrue(logged.any { it.name.contains("biryani", ignoreCase = true) })
        val biryani = logged.first { it.name.contains("biryani", ignoreCase = true) }
        assertTrue("photo portions are estimates", biryani.snapshot.precision == Precision.ESTIMATED)
        assertTrue("estimates keep their range", biryani.snapshot.energyRange != null)
        assertTrue("demo provenance is recorded", biryani.snapshot.provenance.detail?.contains("demo") == true)
    }

}
