package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box MCP tool permissions: a server-annotated `readOnlyHint: true` tool
 * runs without a permission prompt (annotations trusted by default), while
 * unannotated tools keep prompting and `MCP_TRUST_ANNOTATIONS=0` restores the
 * pessimistic always-prompt default. Backed by an in-process streamable-HTTP
 * MCP mock, which also pins the `mcpCapabilities.http` advertisement.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eMcpToolPermissionTest : E2eAgentTest() {

    private fun E2eContext.mcpServer(mcp: MockMcpServer): McpServer.Http =
        McpServer.Http(name = "mock", url = mcp.url, headers = emptyList())

    @Test
    fun `e2e read-only annotated mcp tool runs without permission`() = runBlocking {
        withE2eAgent(
            "mcp-readonly",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_read", buildJsonObject { })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(mcpServer(mcpMock)),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the mcp notes")))
                    assertEndTurn(events)

                    // Behavioral change: a trusted readOnlyHint:true must not prompt.
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
                    println("[ok] read-only annotated MCP tool ran without a permission prompt")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    @Test
    fun `e2e unannotated mcp tool asks permission and executes when allowed`() = runBlocking {
        withE2eAgent(
            "mcp-write",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_write", buildJsonObject { put("k", "v") })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(mcpServer(mcpMock)),
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
                    val marker = markerFile.toFile()
                    assertTrue(marker.isFile, "the allowed MCP tool call must have run")
                    assertTrue(
                        marker.readText().startsWith("mcp_write:"),
                        "marker must record the call: ${marker.readText()}"
                    )
                    println("[ok] unannotated MCP tool asked permission and executed when allowed")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    @Test
    fun `e2e read-only annotation is untrusted when MCP_TRUST_ANNOTATIONS is disabled`() = runBlocking {
        withE2eAgent(
            "mcp-untrusted",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_read", buildJsonObject { })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect(extraEnv = mapOf("MCP_TRUST_ANNOTATIONS" to "0"))
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(mcpServer(mcpMock)),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the mcp notes")))
                    assertEndTurn(events)

                    assertEquals(
                        1,
                        ops.permissionRequests.size,
                        "with untrusted annotations the read-only tool must fall back to prompting",
                    )
                    assertEquals("mcp_read", ops.permissionRequests.single().title, "untrusted titles must not be used")
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                    assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                    println("[ok] MCP_TRUST_ANNOTATIONS=0 kept the pessimistic permission prompt")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    @Test
    fun `e2e destructive mcp tool asks permission with the annotated title`() = runBlocking {
        withE2eAgent("mcp-destructive", { projectDir ->
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "mcp_destructive",
                    buildJsonObject { put("path", projectDir.resolve("marker.txt").absolutePath) }),
            )
        }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val mcpMock = MockMcpServer(markerFile)
            mcpMock.start()
            try {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = connection.client.newSession(
                        SessionCreationParameters(
                            cwd = projectDir.absolutePath,
                            mcpServers = listOf(mcpServer(mcpMock)),
                        ),
                        ClientOperationsFactory { _, _ -> ops },
                    )
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Nuke it")))
                    assertEndTurn(events)

                    assertEquals(1, ops.permissionRequests.size, "a destructive MCP tool must ask permission")
                    assertEquals("Nuke it", ops.permissionRequests.single().title)
                    val rawInput = ops.permissionRequests.single().rawInput as JsonObject
                    assertEquals(
                        markerFile.toFile().absolutePath,
                        (rawInput["path"] as JsonPrimitive).content,
                        "rawInput must still carry the actual arguments",
                    )
                    val marker = markerFile.toFile()
                    assertTrue(marker.isFile, "the allowed destructive call must have run")
                    assertTrue(
                        marker.readText().startsWith("mcp_destructive:"),
                        "marker must record the call: ${marker.readText()}"
                    )
                    println("[ok] destructive MCP tool asked permission with the annotated title")
                } finally {
                    connection.close()
                }
            } finally {
                mcpMock.stop()
            }
        }
    }

    @Test
    fun `e2e initialize advertises the streamable http mcp capability`() = runBlocking {
        withE2eAgent("mcp-cap", { MockOpenAiServer("unused") }) {
            val connection = connect()
            try {
                val info = connection.client.initialize(testClientInfo())
                assertTrue(info.capabilities.mcpCapabilities.http, "mcpCapabilities.http must be advertised")
                println("[ok] initialize advertises mcpCapabilities.http")
            } finally {
                connection.close()
            }
        }
    }
}
