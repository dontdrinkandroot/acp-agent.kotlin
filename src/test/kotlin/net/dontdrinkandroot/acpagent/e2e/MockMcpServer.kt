package net.dontdrinkandroot.acpagent.e2e

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal in-process MCP server over the streamable-HTTP transport, standing in
 * for a real MCP server in the black-box e2e scenarios (same pattern as
 * [MockOpenAiServer] for OpenRouter).
 *
 * Wire contract implemented (verified against kotlin-sdk 0.15.0):
 *  - POST /mcp accepts JSON-RPC messages and answers with `application/json`
 *    (the transport accepts a plain JSON body instead of inline SSE);
 *  - `notifications/initialized` expects 202 Accepted;
 *  - the optional GET SSE stream is refused with 405, which the client treats
 *    as "stream disabled" (non-retryable), and DELETE termination likewise
 *    tolerates 405.
 *
 * Exposes three tools with distinct annotation profiles:
 *  - `mcp_read`        -> `readOnlyHint: true` + `title` (prompt-free when trusted)
 *  - `mcp_write`       -> no annotations (pessimistic default: always prompts)
 *  - `mcp_destructive` -> `readOnlyHint: false` + `destructiveHint: true` + `title`
 *
 * The mutating tools append a line to [markerFile] (side-effect evidence); the
 * read-only tool only returns text.
 */
internal class MockMcpServer(private val markerFile: Path) {

    private var server: HttpServer? = null
    val callCount = AtomicInteger(0)

    val port: Int
        get() = server!!.address.port

    val url: String
        get() = "http://127.0.0.1:$port/mcp"

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/mcp") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    val request = exchange.requestBody.readBytes().decodeToString()
                    val body = handleRequest(request)
                    if (body.isEmpty()) {
                        exchange.sendResponseHeaders(202, -1)
                    } else {
                        val bytes = body.toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                // No server-initiated SSE stream and no session termination: the
                // client degrades gracefully on 405 for both.
                else -> exchange.sendResponseHeaders(405, -1)
            }
            exchange.close()
        }
        http.start()
        server = http
    }

    fun stop() {
        server?.stop(0)
    }

    private fun handleRequest(request: String): String {
        val json = Json.parseToJsonElement(request) as JsonObject
        val id = json["id"] ?: return "" // notification (no response expected)
        val method = (json["method"] as? JsonPrimitive)?.content ?: return ""
        val params = json["params"] as? JsonObject ?: buildJsonObject { }
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                put("serverInfo", buildJsonObject {
                    put("name", "mock-mcp")
                    put("version", "0.0.1")
                })
            }

            "tools/list" -> buildJsonObject { put("tools", toolsJson()) }
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                callTool(name, params["arguments"] as? JsonObject ?: buildJsonObject { })
            }

            else -> buildJsonObject { }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun toolsJson() = buildJsonArray {
        add(buildJsonObject {
            put("name", "mcp_read")
            put("description", "Annotated read-only tool returning a fixed text")
            put("inputSchema", inputSchemaJson())
            put("annotations", buildJsonObject {
                put("title", "Read notes")
                put("readOnlyHint", true)
            })
        })
        add(buildJsonObject {
            put("name", "mcp_write")
            put("description", "Unannotated tool appending to the marker file")
            put("inputSchema", inputSchemaJson())
        })
        add(buildJsonObject {
            put("name", "mcp_destructive")
            put("description", "Annotated destructive tool appending to the marker file")
            put("inputSchema", inputSchemaJson())
            put("annotations", buildJsonObject {
                put("title", "Nuke it")
                put("readOnlyHint", false)
                put("destructiveHint", true)
            })
        })
    }

    private fun inputSchemaJson() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject { put("type", "string") })
        })
    }

    private fun callTool(name: String, arguments: JsonObject) = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put(
                    "text", when (name) {
                        "mcp_read" -> "notes content"
                        "mcp_write" -> {
                            appendMarker("mcp_write:" + flatArguments(arguments))
                            "mcp_write ok"
                        }

                        "mcp_destructive" -> {
                            appendMarker("mcp_destructive:" + flatArguments(arguments))
                            "mcp_destructive ok"
                        }

                        else -> {
                            put("isError", true)
                            appendMarker("unknown:$name")
                            "unknown tool $name"
                        }
                    }
                )
            })
        })
    }

    private fun flatArguments(arguments: JsonObject): String =
        arguments.entries.joinToString(",") { "${it.key}=${(it.value as JsonPrimitive).content}" }

    private fun appendMarker(line: String) {
        callCount.incrementAndGet()
        Files.writeString(markerFile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
