package app.nouri.data.repo

import android.content.Context
import android.util.Log
import app.nouri.core.diary.DiaryEntry
import app.nouri.core.diary.DiaryOperations
import app.nouri.core.diary.DuplicateCheck
import app.nouri.core.diary.DuplicateGuard
import app.nouri.core.diary.MealType
import app.nouri.core.fooddb.FoodReference
import app.nouri.core.fooddb.FoodRepository
import app.nouri.core.fooddb.InMemoryFoodRepository
import app.nouri.core.fooddb.UsdaSr28
import app.nouri.core.ontology.Allergen
import app.nouri.core.pantry.GroceryItem
import app.nouri.core.pantry.PantryItem
import app.nouri.core.progress.BodyMeasurement
import app.nouri.core.progress.WeightEntry
import app.nouri.core.recipes.Recipe
import app.nouri.core.recipes.RecipeCatalog
import app.nouri.core.recipes.RecipeLibrary
import app.nouri.core.recommend.RecipeUsage
import app.nouri.data.db.DiaryDao
import app.nouri.data.db.NameCount
import app.nouri.data.db.PantryDao
import app.nouri.data.db.RecipeDao
import app.nouri.data.db.RecipeUsageEntity
import app.nouri.data.db.ScanDao
import app.nouri.data.db.ScanSessionEntity
import app.nouri.data.db.UserFoodDao
import app.nouri.data.db.WeightDao
import app.nouri.data.db.toDomain
import app.nouri.data.db.toEntities
import app.nouri.data.db.toEntity
import app.nouri.data.db.toFoodReference
import app.nouri.data.db.toJson
import app.nouri.data.db.toStringList
import app.nouri.data.db.toUserFoodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** Result of a log action, so the UI can offer undo or ask about duplicates. */
sealed interface LogResult {
    data class Logged(val entries: List<DiaryEntry>) : LogResult
    data class PossibleDuplicate(val existing: DiaryEntry, val pending: List<DiaryEntry>) : LogResult
    data object AlreadyLogged : LogResult
}

class DiaryRepository(private val dao: DiaryDao, private val clock: () -> Long = System::currentTimeMillis) {
    fun observeDay(date: LocalDate): Flow<List<DiaryEntry>> = dao.observeDay(date.toString()).map { list -> list.map { it.toDomain() } }

    fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DiaryEntry>> =
        dao.observeRange(start.toString(), end.toString()).map { list -> list.map { it.toDomain() } }

    suspend fun range(start: LocalDate, end: LocalDate): List<DiaryEntry> = dao.range(start.toString(), end.toString()).map { it.toDomain() }

    fun observeRecent(limit: Int = 20): Flow<List<DiaryEntry>> = dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeFrequent(limit: Int = 10): Flow<List<NameCount>> = dao.observeFrequentNames(limit)

    suspend fun get(id: String): DiaryEntry? = dao.get(id)?.toDomain()

    /**
     * Logs entries. Exact retries (same idempotency key) are ignored; an accidental
     * double tap is reported back unless [force] is set.
     */
    suspend fun log(entries: List<DiaryEntry>, force: Boolean = false): LogResult {
        if (entries.isEmpty()) return LogResult.Logged(emptyList())
        val existingKeys = dao.byIdempotencyKeys(entries.map { it.idempotencyKey }).map { it.idempotencyKey }.toSet()
        val fresh = entries.filter { it.idempotencyKey !in existingKeys }
        if (fresh.isEmpty()) return LogResult.AlreadyLogged
        if (!force) {
            val sameDay = fresh.map { it.date }.distinct().flatMap { dao.range(it.toString(), it.toString()) }.map { it.toDomain() }
            for (candidate in fresh) {
                val check = DuplicateGuard.check(candidate, sameDay)
                if (check is DuplicateCheck.Likely) return LogResult.PossibleDuplicate(check.existing, fresh)
            }
        }
        dao.insertAll(fresh.map { it.toEntity(clock()) })
        return LogResult.Logged(fresh)
    }

    suspend fun update(entry: DiaryEntry) = dao.upsert(entry.toEntity(clock()))

    /** Soft delete; returns ids for undo. */
    suspend fun delete(ids: List<String>): List<String> {
        dao.softDelete(ids, clock())
        return ids
    }

    suspend fun restore(ids: List<String>) = dao.restore(ids, clock())

    suspend fun copyMeal(from: LocalDate, meal: MealType, to: LocalDate, toMeal: MealType, timeZoneId: String, newId: () -> String): LogResult {
        val source = dao.range(from.toString(), from.toString()).map { it.toDomain() }.filter { it.mealType == meal }
        return log(DiaryOperations.copyEntries(source, to, toMeal, clock(), timeZoneId, newId), force = true)
    }

