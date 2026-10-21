package app.nouri.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.nouri.core.ai.AiPreferences
import app.nouri.core.ai.ProviderKind
import app.nouri.core.diary.DailyTargets
import app.nouri.core.model.FoodPreferences
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.WeightUnit
import app.nouri.core.scan.CorrectionMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What happens to food photos after analysis. Default: processed copies are deleted once analysed. */
@Serializable
enum class ImageRetention(val label: String) {
    DELETE_AFTER_ANALYSIS("Delete after analysis"),
    KEEP_30_DAYS("Keep for 30 days"),
    KEEP_UNTIL_DELETED("Keep until I delete them"),
}

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

@Serializable
data class AiSettings(
    val selected: ProviderKind = ProviderKind.MOCK,
    val photoConsent: Boolean = false,
    val allowApiKeyFallback: Boolean = false,
    /** Model override; null = choose automatically from the account's model list. */
    val planModel: String? = null,
    val apiModel: String? = null,
)

@Serializable
data class PrivacySettings(
    val imageRetention: ImageRetention = ImageRetention.DELETE_AFTER_ANALYSIS,
    /** Barcode numbers and product search words (only those) are sent to Open Food Facts. */
    val barcodeOnlineLookup: Boolean = true,
    /** Favourite cuisines and confirmed dish names are included in AI prompts to improve accuracy. */
    val personaliseAiPrompts: Boolean = true,
)

@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val profile: BodyProfile? = null,
    val targets: NutritionTargets? = null,
    val preferences: FoodPreferences = FoodPreferences(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Minutes after midnight when a new diary day starts (0 = midnight). */
    val dayStartMinute: Int = 0,
    val ai: AiSettings = AiSettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val correctionMemory: CorrectionMemory = CorrectionMemory(),
    val expiryReminders: Boolean = false,
    val keepScreenOnInCookMode: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Demo scenario for the simulated provider; null = derive from the photo. */
    val demoScenario: String? = null,
) {
    val dailyTargets: DailyTargets?
        get() = targets?.let { DailyTargets(it.energyKcal, it.proteinG, it.carbsG, it.fatG, it.fiberG) }

    val aiPreferences: AiPreferences
        get() = AiPreferences(ai.selected, ai.photoConsent, ai.allowApiKeyFallback)
}

/** All app settings as one JSON document, updated atomically. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    /** One instance per process (the app container owns it); DataStore forbids two on the same file. */
    constructor(context: Context) : this(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("nouri_settings") })

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings_v1")

    val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { decode(it[key]) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> prefs[key] = json.encodeToString(AppSettings.serializer(), transform(decode(prefs[key]))) }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun decode(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
}
