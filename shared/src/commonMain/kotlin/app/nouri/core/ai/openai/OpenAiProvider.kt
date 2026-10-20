package app.nouri.core.ai.openai

import app.nouri.core.ai.AiCallMeta
import app.nouri.core.ai.AiCapability
import app.nouri.core.ai.AiError
import app.nouri.core.ai.AiJson
import app.nouri.core.ai.AiProvider
import app.nouri.core.ai.AiResult
import app.nouri.core.ai.AiSchemas
import app.nouri.core.ai.AiValidator
import app.nouri.core.ai.BillingMode
import app.nouri.core.ai.GeneratedRecipes
import app.nouri.core.ai.ImagePayload
import app.nouri.core.ai.IngredientAnalysis
import app.nouri.core.ai.IngredientImageRequest
import app.nouri.core.ai.LabelImageRequest
import app.nouri.core.ai.LabelReading
import app.nouri.core.ai.MealAnalysis
import app.nouri.core.ai.MealImageRequest
import app.nouri.core.ai.MealTextRequest
import app.nouri.core.ai.ParsedMealText
import app.nouri.core.ai.Prompts
import app.nouri.core.ai.ProviderKind
import app.nouri.core.ai.ProviderStatus
import app.nouri.core.ai.RecipeGenerationRequest
import app.nouri.core.ai.Validated
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** What the provider knows about the connected account; supplied by the auth layer. */
data class AccountInfo(val label: String?, val connected: Boolean)

/**
 * AI provider backed by the OpenAI Responses API.
 *
 * - [ProviderKind.CHATGPT_PLAN]: bearer token from Sign in with ChatGPT with the
 *   `chatgpt.tokens.use.direct` scope; eligible requests count against the user's plan.
 * - [ProviderKind.OPENAI_API_KEY]: user-supplied API key; requests are billed to that key.
 *
 * Both modes use `store: false`, streaming, and the `instructions` field so the
 * same request shape satisfies the plan-usage restrictions.
 */
