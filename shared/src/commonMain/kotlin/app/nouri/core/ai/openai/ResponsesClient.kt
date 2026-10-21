package app.nouri.core.ai.openai

import app.nouri.core.ai.AiError
import app.nouri.core.ai.ImagePayload
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Supplies the bearer credential (OAuth access token or API key). */
fun interface BearerTokenSource {
    /** @param forceRefresh true after a 401, so OAuth sources refresh before retrying once. */
    suspend fun token(forceRefresh: Boolean): String?
}

data class ResponsesConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    /**
     * Sign in with ChatGPT plan usage requires `store: false` and `stream: true` on every
     * HTTP inference request; the same settings are used for API keys for consistency.
     */
    val store: Boolean = false,
    val stream: Boolean = true,
    /** Plan-usage requests reject `max_output_tokens`; leave null for that provider. */
    val maxOutputTokens: Int? = null,
    val imageDetail: String = "auto",
    val timeoutMs: Long = 90_000,
    val useStructuredOutputs: Boolean = true,
)

class ResponsesRequest(
    val model: String,
    val instructions: String,
    val text: String,
    val images: List<ImagePayload> = emptyList(),
    val schemaName: String? = null,
    val schema: JsonObject? = null,
)

data class ResponsesOutput(val text: String, val model: String?, val responseId: String?, val usedStructuredOutput: Boolean)

sealed interface ResponsesOutcome {
    data class Success(val output: ResponsesOutput) : ResponsesOutcome
    data class Failure(val error: AiError) : ResponsesOutcome
}

/**
 * Minimal client for `POST /v1/responses` with server-sent-event streaming.
 * A request succeeds only on a `response.completed` event: plan-usage limits can
 * fail a stream after output has started, and partial text is never used.
 */
