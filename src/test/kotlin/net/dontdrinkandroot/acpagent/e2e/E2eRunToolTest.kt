package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
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
 * Black-box run configurations: the `run` tool is available in plan mode,
 * executes a config from `.ai/run.json` after the user grants permission, and
 * fails loudly for unknown configs.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eRunToolTest : E2eAgentTest() {

    @Test
    fun `e2e run executes a configuration after permission in plan mode`() = runBlocking {
        var markerFile: java.io.File? = null
        withE2eAgent("run", { projectDir ->
            val aiDir = projectDir.resolve(".ai").apply { mkdirs() }
            aiDir.resolve("run.json").writeText("""{"marker":{"command":"echo hello; echo hello > marker.txt"}}""")
            markerFile = projectDir.resolve("marker.txt")
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall("run", buildJsonObject { put("config", "marker") }),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Run the marker config")))
                assertEndTurn(events)

                assertEquals(1, ops.permissionRequests.size, "run is mutating and must ask permission")
                assertEquals("run(config: marker)", ops.permissionRequests.single().title)
                val rawInput = ops.permissionRequests.single().rawInput as JsonObject
                assertEquals("marker", (rawInput["config"] as JsonPrimitive).content)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
                assertEquals(1, toolCalls.size)
                assertEquals("run(config: marker)", toolCalls.single().title)
                assertEquals(ToolKind.EXECUTE, toolCalls.single().kind)
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.toolCallId == toolCalls.single().toolCallId }
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                assertTrue(resultUpdates.last().rawOutput.toString().contains("hello"))

                val marker = requireNotNull(markerFile)
                assertTrue(marker.isFile, "the config command must have written the marker file")
                assertEquals("hello", marker.readText().trim())
                println("[ok] run tool executed .ai/run.json config after permission (plan mode)")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e run with unknown configuration fails the tool call`() = runBlocking {
        withE2eAgent("run-unknown", { projectDir ->
            val aiDir = projectDir.resolve(".ai").apply { mkdirs() }
            aiDir.resolve("run.json").writeText("""{"test":{"command":"npm test"}}""")
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall("run", buildJsonObject { put("config", "nope") }),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Run the nope config")))
                assertEndTurn(events)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.FAILED, resultUpdates.last().status)
                assertTrue(resultUpdates.last().rawOutput.toString().contains("Unknown run configuration"), resultUpdates.last().rawOutput.toString())
                println("[ok] unknown run configuration failed the tool call loudly")
            } finally {
                connection.close()
            }
        }
    }
}