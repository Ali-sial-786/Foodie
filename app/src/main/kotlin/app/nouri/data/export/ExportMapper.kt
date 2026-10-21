package app.nouri.data.export

import app.nouri.core.diary.DiaryEntry
import app.nouri.core.export.DiaryExportRow
import app.nouri.core.export.DiaryExportRows
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.pantry.PantryItem
import app.nouri.core.progress.BodyMeasurement
import app.nouri.core.progress.WeightEntry
import app.nouri.core.recipes.Recipe
import app.nouri.data.prefs.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ExportMapper {
    /** Diary rows in date and time order, with the source and confidence of every value. */
    fun diaryRows(entries: List<DiaryEntry>): List<DiaryExportRow> = DiaryExportRows.from(entries)

    fun row(e: DiaryEntry): DiaryExportRow = DiaryExportRows.row(e)
}

/**
 * Everything the user created, in one JSON document. Secrets (sign-in tokens, API
 * keys) are never included, and the AI model overrides are dropped too.
 */
@Serializable
data class NouriBackup(
    val format: String = FORMAT,
    val exportedAtEpochMs: Long,
    val settings: AppSettings,
    val diary: List<DiaryEntry>,
    val weights: List<WeightEntry>,
    val measurements: List<BodyMeasurement>,
    val recipes: List<Recipe>,
    val pantry: List<PantryItem>,
    val grocery: List<GroceryItem>,
) {
    companion object {
        const val FORMAT = "nouri-backup-v1"
    }
}

object BackupExporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(backup: NouriBackup): String = json.encodeToString(NouriBackup.serializer(), backup.copy(settings = backup.settings.withoutDeviceSpecifics()))

    /** Settings carry no secrets; provider choice and consent are device decisions, so they're reset in the copy. */
    private fun AppSettings.withoutDeviceSpecifics(): AppSettings = copy(ai = app.nouri.data.prefs.AiSettings())
}
