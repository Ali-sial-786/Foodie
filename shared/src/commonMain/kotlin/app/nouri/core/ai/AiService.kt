package app.nouri.core.ai

/**
 * User-controlled AI settings. Defaults are conservative: nothing is sent anywhere
 * until the user connects a provider and agrees to photo analysis.
 */
data class AiPreferences(
    val selected: ProviderKind = ProviderKind.MOCK,
    /** The user consented to sending food photos to the selected provider. */
    val photoAnalysisConsent: Boolean = false,
    /** Explicit opt-in to retry on the API-key provider when the plan provider is unavailable. Off by default. */
    val allowApiKeyFallback: Boolean = false,
)

/**
 * Chooses and calls the active provider. Rules:
 * 1. Only the user's selected provider is used.
 * 2. Simulated (demo) results are never substituted for a failed real request.
 * 3. A paid API-key provider is used as fallback only if [AiPreferences.allowApiKeyFallback]
 *    is on — and never for usage-limit errors, which must be shown to the user.
 */
class AiService(
    private val providers: Map<ProviderKind, AiProvider>,
    private val preferences: suspend () -> AiPreferences,
) {
    suspend fun activeKind(): ProviderKind = preferences().selected

    suspend fun activeStatus(): ProviderStatus? = providers[activeKind()]?.status()

    suspend fun statuses(): List<ProviderStatus> = providers.values.map { it.status() }

    suspend fun analyzeMeal(request: MealImageRequest) = withImageConsent { call { it.analyzeMeal(request) } }

    suspend fun identifyIngredients(request: IngredientImageRequest) = withImageConsent { call { it.identifyIngredients(request) } }

    suspend fun readLabel(request: LabelImageRequest) = withImageConsent { call { it.readLabel(request) } }

    suspend fun generateRecipes(request: RecipeGenerationRequest) = call { it.generateRecipes(request) }

    suspend fun parseMealText(request: MealTextRequest) = call { it.parseMealText(request) }

    private suspend fun <T> withImageConsent(block: suspend () -> AiResult<T>): AiResult<T> {
        val prefs = preferences()
        if (prefs.selected != ProviderKind.MOCK && !prefs.photoAnalysisConsent) {
            return AiResult.Failure(AiError.NotConfigured("Photo analysis needs your permission first"))
        }
        return block()
    }

    private suspend fun <T> call(block: suspend (AiProvider) -> AiResult<T>): AiResult<T> {
        val prefs = preferences()
        val primary = providers[prefs.selected] ?: return AiResult.Failure(AiError.NotConfigured())
        val result = block(primary)
        if (result !is AiResult.Failure) return result
        val fallback = providers[ProviderKind.OPENAI_API_KEY]
        val eligible = prefs.allowApiKeyFallback &&
            prefs.selected == ProviderKind.CHATGPT_PLAN &&
            fallback != null &&
            result.error !is AiError.UsageLimitReached &&
            result.error !is AiError.Refused &&
            fallback.status().connected
        return if (eligible) block(fallback!!) else result
    }
}
