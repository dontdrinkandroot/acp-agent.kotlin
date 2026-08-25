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

private const val CHAT_COMPLETIONS_PATH = "chat/completions"
private const val MODELS_PATH = "models"
private const val STREAM_END_EVENT = "[DONE]"

/**
 * App attribution: OpenRouter shows usage in Logs/rankings under this app.
 * See https://openrouter.ai/docs/app-attribution.
 */
private const val APP_TITLE = "DdrAcpAgentKotlin"
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
            val reader = response.bodyAsChannel()
            val current = StringBuilder()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                when {
                    line.isBlank() -> flushEvent(current)?.let { emit(it) }
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> current.append(line.removePrefix("data:").trim())
                }
            }
            flushEvent(current)?.let { emit(it) }
        }
    }

    /**
     * Fetches the models list. Only entries supporting tool calling and text
     * output are returned, sorted by id.
     */
    internal suspend fun fetchModels(): List<OpenRouterModel> {
        val response: OpenRouterModelsResponse = json.decodeFromString(client.get(MODELS_PATH).bodyAsText())
        return response.data
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
            error("fetch endpoints: HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        val payload: OpenRouterEndpointsResponse = json.decodeFromString(response.bodyAsText())
        return payload.data.endpoints.map { endpoint ->
            endpoint.pricing.completion.toDoubleOrNull()
                ?: error("fetch endpoints: invalid completion price \"${endpoint.pricing.completion}\"")
        }
    }

    private fun flushEvent(current: StringBuilder): OpenRouterChatCompletionStreamResponse? {
        val data = current.toString().trim()
        current.setLength(0)
        if (data.isEmpty() || data == STREAM_END_EVENT) return null
        return json.decodeFromString(data)
    }

    public fun close() {
        client.close()
    }
}
