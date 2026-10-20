package app.nouri

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsOnboarding() {
        // Settings load asynchronously from DataStore before the first frame of UI.
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Welcome to Nouri").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Welcome to Nouri").assertIsDisplayed()
    }
}
