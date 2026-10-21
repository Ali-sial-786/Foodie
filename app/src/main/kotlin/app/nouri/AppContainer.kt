package app.nouri

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.nouri.ai.ChatGptSignIn
import app.nouri.core.NouriCore
import app.nouri.core.ai.AiProvider
import app.nouri.core.ai.AiService
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.mock.MockAiProvider
import app.nouri.core.ai.openai.AccountInfo
import app.nouri.core.ai.openai.BearerTokenSource
import app.nouri.core.ai.openai.OpenAiProvider
import app.nouri.core.ai.openai.ResponsesClient
import app.nouri.core.ai.openai.ResponsesConfig
import app.nouri.core.auth.Clock
import app.nouri.core.auth.SecureRandomSource
import app.nouri.core.auth.SiwcAuthManager
import app.nouri.core.recommend.PreparedRecipe
import app.nouri.core.units.MeasurementSystem
import app.nouri.data.db.NouriDatabase
import app.nouri.data.prefs.SettingsRepository
import app.nouri.data.repo.BarcodeRepository
import app.nouri.data.repo.DiaryRepository
import app.nouri.data.repo.OpenFoodFactsService
import app.nouri.data.repo.PantryRepository
import app.nouri.data.repo.RecipeLibraryAssets
import app.nouri.data.repo.RecipeRepository
import app.nouri.data.repo.ScanRepository
import app.nouri.data.repo.UsdaAssetFoods
import app.nouri.data.repo.UserFoodRepository
import app.nouri.data.repo.WeightRepository
import app.nouri.data.secure.AndroidSiwcStore
import app.nouri.data.secure.ApiKeyStore
import app.nouri.data.secure.EncryptedBlobStore
import app.nouri.data.secure.KeystoreCipher
import app.nouri.data.secure.SecretCipher
import app.nouri.image.ImageProcessor
import app.nouri.ui.UserMessages
import app.nouri.ui.recipes.RecipeDrafts
import app.nouri.ui.recipes.RecipeImportInbox
import app.nouri.ui.scan.ScanSessions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Manual dependency injection (see DECISIONS.md): every dependency is created
 * here, once, with compile-time checked wiring and no code generation.
 */