class OpenAiProvider(
    override val kind: ProviderKind,
    private val client: ResponsesClient,
    private val model: suspend () -> String,
    private val account: suspend () -> AccountInfo,
) : AiProvider {

    init {
        require(kind == ProviderKind.CHATGPT_PLAN || kind == ProviderKind.OPENAI_API_KEY) { "Unsupported kind $kind" }
    }

    override suspend fun status(): ProviderStatus {
        val info = account()
        return ProviderStatus(
            kind = kind,
            connected = info.connected,
            accountLabel = info.label,
            capabilities = AiCapability.entries.toSet(),
            billing = if (kind == ProviderKind.CHATGPT_PLAN) BillingMode.PLAN_USAGE else BillingMode.API_BILLED,
            model = runCatching { model() }.getOrNull(),
            restrictions = if (kind == ProviderKind.CHATGPT_PLAN) PLAN_RESTRICTIONS else API_RESTRICTIONS,
        )
    }

    override suspend fun analyzeMeal(request: MealImageRequest): AiResult<MealAnalysis> =
        run(
            instructions = Prompts.mealAnalysis(request),
            text = "Analyse this meal.",
            images = request.images,
            schemaName = "meal_analysis",
            schema = AiSchemas.meal,
            serializer = MealAnalysis.serializer(),
            validate = AiValidator::meal,
        )

    override suspend fun identifyIngredients(request: IngredientImageRequest): AiResult<IngredientAnalysis> =
        run(
            instructions = Prompts.ingredientAnalysis(request),
            text = "List the food items you can see.",
            images = request.images,
            schemaName = "ingredient_analysis",
            schema = AiSchemas.ingredients,
            serializer = IngredientAnalysis.serializer(),
            validate = AiValidator::ingredients,
        )

    override suspend fun generateRecipes(request: RecipeGenerationRequest): AiResult<GeneratedRecipes> =
        run(
            instructions = Prompts.recipeGeneration(request),
            text = "Create the recipes now.",
            images = emptyList(),
            schemaName = "recipes",
            schema = AiSchemas.recipes,
            serializer = GeneratedRecipes.serializer(),
            validate = AiValidator::recipes,
        )

    override suspend fun parseMealText(request: MealTextRequest): AiResult<ParsedMealText> =
        run(
            instructions = Prompts.mealText(request),
            text = request.text.take(1000),
            images = emptyList(),
            schemaName = "meal_text",
            schema = AiSchemas.mealText,
            serializer = ParsedMealText.serializer(),
            validate = AiValidator::mealText,
        )

    override suspend fun readLabel(request: LabelImageRequest): AiResult<LabelReading> =
        run(
            instructions = Prompts.labelReading(),
            text = "Transcribe the nutrition panel.",
            images = listOf(request.image),
            schemaName = "label_reading",
            schema = AiSchemas.label,
            serializer = LabelReading.serializer(),
            validate = { r -> if (r.panelText.isBlank()) Result.failure(IllegalArgumentException("No panel text")) else Result.success(Validated(r, emptyList())) },
        )

    private suspend fun <T> run(
        instructions: String,
        text: String,
        images: List<ImagePayload>,
        schemaName: String,
        schema: JsonObject,
        serializer: KSerializer<T>,
        validate: (T) -> Result<Validated<T>>,
    ): AiResult<T> {
        if (!account().connected) return AiResult.Failure(AiError.NotConfigured())
        val modelId = runCatching { model() }.getOrElse { return AiResult.Failure(AiError.NotConfigured("No model selected")) }
        val start = TimeSource.Monotonic.markNow()
        val outcome = client.create(ResponsesRequest(modelId, instructions, text, images, schemaName, schema))
        val output = when (outcome) {
            is ResponsesOutcome.Failure -> return AiResult.Failure(outcome.error)
            is ResponsesOutcome.Success -> outcome.output
        }
        val decoded = AiJson.decode(output.text, serializer).getOrElse {
            return AiResult.Failure(AiError.InvalidResponse("The AI response could not be read", it.message))
        }
        val validated = validate(decoded).getOrElse {
            return AiResult.Failure(AiError.InvalidResponse(it.message ?: "The AI response was not usable"))
        }
        val warnings = validated.warnings + if (!output.usedStructuredOutput) listOf("Structured output unavailable; validated JSON from text") else emptyList()
        return AiResult.Success(
            validated.value,
            AiCallMeta(kind, output.model ?: modelId, start.elapsedNow().inWholeMilliseconds, simulated = false, warnings = warnings),
        )
    }

    companion object {
        val PLAN_RESTRICTIONS = listOf(
            "Requests count against your ChatGPT plan's usage limits for third-party apps; when a limit is reached, AI features pause until it resets.",
            "Nouri never switches to another billing method automatically.",
            "Only food photos you choose to analyse are sent; Nouri cannot see your ChatGPT conversations or memories.",
            "Tokens are stored only on this device, encrypted with the Android Keystore.",
        )
        val API_RESTRICTIONS = listOf(
            "Requests are billed to the OpenAI API account that owns the key.",
            "Your key is stored only on this device, encrypted with the Android Keystore.",
        )

        /** Preference order when choosing a default vision-capable model from the account's model list. */
        fun chooseModel(available: List<String>, preferred: String? = null, defaults: List<String> = DEFAULT_MODEL_PREFERENCE): String? {
            if (preferred != null && preferred in available) return preferred
            for (candidate in defaults) available.firstOrNull { it == candidate }?.let { return it }
            for (prefix in defaults) available.firstOrNull { it.startsWith(prefix) }?.let { return it }
            return available.firstOrNull { it.startsWith("gpt-") && !it.contains("audio") && !it.contains("realtime") && !it.contains("image") && !it.contains("transcribe") && !it.contains("tts") }
        }

        /** Overridable in settings; validated against the account's model list at runtime. */
        val DEFAULT_MODEL_PREFERENCE = listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-5", "gpt-4.1")
    }
}
