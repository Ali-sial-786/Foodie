package app.nouri.ui.flows

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.MainActivity
import app.nouri.NouriApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every onboarding step can be skipped, and finishing lands on Home. Simulated (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun skippingEverythingStillFinishesOnboarding() {
        waitFor("Get started")
        rule.onNodeWithText("Get started").performClick()
        repeat(4) {
            waitFor("Skip")
            rule.onNodeWithText("Skip").performClick()
        }
        waitFor("Start using Nouri")
        rule.onNodeWithText("Start using Nouri").performClick()

        waitFor("Eaten today")
        val container = (rule.activity.application as NouriApplication).container
        assertTrue(runBlocking { container.settings.current().onboardingComplete })
    }
}
