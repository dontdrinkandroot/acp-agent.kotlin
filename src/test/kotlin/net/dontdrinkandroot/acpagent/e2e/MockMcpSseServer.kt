package net.dontdrinkandroot.acpagent.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP server over the **classic SSE** transport (issue #69), standing in
 * for a real SSE MCP server in the black-box e2e scenarios. The tool payloads
 * come from [MockMcpTools], the same ones the streamable-HTTP
 * ([MockMcpServer]) and stdio ([StdioMcpMockServer]) mocks serve.
 *
 * The transport has two halves, which the MCP SDK's `SseClientTransport`
 * requires (kotlin-sdk 0.16.0):
 *  - `GET /sse` answers `text/event-stream` and stays open for the whole
 *    session: it first emits the `endpoint` event carrying the POST target
 *    (the client blocks in `initialize()` until it arrives), then every
 *    JSON-RPC response as a `message` event;
 *  - `POST /messages?sessionId=<id>` accepts JSON-RPC requests and answers
 *    `202 Accepted` with no body - the response travels back over the stream.
 *
 * [requiredHeader] makes the stream refuse requests that do not carry the
 * given header value, which is how the header-forwarding scenario proves that
 * `McpServer.Sse.headers` actually reaches the server.
 */
internal class MockMcpSseServer(
    private val markerFile: Path,
    private val requiredHeader: Pair<String, String>? = null,
) {

    private var server: HttpServer? = null
    private val sessions = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()

    @Volatile
    private var stopped = false

    val callCount = AtomicInteger(0)

    /** Request headers of every `GET /sse`, captured as the server saw them. */
    val streamRequestHeaders: MutableList<Map<String, List<String>>> =
        java.util.Collections.synchronizedList(mutableListOf())

    val port: Int
        get() = server!!.address.port

    val url: String
        get() = "http://127.0.0.1:$port/sse"

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/sse") { exchange -> handleStream(exchange) }
        http.createContext("/messages") { exchange -> handleMessage(exchange) }
        http.start()
        server = http
    }

    fun stop() {
        stopped = true
        server?.stop(0)
    }

    /** Case-insensitive header lookup over every stream request the mock saw. */
    fun sentHeader(name: String): String? = streamRequestHeaders
        .flatMap { headers -> headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value } }
        .firstOrNull()

    private fun handleStream(exchange: HttpExchange) {
        streamRequestHeaders += exchange.requestHeaders.mapValues { it.value.toList() }
        if (!hasRequiredHeader(exchange)) {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        val sessionId = UUID.randomUUID().toString()
        val queue = LinkedBlockingQueue<String>()
        sessions[sessionId] = queue
        try {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            // 0 = unknown length: the stream stays open until we close it.
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            writeEvent(out, "endpoint", "/messages?sessionId=$sessionId")
            while (!stopped) {
                val message = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                writeEvent(out, "message", message)
            }
        } catch (e: IOException) {
            // The agent closed the stream (session end / agent shutdown): expected.
        } finally {
            sessions.remove(sessionId)
            runCatching { exchange.close() }
        }
    }

    private fun hasRequiredHeader(exchange: HttpExchange): Boolean {
        val (name, value) = requiredHeader ?: return true
        val sent = exchange.requestHeaders.entries
            .filter { it.key.equals(name, ignoreCase = true) }
            .flatMap { it.value }
        return sent.any { it == value }
    }

    private fun handleMessage(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            val sessionId = exchange.requestURI.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("sessionId=") }
                ?.substringAfter("sessionId=")
            val queue = sessionId?.let { sessions[it] }
            val body = exchange.requestBody.readBytes().decodeToString()
            val response = handleRequest(body)
            if (response != null) {
                queue?.put(response)
            }
            exchange.sendResponseHeaders(202, -1)
        } finally {
            runCatching { exchange.close() }
        }
    }

    /** The JSON-RPC response for [request], or null for a notification. */
    private fun handleRequest(request: String): String? {
        val json = Json.parseToJsonElement(request) as JsonObject
        val id = json["id"] ?: return null
        val method = (json["method"] as? JsonPrimitive)?.content ?: return null
        val params = json["params"] as? JsonObject ?: buildJsonObject { }
        val result = when (method) {
            "initialize" -> MockMcpTools.initializeResult()
            "tools/list" -> buildJsonObject { put("tools", MockMcpTools.toolsJson()) }
            "tools/call" -> MockMcpTools.callTool(
                name = (params["name"] as? JsonPrimitive)?.content ?: "",
                arguments = params["arguments"] as? JsonObject ?: buildJsonObject { },
                appendMarker = ::appendMarker,
            )

            else -> buildJsonObject { }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun writeEvent(out: OutputStream, event: String, data: String) {
        out.write("event: $event\ndata: $data\n\n".toByteArray())
        out.flush()
    }

    private fun appendMarker(line: String) {
        callCount.incrementAndGet()
        Files.writeString(markerFile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
