package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box web_fetch: available and prompt-free in plan mode (read-only, no
 * filesystem targets), fetches an HTTP page and returns it as numbered text,
 * with the SSRF guard blocking loopback unless the agent runs with
 * ACP_WEB_FETCH_ALLOW_PRIVATE=1 (the local mock server is the fixture here).
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eWebFetchTest : E2eAgentTest() {

    /** Starts a loopback HTTP server serving one HTML page; stops it in [finally]. */
    private fun startWebServer(path: String, contentType: String, body: String): HttpServer {
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(1)
        val bytes = body.toByteArray()
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `e2e web_fetch fetches a page prompt-free in plan mode`() = runBlocking {
        val webServer = startWebServer(
            "/page",
            "text/html; charset=utf-8",
            "<html><body><h1>Probe Heading</h1><p>probe paragraph</p></body></html>",
        )
        try {
            withE2eAgent("webfetch", { _ ->
                MockOpenAiServer(
                    "unused",
                    toolCall = MockToolCall(
                        "web_fetch",
                        buildJsonObject { put("url", "http://127.0.0.1:${webServer.address.port}/page") },
                    ),
                )
            }) {
                val connection = connect(extraEnv = mapOf("ACP_WEB_FETCH_ALLOW_PRIVATE" to "1"))
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(
                        session,
                        listOf(ContentBlock.Text("Fetch http://127.0.0.1:${webServer.address.port}/page")),
                    )
                    assertEndTurn(events)

                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    assertTrue(ops.permissionRequests.isEmpty(), "web_fetch is read-only and must not ask permission")
                    val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
                    assertEquals(1, toolCalls.size)
                    assertEquals(ToolKind.FETCH, toolCalls.single().kind)
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                        .filter { it.toolCallId == toolCalls.single().toolCallId }
                    assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                    assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                    val output = resultUpdates.last().rawOutput.toString()
                    assertTrue(output.contains("Probe Heading"), "output must contain the heading: $output")
                    assertTrue(output.contains("probe paragraph"), "output must contain the paragraph: $output")
                    println("[ok] web_fetch fetched a page prompt-free in plan mode")
                } finally {
                    connection.close()
                }
            }
        } finally {
            webServer.stop(0)
        }
    }

    @Test
    fun `e2e web_fetch blocks loopback without the opt-out`() = runBlocking {
        val webServer = startWebServer("/page", "text/plain", "never")
        try {
            withE2eAgent("webfetch-blocked", { _ ->
                MockOpenAiServer(
                    "unused",
                    toolCall = MockToolCall(
                        "web_fetch",
                        buildJsonObject { put("url", "http://127.0.0.1:${webServer.address.port}/page") },
                    ),
                )
            }) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(
                        session,
                        listOf(ContentBlock.Text("Fetch http://127.0.0.1:${webServer.address.port}/page")),
                    )
                    assertEndTurn(events)

                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    assertEquals(ToolCallStatus.FAILED, resultUpdates.last().status)
                    val output = resultUpdates.last().rawOutput.toString()
                    assertTrue(
                        output.contains("private or loopback"),
                        "the SSRF guard must refuse the loopback host: $output",
                    )
                    assertTrue(
                        output.contains("ACP_WEB_FETCH_ALLOW_PRIVATE"),
                        "the error must name the opt-out: $output",
                    )
                    println("[ok] web_fetch refused the loopback host without the opt-out")
                } finally {
                    connection.close()
                }
            }
        } finally {
            webServer.stop(0)
        }
    }
}
