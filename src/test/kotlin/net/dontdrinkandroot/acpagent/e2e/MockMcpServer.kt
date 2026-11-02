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
 * [MockOpenAiServer] for OpenRouter). The tool payloads live in [MockMcpTools],
 * shared with the stdio mock ([StdioMcpMockServer]).
 *
 * Wire contract implemented (verified against kotlin-sdk 0.16.0):
 *  - POST /mcp accepts JSON-RPC messages and answers with `application/json`
 *    (the transport accepts a plain JSON body instead of inline SSE);
 *  - `notifications/initialized` expects 202 Accepted;
 *  - the optional GET SSE stream is refused with 405, which the client treats
 *    as "stream disabled" (non-retryable), and DELETE termination likewise
 *    tolerates 405.
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
            "initialize" -> MockMcpTools.initializeResult()
            "tools/list" -> buildJsonObject { put("tools", MockMcpTools.toolsJson()) }
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                MockMcpTools.callTool(name, params["arguments"] as? JsonObject ?: buildJsonObject { }, ::appendMarker)
            }

            else -> buildJsonObject { }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun appendMarker(line: String) {
        callCount.incrementAndGet()
        Files.writeString(markerFile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