class ResponsesClient(
    private val http: HttpClient,
    private val tokens: BearerTokenSource,
    private val config: ResponsesConfig = ResponsesConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun create(request: ResponsesRequest): ResponsesOutcome {
        val first = createOnce(request, structured = config.useStructuredOutputs && request.schema != null)
        // Some endpoints/plans may reject `text.format`; retry once relying on prompt-enforced JSON.
        if (first is ResponsesOutcome.Failure && first.error is AiError.InvalidResponse &&
            first.error.detail == STRUCTURED_OUTPUT_REJECTED
        ) {
            return createOnce(request, structured = false)
        }
        return first
    }

    suspend fun listModels(): Result<List<String>> = runCatching {
        val token = tokens.token(false) ?: throw IllegalStateException("Not authenticated")
        val response = http.get("${config.baseUrl}/models") { header(HttpHeaders.Authorization, "Bearer $token") }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
        parseModelList(response.bodyAsText())
    }

    private suspend fun createOnce(request: ResponsesRequest, structured: Boolean): ResponsesOutcome {
        val body = buildBody(request, structured).toString()
        var forceRefresh = false
        repeat(2) { attempt ->
            val token = tokens.token(forceRefresh) ?: return ResponsesOutcome.Failure(AiError.NotConfigured())
            val outcome = try {
                withTimeout(config.timeoutMs) { send(body, token, structured) }
            } catch (e: TimeoutCancellationException) {
                ResponsesOutcome.Failure(AiError.Timeout())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResponsesOutcome.Failure(AiError.Network(e.message ?: "Network error"))
            }
            if (outcome is ResponsesOutcome.Failure && outcome.error is AiError.Unauthorized && attempt == 0) {
                forceRefresh = true
                return@repeat
            }
            return outcome
        }
        return ResponsesOutcome.Failure(AiError.Unauthorized())
    }

    private suspend fun send(body: String, token: String, structured: Boolean): ResponsesOutcome =
        http.preparePost("${config.baseUrl}/responses") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, if (config.stream) "text/event-stream" else "application/json")
            setBody(TextContent(body, ContentType.Application.Json))
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute errorFrom(response, structured)
            if (config.stream) readStream(response, structured) else readJson(response.bodyAsText(), structured)
        }

    private suspend fun readStream(response: HttpResponse, structured: Boolean): ResponsesOutcome {
        val channel = response.bodyAsChannel()
        val parser = SseParser()
        val accumulator = StreamAccumulator(structured)
        while (true) {
            val line = channel.readUTF8Line() ?: break
            val event = parser.feed(line) ?: continue
            accumulator.accept(event)?.let { return it }
        }
        parser.flush()?.let { event -> accumulator.accept(event)?.let { return it } }
        return accumulator.finish()
    }

    private fun readJson(text: String, structured: Boolean): ResponsesOutcome {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ResponsesOutcome.Failure(AiError.InvalidResponse("Unreadable response"))
        return StreamAccumulator(structured).fromResponseObject(obj)
    }

    private suspend fun errorFrom(response: HttpResponse, structured: Boolean): ResponsesOutcome.Failure {
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val error = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        val param = error?.get("param")?.jsonPrimitive?.contentOrNull
        val status = response.status.value
        if (status == 400 && structured && (param?.startsWith("text") == true || message?.contains("json_schema") == true || message?.contains("text.format") == true)) {
            return ResponsesOutcome.Failure(AiError.InvalidResponse("Structured output not accepted", STRUCTURED_OUTPUT_REJECTED))
        }
        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
        return ResponsesOutcome.Failure(mapError(status, code, message, retryAfter))
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal fun buildBody(request: ResponsesRequest, structured: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("instructions", request.instructions)
        put("store", config.store)
        put("stream", config.stream)
        config.maxOutputTokens?.let { put("max_output_tokens", it) }
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "input_text"); put("text", request.text) })
                                for (image in request.images) {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", "data:${image.mimeType};base64," + Base64.Default.encode(image.bytes))
                                            put("detail", config.imageDetail)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
        if (structured && request.schema != null) {
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", request.schemaName ?: "output")
                            put("schema", request.schema)
                            put("strict", true)
                        },
                    )
                },
            )
        }
    }

    companion object {
        internal const val STRUCTURED_OUTPUT_REJECTED = "structured_output_rejected"

        /**
         * Model ids from `GET /v1/models`. Plan-usage tokens get a catalogue of `models[]`
         * entries, where only `visibility == "list"` entries are offered and `slug` is the
         * model id; API keys get the standard `data[].id` list. Both shapes are accepted.
         */
        fun parseModelList(body: String): List<String> {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            (root["models"] as? JsonArray)?.let { models ->
                return models.mapNotNull { entry ->
                    val o = entry as? JsonObject ?: return@mapNotNull null
                    val visibility = (o["visibility"] as? JsonPrimitive)?.contentOrNull
                    if (visibility != null && visibility != "list") return@mapNotNull null
                    ((o["slug"] ?: o["id"]) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }.distinct()
            }
            return (root["data"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }

        /** Maps HTTP status and error codes to typed errors. Usage-limit codes never trigger a fallback. */
        fun mapError(status: Int, code: String?, message: String?, retryAfterSeconds: Long? = null): AiError {
            val c = code.orEmpty()
            return when {
                "usage_limit" in c || c == "subscription_sharing_usage_limit_exceeded" || c == "insufficient_quota" ->
                    AiError.UsageLimitReached(message ?: AiError.UsageLimitReached().message)
                status == 401 || c == "invalid_api_key" || c == "token_expired" -> AiError.Unauthorized()
                status == 403 -> AiError.Refused(message ?: "This account is not allowed to make this request")
                status == 429 -> AiError.RateLimited(retryAfterSeconds = retryAfterSeconds)
                status == 400 && ("content_policy" in c || "safety" in c) -> AiError.Refused(message ?: AiError.Refused().message)
                status in 400..499 -> AiError.InvalidResponse(message ?: "Request rejected ($status)", detail = code)
                status >= 500 -> AiError.Server(status, message ?: AiError.Server(status).message)
                else -> AiError.Server(status)
            }
        }
    }
}

/** Incremental parser for `text/event-stream`. Returns a complete event when a blank line ends it. */
internal class SseParser {
    private var eventName: String? = null
    private val data = StringBuilder()

    data class Event(val name: String?, val data: String)

    fun feed(line: String): Event? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val (field, value) = line.indexOf(':').let { i ->
            if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 1).removePrefix(" ")
        }
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    fun flush(): Event? {
        if (data.isEmpty() && eventName == null) return null
        val event = Event(eventName, data.toString())
        eventName = null
        data.clear()
        return event
    }
}

/** Collects streamed text and decides the outcome from terminal events. */
internal class StreamAccumulator(private val structured: Boolean) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val text = StringBuilder()
    private var model: String? = null
    private var responseId: String? = null

    fun accept(event: SseParser.Event): ResponsesOutcome? {
        if (event.data == "[DONE]") return null
        val obj = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: event.name
        return when (type) {
            "response.created", "response.in_progress" -> {
                obj["response"]?.jsonObject?.let(::captureMeta)
                null
            }
            "response.output_text.delta" -> {
                obj["delta"]?.jsonPrimitive?.contentOrNull?.let(text::append)
                null
            }
            "response.output_text.done" -> {
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { text.clear(); text.append(it) }
                null
            }
            "response.refusal.done" -> ResponsesOutcome.Failure(
                AiError.Refused(obj["refusal"]?.jsonPrimitive?.contentOrNull ?: AiError.Refused().message),
            )
            "response.completed" -> obj["response"]?.jsonObject?.let(::fromResponseObject) ?: success()
            "response.failed" -> {
                val error = obj["response"]?.jsonObject?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
                ResponsesOutcome.Failure(
                    ResponsesClient.mapError(
                        status = 400,
                        code = error?.get("code")?.jsonPrimitive?.contentOrNull,
                        message = error?.get("message")?.jsonPrimitive?.contentOrNull,
                    ).let { if (it is AiError.InvalidResponse) AiError.Server(500, it.message) else it },
                )
            }
            "response.incomplete" -> {
                val reason = obj["response"]?.jsonObject?.get("incomplete_details")?.let { runCatching { it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ResponsesOutcome.Failure(AiError.InvalidResponse("The response was cut short${reason?.let { " ($it)" } ?: ""}"))
            }
            "error" -> ResponsesOutcome.Failure(
                ResponsesClient.mapError(
                    status = 500,
                    code = obj["code"]?.jsonPrimitive?.contentOrNull ?: obj["error"]?.let { runCatching { it.jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull() },
                    message = obj["message"]?.jsonPrimitive?.contentOrNull,
                ),
            )
            else -> null
        }
    }

    /** Stream ended without a terminal event: never treat partial output as success. */
    fun finish(): ResponsesOutcome = ResponsesOutcome.Failure(AiError.Network("The connection closed before the response completed"))

    fun fromResponseObject(response: JsonObject): ResponsesOutcome {
        captureMeta(response)
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status == "failed") {
            val error = response["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
            return ResponsesOutcome.Failure(
                ResponsesClient.mapError(500, error?.get("code")?.jsonPrimitive?.contentOrNull, error?.get("message")?.jsonPrimitive?.contentOrNull),
            )
        }
        val outputText = extractOutputText(response)
        if (outputText != null && text.isEmpty()) text.append(outputText)
        return success()
    }

    private fun success(): ResponsesOutcome =
        if (text.isBlank()) ResponsesOutcome.Failure(AiError.InvalidResponse("Empty response"))
        else ResponsesOutcome.Success(ResponsesOutput(text.toString(), model, responseId, structured))

    private fun captureMeta(response: JsonObject) {
        response["model"]?.jsonPrimitive?.contentOrNull?.let { model = it }
        response["id"]?.jsonPrimitive?.contentOrNull?.let { responseId = it }
    }

    private fun extractOutputText(response: JsonObject): String? {
        (response["output_text"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        val output = response["output"] as? JsonArray ?: return null
        val parts = output.flatMap { item ->
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: return@flatMap emptyList()
            content.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                if (o["type"]?.jsonPrimitive?.contentOrNull == "output_text") o["text"]?.jsonPrimitive?.contentOrNull else null
            }
        }
        return parts.joinToString("").ifBlank { null }
    }
}
