package net.dontdrinkandroot.acpagent.e2e

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class MockToolCall(val name: String, val arguments: JsonObject)

internal fun pathArgs(path: String): JsonObject = buildJsonObject { put("path", path) }

internal fun readFileArgs(path: String, limit: Int = 2000): JsonObject =
    buildJsonObject {
        put("path", path)
        put("limit", limit)
    }

/**
 * Minimal OpenAI-compatible streaming server:
 *  - 1st completion request  -> a tool_call for `write_file` (writes 'phase4' to the target path)
 *  - subsequent completions  -> plain text chunks "phase4 done"
 * With [textOnly] every request is answered with plain text instead.
 * With [alwaysToolCall] every request carrying a tools field is answered with a `write_file`
 * tool call, while requests without tools (the wind-down pass) get plain text.
 * With [toolCalls] the Nth completion request answers with the Nth tool call (a `null`
 * entry answers with plain text, ending the turn), falling back to plain text afterwards.
 * With [incompleteWindDown] a tools-less request (the wind-down pass) answers `incomplete`
 * with no content, so the agent's synthesized interrupted-note branch runs.
 */
internal class MockOpenAiServer(
    private val targetPath: String,
    private val textOnly: Boolean = false,
    private val planMode: Boolean = false,
    private val imageSupport: Boolean = false,
    private val failEndpoints: Boolean = false,
    private val toolCall: MockToolCall? = null,
    private val toolCalls: List<MockToolCall?> = emptyList(),
    private val firstTurnStreamDeltas: Boolean = false,
    private val alwaysToolCall: Boolean = false,
    private val incompleteWindDown: Boolean = false,
    private val holdFirstRequest: Boolean = false,
) {
    val requestCount = AtomicInteger(0)
    var lastRequestBody: String? = null
    var lastRequestHeaders: Map<String, List<String>> = emptyMap()
    val endpointRequestCount = AtomicInteger(0)
    val requestBodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    /**
     * With [holdFirstRequest] the first chat completion request is held until
     * [releaseFirstRequest] completes, so a test can deterministically
     * interleave a mode switch with a running turn instead of racing a fast
     * mock turn. Default off: every other test's first request passes through.
     */
    val firstRequestStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val firstRequestGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun releaseFirstRequest() = firstRequestGate.complete(Unit)

    private var server: HttpServer? = null
    val port: Int
        get() = server!!.address.port

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/responses") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            lastRequestBody = exchange.requestBody.readBytes().decodeToString()
            lastRequestHeaders = exchange.requestHeaders
            requestBodies += lastRequestBody!!
            val n = requestCount.incrementAndGet()
            // Hold the first chat request (only when the test armed the gate) so
            // the test can deterministically interleave a mode switch with a
            // running turn.
            if (n == 1) {
                firstRequestStarted.complete(Unit)
                if (holdFirstRequest) runBlocking { firstRequestGate.await() }
            }
            val body = when {
                // Always-tool-call mode: requests that carry tools get a tool call, a request
                // without the tools field (the wind-down pass) gets plain text.
                alwaysToolCall && lastRequestBody!!.contains("\"tools\"") ->
                    toolCallSse("write_file", writeFileArguments())

                alwaysToolCall && incompleteWindDown -> incompleteSse()

                alwaysToolCall -> textSse()
                toolCalls.isNotEmpty() && n <= toolCalls.size -> {
                    val call = toolCalls[n - 1]
                    if (call != null) toolCallSse(call.name, call.arguments) else textSse()
                }
                planMode && n == 1 -> planSse()
                toolCall != null && n == 1 -> toolCallSse(toolCall.name, toolCall.arguments)
                textOnly || n > 1 -> textSse()
                else -> toolCallSse("write_file", writeFileArguments())
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/models") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val body = modelsJson()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/models/test-model/endpoints") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            endpointRequestCount.incrementAndGet()
            if (failEndpoints) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return@createContext
            }
            val body =
                """{"data":{"endpoints":[""" +
                        """{"name":"Provider One | test-model","tag":"p1","provider_name":"Provider One","pricing":{"completion":"0.00002"}},""" +
                        """{"name":"Provider Two | test-model","tag":"p2","provider_name":"Provider Two","pricing":{"completion":"0.0001"}}]}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.start()
        server = http
    }

    private fun modelsJson(): String {
        val inputModalities = if (imageSupport) "[\"text\",\"image\"]" else "[\"text\"]"
        return """{"data":[{"id":"test-model","name":"Test Model","description":"A test model","supported_parameters":["tools"],""" +
                """"architecture":{"input_modalities":$inputModalities,"output_modalities":["text"]},""" +
                """"context_length":16384,"reasoning":{"supported_efforts":["high","medium"],"default_effort":"high"}}]}"""
    }

    fun stop() {
        server?.stop(0)
    }

    fun parseChatBody(body: String?): JsonObject =
        llmWireJson.parseToJsonElement(body ?: error("no chat body captured")).jsonObject

    private fun writeFileArguments(): JsonObject = JsonObject(
        mapOf(
            "path" to JsonPrimitive(targetPath),
            "content" to JsonPrimitive("phase4"),
        )
    )

    /**
     * A Responses-API tool-call stream: one `function_call` output item carrying
     * [name]/[arguments], completed by the terminal event.
     */
    private fun toolCallSse(name: String, arguments: JsonObject): String {
        val callId = "call_$name"
        val item = buildJsonObject {
            put("type", "function_call")
            put("id", "fc_$name")
            put("call_id", callId)
            put("name", name)
            put("arguments", arguments.toString())
        }
        return buildString {
            if (firstTurnStreamDeltas) {
                append(reasoningEvent("priming the write"))
                append(contentEvent("writing the file"))
            }
            append(outputItemAddedEvent(0, item))
            append(outputItemDoneEvent(0, item))
            append(responseDoneEvent("completed"))
            append("data: [DONE]\n\n")
        }
    }

    private fun planSse(): String {
        val arguments = JsonObject(
            mapOf(
                "entries" to buildJsonArray {
                    add(buildJsonObject {
                        put("content", "Investigate the bug")
                        put("priority", "high")
                        put("status", "in_progress")
                    })
                    add(buildJsonObject {
                        put("content", "Fix the bug")
                        put("priority", "medium")
                        put("status", "pending")
                    })
                }
            )
        )
        return toolCallSse("update_plan", arguments)
    }

    /**
     * A Responses-API text stream: reasoning deltas, content deltas, the
     * completed assistant message item and the terminal event carrying usage.
     */
    private fun textSse(): String = buildString {
        append(reasoningEvent("pondering "))
        append(reasoningEvent("the request"))
        append(contentEvent("phase4 "))
        append(contentEvent("done"))
        val item = buildJsonObject {
            put("type", "message")
            put("id", "msg_text")
            put("role", "assistant")
            put("status", "completed")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", "phase4 done")
                })
            })
        }
        append(outputItemAddedEvent(0, item))
        append(outputItemDoneEvent(0, item))
        append(
            responseDoneEvent(
                "completed",
                testUsage(),
            )
        )
        append("data: [DONE]\n\n")
    }

    /**
     * A Responses-API stream cut off at the token limit before any output: no
     * content, an `incomplete` terminal event with `incomplete_details` and usage.
     */
    private fun incompleteSse(): String =
        responseDoneEvent("incomplete", testUsage(), incompleteReason = "max_output_tokens") + "data: [DONE]\n\n"

    private fun reasoningEvent(text: String): String =
        "data: " + buildJsonObject {
            put("type", "response.reasoning.delta")
            put("output_index", 0)
            put("delta", text)
        } + "\n\n"

    private fun contentEvent(text: String): String =
        "data: " + buildJsonObject {
            put("type", "response.content_part.delta")
            put("output_index", 0)
            put("delta", text)
        } + "\n\n"

    private fun outputItemAddedEvent(index: Int, item: JsonObject): String =
        "data: " + buildJsonObject {
            put("type", "response.output_item.added")
            put("output_index", index)
            put("item", item)
        } + "\n\n"

    private fun outputItemDoneEvent(index: Int, item: JsonObject): String =
        "data: " + buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", index)
            put("item", item)
        } + "\n\n"

    private fun responseDoneEvent(
        status: String,
        usage: JsonObject? = null,
        incompleteReason: String? = null,
    ): String =
        "data: " + buildJsonObject {
            // The live endpoint emits response.completed / response.incomplete,
            // never the documented response.done (verified 2026-11-06).
            put("type", if (status == "incomplete") "response.incomplete" else "response.completed")
            put("response", buildJsonObject {
                put("id", "resp_test")
                put("model", "test-model")
                put("status", status)
                incompleteReason?.let { reason ->
                    put("incomplete_details", buildJsonObject { put("reason", reason) })
                }
                usage?.let { put("usage", it) }
            })
        } + "\n\n"

    private fun testUsage(): JsonObject = buildJsonObject {
        put("input_tokens", 42)
        put("output_tokens", 5)
        put("total_tokens", 47)
    }
}