    suspend fun copyDay(from: LocalDate, to: LocalDate, timeZoneId: String, newId: () -> String): LogResult {
        val source = dao.range(from.toString(), from.toString()).map { it.toDomain() }
        return log(DiaryOperations.copyEntries(source, to, null, clock(), timeZoneId, newId), force = true)
    }

    suspend fun all(): List<DiaryEntry> = dao.all().map { it.toDomain() }

    suspend fun purgeDeletedOlderThan(ms: Long) = dao.purgeDeleted(clock() - ms)
}

class WeightRepository(private val dao: WeightDao) {
    val entries: Flow<List<WeightEntry>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    val measurements: Flow<List<BodyMeasurement>> = dao.observeMeasurements().map { list -> list.map { it.toDomain() } }
    suspend fun save(entry: WeightEntry) = dao.upsert(entry.toEntity())
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun saveMeasurement(m: BodyMeasurement) = dao.upsertMeasurement(m.toEntity())
    suspend fun deleteMeasurement(id: String) = dao.deleteMeasurement(id)
    suspend fun all(): List<WeightEntry> = dao.all().map { it.toDomain() }
    suspend fun allMeasurements(): List<BodyMeasurement> = dao.allMeasurements().map { it.toDomain() }
}

/**
 * The bundled recipe library (`assets/recipes/library.json`, built from `data/recipes/`).
 * It is decoded the first time recipes are needed, off the main thread, and then kept in
 * memory. If the asset can't be read, the built-in recipes still work.
 */
class RecipeLibraryAssets(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var byId: Map<String, Recipe>? = null

    suspend fun all(): Collection<Recipe> = load().values

    suspend fun get(id: String): Recipe? = load()[id]

    private suspend fun load(): Map<String, Recipe> = byId ?: mutex.withLock {
        byId ?: withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET).bufferedReader().use { RecipeLibrary.decode(it.readText()) } }
                .onFailure { Log.w("Nouri", "Recipe library unavailable: ${it.message}") }
                .getOrDefault(emptyList())
                .associateBy { it.id }
        }.also { byId = it }
    }

    companion object {
        const val ASSET = "recipes/library.json"
    }
}

