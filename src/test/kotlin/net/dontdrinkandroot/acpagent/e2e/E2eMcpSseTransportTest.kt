package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box MCP coverage of the **classic SSE** transport (issue #69) - the one
 * ACP variant left without an e2e after #60 closed the stdio gap. The transport
 * is deprecated upstream (SEP-2596) but ACP's `mcpServers` still carries the
 * `sse` variant, so "kept for compatibility" is exactly what needs a pin.
 *
 * The mock ([MockMcpSseServer]) serves the same tools as the HTTP and stdio
 * mocks ([MockMcpTools]), so a scenario here says the same thing about the
 * agent as its counterparts, only over a stream-plus-POST transport.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eMcpSseTransportTest : E2eAgentTest() {

    private fun E2eContext.sseServer(
        mcp: MockMcpSseServer,
        headers: List<HttpHeader> = emptyList(),
    ): McpServer.Sse = McpServer.Sse(name = "mock-sse", url = mcp.url, headers = headers)

    @Test
    fun `e2e read-only annotated mcp tool runs without permission over sse`() = runBlocking {
        withE2eAgent(
            "mcp-sse-readonly",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_read", buildJsonObject { })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpSseServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(sseServer(mcpMock)),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the mcp notes")))
                    assertEndTurn(events)

                    assertTrue(ops.permissionRequests.isEmpty(), "read-only MCP tool must not ask permission")
                    assertTrue(!markerFile.toFile().exists(), "read-only tool must not touch the marker file")
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val toolCall = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
                    assertEquals("Read notes", toolCall.title, "the title annotation must be the tool-call title")
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                        .filter { it.toolCallId == toolCall.toolCallId }
                    assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                    assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                    assertTrue(resultUpdates.last().rawOutput.toString().contains("notes content"))
                    println("[ok] read-only annotated MCP tool ran without a permission prompt over sse")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    @Test
    fun `e2e unannotated mcp tool asks permission and executes over sse`() = runBlocking {
        withE2eAgent(
            "mcp-sse-write",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_write", buildJsonObject { put("k", "v") })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpSseServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(sseServer(mcpMock)),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Run the mcp write")))
                    assertEndTurn(events)

                    assertEquals(1, ops.permissionRequests.size, "an unannotated MCP tool must ask permission")
                    assertEquals("mcp_write", ops.permissionRequests.single().title)
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                    assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                    // The marker is written by the mock server, so it proves the call
                    // travelled POST -> stream and not just that the agent logged a result.
                    val marker = markerFile.toFile()
                    assertTrue(marker.isFile, "the allowed MCP tool call must have run on the server")
                    assertEquals(
                        "mcp_write:k=v",
                        marker.readText().trim(),
                        "marker must record the arguments the server received",
                    )
                    println("[ok] unannotated MCP tool asked permission and executed over sse")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    /**
     * `McpServer.Sse.headers` must reach the server: the mock refuses the stream
     * unless the configured header arrives, so a dropped `headers.forEach` in
     * `connectSse` fails this scenario instead of silently connecting.
     */
    @Test
    fun `e2e sse server headers are forwarded`() = runBlocking {
        withE2eAgent(
            "mcp-sse-headers",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_read", buildJsonObject { })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpSseServer(markerFile, requiredHeader = "X-Mcp-Token" to "token-42")
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(
                                sseServer(mcpMock, listOf(HttpHeader(name = "X-Mcp-Token", value = "token-42"))),
                            ),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the mcp notes")))
                    assertEndTurn(events)

                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    assertEquals(
                        ToolCallStatus.COMPLETED,
                        resultUpdates.last().status,
                        "the MCP tool must run, which needs the configured header on the SSE stream",
                    )
                    assertEquals(
                        "token-42",
                        mcpMock.sentHeader("X-Mcp-Token"),
                        "McpServer.Sse.headers must reach the server on the SSE request",
                    )
                    println("[ok] McpServer.Sse.headers reached the server")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }
}
