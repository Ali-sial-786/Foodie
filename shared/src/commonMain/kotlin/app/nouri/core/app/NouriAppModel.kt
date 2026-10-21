package app.nouri.core.app

import app.nouri.core.NouriCore
import app.nouri.core.FoodSearchResult
import app.nouri.core.ai.AiError
import app.nouri.core.ai.AiPreferences
import app.nouri.core.ai.AiProvider
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.AiService
import app.nouri.core.ai.ImagePayload
import app.nouri.core.ai.ImageRole
import app.nouri.core.ai.MealImageRequest
import app.nouri.core.ai.MealTextRequest
import app.nouri.core.ai.ParsedMealText
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.mock.MockAiProvider
import app.nouri.core.ai.openai.AccountInfo
import app.nouri.core.ai.openai.BearerTokenSource
import app.nouri.core.ai.openai.OpenAiProvider
import app.nouri.core.ai.openai.ResponsesClient
import app.nouri.core.ai.openai.ResponsesConfig
import app.nouri.core.auth.Clock
import app.nouri.core.auth.PendingAuthorization
import app.nouri.core.auth.SecureRandomSource
import app.nouri.core.auth.SignInResult
import app.nouri.core.auth.SiwcAuthManager
import app.nouri.core.barcode.BarcodeLookupResult
import app.nouri.core.barcode.Gtin
import app.nouri.core.barcode.OpenFoodFactsMapper
import app.nouri.core.diary.DailyTargets
import app.nouri.core.diary.DiaryCalculator
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.DiaryDay
import app.nouri.core.diary.DuplicateCheck
import app.nouri.core.diary.DuplicateGuard
import app.nouri.core.diary.EntryOrigin
import app.nouri.core.diary.MealType
import app.nouri.core.diary.NutritionSnapshot
import app.nouri.core.export.DiaryCsvExporter
import app.nouri.core.export.DiaryExportRows
import app.nouri.core.fooddb.CompositeFoodRepository
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.fooddb.InMemoryFoodRepository
import app.nouri.core.fooddb.UsdaSr28
import app.nouri.core.model.ConfidenceLevel
import app.nouri.core.model.DataQuality
import app.nouri.core.model.EstimateRange
import app.nouri.core.model.NutritionSource
import app.nouri.core.model.Nutrients
import app.nouri.core.model.Provenance
import app.nouri.core.nutrition.LineStatus
import app.nouri.core.nutrition.NutritionRounding
import app.nouri.core.nutrition.Precision
import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.DietGoal
import app.nouri.core.parse.MealTextParser
import app.nouri.core.parse.ResolvedTextItem
import app.nouri.core.parse.SavedRecipeNutrition
import app.nouri.core.parse.TextLogging
import app.nouri.core.progress.ActivityLevel
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.Sex
import app.nouri.core.progress.TargetCalculator
import app.nouri.core.progress.WeightEntry
import app.nouri.core.progress.WeightGoal
import app.nouri.core.progress.WeightTrend
import app.nouri.core.progress.WeightUnit
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Difficulty
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeAnalyzer
import app.nouri.core.recipes.RecipeCatalog
import app.nouri.core.recipes.RecipeCourse
import app.nouri.core.recipes.RecipeLibrary
import app.nouri.core.recipes.RecipeOrigin
import app.nouri.core.recipes.RecipeScaler
import app.nouri.core.recipes.WorldRegion
import app.nouri.core.recommend.DishResult
import app.nouri.core.recommend.PreparedRecipe
import app.nouri.core.recommend.RecipeFilters
import app.nouri.core.recommend.RecipeSearch
import app.nouri.core.recommend.RecipeSort
import app.nouri.core.recommend.RecipeUsage
import app.nouri.core.recommend.RecommendationContext
import app.nouri.core.scan.EstimationContext
import app.nouri.core.scan.MatchMethod
import app.nouri.core.scan.MealScanResult
import app.nouri.core.scan.ScanEdit
import app.nouri.core.scan.ScanLogging
import app.nouri.core.units.ConversionMethod
import app.nouri.core.units.MeasurementSystem
import app.nouri.core.units.PortionSize
import app.nouri.core.units.Quantity
import app.nouri.core.units.QuantityUnit
import app.nouri.core.units.formatAmount
import app.nouri.core.util.SystemTime
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The whole app behind one object, for platforms that bring their own UI (the iOS app):
 * the diary, search and logging, typed meals, photo scans, barcodes, recipes, progress
 * and settings, all on the shared engine. State lives in memory and is written through
 * [storage] after every change; secrets go to [secrets] only.
 *
 * Calls are made from the platform's main thread. Work that may take a while (AI,
 * network) is `suspend`. Nothing here throws: failures come back as messages in the
 * returned views.
 */
