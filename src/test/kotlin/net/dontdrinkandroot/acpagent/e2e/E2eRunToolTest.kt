package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box run configurations: the `run` tool is available and prompt-free in
 * plan mode (non-mutating), executes a config from `.ai/run.json`, and fails
 * loudly for unknown configs.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eRunToolTest : E2eAgentTest() {

    @Test
    fun `e2e run executes a configuration without permission in plan mode`() = runBlocking {
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

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                // Behavioral change: run is non-mutating now and must not prompt.
                assertTrue(ops.permissionRequests.isEmpty(), "run is non-mutating and must not ask permission")
                val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
                assertEquals(1, toolCalls.size)
                // Behavioral change: run titles lead with the config name now.
                assertEquals("run(marker)", toolCalls.single().title)
                assertEquals(ToolKind.EXECUTE, toolCalls.single().kind)
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.toolCallId == toolCalls.single().toolCallId }
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                assertTrue(resultUpdates.last().rawOutput.toString().contains("hello"))

                val marker = requireNotNull(markerFile)
                assertTrue(marker.isFile, "the config command must have written the marker file")
                assertEquals("hello", marker.readText().trim())
                println("[ok] run tool executed .ai/run.json config without permission (plan mode)")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e run args elements are delivered as literal argv tokens in plan mode`() = runBlocking {
        // Regression (issue #4): args used to be spliced into the command
        // string, so a model-controlled value executed arbitrary shell in
        // prompt-free plan mode. The element must reach the command as one
        // literal argument instead.
        withE2eAgent("run-args", { projectDir ->
            val aiDir = projectDir.resolve(".ai").apply { mkdirs() }
            aiDir.resolve("run.json").writeText("""{"echo":{"command":"printf '[%s]' [args]"}}""")
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "run",
                    buildJsonObject {
                        put("config", "echo")
                        putJsonArray("args") { add("""x"; rm -rf ~ #""") }
                    },
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Run the echo config")))
                assertEndTurn(events)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                assertTrue(ops.permissionRequests.isEmpty(), "run is non-mutating and must not ask permission")
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                val resultText = (resultUpdates.last().content ?: emptyList())
                    .filterIsInstance<ToolCallContent.Content>()
                    .map { it.content }
                    .filterIsInstance<ContentBlock.Text>()
                    .joinToString("") { it.text }
                assertTrue(
                    resultText.contains("""[x"; rm -rf ~ #]"""),
                    "the element must arrive as one literal argument: $resultText",
                )
                println("[ok] run args elements are literal argv tokens (no shell re-interpretation)")
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
