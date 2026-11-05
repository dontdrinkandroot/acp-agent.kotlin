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
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box MCP coverage of the **stdio** transport - the one the ACP spec makes
 * mandatory ("All Agents MUST support this transport"). The HTTP transport is
 * covered by [E2eMcpToolPermissionTest]; both mocks serve the same tools
 * ([MockMcpTools]), so a scenario here says the same thing about the agent's
 * behavior as its HTTP counterpart, only over a pipe to a real child process.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eMcpStdioTransportTest : E2eAgentTest() {

    /**
     * Launches [StdioMcpMockServer] as a separate JVM. The marker path travels
     * through `env` (not `args`), which also pins that the agent forwards
     * `McpServer.Stdio.env` into the child's environment.
     */
    private fun stdioServer(
        markerFile: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): McpServer.Stdio =
        McpServer.Stdio(
            name = "mock-stdio",
            command = javaBinary,
            args = listOf("-cp", testClasspath, StdioMcpMockServer::class.java.name),
            env = listOf(EnvVariable("MOCK_MCP_MARKER", markerFile.absolutePath)) +
                    extraEnv.map { (name, value) -> EnvVariable(name, value) },
        )

    @Test
    fun `e2e stdio mcp server runs in the session cwd without the agent's secrets`() = runBlocking {
        withE2eAgent(
            "mcp-stdio-launch",
            { MockOpenAiServer("unused", textOnly = true) },
        ) {
            // The agent process itself starts somewhere else, so the child's cwd
            // can only match the session cwd if the agent sets it (issue #74).
            val agentWorkingDir = Files.createTempDirectory("acp-agent-e2e-launch-cwd").toFile()
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val process = startAgent(llmMock.port, stateHome, workingDir = agentWorkingDir)
            val connection = connectProcess(process)
            try {
                connection.client.initialize(testClientInfo())
                val session = connection.client.newSession(
                    SessionCreationParameters(
                        cwd = projectDir.absolutePath,
                        mcpServers = listOf(
                            stdioServer(markerFile.toFile(), mapOf("MOCK_MCP_PROBE" to "1")),
                        ),
                    ),
                    ClientOperationsFactory { _, _ -> TestClientOperations() },
                )
                val events = collectPrompt(session, listOf(ContentBlock.Text("Hello")))
                assertEndTurn(events)

                val probe = markerLines(markerFile.toFile())
                assertEquals(
                    projectDir.canonicalPath,
                    probe.single { it.startsWith("cwd=") }.removePrefix("cwd="),
                    "a stdio MCP server must run in the session cwd, not the agent's own directory",
                )
                assertTrue(
                    "openrouter_api_key=absent" in probe,
                    "an MCP server must not inherit the agent's API key: $probe",
                )
                assertTrue("path=present" in probe, "the child environment must keep PATH: $probe")
                println("[ok] the stdio MCP server ran in the session cwd and saw no agent secret")
            } finally {
                connection.close()
                agentWorkingDir.deleteRecursively()
            }
        }
    }

    private suspend fun markerLines(markerFile: File): List<String> {
        var lines = emptyList<String>()
        awaitUntil(10_000) {
            lines = if (markerFile.isFile) markerFile.readLines() else emptyList()
            lines.count { it.contains('=') } >= 3
        }
        return lines
    }

    @Test
    fun `e2e read-only annotated mcp tool runs without permission over stdio`() = runBlocking {
        withE2eAgent(
            "mcp-stdio-readonly",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_read", buildJsonObject { })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(
                        cwd = projectDir.absolutePath,
                        mcpServers = listOf(stdioServer(markerFile.toFile())),
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
                println("[ok] read-only annotated MCP tool ran without a permission prompt over stdio")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e unannotated mcp tool asks permission and executes over stdio`() = runBlocking {
        withE2eAgent(
            "mcp-stdio-write",
            { MockOpenAiServer("unused", toolCall = MockToolCall("mcp_write", buildJsonObject { put("k", "v") })) }) {
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(
                        cwd = projectDir.absolutePath,
                        mcpServers = listOf(stdioServer(markerFile.toFile())),
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
                // The marker is written by the mock process, so it proves the call
                // crossed the stdio pipe (not just that the agent logged a result).
                val marker = markerFile.toFile()
                assertTrue(marker.isFile, "the allowed MCP tool call must have run in the server process")
                assertEquals(
                    "mcp_write:k=v",
                    marker.readText().trim(),
                    "marker must record the arguments the server received",
                )
                println("[ok] unannotated MCP tool asked permission and executed over stdio")
            } finally {
                connection.close()
            }
        }
    }

    /**
     * `Main.kt`'s collision rule: a server tool whose name already exists in the
     * registry is ignored with a warning, and the local tool keeps working. The
     * mock exposes a `read_file` next to the annotated `mcp_read`, so the
     * `tools/list` handshake itself must succeed - only the colliding tool is
     * dropped, not the whole server.
     */
    @Test
    fun `e2e mcp tool colliding with a local tool is ignored over stdio`() = runBlocking {
        withE2eAgent(
            "mcp-stdio-shadow",
            { projectDir ->
                MockOpenAiServer(
                    "unused",
                    toolCalls = listOf(
                        MockToolCall("read_file", readFileArgs(projectDir.resolve("notes.txt").absolutePath)),
                        null,
                    ),
                )
            },
        ) {
            val notesFile = projectDir.toPath().resolve("notes.txt")
            java.nio.file.Files.writeString(notesFile, "local content\n")
            val markerFile = projectDir.toPath().resolve("marker.txt")
            val process = startAgent(llmMock.port, stateHome)
            val stderrLines = drainStderr(process)
            val connection = connectProcess(process)
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(
                        cwd = projectDir.absolutePath,
                        mcpServers = listOf(
                            stdioServer(markerFile.toFile(), mapOf("MOCK_MCP_SHADOW" to "1")),
                        ),
                    ),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = collectPrompt(session, listOf(ContentBlock.Text("Read the notes file")))
                assertEndTurn(events)

                assertTrue(ops.permissionRequests.isEmpty(), "in-project read_file must not ask permission")
                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                assertTrue(
                    resultUpdates.last().rawOutput.toString().contains("local content"),
                    "the local read_file must have read the file: ${resultUpdates.last().rawOutput}",
                )
                assertTrue(
                    !markerFile.toFile().exists(),
                    "the colliding MCP tool must never be called: ${
                        markerFile.toFile().takeIf { it.isFile }?.readText()
                    }",
                )
                awaitUntil(10_000) {
                    stderrLines.any {
                        it.contains("tool \"read_file\" collides with an existing tool")
                    }
                }
                println("[ok] the colliding MCP tool was ignored and the local read_file still executed")
            } finally {
                connection.close()
            }
        }
    }

    /**
     * `Main.kt` connects the client's MCP servers fail-open: a server that cannot
     * be launched must not fail `session/new` (the session is still usable), only
     * log a warning. A nonexistent command is the cheapest deterministic failure -
     * a server that starts but speaks broken JSON-RPC would instead hang until the
     * MCP SDK's 60s request timeout.
     */
    @Test
    fun `e2e unstartable stdio mcp server fails open`() = runBlocking {
        withE2eAgent("mcp-stdio-failopen", { MockOpenAiServer("unused", textOnly = true) }) {
            val process = startAgent(llmMock.port, stateHome)
            val stderrLines = drainStderr(process)
            val connection = connectProcess(process)
            try {
                connection.client.initialize(testClientInfo())
                val session = connection.client.newSession(
                    SessionCreationParameters(
                        cwd = projectDir.absolutePath,
                        mcpServers = listOf(
                            McpServer.Stdio(
                                name = "broken-stdio",
                                command = projectDir.resolve("does-not-exist").absolutePath,
                                args = emptyList(),
                                env = emptyList(),
                            ),
                        ),
                    ),
                    ClientOperationsFactory { _, _ -> TestClientOperations() },
                )
                val events = collectPrompt(session, listOf(ContentBlock.Text("Hello")))
                assertEndTurn(events)

                awaitUntil(10_000) {
                    stderrLines.any { it.contains("Failed to connect MCP server broken-stdio") }
                }
                println("[ok] an unstartable stdio MCP server logged a warning instead of failing session/new")
            } finally {
                connection.close()
            }
        }
    }
}