open class AppContainer(
    val context: Context,
    val database: NouriDatabase = NouriDatabase.build(context),
    val settings: SettingsRepository = SettingsRepository(context),
    private val secretCipher: () -> SecretCipher = { KeystoreCipher() },
    /** Simulated-AI latency; tests use 0. */
    mockLatencyMs: Long = 700,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun newId(): String = UUID.randomUUID().toString()

    val http: HttpClient by lazy { HttpClient(OkHttp) { expectSuccess = false } }

    // ---- data
    val diary by lazy { DiaryRepository(database.diaryDao()) }
    val weights by lazy { WeightRepository(database.weightDao()) }
    val recipeLibrary by lazy { RecipeLibraryAssets(context) }
    val recipes by lazy { RecipeRepository(database.recipeDao(), recipeLibrary) }
    val pantry by lazy { PantryRepository(database.pantryDao()) }
    val scans by lazy { ScanRepository(database.scanDao()) }
    val userFoods by lazy { UserFoodRepository(database.userFoodDao(), appScope) }
    val usdaFoods by lazy { UsdaAssetFoods(context) }
    val images by lazy { ImageProcessor(context) }
    val scanSessions = ScanSessions()
    val messages = UserMessages()
    val recipeInbox = RecipeImportInbox()
    val recipeDrafts = RecipeDrafts()

    private val secrets by lazy { EncryptedBlobStore(File(context.noBackupFilesDir, "secrets"), secretCipher()) }
    val apiKeys by lazy { ApiKeyStore(secrets) }

    // ---- shared engine (one per measurement system so cup sizes follow the user's setting)
    private val cores = HashMap<MeasurementSystem, NouriCore>()
    fun core(system: MeasurementSystem = MeasurementSystem.METRIC): NouriCore = synchronized(cores) {
        cores.getOrPut(system) { NouriCore(userFoods = userFoods, extendedFoods = usdaFoods, measurementSystem = system, newId = ::newId) }
    }

    /**
     * Every recipe with its ingredient analysis and per-serving nutrition. Each recipe is
     * prepared once and reused until it (or the measurement system) changes, so ranking,
     * search and filters stay fast with hundreds of recipes.
     */
    val preparedRecipes: Flow<List<PreparedRecipe>> by lazy {
        val cache = HashMap<String, PreparedRecipe>()
        var cachedFor: MeasurementSystem? = null
        combine(recipes.all, settings.settings.map { it.preferences.measurementSystem }.distinctUntilChanged()) { list, system ->
            if (system != cachedFor) {
                cache.clear()
                cachedFor = system
            }
            val ranker = core(system).ranker
            list.map { r -> cache[r.id]?.takeIf { it.recipe === r || it.recipe == r } ?: ranker.prepare(r).also { cache[r.id] = it } }
        }.flowOn(Dispatchers.Default).shareIn(appScope, SharingStarted.WhileSubscribed(60_000), replay = 1)
    }

    val barcodes by lazy {
        val off = OpenFoodFactsService(http)
        BarcodeRepository(userFoods, off, onlineAllowed = { settings.current().privacy.barcodeOnlineLookup }, search = off::search)
    }

    // ---- AI
    private val random = SecureRandomSource { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } }
    val siwc by lazy { SiwcAuthManager(http, AndroidSiwcStore(secrets), random, Clock { System.currentTimeMillis() }) }
    val chatGptSignIn by lazy {
        ChatGptSignIn(siwc) {
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        }
    }

    private val planClient by lazy { ResponsesClient(http, siwc, ResponsesConfig()) }
    private val apiClient by lazy { ResponsesClient(http, BearerTokenSource { apiKeys.get() }, ResponsesConfig()) }

    @Volatile private var discoveredPlanModel: String? = null
    @Volatile private var discoveredApiModel: String? = null

    /** The user's override, else the best match from the models this account can actually use. */
    private suspend fun planModel(): String {
        settings.current().ai.planModel?.let { return it }
        discoveredPlanModel?.let { return it }
        val available = planClient.listModels().getOrNull().orEmpty()
        // The plan catalogue lists only models offered to this account, so its first entry is a safe default.
        val chosen = OpenAiProvider.chooseModel(available) ?: available.firstOrNull() ?: OpenAiProvider.DEFAULT_MODEL_PREFERENCE.first()
        return chosen.also { if (available.isNotEmpty()) discoveredPlanModel = it }
    }

    private suspend fun apiModel(): String {
        settings.current().ai.apiModel?.let { return it }
        discoveredApiModel?.let { return it }
        val available = apiClient.listModels().getOrNull().orEmpty()
        return (OpenAiProvider.chooseModel(available) ?: OpenAiProvider.DEFAULT_MODEL_PREFERENCE.first()).also { if (available.isNotEmpty()) discoveredApiModel = it }
    }

    fun forgetDiscoveredModels() {
        discoveredPlanModel = null
        discoveredApiModel = null
    }

    val mockProvider = MockAiProvider(latencyMs = mockLatencyMs)

    val providers: Map<ProviderKind, AiProvider> by lazy {
        mapOf(
            ProviderKind.CHATGPT_PLAN to OpenAiProvider(ProviderKind.CHATGPT_PLAN, planClient, ::planModel) { siwc.account() },
            ProviderKind.OPENAI_API_KEY to OpenAiProvider(ProviderKind.OPENAI_API_KEY, apiClient, ::apiModel) {
                val key = apiKeys.get()
                AccountInfo(label = key?.let { "Key ending ${it.takeLast(4)}" }, connected = key != null)
            },
            ProviderKind.MOCK to mockProvider,
        )
    }

    val ai by lazy { AiService(providers) { settings.current().aiPreferences } }

    /** Starts background loading that the UI benefits from but never waits on. */
    fun warmUp() {
        appScope.launch { runCatching { usdaFoods.load() } }
        // Opens the database and fills the user-food search index off the main thread.
        appScope.launch { runCatching { userFoods.foods.first() } }
    }
}
