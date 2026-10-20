package app.nouri.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.testing.testContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryRepositoryTest {
    private val c = testContainer()
    private val day = LocalDate(2026, 10, 20)

    private fun entry(id: String, name: String = "Dal", kcal: Double = 230.0, at: Long = 1_000_000L, key: String = "k-$id") = DiaryEntry(
        id = id, date = day, mealType = MealType.LUNCH, loggedAtEpochMs = at, timeZoneId = "UTC", name = name,
        snapshot = NutritionSnapshot(Nutrients(kcal, 12.0, 30.0, 6.0), 200.0, Provenance(NutritionSource.VERIFIED_DATABASE), ConfidenceLevel.HIGHER, Precision.CALCULATED, capturedAtEpochMs = at),
        origin = EntryOrigin.SEARCH, idempotencyKey = key,
    )

    @Test
    fun retriesWithTheSameKeyAreNotStoredTwice() = runBlocking {
        assertTrue(c.diary.log(listOf(entry("a"))) is LogResult.Logged)
        assertEquals(LogResult.AlreadyLogged, c.diary.log(listOf(entry("b", key = "k-a"))))
        assertEquals(1, c.diary.observeDay(day).first().size)
    }

    @Test
    fun accidentalDoubleTapIsReportedUnlessForced() = runBlocking {
        c.diary.log(listOf(entry("a")))
        val second = c.diary.log(listOf(entry("b", at = 1_060_000L)))
        assertTrue(second is LogResult.PossibleDuplicate)
        assertEquals(1, c.diary.observeDay(day).first().size)
        assertTrue(c.diary.log(listOf(entry("b", at = 1_060_000L)), force = true) is LogResult.Logged)
        assertEquals(2, c.diary.observeDay(day).first().size)
    }

    @Test
    fun deleteIsSoftAndCanBeUndone() = runBlocking {
        c.diary.log(listOf(entry("a")))
        c.diary.delete(listOf("a"))
        assertTrue(c.diary.observeDay(day).first().isEmpty())
        c.diary.restore(listOf("a"))
        assertEquals(listOf("a"), c.diary.observeDay(day).first().map { it.id })
    }

    @Test
    fun snapshotsSurviveStorageUnchanged() = runBlocking {
        val original = entry("a")
        c.diary.log(listOf(original))
        val stored = c.diary.get("a")!!
        assertEquals(original.snapshot, stored.snapshot)
        assertEquals(original.date, stored.date)
    }

    @Test
    fun copyingADayCreatesNewEntriesWithFrozenNutrition() = runBlocking {
        c.diary.log(listOf(entry("a"), entry("b", name = "Roti", kcal = 120.0, at = 2_000_000L)), force = true)
        val next = LocalDate(2026, 10, 21)
        val result = c.diary.copyDay(day, next, "UTC") { java.util.UUID.randomUUID().toString() }
        assertTrue(result is LogResult.Logged)
        val copied = c.diary.observeDay(next).first()
        assertEquals(setOf("Dal", "Roti"), copied.map { it.name }.toSet())
        assertEquals(350.0, copied.sumOf { it.nutrients.energyKcal }, 1e-9)
        assertTrue(copied.all { it.origin == EntryOrigin.COPY })
    }

    @Test
    fun savedUserFoodsAreSearchableImmediately() = runBlocking {
        val food = app.nouri.core.fooddb.FoodReference(
            id = "label:123", name = "Test granola", per100g = Nutrients(450.0, 10.0, 60.0, 18.0),
            source = NutritionSource.MANUFACTURER_LABEL, quality = app.nouri.core.model.DataQuality.HIGH,
        )
        c.userFoods.save(food, barcode = "0012345678905")
        assertEquals("Test granola", c.userFoods.get("label:123")?.name)
        assertEquals("label:123", c.userFoods.byBarcode("0012345678905")?.id)
    }
}
