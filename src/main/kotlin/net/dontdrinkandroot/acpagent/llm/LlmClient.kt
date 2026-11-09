package net.dontdrinkandroot.acpagent.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.dontdrinkandroot.acpagent.llm.models.FunctionTool
import net.dontdrinkandroot.acpagent.llm.models.ReasoningRequest
import net.dontdrinkandroot.acpagent.llm.models.ResponsesRequest

private const val RESPONSES_PATH = "responses"
private const val MODELS_PATH = "models"
private const val STREAM_END_EVENT = "[DONE]"

/**
 * The Responses API's terminal stream events: `response.completed`,
 * `response.incomplete` (a token-limit/`max_output_tokens` cut-off) and
 * `response.failed`. Verified against the live endpoint 2026-11-06 - the
 * documented `response.done` name does **not** occur in practice.
 */
private val RESPONSE_TERMINAL_EVENTS = setOf(
    "response.completed",
    "response.incomplete",
    "response.failed",
)

/** True for the event types that end a response stream ([RESPONSE_TERMINAL_EVENTS]). */
private fun JsonObject.isTerminalResponseEvent(): Boolean =
    (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content in RESPONSE_TERMINAL_EVENTS

/**
 * Idle timeout between two data packets on the underlying socket, in
 * milliseconds. A live SSE stream keeps sending data, so an active response
 * never hits this; a server that goes silent for this long gets a loud
 * failure instead of an infinite hang.
 */
private const val LLM_SOCKET_IDLE_TIMEOUT_MILLIS = 120_000L

/**
 * Raised when OpenRouter answers with an HTTP error status or an error object
 * inside the stream. Carries the server-provided message so the turn fails
 * with a diagnostic the user can act on instead of an empty or cryptic reply.
 */
public class LlmException(message: String) : Exception(message)

/**
 * App attribution: OpenRouter shows usage in Logs/rankings under this app.
 * See https://openrouter.ai/docs/app-attribution.
 */
private const val APP_TITLE = "DdrAcpAgent"
private const val APP_URL = "https://github.com/dontdrinkandroot/acp-agent.kotlin"

/**
 * Provider preferences steering OpenRouter's provider routing (the `provider`
 * object in the chat completion body). Every member is optional; the two
 * producers each set a coherent subset: the auto routing policy sends
 * `sort` (+ `max_price`), a manual pick sends `order` only. `llmWireJson`
 * drops absent members from the wire.
 */
@Serializable
public data class ProviderPreferences(
    val sort: String? = null,
    val order: List<String>? = null,
    val maxPrice: ProviderMaxPrice? = null,
)

@Serializable
public data class ProviderMaxPrice(
    val completion: Double,
)

/**
 * The LLM transport: streaming chat completions and the `GET /models` feed.
 * Both use the shared wire [json] so wire-shaped data round-trips losslessly.
 */
public class LlmClient(
    apiKey: String,
    baseUrl: String,
) : ChatCompleter {
    private val json = llmWireJson
    private val client = HttpClient(CIO) {
        engine {
            // The CIO engine's default `requestTimeout` is a 15s aggregate
            // wall-clock deadline covering the whole HTTP call. Reasoning
            // models and long tool loops routinely stream for longer than
            // that, so it must not kill a live response; a dead stream is
            // instead caught by the socket idle timeout below. 0 disables
            // the aggregate timeout entirely.
            requestTimeout = 0
            endpoint {
                socketTimeout = LLM_SOCKET_IDLE_TIMEOUT_MILLIS
            }
        }
        defaultRequest {
            url(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header("HTTP-Referer", APP_URL)
            header("X-OpenRouter-Title", APP_TITLE)
        }
    }

    public override fun responsesStream(
        input: List<JsonObject>,
        tools: List<FunctionTool>,
        reasoning: String?,
        model: String,
        provider: ProviderPreferences?,
    ): Flow<JsonObject> = flow {
        val body = json.encodeToString(
            ResponsesRequest(
                model = model,
                input = input,
                tools = tools.ifEmpty { null },
                reasoning = reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningRequest(it) },
                provider = provider,
            ),
        )
        client.preparePost(RESPONSES_PATH) {
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = response.bodyAsText().take(500)
                throw LlmException("responses request failed: HTTP ${response.status.value}: $detail")
            }
            val reader = response.bodyAsChannel()
            val current = StringBuilder()
            var completed = false

            // One flush path for both event boundaries (a blank line and the end
            // of the stream), so a stream whose last event is not followed by a
            // blank line - e.g. a connection closed right after `data: [DONE]` -
            // still counts as complete instead of failing as a dead stream.
            suspend fun flush(): Boolean {
                if (current.toString().trim() == STREAM_END_EVENT) {
                    current.setLength(0)
                    return true
                }
                val event = parseAccumulatedEvent(current) ?: return false
                emit(event)
                return event.isTerminalResponseEvent()
            }

            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                when {
                    line.isBlank() -> if (flush()) completed = true
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> current.append(line.removePrefix("data:").trim())
                }
            }
            if (flush()) completed = true
            if (!completed) {
                // The connection dropped before the [DONE] sentinel or a
                // response.done/failed event: the accumulated deltas are
                // incomplete. Fail loudly instead of executing truncated tool
                // calls or emitting an empty END_TURN for a dead stream.
                throw LlmException("responses stream ended unexpectedly before completion")
            }
        }
    }

    /**
     * Fetches the models list. Only entries supporting tool calling and text
     * output are returned, sorted by id.
     */
    internal suspend fun fetchModels(): List<OpenRouterModel> {
        val response = client.get(MODELS_PATH)
        if (!response.status.isSuccess()) {
            throw LlmException("fetch models failed: HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        val models: OpenRouterModelsResponse = json.decodeFromString(response.bodyAsText())
        return models.data
            .filter {
                "tools" in it.supportedParameters && "text" in (it.architecture?.outputModalities ?: emptyList())
            }
            .sortedBy { it.id }
    }

    /**
     * Fetches the provider endpoints of a model. Model ids are expected as
     * "author/slug". Returned verbatim - price parsing is the provider routing
     * policy's concern.
     */
    internal suspend fun fetchEndpoints(modelId: String): List<OpenRouterEndpoint> {
        val response = client.get("models/$modelId/endpoints")
        if (response.status.value != 200) {
            throw LlmException(
                "fetch endpoints failed: HTTP ${response.status.value}: ${response.bodyAsText().take(500)}"
            )
        }
        val payload: OpenRouterEndpointsResponse = json.decodeFromString(response.bodyAsText())
        return payload.data.endpoints
    }

    /**
     * Parses one SSE data payload into an event object. Raises on the
     * error-envelope shapes (top-level `{"error": {...}}` stream events and
     * `response.failed` events), returns null for data-less events. Unknown
     * event types are returned as raw objects - the consumer decides what to
     * do with them.
     */
    private fun parseAccumulatedEvent(current: StringBuilder): JsonObject? {
        val data = current.toString().trim()
        current.setLength(0)
        if (data.isEmpty() || data == STREAM_END_EVENT) return null
        val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
            ?: throw LlmException("OpenRouter stream error: undecodable event payload: ${data.take(200)}")
        val event = element as? JsonObject
            ?: throw LlmException("OpenRouter stream error: unexpected event payload: ${data.take(200)}")
        (event["error"] as? JsonObject)?.let { errorObject ->
            val message = (errorObject["message"] as? JsonPrimitive)?.content
                ?: errorObject.toString()
            throw LlmException("OpenRouter stream error: $message")
        }
        val type = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type == "response.failed") {
            val errorObject = event["response"]?.jsonObject?.get("error") as? JsonObject
            val message = (errorObject?.get("message") as? JsonPrimitive)?.content
                ?: event.toString()
            throw LlmException("OpenRouter responses error: $message")
        }
        return event
    }

    public fun close() {
        client.close()
    }
}
