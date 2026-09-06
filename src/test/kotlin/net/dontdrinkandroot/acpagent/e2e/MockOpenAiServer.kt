package net.dontdrinkandroot.acpagent.e2e

import com.sun.net.httpserver.HttpServer
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
 */
internal class MockOpenAiServer(
    private val targetPath: String,
    private val textOnly: Boolean = false,
    private val planMode: Boolean = false,
    private val imageSupport: Boolean = false,
    private val failEndpoints: Boolean = false,
    private val toolCall: MockToolCall? = null,
    private val firstTurnStreamDeltas: Boolean = false,
    private val alwaysToolCall: Boolean = false,
) {
    val requestCount = AtomicInteger(0)
    var lastRequestBody: String? = null
    var lastRequestHeaders: Map<String, List<String>> = emptyMap()
    val endpointRequestCount = AtomicInteger(0)
    val requestBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var server: HttpServer? = null
    val port: Int
        get() = server!!.address.port

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/chat/completions") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            lastRequestBody = exchange.requestBody.readBytes().decodeToString()
            lastRequestHeaders = exchange.requestHeaders
            requestBodies += lastRequestBody!!
            val n = requestCount.incrementAndGet()
            val body = when {
                // Always-tool-call mode: requests that carry tools get a tool call, a request
                // without the tools field (the wind-down pass) gets plain text.
                alwaysToolCall && lastRequestBody!!.contains("\"tools\"") ->
                    toolCallSse("write_file", writeFileArguments())

                alwaysToolCall -> textSse()
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
                """{"data":{"endpoints":[{"name":"p1","pricing":{"completion":"0.00002"}},{"name":"p2","pricing":{"completion":"0.0001"}}]}}"""
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

    private fun toolCallSse(name: String, arguments: JsonObject): String {
        val firstTurnDeltas = if (firstTurnStreamDeltas) {
            "data: " + "{\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"priming the write\",\"content\":\"\"},\"finish_reason\":null}]}\n\n" +
                "data: " + "{\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"writing the file\"},\"finish_reason\":null}]}\n\n"
        } else {
            ""
        }
        val chunk = buildJsonObject {
            put("id", "chatcmpl-ph4-1")
            put("object", "chat.completion.chunk")
            put("created", 0)
            put("model", "test-model")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("finish_reason", "tool_calls")
                            put(
                                "delta",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("index", 0)
                                                    put("id", "call_write")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", name)
                                                            put("arguments", arguments.toString())
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return firstTurnDeltas +
            "data: $chunk\n\n" +
            "data: {\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
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
        val chunk = buildJsonObject {
            put("id", "chatcmpl-plan-1")
            put("object", "chat.completion.chunk")
            put("created", 0)
            put("model", "test-model")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("finish_reason", "tool_calls")
                            put(
                                "delta",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("index", 0)
                                                    put("id", "call_plan")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", "update_plan")
                                                            put("arguments", arguments.toString())
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return "data: $chunk\n\n" +
                "data: {\"id\":\"chatcmpl-plan-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
    }

    private fun textSse(): String {
        return buildString {
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"pondering \",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"the request\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"phase4 \"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\",\"choices\":[]," +
                        "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":5,\"total_tokens\":47}}\n\n"
            )
            append("data: [DONE]\n\n")
        }
    }
}
