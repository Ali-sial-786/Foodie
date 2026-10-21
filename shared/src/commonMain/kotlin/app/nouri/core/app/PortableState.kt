package app.nouri.core.app

import app.nouri.core.ai.ProviderKind
import app.nouri.core.auth.SiwcRegistration
import app.nouri.core.auth.SiwcSession
import app.nouri.core.auth.SiwcStore
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.model.FoodPreferences
import app.nouri.core.pantry.PantryItem
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightUnit
import app.nouri.core.recipes.Recipe
import app.nouri.core.recommend.RecipeUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Text files the platform keeps for the app. On iOS: the app's Application Support
 * folder, excluded from iCloud backup. Writes should be atomic.
 */
interface TextStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    fun names(): List<String>
}

/** Small secrets (API key, sign-in tokens) in the platform's secure store: the iOS Keychain, this device only. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

class MemoryTextStorage : TextStorage {
    private val files = LinkedHashMap<String, String>()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun delete(name: String) { files.remove(name) }
    override fun names() = files.keys.toList()
}

class MemorySecretStore : SecretStore {
    private val values = HashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/** Everything the user chooses, as one JSON document (no secrets: those live in [SecretStore]). */
@Serializable
data class PortableSettings(
    val onboarded: Boolean = false,
    val profile: BodyProfile = BodyProfile(),
    val targets: NutritionTargets? = null,
    val preferences: FoodPreferences = FoodPreferences(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Minutes after midnight when a new diary day starts (night shifts, suhoor). */
    val dayStartMinute: Int = 0,
    /** Demo until the user picks a real provider. */
    val provider: ProviderKind = ProviderKind.MOCK,
    val photoConsent: Boolean = false,
    /** Explicit opt-in only; never used after a plan usage limit. */
    val allowApiKeyFallback: Boolean = false,
    val planModel: String? = null,
    val apiModel: String? = null,
    /** Open Food Facts barcode and product-name lookups. */
    val onlineProductLookup: Boolean = true,
)

/** Records other than the diary, which is stored by month. */
@Serializable
data class PortableData(
    val weights: List<WeightEntry> = emptyList(),
    val savedRecipes: List<Recipe> = emptyList(),
    val usage: Map<String, RecipeUsage> = emptyMap(),
    val userFoods: List<FoodReference> = emptyList(),
    val pantry: List<PantryItem> = emptyList(),
)

/** File names and encoding of the portable store. */
internal object PortableFiles {
    const val SETTINGS = "settings.json"
    const val DATA = "data.json"
    const val DIARY_PREFIX = "diary-"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    val entries = ListSerializer(DiaryEntry.serializer())

    /** One file per calendar month ("diary-2026-10.json") keeps each save small. */
    fun diaryFile(month: String) = "$DIARY_PREFIX$month.json"
}

/** Sign in with ChatGPT tokens in the platform's secure store, as JSON. Never backed up. */
class SecretSiwcStore(private val secrets: SecretStore) : SiwcStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun hostId(): String? = secrets.get(HOST_ID)
    override suspend fun saveHostId(hostId: String) = secrets.set(HOST_ID, hostId)

    override suspend fun session(): SiwcSession? =
        secrets.get(SESSION)?.let { runCatching { json.decodeFromString(SiwcSession.serializer(), it) }.getOrNull() }

    override suspend fun saveSession(session: SiwcSession) = secrets.set(SESSION, json.encodeToString(SiwcSession.serializer(), session))
    override suspend fun clearSession() = secrets.remove(SESSION)

    override suspend fun registrations(): List<SiwcRegistration> =
        secrets.get(REGISTRATIONS)?.let { runCatching { json.decodeFromString(ListSerializer(SiwcRegistration.serializer()), it) }.getOrNull() }.orEmpty()

    override suspend fun saveRegistration(registration: SiwcRegistration) {
        val updated = registrations().filter { it.accountSubject != registration.accountSubject } + registration
        secrets.set(REGISTRATIONS, json.encodeToString(ListSerializer(SiwcRegistration.serializer()), updated))
    }

    companion object {
        const val HOST_ID = "siwc.hostId"
        const val SESSION = "siwc.session"
        const val REGISTRATIONS = "siwc.registrations"
    }
}