/** Built-in and library recipes plus the user's saved, AI-generated and variation recipes. */
class RecipeRepository(
    private val dao: RecipeDao,
    private val library: RecipeLibraryAssets? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val saved: Flow<List<Recipe>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    val all: Flow<List<Recipe>> = saved.map { savedRecipes ->
        val ids = savedRecipes.map { it.id }.toSet()
        savedRecipes + RecipeCatalog.all.filter { it.id !in ids } + library?.all().orEmpty().filter { it.id !in ids }
    }

    /** Built-in or library recipe that the user hasn't saved a copy of. */
    suspend fun isReference(id: String): Boolean = RecipeCatalog[id] != null || library?.get(id) != null

    val usage: Flow<Map<String, RecipeUsage>> = dao.observeUsage().map { list ->
        list.associate { it.recipeId to RecipeUsage(it.favourite, it.cookedCount, it.lastCookedAtEpochMs, it.rating) }
    }

    suspend fun get(id: String): Recipe? = dao.get(id)?.toDomain() ?: RecipeCatalog[id] ?: library?.get(id)

    suspend fun save(recipe: Recipe) {
        val (r, ingredients, steps) = recipe.toEntities(clock())
        dao.save(r, ingredients, steps)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun setFavourite(id: String, favourite: Boolean) {
        val current = dao.usage(id)
        dao.upsertUsage(RecipeUsageEntity(id, favourite, current?.cookedCount ?: 0, current?.lastCookedAtEpochMs, current?.rating))
    }

    suspend fun markCooked(id: String) {
        val current = dao.usage(id)
        dao.upsertUsage(RecipeUsageEntity(id, current?.favourite ?: false, (current?.cookedCount ?: 0) + 1, clock(), current?.rating))
    }

    /** The user's own 1–5 stars, or null to clear. Kept on this device like favourites. */
    suspend fun setRating(id: String, rating: Int?) {
        require(rating == null || rating in 1..5) { "Ratings are 1 to 5 stars" }
        val current = dao.usage(id)
        dao.upsertUsage(RecipeUsageEntity(id, current?.favourite ?: false, current?.cookedCount ?: 0, current?.lastCookedAtEpochMs, rating))
    }

    suspend fun savedRecipes(): List<Recipe> = dao.all().map { it.toDomain() }
}

class PantryRepository(private val dao: PantryDao) {
    val pantry: Flow<List<PantryItem>> = dao.observePantry().map { list -> list.map { it.toDomain() } }
    val grocery: Flow<List<GroceryItem>> = dao.observeGrocery().map { list -> list.map { it.toDomain() } }
    suspend fun pantryNow(): List<PantryItem> = dao.pantry().map { it.toDomain() }
    suspend fun groceryNow(): List<GroceryItem> = dao.grocery().map { it.toDomain() }
    suspend fun savePantry(items: List<PantryItem>) = dao.upsertPantry(items.map { it.toEntity() })
    suspend fun deletePantry(ids: List<String>) = dao.deletePantry(ids)
    suspend fun replacePantry(before: List<PantryItem>, after: List<PantryItem>) {
        val removed = before.map { it.id }.toSet() - after.map { it.id }.toSet()
        if (removed.isNotEmpty()) dao.deletePantry(removed.toList())
        dao.upsertPantry(after.map { it.toEntity() })
    }
    suspend fun saveGrocery(items: List<GroceryItem>) = dao.upsertGrocery(items.map { it.toEntity() })
    suspend fun deleteGrocery(ids: List<String>) = dao.deleteGrocery(ids)
    suspend fun clearChecked() = dao.clearCheckedGrocery()
}

/**
 * The user's own foods (custom, label scans, cached barcode products). Kept in an
 * in-memory index so the synchronous [FoodRepository] contract is fast.
 */
class UserFoodRepository(private val dao: UserFoodDao, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) : FoodRepository {
    private val all = MutableStateFlow<List<FoodReference>>(emptyList())
    private val index = MutableStateFlow(InMemoryFoodRepository(emptyList()))

    val foods: Flow<List<FoodReference>> = dao.observeAll().map { list -> list.map { it.toFoodReference() } }

    init {
        scope.launch { foods.collect(::replaceIndex) }
    }

    private fun replaceIndex(foods: List<FoodReference>) {
        all.value = foods
        index.value = InMemoryFoodRepository(foods)
    }

    override fun get(id: String): FoodReference? = index.value.get(id)
    override fun search(query: String, limit: Int): List<FoodReference> = index.value.search(query, limit)

    suspend fun save(food: FoodReference, brand: String? = null, barcode: String? = null, allergens: Set<Allergen> = emptySet()) {
        dao.upsert(food.toUserFoodEntity(brand, barcode, allergens, clock()))
        // Findable immediately (e.g. by the screen opened next); the Room flow refreshes the index shortly after.
        replaceIndex(all.value.filterNot { it.id == food.id } + food)
    }

    suspend fun byBarcode(barcode: String): FoodReference? = dao.byBarcode(barcode)?.toFoodReference()

    suspend fun delete(id: String) = dao.delete(id)
}

/** The full USDA SR28 table bundled as an asset, loaded once in the background for search. */
class UsdaAssetFoods(private val context: Context) : FoodRepository {
    private val loaded = MutableStateFlow<InMemoryFoodRepository?>(null)
    val isLoaded: StateFlow<InMemoryFoodRepository?> get() = loaded

    suspend fun load() {
        if (loaded.value != null) return
        val foods = withContext(Dispatchers.IO) {
            context.assets.open(ASSET).bufferedReader().useLines { lines -> lines.mapNotNull(UsdaSr28::parseTsvLine).toList() }
        }
        loaded.value = InMemoryFoodRepository(foods)
    }

    override fun get(id: String): FoodReference? = loaded.value?.get(id)
    override fun search(query: String, limit: Int): List<FoodReference> = loaded.value?.search(query, limit).orEmpty()

    companion object {
        const val ASSET = "fooddb/usda_sr28.tsv"
    }
}

class ScanRepository(private val dao: ScanDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun save(id: String, kind: String, providerKind: String, simulated: Boolean, analysisJson: String, imagePaths: List<String>) {
        dao.upsert(ScanSessionEntity(id, clock(), kind, providerKind, simulated, analysisJson, imagePaths.toJson(), "[]"))
    }

    suspend fun markLogged(id: String, entryIds: List<String>) {
        val s = dao.get(id) ?: return
        dao.upsert(s.copy(loggedEntryIdsJson = (s.loggedEntryIdsJson.toStringList() + entryIds).toJson()))
    }

    suspend fun imagePaths(): Map<String, List<String>> = dao.all().associate { it.id to it.imagePathsJson.toStringList() }

    suspend fun sessionsOlderThan(ms: Long): List<ScanSessionEntity> = dao.all().filter { it.createdAtEpochMs < clock() - ms }

    suspend fun clearImages(ids: List<String>) = dao.clearImages(ids)

    suspend fun all(): List<ScanSessionEntity> = dao.all()
}

/** Merges two flows of lists into a map keyed by id — tiny helper used by view models. */
fun <A, B, R> Flow<A>.with(other: Flow<B>, transform: suspend (A, B) -> R): Flow<R> = combine(this, other, transform)
