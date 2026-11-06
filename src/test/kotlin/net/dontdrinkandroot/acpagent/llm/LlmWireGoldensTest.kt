package net.dontdrinkandroot.acpagent.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden wire pins for the chat-completion LLM traffic. These exact JSON
 * documents were captured from the Koog wire types before they were replaced
 * by the owned types in `llm/models/LlmWireModels.kt`, so they pin the wire
 * contract across that swap. Expected strings are the observed output of the
 * original types; do not "fix" them by hand - regenerate from a failing run
 * and sanity-check the shape.
 */
internal class LlmWireGoldensTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Test
    fun `request with messages tools reasoning and provider has the pinned wire shape`() {
        val request = OpenRouterChatCompletionRequest(
            model = "anthropic/claude-sonnet-4.5",
            messages = listOf(
                OpenAIMessage.System(Content.Text("You are a coding agent.")),
                OpenAIMessage.User(Content.Text("What is in this image?")),
                OpenAIMessage.User(
                    Content.Parts(
                        listOf(
                            OpenAIContentPart.Text("Describe this file:"),
                            OpenAIContentPart.Image(OpenAIContentPart.ImageUrl("data:image/png;base64,AAAA")),
                        )
                    )
                ),
                OpenAIMessage.Assistant(
                    content = Content.Text("I will look at the file."),
                    toolCalls = listOf(
                        OpenAIToolCall("call_1", OpenAIFunction("read_file", "{\"path\":\"src/main.kt\"}"))
                    ),
                ),
                OpenAIMessage.Tool(Content.Text("fun main() {}"), toolCallId = "call_1"),
            ),
            tools = listOf(
                OpenAITool(OpenAIToolFunction("read_file", "Read a file.", buildJsonObject { }))
            ),
            reasoning = ReasoningEffort("medium"),
            provider = ProviderPreferences(sort = "throughput", maxPrice = ProviderMaxPrice(1.5)),
        )

        val encoded = json.encodeToString(request)

        assertEquals(
            "{\"model\":\"anthropic/claude-sonnet-4.5\",\"messages\":" +
                    "[{\"role\":\"system\",\"content\":\"You are a coding agent.\"}," +
                    "{\"role\":\"user\",\"content\":\"What is in this image?\"}," +
                    "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Describe this file:\"}," +
                    "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}," +
                    "{\"role\":\"assistant\",\"content\":\"I will look at the file.\",\"tool_calls\":[" +
                    "{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"src/main.kt\\\"}\"},\"type\":\"function\"}]}," +
                    "{\"role\":\"tool\",\"content\":\"fun main() {}\",\"tool_call_id\":\"call_1\"}]," +
                    "\"tools\":[{\"function\":{\"name\":\"read_file\",\"description\":\"Read a file.\",\"parameters\":{}},\"type\":\"function\"}]," +
                    "\"stream\":true,\"reasoning\":{\"effort\":\"medium\"}," +
                    "\"provider\":{\"sort\":\"throughput\",\"max_price\":{\"completion\":1.5}}}",
            encoded,
        )
    }

    @Test
    fun `stream chunk with tool call deltas reasoning and usage parses and encodes to the pinned shape`() {
        val document = buildJsonObject {
            put("id", "c1")
            put("object", "chat.completion.chunk")
            put("created", 12)
            put("model", "m/n")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        put("role", "assistant")
                        put("content", "Hi")
                        put("reasoning", "think")
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", 0)
                                put("id", "call_1")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "read_file")
                                    put("arguments", "{\"pa")
                                })
                            })
                        })
                    })
                    put("finish_reason", JsonNull)
                    put("native_finish_reason", JsonNull)
                })
            })
            put("usage", JsonNull)
        }
        val chunk = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(document.toString())

        val choice = chunk.choices.single()
        assertEquals("Hi", choice.delta.content)
        assertEquals("think", choice.delta.reasoning)
        assertEquals("read_file", choice.delta.toolCalls?.single()?.function?.name)
        assertEquals(0, choice.delta.toolCalls?.single()?.index)
        assertEquals("call_1", choice.delta.toolCalls?.single()?.id)

        val finalChunk = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(
            """{"id":"c2","object":"chat.completion.chunk","created":13,"model":"m/n","choices":[""" +
                    """{"index":0,"delta":{},"finish_reason":"tool_calls","native_finish_reason":"tool_calls"}],"usage":{"prompt_tokens":42,"completion_tokens":5,"total_tokens":47}}"""
        )

        assertEquals("tool_calls", finalChunk.choices.single().finishReason)
        assertEquals("tool_calls", finalChunk.choices.single().nativeFinishReason)
        assertEquals(42, finalChunk.usage?.promptTokens)

        assertEquals(
            "{\"choices\":[{\"delta\":{\"content\":\"Hi\",\"role\":\"assistant\"," +
                    "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"},\"type\":\"function\"}]," +
                    "\"reasoning\":\"think\"}}],\"created\":12,\"id\":\"c1\",\"model\":\"m/n\",\"object\":\"chat.completion.chunk\"}",
            json.encodeToString(chunk),
        )
    }

    @Test
    fun `content deserializer rejects a non-string primitive and an empty parts array`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Content>("42")
        }.let { assertTrue(it.message!!.contains("string"), it.message ?: "") }

        assertFailsWith<SerializationException> {
            json.decodeFromString<Content>("[]")
        }.let { assertTrue(it.message!!.contains("empty"), it.message ?: "") }
    }

    @Test
    fun `content deserializer rejects an object`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Content>("{\"text\":\"x\"}")
        }.let { assertTrue(it.message!!.contains("string or an array"), it.message ?: "") }
    }

    @Test
    fun `stream chunk with usage-only shape parses as an empty choices list`() {
        // OpenRouter sends a final usage-only chunk with an empty choices
        // array; the owned envelope must not require choices to be present.
        val usageChunk = json.decodeFromString<OpenRouterChatCompletionStreamResponse>(
            """{"id":"c4","object":"chat.completion.chunk","created":15,"model":"m/n","choices":[],""" +
                    """"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}"""
        )
        assertEquals(emptyList(), usageChunk.choices)
        assertEquals(9, usageChunk.usage?.promptTokens)
    }
}
