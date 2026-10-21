package app.nouri.core.app

import app.nouri.core.auth.SecureRandomSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NouriAppModelTest {
    private val storage = MemoryTextStorage()
    private val secrets = MemorySecretStore()
    private var clock = 1_792_573_200_000L // 2026-10-21 08:20 UTC
    private val random = SecureRandomSource { size -> ByteArray(size) { it.toByte() } }

    private fun model() = NouriAppModel(storage, secrets, http = null, random = random, now = { clock }, zone = { "UTC" }, mockLatencyMs = 0)

    @Test
    fun quickAddShowsUpInTodayAndSurvivesARestart() {
        val app = model()
        val today = app.todayIso()
        assertEquals("2026-10-21", today)
        assertTrue(app.quickAdd("Chai", 120.0, 4.0, 15.0, 5.0, "BREAKFAST", today).logged)
        val day = app.day(today)
        assertEquals("Today", day.title)
        assertEquals(120.0, day.consumed.kcal, 1e-9)
        assertEquals("Chai", day.meals.first { it.mealId == "BREAKFAST" }.entries.single().name)
        // A fresh model reads the same files.
        assertEquals(120.0, model().day(today).consumed.kcal, 1e-9)
    }

    @Test
    fun searchingAndLoggingAFoodUsesTheEngine() {
        val app = model()
        val hit = app.search("basmati rice").first()
        val options = app.portionOptions(hit.token)
        val grams = options.indexOfFirst { it.label == "grams" }
        assertTrue(grams >= 0)
        val preview = app.previewFood(hit.token, 150.0, grams)
        assertTrue(preview.canLog && preview.nutrition.kcal > 0)
        assertTrue(app.logFood(hit.token, 150.0, grams, "LUNCH", app.todayIso(), force = false).logged)
        // Logging the same thing again moments later asks first.
        clock += 20_000
        val again = app.logFood(hit.token, 150.0, grams, "LUNCH", app.todayIso(), force = false)
        assertFalse(again.logged)
        assertNotNull(again.duplicateMessage)
    }

    @Test
    fun typedMealsAreReadOnTheDevice() {
        val app = model()
        val result = app.describe("2 rotis and a bowl of dal")
        assertTrue(result.items.size >= 2)
        assertTrue(result.total.kcal > 100)
        assertTrue(app.logDescribed("DINNER", app.todayIso(), force = false).logged)
        assertTrue(app.day(app.todayIso()).consumed.kcal > 100)
    }

    @Test
    fun deletingAnEntryCanBeUndone() {
        val app = model()
        app.quickAdd("Snack", 200.0, 0.0, 0.0, 0.0, "SNACK", app.todayIso())
        val id = app.day(app.todayIso()).meals.first { it.mealId == "SNACK" }.entries.single().id
        assertTrue(app.deleteEntry(id))
        assertEquals(0.0, app.day(app.todayIso()).consumed.kcal, 1e-9)
        assertTrue(app.undoDelete())
        assertEquals(200.0, app.day(app.todayIso()).consumed.kcal, 1e-9)
    }

    @Test
    fun recipesSearchRateAndLog() {
        val app = model()
        val all = app.searchRecipes("", null, null, null, null, emptyList(), 0, 0, false, false, false)
        assertTrue(all.dishes.isNotEmpty() && all.total >= all.dishes.size)
        val butter = app.searchRecipes("butter chicken", null, null, null, null, emptyList(), 0, 0, false, false, false).dishes.first()
        assertEquals(2, butter.versionCount)
        app.rateRecipe(butter.id, 5)
        val detail = assertNotNull(app.recipe(butter.id, 4))
        assertEquals(5, detail.rating)
        assertTrue(detail.steps.size >= 6 && detail.ingredients.isNotEmpty())
        assertEquals(2, detail.versions.size)
        assertTrue(app.logRecipe(butter.id, 4, 1.0, "DINNER", app.todayIso(), force = false).logged)
        assertEquals(detail.perServing.kcal, app.day(app.todayIso()).consumed.kcal, 1.0)
        val vegan = app.searchRecipes("", null, null, null, null, listOf("VEGAN"), 0, 0, false, false, false)
        assertTrue(vegan.dishes.all { it.dietLabel == "Vegan" })
    }

    @Test
    fun weightsTargetsAndSettings() {
        val app = model()
        assertNotNull(app.suggestTargets()) // no weight yet
        assertNull(app.logWeight(82.0, app.todayIso()))
        assertEquals("82 kg", app.weights().latestText)
        assertNull(app.updateProfile("MALE", 1990, 178.0, "MODERATE", "LOSE"))
        assertNull(app.suggestTargets())
        val s = app.settings()
        assertTrue((s.targetKcal ?: 0.0) > 1200)
        app.setWeightUnit("LB")
        assertTrue(app.weights().latestText!!.endsWith("lb"))
    }

    @Test
    fun demoPhotoScanIsLabelledSimulatedAndLogs() = runTest {
        val app = model()
        val scan = app.analyzePhoto(ByteArray(10), 100, 100, demoScenario = null)
        assertNull(scan.error)
        assertTrue(scan.simulated)
        assertTrue(scan.items.isNotEmpty())
        val first = scan.items.first()
        val edited = app.setScanItemGrams(first.id, first.grams * 2)
        assertTrue(edited.total.kcal > scan.total.kcal)
        assertTrue(app.logScan("LUNCH", app.todayIso()).logged)
    }

    @Test
    fun deleteEverythingClearsFilesAndSecrets() {
        val app = model()
        app.quickAdd("Snack", 200.0, 0.0, 0.0, 0.0, "SNACK", app.todayIso())
        app.setApiKey("test-key-not-real")
        app.deleteEverything()
        assertTrue(storage.names().isEmpty())
        assertFalse(app.settings().hasApiKey)
        assertEquals(0.0, model().day(app.todayIso()).consumed.kcal, 1e-9)
    }
}
