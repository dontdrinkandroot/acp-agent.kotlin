package net.dontdrinkandroot.acpagent.llm

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterChatCompletionStreamResponse
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

private const val CHAT_COMPLETIONS_PATH = "chat/completions"
private const val MODELS_PATH = "models"
private const val STREAM_END_EVENT = "[DONE]"

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

@Serializable
internal data class OpenRouterChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val tools: List<OpenAITool>? = null,
    val stream: Boolean = true,
    val reasoning: ReasoningEffort? = null,
    val provider: ProviderPreferences? = null,
)

@Serializable
internal data class ReasoningEffort(
    val effort: String,
)

/**
 * Provider preferences steering OpenRouter's provider routing (the `provider`
 * object in the chat completion body).
 */
@Serializable
public data class ProviderPreferences(
    val sort: String,
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
    private val model: String,
) {
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

    public fun chatCompletion(
        messages: List<OpenAIMessage>,
        tools: List<OpenAITool>,
        reasoning: String? = null,
        model: String = this.model,
        provider: ProviderPreferences? = null,
    ): Flow<OpenRouterChatCompletionStreamResponse> = flow {
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = model,
                messages = messages,
                tools = tools.ifEmpty { null },
                reasoning = reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningEffort(it) },
                provider = provider,
            ),
        )
        client.preparePost(CHAT_COMPLETIONS_PATH) {
            setBody(body)
        }.execute { response ->
            if (response.status.value !in 200..299) {
                val detail = response.bodyAsText().take(500)
                throw LlmException("chat completion failed: HTTP ${response.status.value}: $detail")
            }
            val reader = response.bodyAsChannel()
            val current = StringBuilder()
            var completed = false
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                when {
                    line.isBlank() -> {
                        val data = current.toString().trim()
                        if (data == STREAM_END_EVENT) {
                            completed = true
                            current.setLength(0)
                        } else {
                            flushEvent(current)?.let { event ->
                                if (event.choices.any { it.finishReason != null }) completed = true
                                emit(event)
                            }
                        }
                    }

                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> current.append(line.removePrefix("data:").trim())
                }
            }
            flushEvent(current)?.let { event ->
                if (event.choices.any { it.finishReason != null }) completed = true
                emit(event)
            }
            if (!completed) {
                // The connection dropped before the [DONE] sentinel or a
                // finish_reason: the accumulated deltas are incomplete. Fail
                // loudly instead of executing truncated tool calls or emitting
                // an empty END_TURN for a dead stream.
                throw LlmException("chat completion stream ended unexpectedly before [DONE]")
            }
        }
    }

    /**
     * Fetches the models list. Only entries supporting tool calling and text
     * output are returned, sorted by id.
     */
    internal suspend fun fetchModels(): List<OpenRouterModel> {
        val response = client.get(MODELS_PATH)
        if (response.status.value !in 200..299) {
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
     * Fetches the provider endpoints of a model and returns their completion
     * prices in USD per token. Model ids are expected as "author/slug".
     */
    internal suspend fun fetchEndpoints(modelId: String): List<Double> {
        val response = client.get("models/$modelId/endpoints")
        if (response.status.value != 200) {
            throw LlmException(
                "fetch endpoints failed: HTTP ${response.status.value}: ${response.bodyAsText().take(500)}"
            )
        }
        val payload: OpenRouterEndpointsResponse = json.decodeFromString(response.bodyAsText())
        return payload.data.endpoints.map { endpoint ->
            endpoint.pricing.completion.toDoubleOrNull()
                ?: throw LlmException("fetch endpoints: invalid completion price \"${endpoint.pricing.completion}\"")
        }
    }

    private fun flushEvent(current: StringBuilder): OpenRouterChatCompletionStreamResponse? {
        val data = current.toString().trim()
        current.setLength(0)
        if (data.isEmpty() || data == STREAM_END_EVENT) return null
        val errorObject = runCatching {
            json.parseToJsonElement(data) as? JsonObject
        }.getOrNull()
            ?.get("error") as? JsonObject
        if (errorObject != null) {
            val message = (errorObject["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: errorObject.toString()
            throw LlmException("OpenRouter stream error: $message")
        }
        val response = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(data)
        response.choices.firstOrNull { it.error != null }?.let { choice ->
            val message = choice.error?.message?.takeIf { it.isNotBlank() } ?: choice.error.toString()
            throw LlmException("OpenRouter stream error: $message")
        }
        return response
    }

    public fun close() {
        client.close()
    }
}
