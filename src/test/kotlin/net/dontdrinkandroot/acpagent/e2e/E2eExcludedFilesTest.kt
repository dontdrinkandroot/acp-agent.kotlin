package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Black-box file-access exclusions (currently the fixed `.env*.local` rule):
 * direct-target tool calls on an excluded file fail loudly without reading it
 * (no client fs proxy round trip, no permission prompt), and listings/searches
 * hide excluded entries instead of surfacing their existence.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eExcludedFilesTest : E2eAgentTest() {

    @Test
    fun `e2e read_file on an excluded env local file fails without reading it`() = runBlocking {
        withE2eAgent("excluded-read", { projectDir ->
            projectDir.resolve(".env.local").writeText("SECRET_KEY=super-secret-value")
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "read_file",
                    buildJsonObject { put("path", projectDir.resolve(".env.local").absolutePath); put("limit", 100) },
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(
                    testClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true
                            )
                        ),
                    )
                )
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Read the env file")))
                assertEndTurn(events)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val toolCall = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
                assertEquals("read_file", toolCall.title)
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.toolCallId == toolCall.toolCallId }
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.FAILED, resultUpdates.last().status)
                val output = resultUpdates.last().rawOutput.toString()
                assertTrue(output.contains("excluded from tool access"), output)
                assertFalse(output.contains("super-secret-value"), "the refusal must not leak the content: $output")
                assertTrue(ops.permissionRequests.isEmpty(), "the refusal must not prompt permission")
                assertTrue(ops.fsReadCalls.isEmpty(), "the refusal must not round-trip through the client fs")
                println("[ok] read_file on .env.local failed without reading or prompting")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e list_dir hides excluded entries`() = runBlocking {
        withE2eAgent("excluded-list", { projectDir ->
            projectDir.resolve(".env.local").writeText("SECRET_KEY=super-secret-value")
            projectDir.resolve("readme.md").writeText("visible")
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "list_dir",
                    buildJsonObject { put("path", projectDir.absolutePath) },
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("List the project")))
                assertEndTurn(events)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val toolCall = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.toolCallId == toolCall.toolCallId }
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                val output = resultUpdates.last().rawOutput.toString()
                assertTrue(output.contains("readme.md"), output)
                assertFalse(output.contains(".env.local"), "the listing must hide the excluded entry: $output")
                assertFalse(output.contains("super-secret-value"), output)
                assertTrue(ops.permissionRequests.isEmpty(), "in-project list_dir must not ask permission")
                println("[ok] list_dir hid the .env.local entry")
            } finally {
                connection.close()
            }
        }
    }
}
