package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project permission overlay end to end (issue #85): `<launch cwd>/.ai/ddr-acp-agent.json`
 * adds project directories on top of the selected base configuration. The overlay is read
 * once at startup from the launch directory - the harness launches the agent in the scenario's
 * project dir by default, like an IDE launching it in the opened project.
 *
 * The sandbox side of the same file (its directories becoming the mount list) is pinned at
 * the process level by `SandboxSupervisorTest`; here the agent's decision is the subject.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eProjectConfigTest : E2eAgentTest() {

    private fun overlay(readable: File): String =
        """{"permissions": {"readableDirectories": ["${readable.absolutePath}"]}}"""

    private fun File.writeOverlay(json: String): File {
        resolve(".ai").apply { mkdirs() }.resolve("ddr-acp-agent.json").writeText(json)
        return this
    }

    /** The single tool-call result of a turn, asserted to exist. */
    private fun toolCallResult(events: List<Event>, toolCallId: ToolCallId): SessionUpdate.ToolCallUpdate =
        events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
            .filterIsInstance<SessionUpdate.ToolCallUpdate>()
            .last { it.toolCallId == toolCallId }

    private fun toolCall(events: List<Event>): SessionUpdate.ToolCall =
        events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
            .filterIsInstance<SessionUpdate.ToolCall>()
            .single()

    @Test
    fun `a project overlay grants prompt-free access to its directories`() = runBlocking {
        val tier = Files.createTempDirectory("acp-e2e-overlay-tier").toFile()
        try {
            val target = tier.resolve("notes.txt").apply { writeText("overlay content") }
            withE2eAgent("project-overlay", { projectDir ->
                projectDir.writeOverlay(overlay(tier))
                MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(target.absolutePath)))
            }) {
                val connection = connect(workingDir = projectDir)
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the overlay file")))
                    assertEndTurn(events)

                    val result = toolCallResult(events, toolCall(events).toolCallId)
                    assertEquals(ToolCallStatus.COMPLETED, result.status)
                    assertTrue(result.rawOutput.toString().contains("overlay content"), result.rawOutput.toString())
                    assertTrue(
                        ops.permissionRequests.isEmpty(),
                        "a directory named by the project overlay must be prompt-free",
                    )
                    assertTrue(
                        llmMock.requestBodies.first().contains(tier.absolutePath),
                        "the merged tier must reach the system prompt like a base entry",
                    )
                    println("[ok] the project overlay granted prompt-free access and surfaced the tier")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tier.deleteRecursively()
        }
    }

    @Test
    fun `without an overlay the same read still prompts`() = runBlocking {
        // The acceptance's "removing the file restores base behaviour": the same out-of-project
        // read that the overlay granted above asks the user when the file is absent.
        val tier = Files.createTempDirectory("acp-e2e-overlay-base").toFile()
        try {
            val target = tier.resolve("notes.txt").apply { writeText("base content") }
            withE2eAgent(
                "project-overlay-absent",
                MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(target.absolutePath))),
            ) {
                val connection = connect(workingDir = projectDir)
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the file")))
                    assertEndTurn(events)

                    assertEquals(
                        1,
                        ops.permissionRequests.size,
                        "without an overlay the out-of-project read must ask the user",
                    )
                    assertEquals(ToolCallStatus.COMPLETED, toolCallResult(events, toolCall(events).toolCallId).status)
                    println("[ok] without the overlay the read prompted")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tier.deleteRecursively()
        }
    }

    @Test
    fun `the overlay is refused to the file tools`() = runBlocking {
        // Issue #85: a grant file the model could rewrite is not a boundary - the generic file
        // tools refuse it, so a rejected grant cannot be re-applied in-project.
        val tier = Files.createTempDirectory("acp-e2e-overlay-guarded").toFile()
        try {
            withE2eAgent("project-overlay-guarded", { projectDir ->
                projectDir.writeOverlay(overlay(tier))
                MockOpenAiServer(
                    "unused",
                    toolCall = MockToolCall("read_file", readFileArgs(projectDir.resolve(".ai/ddr-acp-agent.json").absolutePath)),
                )
            }) {
                val connection = connect(workingDir = projectDir)
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the overlay")))
                    assertEndTurn(events)

                    val result = toolCallResult(events, toolCall(events).toolCallId)
                    assertEquals(ToolCallStatus.FAILED, result.status)
                    val output = result.rawOutput.toString()
                    assertTrue(output.contains("excluded from tool access"), output)
                    assertTrue(ops.permissionRequests.isEmpty(), "the refusal must not prompt permission")
                    assertTrue(ops.fsReadCalls.isEmpty(), "the refusal must not round-trip through the client fs")
                    println("[ok] read_file on the overlay failed without reading or prompting")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tier.deleteRecursively()
        }
    }

    @Test
    fun `an overlay in the session cwd is not consulted when the launch cwd differs`() = runBlocking {
        // The documented residual (issue #85): the overlay is read once from the launch
        // directory, because the sandbox composes its mounts from that same directory. A
        // session opened elsewhere therefore runs under the launch directory's overlay.
        val sessionTier = Files.createTempDirectory("acp-e2e-overlay-session-tier").toFile()
        val sessionDir = Files.createTempDirectory("acp-e2e-overlay-session").toFile()
        try {
            sessionDir.writeOverlay(overlay(sessionTier))
            val target = sessionTier.resolve("notes.txt").apply { writeText("session overlay content") }
            withE2eAgent(
                "project-overlay-residual",
                MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(target.absolutePath))),
            ) {
                // The launch directory has no overlay; the session directory has one.
                val connection = connect(workingDir = projectDir)
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, sessionDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the file")))
                    assertEndTurn(events)

                    assertEquals(
                        1,
                        ops.permissionRequests.size,
                        "the session directory's overlay must not be consulted - the launch directory decides",
                    )
                    assertTrue(llmMock.requestBodies.first().contains(sessionDir.absolutePath))
                    assertFalse(llmMock.requestBodies.first().contains(sessionTier.absolutePath))
                    println("[ok] the session directory's overlay was ignored")
                } finally {
                    connection.close()
                }
            }
        } finally {
            sessionTier.deleteRecursively()
            sessionDir.deleteRecursively()
        }
    }

    @Test
    fun `a project writable tier allows a prompt-free write`() = runBlocking {
        // The writable union is the second half of the merge: an overlay-only write tier must
        // act like a base entry.
        val tier = Files.createTempDirectory("acp-e2e-overlay-write").toFile()
        try {
            val target = tier.resolve("written.txt")
            withE2eAgent("project-overlay-write", { projectDir ->
                projectDir.writeOverlay("""{"permissions": {"writableDirectories": ["${tier.absolutePath}"]}}""")
                MockOpenAiServer(
                    "unused",
                    toolCall = MockToolCall(
                        "write_file",
                        buildJsonObject {
                            put("path", target.absolutePath)
                            put("content", "overlay write")
                        },
                    ),
                )
            }) {
                val connection = connect(workingDir = projectDir)
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Write the file")))
                    assertEndTurn(events)

                    assertEquals(ToolCallStatus.COMPLETED, toolCallResult(events, toolCall(events).toolCallId).status)
                    assertTrue(target.exists() && target.readText() == "overlay write", "the write must have executed")
                    assertTrue(ops.permissionRequests.isEmpty(), "a project writable tier must be prompt-free")
                    println("[ok] the project writable tier allowed a prompt-free write")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tier.deleteRecursively()
        }
    }
}
