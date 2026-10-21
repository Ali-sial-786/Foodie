package app.nouri.data.repo

import app.nouri.BuildConfig
import app.nouri.core.barcode.BarcodeLookupResult
import app.nouri.core.barcode.BarcodeLookupService
import app.nouri.core.barcode.BrandedProduct
import app.nouri.core.barcode.Gtin
import app.nouri.core.barcode.OpenFoodFactsMapper
import app.nouri.core.fooddb.FoodReference
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Open Food Facts. Barcode lookups send only the number; name searches send only the search words. */
class OpenFoodFactsService(private val http: HttpClient) : BarcodeLookupService {
    private val userAgent = "Nouri/${BuildConfig.VERSION_NAME} (Android; https://github.com/Ali-sial-786/Foodie)"
    private val fields = "code,product_name,product_name_en,generic_name,brands,quantity,serving_size,serving_quantity,serving_quantity_unit,nutrition_data_per,nutriments,allergens_tags,countries_tags"

    /** Full-text product search (brand + name), for foods typed rather than scanned. */
    suspend fun search(terms: String): Result<List<BrandedProduct>> = try {
        val response = http.get("https://world.openfoodfacts.org/cgi/search.pl") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("search_terms", terms)
            parameter("search_simple", 1)
            parameter("action", "process")
            parameter("json", 1)
            parameter("page_size", 20)
            parameter("fields", fields)
        }
        if (response.status.isSuccess()) Result.success(OpenFoodFactsMapper.mapSearch(response.bodyAsText()))
        else Result.failure(IllegalStateException("Product database returned ${response.status.value}"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException("No connection to the product database"))
    }

    override suspend fun lookup(gtin: String): BarcodeLookupResult = try {
        val response = http.get("https://world.openfoodfacts.org/api/v2/product/$gtin.json") {
            // OFF asks apps to identify themselves with a descriptive User-Agent.
            header(HttpHeaders.UserAgent, userAgent)
            parameter("fields", "code,product_name,product_name_en,brands,quantity,serving_size,serving_quantity,nutrition_data_per,nutriments,allergens_tags,ingredients_text,ingredients_text_en,countries_tags")
        }
        when {
            response.status.value == 404 -> BarcodeLookupResult.NotFound
            !response.status.isSuccess() -> BarcodeLookupResult.Failed("Product database returned ${response.status.value}", retryable = response.status.value >= 500)
            else -> OpenFoodFactsMapper.map(response.bodyAsText())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BarcodeLookupResult.Failed("No connection to the product database", retryable = true)
    }
}

sealed interface BarcodeOutcome {
    /** Nutrition is known (from cache, database or a label the user scanned earlier). */
    data class Ready(val food: FoodReference, val product: BrandedProduct?, val fromCache: Boolean) : BarcodeOutcome

    /** The product exists but nutrition is incomplete; offer label scanning. */
    data class NeedsLabel(val product: BrandedProduct) : BarcodeOutcome

    data object NotFound : BarcodeOutcome
    data class Offline(val reason: String) : BarcodeOutcome
    data class Invalid(val reason: String) : BarcodeOutcome
}

sealed interface ProductSearchOutcome {
    data class Results(val products: List<BrandedProduct>) : ProductSearchOutcome
    data class Unavailable(val reason: String) : ProductSearchOutcome
}

/** Cache-first barcode lookup: the user's own foods, then Open Food Facts (if allowed). */
class BarcodeRepository(
    private val userFoods: UserFoodRepository,
    private val service: BarcodeLookupService,
    private val onlineAllowed: suspend () -> Boolean,
    private val search: (suspend (String) -> Result<List<BrandedProduct>>)? = null,
) {
    /** Searches Open Food Facts by name (e.g. "deep naan"); only the search words are sent. */
    suspend fun searchProducts(terms: String): ProductSearchOutcome {
        val q = terms.trim()
        if (q.length < 2) return ProductSearchOutcome.Results(emptyList())
        if (!onlineAllowed()) return ProductSearchOutcome.Unavailable("Online product lookup is turned off in Privacy settings")
        val searcher = search ?: return ProductSearchOutcome.Unavailable("Product search isn't available")
        return searcher(q).fold(
            onSuccess = { list -> ProductSearchOutcome.Results(list.sortedByDescending { it.food != null }) },
            onFailure = { ProductSearchOutcome.Unavailable(it.message ?: "Product search failed") },
        )
    }

    /** Keeps a chosen product as one of the user's foods (offline next time) and returns it. */
    suspend fun keep(product: BrandedProduct): FoodReference? {
        val food = product.food ?: return null
        userFoods.save(food, product.brand, product.gtin, product.allergens)
        return food
    }

    suspend fun lookup(raw: String): BarcodeOutcome {
        val gtin = Gtin.normalize(raw) ?: return BarcodeOutcome.Invalid("That barcode doesn't look valid")
        userFoods.byBarcode(gtin)?.let { return BarcodeOutcome.Ready(it, null, fromCache = true) }
        if (!onlineAllowed()) return BarcodeOutcome.Offline("Online product lookup is turned off in Privacy settings")
        return when (val result = service.lookup(gtin)) {
            is BarcodeLookupResult.Found -> {
                val food = result.product.food
                if (food != null) {
                    userFoods.save(food, result.product.brand, gtin, result.product.allergens)
                    BarcodeOutcome.Ready(food, result.product, fromCache = false)
                } else {
                    BarcodeOutcome.NeedsLabel(result.product)
                }
            }
            BarcodeLookupResult.NotFound -> BarcodeOutcome.NotFound
            is BarcodeLookupResult.Failed -> BarcodeOutcome.Offline(result.reason)
        }
    }
}