@OptIn(ExperimentalUuidApi::class)
class NouriAppModel(
    private val storage: TextStorage,
    private val secrets: SecretStore,
    private val http: HttpClient?,
    random: SecureRandomSource,
    /** The full USDA table as bundled with the app (`usda_sr28.tsv`); optional. */
    usdaTsv: String? = null,
    /** The bundled recipe library (`library.json`); optional. */
    libraryJson: String? = null,
    private val now: () -> Long = SystemTime::nowEpochMs,
    private val zone: () -> String = { SystemTime.zoneId() },
    private val userAgent: String = "Nouri (iOS)",
    mockLatencyMs: Long = 600,
) {
    private var settings: PortableSettings = load(PortableFiles.SETTINGS, PortableSettings.serializer()) ?: PortableSettings()
    private var data: PortableData = load(PortableFiles.DATA, PortableData.serializer()) ?: PortableData()
    private val diaryMonths = HashMap<String, MutableList<DiaryEntry>>()
    private var lastDeleted: List<DiaryEntry> = emptyList()

    private val newId: () -> String = { Uuid.random().toString() }

    private val userFoods = object : FoodRepository {
        private var index = InMemoryFoodRepository(data.userFoods)
        fun refresh() { index = InMemoryFoodRepository(data.userFoods) }
        override fun get(id: String) = index.get(id)
        override fun search(query: String, limit: Int) = index.search(query, limit)
    }
    private val usda: FoodRepository? = usdaTsv?.let { tsv ->
        val loaded by lazy { InMemoryFoodRepository(tsv.lineSequence().mapNotNull(UsdaSr28::parseTsvLine).toList()) }
        object : FoodRepository {
            override fun get(id: String) = loaded.get(id)
            override fun search(query: String, limit: Int) = loaded.search(query, limit)
        }
    }
    private val library: List<Recipe> = libraryJson?.let { runCatching { RecipeLibrary.decode(it) }.getOrNull() }.orEmpty()

    private var coreFor: Pair<MeasurementSystem, NouriCore>? = null
    private val core: NouriCore
        get() {
            val system = settings.preferences.measurementSystem
            coreFor?.takeIf { it.first == system }?.let { return it.second }
            return NouriCore(userFoods, usda, system, newId).also { coreFor = system to it; preparedCache.clear() }
        }

    // ------------------------------------------------------------------ AI providers

    private val siwc: SiwcAuthManager? = http?.let { SiwcAuthManager(it, SecretSiwcStore(secrets), random, Clock { now() }) }
    private var pendingSignIn: PendingAuthorization? = null
    private val mock = MockAiProvider(latencyMs = mockLatencyMs)
    private var discoveredPlanModel: String? = null
    private var discoveredApiModel: String? = null

    private val providers: Map<ProviderKind, AiProvider> = buildMap {
        put(ProviderKind.MOCK, mock)
        if (http != null && siwc != null) {
            val planClient = ResponsesClient(http, siwc, ResponsesConfig())
            val apiClient = ResponsesClient(http, BearerTokenSource { secrets.get(API_KEY) }, ResponsesConfig())
            put(ProviderKind.CHATGPT_PLAN, OpenAiProvider(ProviderKind.CHATGPT_PLAN, planClient, { model(planClient, plan = true) }) { siwc.account() })
            put(ProviderKind.OPENAI_API_KEY, OpenAiProvider(ProviderKind.OPENAI_API_KEY, apiClient, { model(apiClient, plan = false) }) {
                val key = secrets.get(API_KEY)
                AccountInfo(label = key?.let { "Key ending ${it.takeLast(4)}" }, connected = key != null)
            })
        }
    }
    private val ai = AiService(providers) {
        AiPreferences(settings.provider, settings.photoConsent, settings.allowApiKeyFallback)
    }

    private suspend fun model(client: ResponsesClient, plan: Boolean): String {
        (if (plan) settings.planModel else settings.apiModel)?.let { return it }
        (if (plan) discoveredPlanModel else discoveredApiModel)?.let { return it }
        val available = client.listModels().getOrNull().orEmpty()
        val chosen = OpenAiProvider.chooseModel(available) ?: (if (plan) available.firstOrNull() else null) ?: OpenAiProvider.DEFAULT_MODEL_PREFERENCE.first()
        if (available.isNotEmpty()) { if (plan) discoveredPlanModel = chosen else discoveredApiModel = chosen }
        return chosen
    }

    // ------------------------------------------------------------------ options

    val meals: List<OptionView> = MealType.entries.map { OptionView(it.name, it.label) }
    val courses: List<OptionView> = RecipeCourse.entries.map { OptionView(it.name, it.label) }
    val diets: List<OptionView> = RecipeSearch.DIET_FILTERS.map { OptionView(it.name, it.label) }
    val sorts: List<OptionView> = RecipeSort.entries.map { OptionView(it.name, it.label) }
    val sexes: List<OptionView> = Sex.entries.map { OptionView(it.name, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("Unspecified", "Prefer not to say")) }
    val activityLevels: List<OptionView> = ActivityLevel.entries.map { OptionView(it.name, it.label) }
    val goals: List<OptionView> = WeightGoal.entries.map { OptionView(it.name, it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }
    val allergens: List<OptionView> = Allergen.entries.map { OptionView(it.name, it.label) }

    fun suggestedMeal(): String = MealType.forMinuteOfDay(DiaryDay.minuteOfDay(now(), zone())).name

    fun todayIso(): String = today().toString()

    fun shiftDate(dateIso: String, days: Int): String = LocalDate.parse(dateIso).plus(days, DateTimeUnit.DAY).toString()

    // ------------------------------------------------------------------ diary

    fun day(dateIso: String): DayView {
        val date = LocalDate.parse(dateIso)
        val targets = settings.targets?.let { DailyTargets(it.energyKcal, it.proteinG, it.carbsG, it.fatG, it.fiberG) }
        val summary = DiaryCalculator.summarize(date, entriesFor(date), targets)
        return DayView(
            dateIso = dateIso,
            title = dayTitle(date),
            consumed = nutrition(summary.consumed),
            targetKcal = targets?.energyKcal,
            remainingKcal = summary.remaining?.energyKcal,
            progress = summary.progress(targets?.energyKcal, summary.consumed.energyKcal),
            meals = summary.meals.map { m -> MealView(m.mealType.name, m.mealType.label, m.entries.map(::entryView), nutrition(m.total)) },
            containsEstimates = summary.containsEstimates,
        )
    }

    fun deleteEntry(id: String): Boolean {
        val entry = allLoadedEntries().firstOrNull { it.id == id } ?: return false
        month(entry.date).removeAll { it.id == id }
        lastDeleted = listOf(entry)
        saveMonth(entry.date)
        return true
    }

    fun undoDelete(): Boolean {
        val restore = lastDeleted.ifEmpty { return false }
        restore.forEach { e -> month(e.date) += e; saveMonth(e.date) }
        lastDeleted = emptyList()
        return true
    }

    /** Copies an entry to now (same frozen nutrition). */
    fun relog(id: String, mealId: String, dateIso: String): LogOutcomeView {
        val entry = allLoadedEntries().firstOrNull { it.id == id } ?: return LogOutcomeView(false, null, "Entry not found", 0)
        val t = now()
        val copy = entry.copy(
            id = newId(), date = LocalDate.parse(dateIso), mealType = meal(mealId), loggedAtEpochMs = t, timeZoneId = zone(),
            origin = EntryOrigin.FAVOURITE, idempotencyKey = "relog:${entry.id}:${t / 1000}",
        )
        return log(listOf(copy), force = true)
    }

    fun recentFoods(limit: Int = 12): List<EntryView> =
        lastMonths(2).sortedByDescending { it.loggedAtEpochMs }.distinctBy { it.name.lowercase() }.take(limit).map(::entryView)

    // ------------------------------------------------------------------ search and log a food

    private val hits = HashMap<String, FoodSearchResult>()

    fun search(query: String): List<FoodHitView> {
        val results = core.search(query, 30)
        hits.clear()
        return results.mapIndexed { i, r ->
            val token = "hit$i:${r.food.id}"
            hits[token] = r
            FoodHitView(token, r.title, r.subtitle, "${r.food.per100g.energyKcal.roundToInt()} kcal / 100 g")
        }
    }

    fun portionOptions(token: String): List<PortionOptionView> {
        val food = hits[token]?.food ?: return emptyList()
        return unitOptions(food).mapIndexed { i, o -> PortionOptionView(i, o.label, o.defaultAmount) }
    }

    fun previewFood(token: String, amount: Double, optionIndex: Int): FoodPreviewView {
        val hit = hits[token] ?: return FoodPreviewView(nutrition(Nutrients.ZERO), null, false, null, listOf("Search again"), false)
        val calc = calculateFood(hit, amount, optionIndex)
        return FoodPreviewView(
            nutrition(calc.nutrients), calc.grams?.let { "${formatAmount(it)} g" }, calc.precision == Precision.ESTIMATED,
            calc.range?.let(::rangeText), calc.notes, calc.ok,
        )
    }

    fun logFood(token: String, amount: Double, optionIndex: Int, mealId: String, dateIso: String, force: Boolean): LogOutcomeView {
        val hit = hits[token] ?: return LogOutcomeView(false, null, "Search again", 0)
        val calc = calculateFood(hit, amount, optionIndex)
        if (!calc.ok) return LogOutcomeView(false, null, calc.notes.firstOrNull() ?: "This amount can't be calculated", 0)
        val t = now()
        val date = LocalDate.parse(dateIso)
        val entry = DiaryEntry(
            id = newId(), date = date, mealType = meal(mealId), loggedAtEpochMs = t, timeZoneId = zone(),
            name = hit.title, quantity = calc.quantity, foodRefId = hit.food.id,
            snapshot = NutritionSnapshot(calc.nutrients, calc.grams, hit.food.provenance(), calc.confidence, calc.precision, calc.range, t, calc.notes),
            origin = EntryOrigin.SEARCH, idempotencyKey = "food:${hit.food.id}:$date:$mealId:${t / 1000}",
        )
        return log(listOf(entry), force)
    }

    fun quickAdd(name: String, kcal: Double, proteinG: Double, carbsG: Double, fatG: Double, mealId: String, dateIso: String): LogOutcomeView {
        if (kcal < 0 || kcal > 10_000) return LogOutcomeView(false, null, "Enter calories between 0 and 10,000", 0)
        val t = now()
        val entry = DiaryEntry(
            id = newId(), date = LocalDate.parse(dateIso), mealType = meal(mealId), loggedAtEpochMs = t, timeZoneId = zone(),
            name = name.ifBlank { "Quick add" },
            snapshot = NutritionSnapshot(
                Nutrients(kcal, proteinG, carbsG, fatG), null, Provenance(NutritionSource.USER_ENTERED, detail = "Entered by you"),
                ConfidenceLevel.HIGHER, Precision.MEASURED, capturedAtEpochMs = t,
            ),
            origin = EntryOrigin.QUICK_ADD, idempotencyKey = "quick:${t / 1000}:$name:$kcal",
        )
        return log(listOf(entry), force = true)
    }

    // ------------------------------------------------------------------ typed meals

    private var described: List<ResolvedTextItem> = emptyList()
    private var describedKey: String = ""

    /** Reads "2 rotis and dal" on the device (no AI). */
    fun describe(text: String): DescribeResultView {
        described = resolver().resolve(MealTextParser.parse(text))
        describedKey = "text:${text.trim().lowercase()}:${now() / 60_000}"
        return describeView(usedAi = false, simulated = false, error = null)
    }

    /** Interprets a typed meal with the selected AI provider; the engine still calculates. */
    suspend fun describeWithAi(text: String): DescribeResultView {
        val recent = data.savedRecipes.map { it.name }.take(20)
        return when (val r = ai.parseMealText(MealTextRequest(text, recent))) {
            is AiResult.Success<ParsedMealText> -> {
                described = resolver().resolve(r.value)
                describedKey = "ai:${text.trim().lowercase()}:${now() / 60_000}"
                describeView(usedAi = true, simulated = r.meta.simulated, error = null)
            }
            is AiResult.Failure -> describeView(usedAi = false, simulated = false, error = r.error.message)
        }
    }

    fun logDescribed(mealId: String, dateIso: String, force: Boolean): LogOutcomeView {
        if (described.none { it.isResolved }) return LogOutcomeView(false, null, "Nothing recognised to log", 0)
        val entries = TextLogging.toDiaryEntries(described, LocalDate.parse(dateIso), meal(mealId), now(), zone(), describedKey, newId)
        return log(entries, force)
    }

    // ------------------------------------------------------------------ photo scan

    private var scan: MealScanResult? = null
    private var scanSimulated = false

    /**
     * Analyses one meal photo. [jpeg] must already be the processed copy: downscaled
     * (≤ 1536 px), re-encoded so location and camera metadata are gone. Real providers
     * need photo consent first ([setPhotoConsent]).
     */
    suspend fun analyzePhoto(jpeg: ByteArray, widthPx: Int, heightPx: Int, demoScenario: String?): ScanResultView {
        val image = ImagePayload(jpeg, "image/jpeg", widthPx, heightPx, ImageRole.TOP_DOWN)
        val request = MealImageRequest(
            images = listOf(image), preferredCuisines = settings.preferences.favouriteCuisines,
            demoScenario = demoScenario,
        )
        return when (val r = ai.analyzeMeal(request)) {
            is AiResult.Success -> {
                scanSimulated = r.meta.simulated
                scan = core.estimator.estimate(r.value, EstimationContext(newId(), imageCount = 1, simulated = r.meta.simulated))
                scanView(null)
            }
            is AiResult.Failure -> ScanResultView(emptyList(), nutrition(Nutrients.ZERO), "", "", "", false, emptyList(), r.error.message)
        }
    }

    fun setScanItemGrams(itemId: String, grams: Double): ScanResultView = editScan(ScanEdit.SetGrams(itemId, grams))

    fun setScanItemIncluded(itemId: String, included: Boolean): ScanResultView = editScan(ScanEdit.SetIncluded(itemId, included))

    fun logScan(mealId: String, dateIso: String): LogOutcomeView {
        val result = scan ?: return LogOutcomeView(false, null, "Scan again", 0)
        val entries = ScanLogging.toDiaryEntries(result, LocalDate.parse(dateIso), meal(mealId), now(), zone(), newId)
            .map { it.copy(origin = EntryOrigin.PHOTO_SCAN, scanId = result.scanId) }
        return log(entries, force = true).also { if (it.logged) scan = null }
    }

    // ------------------------------------------------------------------ barcodes

    /** Looks a barcode up on Open Food Facts (only the number is sent). Returns a search token on success. */
    suspend fun lookupBarcode(code: String): ProductView {
        if (!settings.onlineProductLookup) return ProductView(null, "Online lookup is off", "Turn it on in Settings › Privacy", null, "Online lookup is off")
        val client = http ?: return ProductView(null, "Offline", "", null, "No network client")
        val gtin = Gtin.normalize(code) ?: return ProductView(null, "Not a valid barcode", code, null, "Not a valid barcode")
        val result = try {
            val response = client.get("https://world.openfoodfacts.org/api/v2/product/$gtin.json") { header("User-Agent", userAgent) }
            if (response.status.isSuccess() || response.status.value == 404) OpenFoodFactsMapper.map(response.bodyAsText())
            else BarcodeLookupResult.Failed("Open Food Facts returned ${response.status.value}", retryable = true)
        } catch (e: Exception) {
            BarcodeLookupResult.Failed(e.message ?: "Network error", retryable = true)
        }
        return when (result) {
            is BarcodeLookupResult.Found -> {
                val food = result.product.food ?: return ProductView(null, result.product.name, "No nutrition data on Open Food Facts", null, "No nutrition data")
                keepUserFood(food)
                val token = "barcode:${food.id}"
                hits[token] = core.matcher.fromReference(food, MatchMethod.SAVED_FOOD).let { FoodSearchResult(it, food.name, result.product.brand ?: "Open Food Facts") }
                ProductView(token, food.name, listOfNotNull(result.product.brand, result.product.packageQuantityText).joinToString(" · "), "${food.per100g.energyKcal.roundToInt()} kcal / 100 g", null)
            }
            BarcodeLookupResult.NotFound -> ProductView(null, "Not found", "Open Food Facts has no product with this barcode", null, "Not found")
            is BarcodeLookupResult.Failed -> ProductView(null, "Lookup failed", result.reason, null, result.reason)
        }
    }

    // ------------------------------------------------------------------ recipes

    private val preparedCache = HashMap<String, PreparedRecipe>()

    private fun allRecipes(): List<Recipe> {
        val saved = data.savedRecipes
        val ids = saved.map { it.id }.toSet()
        return saved + RecipeCatalog.all.filter { it.id !in ids } + library.filter { it.id !in ids }
    }

    private fun prepared(): List<PreparedRecipe> {
        val ranker = core.ranker
        return allRecipes().map { r -> preparedCache[r.id]?.takeIf { it.recipe == r } ?: ranker.prepare(r).also { preparedCache[r.id] = it } }
    }

    /** Discover. Enum filters are passed as names; null or blank means "any". */
    fun searchRecipes(
        query: String, sortId: String?, regionId: String?, cuisineId: String?, courseId: String?, dietIds: List<String>,
        maxMinutes: Int, maxKcal: Int, easyOnly: Boolean, highProtein: Boolean, favouritesOnly: Boolean,
    ): RecipeSearchView {
        val filters = RecipeFilters(
            query = query,
            sort = sortId?.let { id -> RecipeSort.entries.firstOrNull { it.name == id } } ?: RecipeSort.BEST_MATCH,
            worldRegion = regionId?.let { id -> WorldRegion.entries.firstOrNull { it.name == id } },
            cuisineId = cuisineId?.ifBlank { null },
            course = courseId?.let { id -> RecipeCourse.entries.firstOrNull { it.name == id } },
            diets = dietIds.mapNotNull { id -> DietGoal.entries.firstOrNull { it.name == id } }.toSet(),
            maxMinutes = maxMinutes.takeIf { it > 0 },
            maxKcal = maxKcal.takeIf { it > 0 },
            easyOnly = easyOnly, highProtein = highProtein, favouritesOnly = favouritesOnly,
        )
        val all = prepared()
        val result = RecipeSearch.search(all, filters, data.usage, settings.preferences, core.ranker, data.pantry, today())
        return RecipeSearchView(
            dishes = result.results.map(::cardView),
            matchingRecipes = result.matchingRecipes,
            total = result.total,
            hiddenByPreferences = result.hiddenByPreferences.size,
            regions = result.cuisines.map { (region, list) ->
                RegionGroupView(region.name, region.label, list.sumOf { it.count }, list.map { CuisineOptionView(it.id, it.name, it.count) })
            },
        )
    }

    /** Top suggestions for the home screen, with today's remaining energy in mind. */
    fun recipeIdeas(limit: Int): List<RecipeCardView> {
        val remaining = settings.targets?.let { t -> t.energyKcal - DiaryCalculator.summarize(today(), entriesFor(today()), null).consumed.energyKcal }
        val ranked = core.ranker.rankPrepared(
            prepared(),
            RecommendationContext(
                preferences = settings.preferences, remainingKcal = remaining, maxMinutes = settings.preferences.usualMaxMinutes,
                favouriteRecipeIds = data.usage.filterValues { it.favourite }.keys,
                ratings = data.usage.mapNotNull { (id, u) -> u.rating?.let { id to it } }.toMap(),
            ),
        ).ranked
        return RecipeSearch.dishes(ranked, prepared()).take(limit).map(::cardView)
    }

    /** [servings] ≤ 0 means the recipe's own number of servings. */
    fun recipe(id: String, servings: Int): RecipeDetailView? {
        val base = allRecipes().firstOrNull { it.id == id } ?: return null
        val scaled = RecipeScaler.scale(base, if (servings <= 0) base.servings else servings.coerceIn(1, 100))
        val report = core.engine.calculateRecipe(scaled.recipe)
        val analysis = RecipeAnalyzer.analyze(base)
        val usage = data.usage[id]
        val versions = RecipeSearch.versionsOf(id, prepared())
        return RecipeDetailView(
            id = base.id, name = base.name, subtitle = recipeSubtitle(base), about = base.description,
            originLabel = base.origin.label, attribution = base.attribution, variantSummary = base.variantSummary,
            servings = scaled.recipe.servings, baseServings = base.servings,
            minutesText = minutes(base.totalMinutes), difficulty = base.difficulty.label, difficultyLevel = base.difficulty.ordinal + 1,
            perServing = nutrition(report.perServing),
            completenessText = "${(report.completeness * 100).roundToInt()}% of ingredients calculated",
            confidenceLabel = report.confidence.label,
            diets = analysis.diets.filter { it in RecipeSearch.DIET_FILTERS }.map { it.label },
            allergens = analysis.allergens.map { it.label },
            halalNotes = analysis.halalNotes,
            ingredients = scaled.recipe.ingredients.map { IngredientLineView(ingredientText(it), it.group, it.optional) },
            steps = scaled.recipe.steps.mapIndexed { i, s ->
                StepView(i + 1, s.text, listOfNotNull(s.timerSeconds?.let { "Timer ${minutes((it + 59) / 60)}" }, s.temperatureC?.let { "$it °C" }).joinToString(" · ").ifBlank { null })
            },
            equipment = base.equipment,
            safety = listOfNotNull(base.storage?.let { "Storage: $it" }, base.reheating?.let { "Reheating: $it" }) + base.foodSafety,
            notes = base.notes + report.warnings,
            scaleNotes = scaled.notes,
            rating = usage?.rating, favourite = usage?.favourite == true, cookedCount = usage?.cookedCount ?: 0,
            versions = if (versions.size > 1) versions.map(::versionView) else emptyList(),
        )
    }

    fun rateRecipe(id: String, stars: Int) {
        val rating = stars.takeIf { it in 1..5 }
        updateUsage(id) { it.copy(rating = rating) }
    }

    fun toggleFavourite(id: String) = updateUsage(id) { it.copy(favourite = !it.favourite) }

    fun markCooked(id: String) = updateUsage(id) { it.copy(cookedCount = it.cookedCount + 1, lastCookedAtEpochMs = now()) }

    /** Logs [portionServings] servings of the recipe as cooked for [servings]. */
    fun logRecipe(id: String, servings: Int, portionServings: Double, mealId: String, dateIso: String, force: Boolean): LogOutcomeView {
        val base = allRecipes().firstOrNull { it.id == id } ?: return LogOutcomeView(false, null, "Recipe not found", 0)
        if (portionServings <= 0) return LogOutcomeView(false, null, "Choose how much you ate", 0)
        val recipe = RecipeScaler.scale(base, servings.coerceIn(1, 100)).recipe
        val report = core.engine.calculateRecipe(recipe)
        val fraction = portionServings / recipe.servings
        val nutrients = report.forServings(portionServings)
        val estimated = report.confidence == ConfidenceLevel.LOW
        val reference = base.origin == RecipeOrigin.EDITORIAL_TRADITIONAL || base.origin == RecipeOrigin.EDITORIAL_VARIATION
        val t = now()
        val entry = DiaryEntry(
            id = newId(), date = LocalDate.parse(dateIso), mealType = meal(mealId), loggedAtEpochMs = t, timeZoneId = zone(),
            name = base.name, quantity = Quantity(portionServings, QuantityUnit.SERVING), recipeId = base.id, recipeFraction = fraction,
            snapshot = NutritionSnapshot(
                nutrients = nutrients,
                grams = report.cookedWeightGrams.takeIf { it > 0 }?.times(fraction),
                provenance = Provenance(
                    if (reference) NutritionSource.REFERENCE_RECIPE else NutritionSource.SAVED_RECIPE, "recipe:${base.id}",
                    "Calculated from ${recipe.ingredients.size} ingredients at ${recipe.servings} servings",
                ),
                confidence = report.confidence,
                precision = if (estimated) Precision.ESTIMATED else Precision.CALCULATED,
                energyRange = if (estimated) EstimateRange.around(nutrients.energyKcal, 0.2) else null,
                capturedAtEpochMs = t,
                notes = report.warnings,
            ),
            origin = EntryOrigin.RECIPE, idempotencyKey = "recipe:${base.id}:$dateIso:$mealId:${t / 1000}",
        )
        return log(listOf(entry), force)
    }

    // ------------------------------------------------------------------ progress

    fun weights(): WeightView {
        val unit = settings.weightUnit
        val sorted = data.weights.sortedWith(compareBy({ it.date }, { it.minuteOfDay ?: 0 }))
        val points = WeightTrend.compute(sorted)
        return WeightView(
            unit = unit.symbol,
            entries = sorted.reversed().map { WeightEntryView(it.id, it.date.toString(), weightText(it.weightKg)) },
            points = points.map { WeightPointView(it.date.toString(), unit.fromKg(it.scaleKg), unit.fromKg(it.trendKg)) },
            latestText = sorted.lastOrNull()?.let { weightText(it.weightKg) },
            rateText = WeightTrend.ratePerWeek(points)?.let { r ->
                val v = unit.fromKg(r)
                "Trend: ${if (v > 0) "+" else if (v < 0) "−" else ""}${decimals(kotlin.math.abs(v), 2)} ${unit.symbol} per week"
            },
        )
    }

    fun logWeight(value: Double, dateIso: String): String? {
        val kg = settings.weightUnit.toKg(value)
        if (!kg.isFinite() || kg !in 20.0..400.0) return "Enter a weight between ${weightText(20.0)} and ${weightText(400.0)}"
        data = data.copy(weights = data.weights + WeightEntry(newId(), LocalDate.parse(dateIso), DiaryDay.minuteOfDay(now(), zone()), kg))
        saveData()
        return null
    }

    fun deleteWeight(id: String) {
        data = data.copy(weights = data.weights.filter { it.id != id })
        saveData()
    }

    /** Energy eaten on each of the last [days] days (oldest first), for a bar chart. */
    fun intake(days: Int): List<DayTotalView> {
        val end = today()
        val target = settings.targets?.energyKcal
        return (days - 1 downTo 0).map { back ->
            val date = end.minus(back, DateTimeUnit.DAY)
            val kcal = entriesFor(date).sumOf { it.nutrients.energyKcal }
            DayTotalView(date.toString(), date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, kcal, target)
        }
    }

    // ------------------------------------------------------------------ settings

    fun settings(): SettingsView {
        val s = settings
        return SettingsView(
            onboarded = s.onboarded, sex = s.profile.sex.name, birthYear = s.profile.birthYear, heightCm = s.profile.heightCm,
            activity = s.profile.activityLevel.name, goal = s.profile.goal.name, weightUnit = s.weightUnit.name,
            measurementSystem = s.preferences.measurementSystem.name,
            targetKcal = s.targets?.energyKcal, targetProteinG = s.targets?.proteinG, targetCarbsG = s.targets?.carbsG, targetFatG = s.targets?.fatG,
            targetsBasis = s.targets?.basis, targetsManual = s.targets?.manual == true,
            provider = s.provider.name, photoConsent = s.photoConsent, allowApiKeyFallback = s.allowApiKeyFallback,
            hasApiKey = secrets.get(API_KEY) != null, onlineProductLookup = s.onlineProductLookup, dayStartMinute = s.dayStartMinute,
            requiredDiets = s.preferences.requiredDiets.map { it.name }, allergies = s.preferences.allergies.map { it.name },
        )
    }

    fun completeOnboarding() = updateSettings { it.copy(onboarded = true) }

    /** Returns an error message, or null when saved. */
    fun updateProfile(sexId: String, birthYear: Int, heightCm: Double, activityId: String, goalId: String): String? {
        val profile = runCatching {
            settings.profile.copy(
                sex = Sex.entries.firstOrNull { it.name == sexId } ?: Sex.UNSPECIFIED,
                birthYear = birthYear.takeIf { it in 1900..today().year },
                heightCm = heightCm.takeIf { it in 50.0..250.0 },
                activityLevel = ActivityLevel.entries.firstOrNull { it.name == activityId } ?: ActivityLevel.LIGHT,
                goal = WeightGoal.entries.firstOrNull { it.name == goalId } ?: WeightGoal.MAINTAIN,
            )
        }.getOrElse { return it.message ?: "Check the profile" }
        updateSettings { it.copy(profile = profile) }
        return null
    }

    /** Suggests targets from the profile and latest weight; returns a reason when it can't. */
    fun suggestTargets(): String? {
        val weight = data.weights.maxWithOrNull(compareBy({ it.date }, { it.minuteOfDay ?: 0 }))?.weightKg
            ?: return "Log your weight first"
        val targets = TargetCalculator.suggest(settings.profile, weight, today(), settings.weightUnit)
            ?: return "Add your sex, birth year and height to get a suggestion"
        updateSettings { it.copy(targets = targets) }
        return null
    }

    fun setManualTargets(kcal: Double, proteinG: Double, carbsG: Double, fatG: Double): List<String> {
        if (kcal <= 0) return listOf("Enter a calorie target")
        val manual = TargetCalculator.manual(kcal, proteinG, carbsG, fatG)
        updateSettings { it.copy(targets = manual.targets) }
        return manual.warnings
    }

    fun clearTargets() = updateSettings { it.copy(targets = null) }

    fun setWeightUnit(unitId: String) = updateSettings { s -> s.copy(weightUnit = WeightUnit.entries.firstOrNull { it.name == unitId } ?: s.weightUnit) }

    fun setMeasurementSystem(systemId: String) = updateSettings { s ->
        s.copy(preferences = s.preferences.copy(measurementSystem = MeasurementSystem.entries.firstOrNull { it.name == systemId } ?: s.preferences.measurementSystem))
    }

    fun setRequiredDiets(dietIds: List<String>) = updateSettings { s ->
        s.copy(preferences = s.preferences.copy(requiredDiets = dietIds.mapNotNull { id -> DietGoal.entries.firstOrNull { it.name == id } }.toSet()))
    }

    fun setAllergies(allergenIds: List<String>) = updateSettings { s ->
        s.copy(preferences = s.preferences.copy(allergies = allergenIds.mapNotNull { id -> Allergen.entries.firstOrNull { it.name == id } }.toSet()))
    }

    fun setOnlineProductLookup(on: Boolean) = updateSettings { it.copy(onlineProductLookup = on) }

    // ------------------------------------------------------------------ AI settings

    fun selectProvider(providerId: String) = updateSettings { s -> s.copy(provider = ProviderKind.entries.firstOrNull { it.name == providerId && it in providers } ?: s.provider) }

    fun setPhotoConsent(granted: Boolean) = updateSettings { it.copy(photoConsent = granted) }

    /** The separately billed API key as a fallback for the plan; explicit opt-in only. */
    fun setApiKeyFallback(allowed: Boolean) = updateSettings { it.copy(allowApiKeyFallback = allowed) }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) secrets.remove(API_KEY) else secrets.set(API_KEY, trimmed)
        discoveredApiModel = null
    }

    suspend fun providerStatuses(): List<ProviderView> = providers.values.map { p ->
        val st = p.status()
        ProviderView(
            st.kind.name, st.kind.label, st.kind == settings.provider, st.connected, st.accountLabel, st.billing.name,
            st.isSimulated, st.restrictions,
        )
    }

    /**
     * Starts Sign in with ChatGPT with a loopback redirect on [port] (the platform listens on
     * 127.0.0.1). Returns the authorization URL to open, or null when unavailable.
     */
    suspend fun beginChatGptSignIn(port: Int): String? {
        val manager = siwc ?: return null
        val pending = runCatching { manager.begin("http://127.0.0.1:$port/auth/callback") }.getOrNull() ?: return null
        pendingSignIn = pending
        return pending.authorizationUrl
    }

    /** Finishes sign-in with the callback URL the loopback listener received. Returns null on success, else a message. */
    suspend fun completeChatGptSignIn(callbackUrl: String): String? {
        val manager = siwc ?: return "Sign-in is unavailable"
        val pending = pendingSignIn ?: return "Start sign-in again"
        return when (val r = manager.complete(pending, callbackUrl)) {
            is SignInResult.Success -> { pendingSignIn = null; discoveredPlanModel = null; selectProvider(ProviderKind.CHATGPT_PLAN.name); null }
            is SignInResult.SignedInWithoutPlanUsage -> { pendingSignIn = null; "Signed in, but plan usage wasn't granted, so AI requests won't use your ChatGPT plan." }
            is SignInResult.Cancelled -> { pendingSignIn = null; r.reason }
            is SignInResult.Failed -> { if (!r.unreachable) pendingSignIn = null; r.reason }
        }
    }

    suspend fun signOutChatGpt(): String {
        val manager = siwc ?: return "Not signed in"
        if (settings.provider == ProviderKind.CHATGPT_PLAN) updateSettings { it.copy(provider = ProviderKind.MOCK) }
        return manager.signOut().message
    }

    // ------------------------------------------------------------------ privacy

    fun exportCsv(): String = DiaryCsvExporter.export(DiaryExportRows.from(allEntries()))

    /** Deletes every record, setting and secret on this device. */
    fun deleteEverything() {
        storage.names().forEach(storage::delete)
        listOf(API_KEY, SecretSiwcStore.SESSION, SecretSiwcStore.REGISTRATIONS, SecretSiwcStore.HOST_ID).forEach(secrets::remove)
        settings = PortableSettings()
        data = PortableData()
        diaryMonths.clear()
        lastDeleted = emptyList()
        userFoods.refresh()
        preparedCache.clear()
    }

    val recipeCount: Int get() = allRecipes().size

    // ================================================================== internals

    private fun today(): LocalDate = DiaryDay.of(now(), zone(), settings.dayStartMinute)

    private fun meal(id: String): MealType = MealType.entries.firstOrNull { it.name == id } ?: MealType.forMinuteOfDay(DiaryDay.minuteOfDay(now(), zone()))

    private fun monthKey(date: LocalDate) = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"

    private fun month(date: LocalDate): MutableList<DiaryEntry> = diaryMonths.getOrPut(monthKey(date)) {
        storage.read(PortableFiles.diaryFile(monthKey(date)))
            ?.let { runCatching { PortableFiles.json.decodeFromString(PortableFiles.entries, it) }.getOrNull() }
            .orEmpty().toMutableList()
    }

    private fun entriesFor(date: LocalDate): List<DiaryEntry> = month(date).filter { it.date == date }

    private fun lastMonths(n: Int): List<DiaryEntry> {
        val today = today()
        return (0 until n).flatMap { month(today.minus(it, DateTimeUnit.MONTH)) }
    }

    private fun allLoadedEntries(): List<DiaryEntry> = diaryMonths.values.flatten()

    private fun allEntries(): List<DiaryEntry> {
        storage.names().filter { it.startsWith(PortableFiles.DIARY_PREFIX) }.forEach { name ->
            val key = name.removePrefix(PortableFiles.DIARY_PREFIX).removeSuffix(".json")
            if (key !in diaryMonths) runCatching { month(LocalDate.parse("$key-01")) }
        }
        return allLoadedEntries().sortedBy { it.loggedAtEpochMs }
    }

    private fun saveMonth(date: LocalDate) {
        storage.write(PortableFiles.diaryFile(monthKey(date)), PortableFiles.json.encodeToString(PortableFiles.entries, month(date)))
    }

    private fun log(entries: List<DiaryEntry>, force: Boolean): LogOutcomeView {
        if (entries.isEmpty()) return LogOutcomeView(false, null, "Nothing to log", 0)
        val existing = entries.map { it.date }.distinct().flatMap(::entriesFor)
        for (e in entries) {
            when (val check = DuplicateGuard.check(e, existing)) {
                is DuplicateCheck.Exact -> return LogOutcomeView(false, null, "Already logged", 0)
                is DuplicateCheck.Likely -> if (!force) return LogOutcomeView(false, "You logged ${check.existing.name} a moment ago. Log it again?", null, 0)
                DuplicateCheck.Unique -> Unit
            }
        }
        entries.forEach { month(it.date) += it }
        entries.map { it.date }.distinct().forEach(::saveMonth)
        return LogOutcomeView(true, null, null, entries.size)
    }

    private fun updateUsage(id: String, transform: (RecipeUsage) -> RecipeUsage) {
        data = data.copy(usage = data.usage + (id to transform(data.usage[id] ?: RecipeUsage())))
        saveData()
    }

    private fun updateSettings(transform: (PortableSettings) -> PortableSettings) {
        settings = transform(settings)
        storage.write(PortableFiles.SETTINGS, PortableFiles.json.encodeToString(PortableSettings.serializer(), settings))
    }

    private fun saveData() = storage.write(PortableFiles.DATA, PortableFiles.json.encodeToString(PortableData.serializer(), data))

    private fun keepUserFood(food: FoodReference) {
        data = data.copy(userFoods = data.userFoods.filter { it.id != food.id } + food)
        userFoods.refresh()
        saveData()
    }

    private fun <T> load(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        storage.read(name)?.let { runCatching { PortableFiles.json.decodeFromString(serializer, it) }.getOrNull() }

    private fun resolver() = core.textResolver { reference ->
        val ref = reference.lowercase().trim()
        val recipes = allRecipes()
        (recipes.firstOrNull { it.name.lowercase() == ref } ?: data.savedRecipes.firstOrNull { it.name.lowercase().contains(ref) })?.let { r ->
            val report = core.engine.calculateRecipe(r)
            SavedRecipeNutrition(r.id, r.name, report.total, r.servings, report.cookedWeightGrams.takeIf { report.cookedWeightMeasured })
        }
    }

    private fun describeView(usedAi: Boolean, simulated: Boolean, error: String?): DescribeResultView {
        val items = described.map { item ->
            DescribedItemView(
                name = item.displayName,
                amountText = listOfNotNull(item.quantity?.let { q -> "${formatAmount(q.amount)} ${q.unit.symbol}" }, item.grams?.let { "${formatAmount(it)} g" }).joinToString(" · "),
                nutrition = nutrition(item.nutrients), resolved = item.isResolved, notes = item.notes,
            )
        }
        return DescribeResultView(items, nutrition(Nutrients.sum(described.filter { it.isResolved }.map { it.nutrients })), usedAi, simulated, error)
    }

    private fun editScan(edit: ScanEdit): ScanResultView {
        val current = scan ?: return ScanResultView(emptyList(), nutrition(Nutrients.ZERO), "", "", "", false, emptyList(), "Scan again")
        scan = core.estimator.apply(current, edit)
        return scanView(null)
    }

    private fun scanView(error: String?): ScanResultView {
        val r = scan ?: return ScanResultView(emptyList(), nutrition(Nutrients.ZERO), "", "", "", false, emptyList(), error ?: "Scan again")
        return ScanResultView(
            items = r.items.map { item ->
                ScanItemView(
                    item.id, item.displayName, item.portion.grams, "${formatAmount(item.portion.grams)} g · ${item.portion.source.label}",
                    nutrition(item.nutrients), rangeText(item.energyRange), item.confidence.overall.label, item.included, item.notes,
                )
            },
            total = nutrition(r.total), rangeText = rangeText(r.totalEnergyRange), explanation = r.explanation,
            confidenceLabel = r.overallConfidence.label, simulated = r.simulated, warnings = r.warnings + r.unidentified.map { "Not identified: $it" },
            error = error,
        )
    }

    private class FoodCalc(
        val ok: Boolean, val nutrients: Nutrients, val grams: Double?, val quantity: Quantity, val precision: Precision,
        val confidence: ConfidenceLevel, val range: EstimateRange?, val notes: List<String>,
    )

    private class UnitOption(val label: String, val unit: QuantityUnit, val size: PortionSize?, val defaultAmount: Double)

    private fun unitOptions(food: FoodReference): List<UnitOption> = buildList {
        food.servingGrams?.let { add(UnitOption("serving (${formatAmount(it)} g)", QuantityUnit.SERVING, null, 1.0)) }
        food.portions.filter { it.unit != QuantityUnit.SERVING || food.servingGrams == null }.forEach { p ->
            val label = p.label.ifBlank { listOfNotNull(p.size?.name?.lowercase(), p.unit.symbol).joinToString(" ") }
            add(UnitOption("$label (${formatAmount(p.grams)} g)", p.unit, p.size, 1.0))
        }
        add(UnitOption("grams", QuantityUnit.GRAM, null, food.servingGrams ?: 100.0))
        if (settings.preferences.measurementSystem == MeasurementSystem.US_CUSTOMARY) add(UnitOption("ounces", QuantityUnit.OUNCE, null, 4.0))
        if (food.densityGPerMl != null || food.portions.any { it.unit == QuantityUnit.CUP }) {
            if (none { it.unit == QuantityUnit.CUP }) add(UnitOption("cup", QuantityUnit.CUP, null, 1.0))
            add(UnitOption("tablespoon", QuantityUnit.TABLESPOON, null, 1.0))
        }
    }.distinctBy { Triple(it.unit, it.size, it.label) }

    private fun calculateFood(hit: FoodSearchResult, amount: Double, optionIndex: Int): FoodCalc {
        val food = hit.food
        val option = unitOptions(food).getOrNull(optionIndex) ?: unitOptions(food).first()
        val quantity = Quantity(amount.coerceAtLeast(0.0), option.unit, option.size)
        val result = core.engine.calculateFood(food, quantity)
        val isDish = hit.match.dishId != null
        val generic = result.conversion?.method in setOf(ConversionMethod.ASSUMED_WATER_DENSITY, ConversionMethod.GENERIC_PORTION)
        val precision = when {
            isDish || generic || food.quality == DataQuality.APPROXIMATE -> Precision.ESTIMATED
            food.source == NutritionSource.MANUFACTURER_LABEL || food.source == NutritionSource.USER_ENTERED -> Precision.MEASURED
            else -> Precision.CALCULATED
        }
        val confidence = when {
            result.status == LineStatus.UNRESOLVED -> ConfidenceLevel.LOW
            food.quality == DataQuality.APPROXIMATE -> ConfidenceLevel.LOW
            isDish || generic -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGHER
        }
        val spread = if (precision == Precision.ESTIMATED) hit.match.energyVariability + if (generic) 0.15 else 0.0 else 0.0
        return FoodCalc(
            ok = result.status != LineStatus.UNRESOLVED && amount > 0,
            nutrients = result.nutrients, grams = result.gramsEaten, quantity = quantity, precision = precision, confidence = confidence,
            range = EstimateRange.around(result.nutrients.energyKcal, spread).takeIf { spread > 0 },
            notes = result.notes + listOfNotNull(result.conversion?.assumption),
        )
    }

    private fun cardView(d: DishResult): RecipeCardView {
        val r = d.shown.recipe
        val usage = data.usage[r.id]
        return RecipeCardView(
            id = r.id, name = r.name, subtitle = recipeSubtitle(r), minutes = r.totalMinutes, nutrition = nutrition(d.shown.perServing),
            difficulty = r.difficulty.label, difficultyLevel = r.difficulty.ordinal + 1, rating = usage?.rating,
            cookedCount = usage?.cookedCount ?: 0, favourite = usage?.favourite == true,
            dietLabel = when {
                DietGoal.VEGAN in d.shown.analysis.diets -> "Vegan"
                DietGoal.VEGETARIAN in d.shown.analysis.diets -> "Vegetarian"
                else -> null
            },
            originLabel = r.origin.label, isTraditional = r.origin == RecipeOrigin.EDITORIAL_TRADITIONAL,
            reasons = d.shown.reasons.take(2), versionCount = d.versionCount,
            versions = if (d.versionCount > 1) d.versions.map(::versionView) else emptyList(),
        )
    }

    private fun versionView(p: PreparedRecipe): RecipeVersionView {
        val r = p.recipe
        val label = when (r.origin) {
            RecipeOrigin.EDITORIAL_TRADITIONAL -> r.region?.let { "Traditional · $it" } ?: "Traditional"
            RecipeOrigin.EDITORIAL_VARIATION -> "Variation"
            RecipeOrigin.AI_GENERATED -> "AI-generated"
            RecipeOrigin.USER -> "Your version"
            RecipeOrigin.EXTERNAL_LICENSED -> "External"
            RecipeOrigin.IMPORTED -> "Saved by you"
        }
        return RecipeVersionView(
            r.id, r.name, label, r.totalMinutes, "${p.perServing.energyKcal.roundToInt()} kcal", r.difficulty.label, r.difficulty.ordinal + 1,
            data.usage[r.id]?.rating,
        )
    }

    private fun recipeSubtitle(r: Recipe) = listOfNotNull(RecipeCourse.of(r).label, CuisineCatalog[r.cuisineId]?.name, r.region).joinToString(" · ")

    private fun ingredientText(line: app.nouri.core.recipes.RecipeIngredient): String {
        val q = line.quantity
        val amount = when {
            q.amount <= 0 -> ""
            q.unit == QuantityUnit.PIECE -> "${formatAmount(q.amount)} "
            else -> "${formatAmount(q.amount)} ${q.unit.symbol} "
        }
        return amount + line.displayName + (line.preparation?.let { ", $it" } ?: "") + if (line.optional) " (optional)" else ""
    }

    private fun entryView(e: DiaryEntry): EntryView {
        val s = e.snapshot
        val amount = e.quantity?.let { q -> "${formatAmount(q.amount)} ${if (q.unit == QuantityUnit.SERVING) if (q.amount == 1.0) "serving" else "servings" else q.unit.symbol}" }
            ?: s.grams?.let { "${formatAmount(it)} g" }
        return EntryView(
            id = e.id, name = e.name, detail = listOfNotNull(amount, s.provenance.source.label).joinToString(" · "),
            nutrition = nutrition(s.nutrients, s.precision), isEstimate = s.precision == Precision.ESTIMATED,
            rangeText = s.energyRange?.let(::rangeText), confidenceLabel = s.confidence.label,
        )
    }

    private fun nutrition(n: Nutrients, precision: Precision = Precision.CALCULATED) = NutritionView(
        n.energyKcal, n.proteinG, n.carbsG, n.fatG,
        NutritionRounding.formatKcal(n.energyKcal, precision),
        "P ${n.proteinG.roundToInt()} g · C ${n.carbsG.roundToInt()} g · F ${n.fatG.roundToInt()} g",
    )

    private fun rangeText(r: EstimateRange) = "${r.low.roundToInt()}–${r.high.roundToInt()} kcal"

    private fun weightText(kg: Double): String = "${decimals(settings.weightUnit.fromKg(kg), 1)} ${settings.weightUnit.symbol}"

    private fun decimals(v: Double, places: Int): String {
        val factor = if (places == 1) 10.0 else 100.0
        val rounded = (v * factor).roundToInt() / factor
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private fun minutes(m: Int): String = if (m < 60) "$m min" else "${m / 60} h${if (m % 60 > 0) " ${m % 60} min" else ""}"

    private fun dayTitle(date: LocalDate): String {
        val today = today()
        return when (date) {
            today -> "Today"
            today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
            today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
            else -> "${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.day} ${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
        }
    }

    companion object {
        const val API_KEY = "openai.apiKey"
    }
}
