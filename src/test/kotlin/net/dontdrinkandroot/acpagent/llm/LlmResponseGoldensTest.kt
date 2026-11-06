package net.dontdrinkandroot.acpagent.llm

import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.models.*
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputImage
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputText
import kotlin.test.*

/**
 * Golden wire pins for the OpenRouter Responses API traffic. The expected
 * strings are derived from the documented shapes (api_reference/responses);
 * Like the wire pins before them they are byte-exact, do not "fix" them by hand -
 * regenerate from a failing run and sanity-check the shape against the docs.
 */
internal class LlmResponseGoldensTest {

    private val reasoningItem = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_abc123")
        put("encrypted_content", "gAAAAABotI9-FK1PbhZhaZk4yMrZw3XDI1AWFaKb9T0NQq7LndK6zaRB...")
        put("summary", buildJsonArray {
            add(JsonPrimitive("First, I need to determine the current year"))
        })
    }

    private val request = ResponsesRequest(
        model = "anthropic/claude-sonnet-4.5",
        input = listOf(
            InputMessage("user", listOf(InputText("What is in this image?"))).toJsonItem(),
            InputMessage(
                "user",
                listOf(
                    InputText("Describe this file:"),
                    InputImage("data:image/png;base64,AAAA"),
                ),
            ).toJsonItem(),
            // Assistant history is replayed verbatim (captured output items).
            buildJsonObject {
                put("type", "message")
                put("id", "msg_1")
                put("role", "assistant")
                put("status", "completed")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "output_text")
                        put("text", "I will look at the file.")
                        put("annotations", buildJsonArray { })
                    })
                })
            },
            buildJsonObject {
                put("type", "function_call")
                put("id", "fc_1")
                put("call_id", "call_1")
                put("name", "read_file")
                put("arguments", "{\"path\":\"src/main.kt\"}")
            },
            reasoningItem,
            FunctionCallOutput(callId = "call_1", output = "fun main() {}").toJsonItem(),
        ),
        tools = listOf(FunctionTool("read_file", "Read a file.", buildJsonObject { })),
        reasoning = ReasoningRequest("medium"),
        provider = null,
    )

    @Test
    fun `request with items tools and reasoning has the pinned wire shape`() {
        val encoded = llmWireJson.encodeToString(request)

        assertEquals(
            "{\"model\":\"anthropic/claude-sonnet-4.5\",\"input\":[" +
                    "{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"What is in this image?\"}],\"type\":\"message\"}," +
                    "{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Describe this file:\"}," +
                    "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AAAA\"}],\"type\":\"message\"}," +
                    "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\"," +
                    "\"content\":[{\"type\":\"output_text\",\"text\":\"I will look at the file.\",\"annotations\":[]}]}," +
                    "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"read_file\"," +
                    "\"arguments\":\"{\\\"path\\\":\\\"src/main.kt\\\"}\"}," +
                    "{\"type\":\"reasoning\",\"id\":\"rs_abc123\"," +
                    "\"encrypted_content\":\"gAAAAABotI9-FK1PbhZhaZk4yMrZw3XDI1AWFaKb9T0NQq7LndK6zaRB...\"," +
                    "\"summary\":[\"First, I need to determine the current year\"]}," +
                    "{\"call_id\":\"call_1\",\"output\":\"fun main() {}\",\"type\":\"function_call_output\"}]," +
                    "\"tools\":[{\"name\":\"read_file\",\"description\":\"Read a file.\",\"parameters\":{},\"type\":\"function\"}]," +
                    "\"stream\":true,\"reasoning\":{\"effort\":\"medium\"}}",
            encoded,
        )
    }

    @Test
    fun `request omits temperature max_output_tokens stateless params and reasoning context`() {
        val encoded = llmWireJson.encodeToString(request)
        val obj = llmWireJson.parseToJsonElement(encoded).jsonObject

        assertFalse(obj.containsKey("temperature"), "temperature must not be sent")
        assertFalse(
            obj.containsKey("max_output_tokens"),
            "max_output_tokens must be omitted (decision: provider defaults)"
        )
        listOf("store", "previous_response_id", "include", "tool_choice", "parallel_tool_calls", "text").forEach {
            assertFalse(obj.containsKey(it), "$it must not be sent")
        }
        assertNull(obj["reasoning"]!!.jsonObject["context"], "reasoning.context must be omitted")
    }

    @Test
    fun `reasoning item passes through construct-encode-decode-encode unchanged`() {
        // The replay path: an output item captured from a response is
        // re-serialized into the next request byte-identically.
        val decoded = llmWireJson.parseToJsonElement(reasoningItem.toString())
        val reEncoded = llmWireJson.encodeToString(
            ResponsesRequest(model = "m/n", input = listOf(decoded.jsonObject)),
        )
        val inputElement = llmWireJson.parseToJsonElement(reEncoded).jsonObject["input"]!!
        val inputArray = inputElement as kotlinx.serialization.json.JsonArray
        assertEquals(
            reasoningItem.toString(),
            inputArray.single().toString(),
        )
        assertTrue(
            inputArray.single().toString().contains("\"encrypted_content\":\"gAAAAABotI9"),
            "encrypted content survives verbatim"
        )
    }

    @Test
    fun `response output array carries items verbatim for history capture`() {
        // The non-streaming "Response with Reasoning" example from the docs.
        val body = """
            {"id":"resp_1","object":"response","created_at":1,"model":"m/n",
             "output":[
               {"type":"reasoning","id":"rs_1","encrypted_content":"enc...","summary":["step one"]},
               {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                "content":[{"type":"output_text","text":"The answer is 42.","annotations":[]}]}],
             "usage":{"input_tokens":15,"output_tokens":85,"total_tokens":100},
             "status":"completed"}
        """.trimIndent()
        val response = llmWireJson.decodeFromString<ResponsesResponse>(body)

        assertEquals(2, response.output.size)
        assertEquals("rs_1", response.output[0]["id"]!!.let { (it as JsonPrimitive).content })
        assertEquals("reasoning", response.output[0]["type"]!!.let { (it as JsonPrimitive).content })
        assertEquals("completed", response.status)
        assertEquals(85, response.usage?.outputTokens)
    }

    @Test
    fun `response failed with canonical error_type parses`() {
        val body = """
            {"id":"resp_abc123","status":"failed","error":{"code":"server_error","message":"Invalid credentials"},"error_type":"authentication"}
        """.trimIndent()
        val response = llmWireJson.decodeFromString<ResponsesResponse>(body)

        assertEquals("failed", response.status)
        assertEquals("server_error", response.error?.code)
        assertEquals("Invalid credentials", response.error?.message)
        assertEquals("authentication", response.errorType)
    }

    @Test
    fun `response incomplete with max_output_tokens reason parses`() {
        val body = """
            {"id":"resp_2","object":"response","status":"incomplete",
             "incomplete_details":{"reason":"max_output_tokens"},
             "usage":{"input_tokens":10,"output_tokens":900,"total_tokens":910,"output_tokens_details":{"reasoning_tokens":900}}}
        """.trimIndent()
        val response = llmWireJson.decodeFromString<ResponsesResponse>(body)

        assertEquals("incomplete", response.status)
        assertEquals("max_output_tokens", response.incompleteDetails?.reason)
        assertEquals(900, response.usage?.outputTokensDetails?.reasoningTokens)
    }

    private fun InputMessage.toJsonItem(): kotlinx.serialization.json.JsonObject =
        llmWireJson.encodeToJsonElement(this) as kotlinx.serialization.json.JsonObject

    private fun FunctionCallOutput.toJsonItem(): kotlinx.serialization.json.JsonObject =
        llmWireJson.encodeToJsonElement(this) as kotlinx.serialization.json.JsonObject
}

