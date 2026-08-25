package net.dontdrinkandroot.acpagent.llm

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolFunction
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterChatCompletionStreamResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class LlmRequestTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Test
    fun `request is emitted with stream true and no temperature`() {
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = "m/n",
                messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                tools = null,
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("true", obj["stream"]?.jsonPrimitive?.content, "stream must be requested for SSE")
        assertFalse(obj.containsKey("temperature"), "temperature must not be sent to OpenRouter")
    }

    @Test
    fun `tool schemas pass through verbatim`() {
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                },
            )
        }
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = "m/n",
                messages = listOf(OpenAIMessage.User(Content.Text("u"))),
                tools = listOf(OpenAITool(OpenAIToolFunction("write_file", "desc", schema))),
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        val tools = obj["tools"] as JsonArray
        val parameters = (tools[0] as kotlinx.serialization.json.JsonObject)["function"]!!
            .jsonObject["parameters"] as kotlinx.serialization.json.JsonObject
        assertEquals(schema, parameters)
    }

    @Test
    fun `reasoning effort is serialized as an object`() {
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = "m/n",
                messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                reasoning = ReasoningEffort("medium"),
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("medium", obj["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `provider preferences are serialized as throughput sort plus median cap`() {
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = "m/n",
                messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                provider = ProviderPreferences("throughput", ProviderMaxPrice(60.0)),
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        val provider = obj["provider"]?.jsonObject
        assertEquals("throughput", provider?.get("sort")?.jsonPrimitive?.content)
        assertEquals(
            60.0,
            provider?.get("max_price")?.jsonObject?.get("completion")?.jsonPrimitive?.content?.toDouble()
        )
        assertFalse(obj.containsKey("temperature"), "provider routing must not introduce a temperature")
    }

    @Test
    fun `provider is omitted when null`() {
        val body = json.encodeToString(
            OpenRouterChatCompletionRequest(
                model = "m/n",
                messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        assertFalse(obj.containsKey("provider"), "null provider preferences must be omitted")
    }

    @Test
    fun `usage in the final stream chunk is parsed`() {
        val chunk = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(
            """{"id":"c","object":"chat.completion.chunk","created":0,"model":"m","choices":[],""" +
                    """"usage":{"prompt_tokens":42,"completion_tokens":5,"total_tokens":47}}""",
        )
        assertEquals(42, chunk.usage?.promptTokens)
    }

    @Test
    fun `reasoning deltas are parsed from the stream chunk`() {
        val chunk = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(
            """{"id":"c","object":"chat.completion.chunk","created":0,"model":"m","choices":[{""" +
                    """"index":0,"delta":{"reasoning":"thinking..."},"finish_reason":null}]}""",
        )
        assertEquals("thinking...", chunk.choices.single().delta.reasoning)
    }
}
