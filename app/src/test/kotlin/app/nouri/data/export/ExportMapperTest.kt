package app.nouri.data.export

import app.nouri.core.ai.ProviderKind
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.export.DiaryCsvExporter
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.Precision
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.data.prefs.AiSettings
import app.nouri.data.prefs.AppSettings
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMapperTest {
    private fun entry(id: String, date: LocalDate, atMs: Long, zone: String = "UTC", meal: MealType = MealType.BREAKFAST) = DiaryEntry(
        id = id,
        date = date,
        mealType = meal,
        loggedAtEpochMs = atMs,
        timeZoneId = zone,
        name = "Paratha",
        quantity = Quantity(2.0, QuantityUnit.PIECE),
        snapshot = NutritionSnapshot(
            nutrients = Nutrients(energyKcal = 520.0, proteinG = 10.0, carbsG = 70.0, fatG = 22.0, fiberG = 4.0),
            grams = 160.0,
            provenance = Provenance(NutritionSource.REFERENCE_RECIPE, "dish:paratha"),
            confidence = ConfidenceLevel.MODERATE,
            precision = Precision.ESTIMATED,
            capturedAtEpochMs = atMs,
        ),
        origin = EntryOrigin.SEARCH,
        idempotencyKey = "k$id",
    )

    @Test
    fun rowKeepsValuesSourceAndLocalTime() {
        // 2026-10-20 08:05 UTC is 13:05 in Karachi (UTC+5).
        val row = ExportMapper.row(entry("a", LocalDate(2026, 10, 20), 1_792_483_500_000, zone = "Asia/Karachi"))
        assertEquals("13:05", row.time)
        assertEquals("Breakfast", row.meal)
        assertEquals(2.0, row.amount!!, 0.0)
        assertEquals("piece", row.unit)
        assertEquals(160.0, row.grams!!, 0.0)
        assertEquals(520.0, row.energyKcal, 0.0)
        assertEquals(4.0, row.fiberG!!, 0.0)
        assertNull("unknown sugar stays unknown, not zero", row.sugarG)
        assertEquals(NutritionSource.REFERENCE_RECIPE.label, row.source)
        assertEquals(ConfidenceLevel.MODERATE.label, row.confidence)
    }

    @Test
    fun rowsAreInDateThenTimeOrder() {
        val later = entry("late", LocalDate(2026, 10, 20), 1_792_483_500_000)
        val earlierSameDay = entry("early", LocalDate(2026, 10, 20), 1_792_483_500_000 - 3_600_000)
        val previousDay = entry("prev", LocalDate(2026, 10, 19), 1_792_445_400_000, meal = MealType.DINNER)
        val rows = ExportMapper.diaryRows(listOf(later, previousDay, earlierSameDay))
        assertEquals(listOf("21:30", "07:05", "08:05"), rows.map { it.time })
        val csv = DiaryCsvExporter.export(rows)
        assertTrue(csv.lines().first().startsWith("date,time,meal,food"))
        assertEquals(4, csv.trim().lines().size)
    }

    @Test
    fun backupDropsDeviceSpecificAiSettings() {
        val settings = AppSettings(onboardingComplete = true, ai = AiSettings(selected = ProviderKind.CHATGPT_PLAN, photoConsent = true, planModel = "custom-model"))
        val json = BackupExporter.encode(
            NouriBackup(
                exportedAtEpochMs = 1L, settings = settings, diary = listOf(entry("a", LocalDate(2026, 10, 20), 1_792_483_500_000)),
                weights = emptyList(), measurements = emptyList(), recipes = emptyList(), pantry = emptyList(), grocery = emptyList(),
            ),
        )
        assertTrue(json.contains(NouriBackup.FORMAT))
        assertTrue(json.contains("Paratha"))
        assertFalse(json.contains("custom-model"))
        assertFalse(json.contains("CHATGPT_PLAN"))
    }
}
