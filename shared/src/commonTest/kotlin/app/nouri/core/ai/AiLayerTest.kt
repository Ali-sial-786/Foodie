package app.nouri.core.ai

import app.nouri.core.ai.mock.MockAiProvider
import app.nouri.core.ai.mock.MockScenarios
import app.nouri.core.ai.openai.AccountInfo
import app.nouri.core.ai.openai.BearerTokenSource
import app.nouri.core.ai.openai.OpenAiProvider
import app.nouri.core.ai.openai.ResponsesClient
import app.nouri.core.ai.openai.ResponsesConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiLayerTest {
    /** The client uses real-time timeouts; run network-style tests off the virtual-time test dispatcher. */
    private fun realTime(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private val mealJson = """{"items":[{"label":"Chicken biryani","candidates":[{"name":"chicken biryani","likelihood":"high","reason":"layered"}],"cuisine":"Pakistani","components":["rice"],"cookingMethod":null,"visibleFat":"medium","estimatedGrams":{"low":250,"typical":350,"high":450},"householdEstimate":"1.5 cups","portionBasis":"plate assumed","isSauceOrCondiment":false,"partiallyHidden":false}],"referenceObjects":[],"unidentified":[],"suggestedQuestion":null,"imageQuality":"good","notes":null}"""

    private fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    private fun deltas(text: String): Array<Pair<String, String>> {
        val chunks = text.chunked(40)
        val escaped = chunks.map { Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(it)) }
        return (
            listOf("response.created" to """{"type":"response.created","response":{"id":"resp_1","model":"gpt-test","status":"in_progress"}}""") +
                escaped.map { "response.output_text.delta" to """{"type":"response.output_text.delta","delta":$it}""" }
            ).toTypedArray()
    }

    private class Recorder {
        val bodies = mutableListOf<JsonObject>()
        val auth = mutableListOf<String?>()
    }

    private fun provider(recorder: Recorder, responder: (Int) -> Pair<HttpStatusCode, String>, tokens: BearerTokenSource = BearerTokenSource { "tok" }): OpenAiProvider {
        var call = 0
        val http = HttpClient(
            MockEngine { request ->
                recorder.auth += request.headers[HttpHeaders.Authorization]
                recorder.bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val (status, body) = responder(call++)
                respond(body, status, headersOf(HttpHeaders.ContentType, if (status == HttpStatusCode.OK) "text/event-stream" else "application/json"))
            },
        )
        return OpenAiProvider(ProviderKind.CHATGPT_PLAN, ResponsesClient(http, tokens, ResponsesConfig()), { "gpt-test" }, { AccountInfo("me", true) })
    }

    private fun image() = ImagePayload(byteArrayOf(1, 2, 3), widthPx = 10, heightPx = 10, role = ImageRole.TOP_DOWN)

    @Test
    fun streamingSuccessProducesValidatedAnalysisAndCompliantRequest() = realTime {
        val rec = Recorder()
        val p = provider(rec, { HttpStatusCode.OK to sse(*deltas(mealJson), "response.completed" to """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed"}}""") })
        val result = p.analyzeMeal(MealImageRequest(listOf(image())))
        val success = assertIs<AiResult.Success<MealAnalysis>>(result)
        assertEquals("Chicken biryani", success.value.items.single().label)
        assertFalse(success.meta.simulated)

        val body = rec.bodies.single()
        assertFalse(body["store"]!!.jsonPrimitive.boolean, "store must be false")
        assertTrue(body["stream"]!!.jsonPrimitive.boolean, "stream must be true")
        assertTrue("max_output_tokens" !in body, "plan usage rejects max_output_tokens")
        assertTrue(body["instructions"]!!.jsonPrimitive.content.isNotBlank())
        val input = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("user", input["role"]!!.jsonPrimitive.content, "no system-role messages")
        val image = input["content"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "input_image" }
        assertTrue(image["image_url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals("json_schema", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals<String?>("Bearer tok", rec.auth.single())
    }

    @Test
    fun usageLimitMidStreamIsReportedNotRetried() = realTime {
        val rec = Recorder()
        val p = provider(rec, {
            HttpStatusCode.OK to sse(
                *deltas(mealJson.take(60)),
                "response.failed" to """{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Limit reached"}}}""",
            )
        })
        val result = p.analyzeMeal(MealImageRequest(listOf(image())))
        val failure = assertIs<AiResult.Failure>(result)
        assertIs<AiError.UsageLimitReached>(failure.error)
        assertEquals(1, rec.bodies.size)
    }

    @Test
    fun streamWithoutCompletedEventIsNotSuccess() = realTime {
        val p = provider(Recorder(), { HttpStatusCode.OK to sse(*deltas(mealJson)) })
        assertIs<AiResult.Failure>(p.analyzeMeal(MealImageRequest(listOf(image()))))
    }

    @Test
    fun unauthorizedRefreshesTokenOnceAndRetries() = realTime {
        val rec = Recorder()
        val forced = mutableListOf<Boolean>()
        val tokens = BearerTokenSource { force -> forced += force; if (force) "new" else "old" }
        val p = provider(rec, { call ->
            if (call == 0) HttpStatusCode.Unauthorized to """{"error":{"code":"token_expired","message":"expired"}}"""
            else HttpStatusCode.OK to sse(*deltas(mealJson), "response.completed" to """{"type":"response.completed","response":{"status":"completed"}}""")
        }, tokens)
        assertIs<AiResult.Success<MealAnalysis>>(p.analyzeMeal(MealImageRequest(listOf(image()))))
        assertEquals(listOf(false, true), forced)
        assertEquals<List<String?>>(listOf("Bearer old", "Bearer new"), rec.auth)
    }

    @Test
    fun rejectedStructuredOutputFallsBackToPlainJson() = realTime {
        val rec = Recorder()
        val p = provider(rec, { call ->
            if (call == 0) HttpStatusCode.BadRequest to """{"error":{"code":"invalid_request","param":"text.format","message":"json_schema not supported"}}"""
            else HttpStatusCode.OK to sse(*deltas("```json\n$mealJson\n```"), "response.completed" to """{"type":"response.completed","response":{"status":"completed"}}""")
        })
        val result = assertIs<AiResult.Success<MealAnalysis>>(p.analyzeMeal(MealImageRequest(listOf(image()))))
        assertTrue("text" !in rec.bodies[1])
        assertTrue(result.meta.warnings.any { "Structured output" in it })
    }

    @Test
    fun errorMapping() {
        assertIs<AiError.RateLimited>(ResponsesClient.mapError(429, "rate_limit_exceeded", null))
        assertIs<AiError.UsageLimitReached>(ResponsesClient.mapError(429, "subscription_sharing_usage_limit_exceeded", null))
        assertIs<AiError.Unauthorized>(ResponsesClient.mapError(401, null, null))
        assertIs<AiError.Server>(ResponsesClient.mapError(503, null, null))
        assertTrue(ResponsesClient.mapError(503, null, null).retryable)
    }

    @Test
    fun validatorRepairsAndRejects() {
        val raw = MealAnalysis(
            items = listOf(
                DetectedFoodItem("  Daal ", estimatedGrams = GramRange(300.0, 200.0, 250.0)),
                DetectedFoodItem(" "),
                DetectedFoodItem("Rice", estimatedGrams = GramRange(-5.0, 100.0, 200.0)),
            ),
            suggestedQuestion = ClarifyingQuestion("?", listOf("only one")),
        )
        val v = AiValidator.meal(raw).getOrThrow()
        assertEquals(listOf("Daal", "Rice"), v.value.items.map { it.label })
        assertEquals(GramRange(200.0, 250.0, 300.0), v.value.items[0].estimatedGrams)
        assertNull(v.value.items[1].estimatedGrams)
        assertNull(v.value.suggestedQuestion)
        assertTrue(v.warnings.isNotEmpty())
        assertTrue(AiValidator.meal(MealAnalysis(emptyList(), imageQuality = "not_food")).isFailure)
    }

    @Test
    fun jsonExtractionToleratesProse() {
        val decoded = AiJson.decode("Sure! Here you go:\n```json\n$mealJson\n```\nEnjoy", MealAnalysis.serializer())
        assertEquals("Chicken biryani", decoded.getOrThrow().items.single().label)
    }

    @Test
    fun mockProviderIsDeterministicAndFlagged() = runTest {
        val mock = MockAiProvider(latencyMs = 0)
        val a = mock.analyzeMeal(MealImageRequest(emptyList(), demoScenario = "biryani_plate"))
        val s = assertIs<AiResult.Success<MealAnalysis>>(a)
        assertTrue(s.meta.simulated)
        assertEquals(3, s.value.items.size)
        assertTrue(mock.status().isSimulated)
        val b = mock.analyzeMeal(MealImageRequest(emptyList(), demoScenario = "biryani_plate"))
        assertEquals(s.value, (b as AiResult.Success).value)
        assertTrue(MockScenarios.meals.values.all { AiValidator.meal(it).isSuccess })
    }

    @Test
    fun serviceNeverSubstitutesDemoOrPaidResultsSilently() = runTest {
        val failing = object : AiProvider by MockAiProvider(0) {
            override val kind = ProviderKind.CHATGPT_PLAN
            override suspend fun analyzeMeal(request: MealImageRequest): AiResult<MealAnalysis> = AiResult.Failure(AiError.UsageLimitReached())
            override suspend fun generateRecipes(request: RecipeGenerationRequest): AiResult<GeneratedRecipes> = AiResult.Failure(AiError.Network())
        }
        var apiCalls = 0
        val api = object : AiProvider by MockAiProvider(0) {
            override val kind = ProviderKind.OPENAI_API_KEY
            override suspend fun generateRecipes(request: RecipeGenerationRequest): AiResult<GeneratedRecipes> {
                apiCalls++
                return AiResult.Success(GeneratedRecipes(emptyList()), AiCallMeta(kind, null, 0, false))
            }
        }
        val providers = mapOf(ProviderKind.CHATGPT_PLAN to failing, ProviderKind.OPENAI_API_KEY to api, ProviderKind.MOCK to MockAiProvider(0))

        var prefs = AiPreferences(selected = ProviderKind.CHATGPT_PLAN, photoAnalysisConsent = true, allowApiKeyFallback = false)
        val service = AiService(providers) { prefs }
        assertIs<AiError.UsageLimitReached>((service.analyzeMeal(MealImageRequest(listOf(image()))) as AiResult.Failure).error)
        assertIs<AiResult.Failure>(service.generateRecipes(RecipeGenerationRequest(listOf("egg"))))
        assertEquals(0, apiCalls, "no fallback without opt-in")

        prefs = prefs.copy(allowApiKeyFallback = true)
        assertIs<AiResult.Success<GeneratedRecipes>>(service.generateRecipes(RecipeGenerationRequest(listOf("egg"))))
        assertEquals(1, apiCalls)
        assertIs<AiError.UsageLimitReached>((service.analyzeMeal(MealImageRequest(listOf(image()))) as AiResult.Failure).error, "usage limits never fall back")

        prefs = prefs.copy(photoAnalysisConsent = false)
        assertIs<AiError.NotConfigured>((service.analyzeMeal(MealImageRequest(listOf(image()))) as AiResult.Failure).error)
    }

    @Test
    fun modelChoicePrefersConfiguredThenKnownThenGeneric() {
        assertEquals("custom", OpenAiProvider.chooseModel(listOf("a", "custom"), preferred = "custom"))
        assertEquals("gpt-6-astra", OpenAiProvider.chooseModel(listOf("gpt-6-astra", "gpt-4.1")))
        assertEquals("gpt-9-new", OpenAiProvider.chooseModel(listOf("whisper-1", "gpt-9-new", "gpt-image-2")))
        assertNull(OpenAiProvider.chooseModel(listOf("whisper-1")))
    }
}
