package app.nouri.core.ai

/** What an AI provider can do. Checked before every request; unavailable features degrade gracefully. */
enum class AiCapability(val label: String) {
    MEAL_IMAGE_ANALYSIS("Meal photo analysis"),
    INGREDIENT_IMAGE_ANALYSIS("Ingredient photo analysis"),
    LABEL_IMAGE_READING("Nutrition label reading"),
    RECIPE_GENERATION("Recipe generation"),
    MEAL_TEXT_PARSING("Natural-language meal logging"),
}

enum class ProviderKind(val label: String) {
    /** Sign in with ChatGPT with plan-usage authorisation (eligible requests use the user's plan). */
    CHATGPT_PLAN("ChatGPT plan"),

    /** User-supplied OpenAI API key; billed to the user's API account. Disabled until configured. */
    OPENAI_API_KEY("OpenAI API key"),

    /** Deterministic simulated provider for development and demos. Results are labelled as simulated. */
    MOCK("Demo (simulated)"),

    /** Reserved for future on-device models. */
    ON_DEVICE("On-device"),
}

enum class BillingMode(val label: String) {
    PLAN_USAGE("Uses your ChatGPT plan limits"),
    API_BILLED("Billed to your OpenAI API account"),
    NONE("No usage charges"),
}

data class ProviderStatus(
    val kind: ProviderKind,
    val connected: Boolean,
    val accountLabel: String? = null,
    val capabilities: Set<AiCapability>,
    val billing: BillingMode,
    val model: String? = null,
    /** Known restrictions, shown verbatim in settings. */
    val restrictions: List<String> = emptyList(),
    val isSimulated: Boolean = false,
)

/** Typed failures. The UI maps each to a clear, recoverable message; none of them lose user data. */
sealed interface AiError {
    val message: String
    val retryable: Boolean

    data class NotConfigured(override val message: String = "No AI provider is connected") : AiError {
        override val retryable = false
    }

    /** Token expired or revoked; reconnect needed. */
    data class Unauthorized(override val message: String = "Your AI connection needs to be renewed") : AiError {
        override val retryable = false
    }

    /** ChatGPT plan usage limit (or this app's allowance) reached. Never falls back to paid usage silently. */
    data class UsageLimitReached(
        override val message: String = "You've reached the usage limit for this app on your ChatGPT plan",
        val settingsUrl: String? = "https://chatgpt.com/#settings",
    ) : AiError {
        override val retryable = false
    }

    data class RateLimited(override val message: String = "Too many requests; try again shortly", val retryAfterSeconds: Long? = null) : AiError {
        override val retryable = true
    }

    data class CapabilityUnavailable(val capability: AiCapability, override val message: String) : AiError {
        override val retryable = false
    }

    /** Model output could not be parsed or failed validation. */
    data class InvalidResponse(override val message: String, val detail: String? = null) : AiError {
        override val retryable = true
    }

    data class Network(override val message: String = "No connection to the AI service") : AiError {
        override val retryable = true
    }

    data class Timeout(override val message: String = "The AI service took too long to respond") : AiError {
        override val retryable = true
    }

    data class Server(val status: Int, override val message: String = "The AI service had a problem") : AiError {
        override val retryable = status >= 500
    }

    data class Refused(override val message: String = "The AI service declined this request") : AiError {
        override val retryable = false
    }
}

data class AiCallMeta(
    val provider: ProviderKind,
    val model: String?,
    val latencyMs: Long,
    val simulated: Boolean,
    val warnings: List<String> = emptyList(),
)

sealed interface AiResult<out T> {
    data class Success<T>(val value: T, val meta: AiCallMeta) : AiResult<T>
    data class Failure(val error: AiError) : AiResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

/** Which picture this is in a multi-photo capture. */
enum class ImageRole { TOP_DOWN, ANGLED, CLOSE_UP, LABEL, OTHER }

/**
 * An image prepared for upload: already downscaled, re-encoded (metadata such as
 * GPS stripped) and compressed by the platform. Only this processed copy is ever
 * sent to a provider.
 */
class ImagePayload(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val widthPx: Int,
    val heightPx: Int,
    val role: ImageRole = ImageRole.OTHER,
    /** Content hash used to avoid re-sending identical images. */
    val sha256Hex: String? = null,
)

/** Optional user-supplied context that improves accuracy (all fields optional). */
data class PortionHints(
    val plateDiameterCm: Double? = null,
    val bowlVolumeMl: Double? = null,
    val totalWeightGrams: Double? = null,
    val referenceObject: String? = null,
    val homemade: Boolean? = null,
    val userNote: String? = null,
)

data class MealImageRequest(
    val images: List<ImagePayload>,
    val hints: PortionHints = PortionHints(),
    /** Cuisines the user eats often; helps disambiguate visually similar dishes. */
    val preferredCuisines: List<String> = emptyList(),
    /** Dish names the user has confirmed before (personalisation, local data only). */
    val knownDishes: List<String> = emptyList(),
    /** Used only by the simulated provider to pick a demo scenario. */
    val demoScenario: String? = null,
)

data class IngredientImageRequest(val images: List<ImagePayload>, val demoScenario: String? = null)

data class RecipeGenerationRequest(
    val availableIngredients: List<String>,
    val userPrompt: String? = null,
    val cuisines: List<String> = emptyList(),
    val dietaryRequirements: List<String> = emptyList(),
    val allergiesToAvoid: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
    val maxMinutes: Int? = null,
    val servings: Int = 2,
    val targetKcalPerServing: Int? = null,
    val onlyAvailableIngredients: Boolean = false,
    val count: Int = 3,
    val equipment: List<String> = emptyList(),
    val skill: String? = null,
)

data class MealTextRequest(val text: String, val recentRecipeNames: List<String> = emptyList())

data class LabelImageRequest(val image: ImagePayload)

/**
 * Interchangeable AI provider. Authentication is handled outside this interface
 * (OAuth for ChatGPT, key entry for API keys), so providers only perform requests.
 */
interface AiProvider {
    val kind: ProviderKind

    suspend fun status(): ProviderStatus

    suspend fun analyzeMeal(request: MealImageRequest): AiResult<MealAnalysis>

    suspend fun identifyIngredients(request: IngredientImageRequest): AiResult<IngredientAnalysis>

    suspend fun generateRecipes(request: RecipeGenerationRequest): AiResult<GeneratedRecipes>

    suspend fun parseMealText(request: MealTextRequest): AiResult<ParsedMealText>

    suspend fun readLabel(request: LabelImageRequest): AiResult<LabelReading>
}
