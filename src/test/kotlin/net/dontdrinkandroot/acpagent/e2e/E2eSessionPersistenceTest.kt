package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.agent.SessionStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box session lifecycle flows across agent processes sharing one XDG state dir.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eSessionPersistenceTest : E2eAgentTest() {

    /**
     *   process 1: session/new -> prompt (text-only) -> set_config_option(build)
     *   process 2: session/list -> session/load (replay) -> prompt (history continuity)
     *   process 3: session/resume (no replay) -> load guards -> session/delete (idempotent)
     */
    @Test
    fun `e2e session persistence - record, list, load, resume, delete across restart`() = runBlocking {
        val llmMock = MockOpenAiServer("unused", textOnly = true)
        withE2eAgent("project", llmMock) {
            val sessionId: SessionId
            val first = connect()
            try {
                first.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(first.client, projectDir, ops)
                sessionId = session.sessionId
                val events = collectPrompt(session, listOf(ContentBlock.Text("Say hello")))
                assertEndTurn(events)
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                println("[ok] session/new + prompt + mode switch on first agent")
            } finally {
                first.close()
            }

            val store = SessionStore(sessionsStoreDir)
            val record = store.load(sessionId.value)
            assertNotNull(record, "expected persisted session record")
            assertEquals(projectDir.absolutePath, record.cwd)
            assertEquals("build", record.mode)
            assertEquals("Say hello", record.title)
            val rawRecord =
                sessionsStoreDir.resolve("${sessionId.value}.json").let { java.nio.file.Files.readString(it) }
            assertTrue(
                !rawRecord.contains("pondering the request"),
                "reasoning deltas must not be persisted to history",
            )
            println("[ok] session record persisted (mode=${record.mode}, ${record.history.size} messages, no reasoning text)")

            val second = connect()
            try {
                second.client.initialize(testClientInfo())
                val listed = second.client.listSessions(projectDir.absolutePath).toList()
                assertEquals(1, listed.size)
                assertEquals(sessionId, listed.first().sessionId)
                assertEquals("Say hello", listed.first().title)
                assertNotNull(listed.first().updatedAt)
                println("[ok] session/list after restart (cwd filter, recency)")

                val loadOps = TestClientOperations()
                val loaded = second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                assertEquals(SessionModeId("build"), loaded.currentMode.value)
                // Replay is emitted by the agent after the load response; wait for it to arrive.
                awaitUntil(5_000) { loadOps.notifications.any { it is SessionUpdate.UserMessageChunk } }
                val userChunks = loadOps.notifications.filterIsInstance<SessionUpdate.UserMessageChunk>()
                assertEquals(1, userChunks.size)
                assertEquals("Say hello", (userChunks.single().content as ContentBlock.Text).text)
                val agentText = loadOps.notifications.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                    .joinToString("")
                assertTrue(agentText.contains("phase4 done"), "expected replayed agent text, got '$agentText'")
                println("[ok] session/load replays user + agent message chunks, mode=build")

                val continuation = collectPrompt(loaded, listOf(ContentBlock.Text("Continue")))
                assertEndTurn(continuation)
                println("[ok] prompt on loaded session (history continuity)")
            } finally {
                second.close()
            }

            val third = connect()
            try {
                third.client.initialize(testClientInfo())
                val resumeOps = TestClientOperations()
                val resumed = third.client.resumeSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> resumeOps },
                )
                assertEquals(SessionModeId("build"), resumed.currentMode.value)
                assertTrue(resumeOps.notifications.isEmpty(), "resume must not replay history")
                println("[ok] session/resume restores mode without replay")

                assertFailsWith<AcpExpectedError>("cwd mismatch must fail") {
                    third.client.loadSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.parent.toString(), mcpServers = emptyList()),
                        ClientOperationsFactory { _, _ -> TestClientOperations() },
                    )
                }
                assertFailsWith<AcpExpectedError>("double load must fail") {
                    third.client.loadSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                        ClientOperationsFactory { _, _ -> TestClientOperations() },
                    )
                }
                println("[ok] session/load rejected for cwd mismatch and already-active session")

                third.client.deleteSession(sessionId)
                assertTrue(!Files.exists(sessionsStoreDir.resolve("${sessionId.value}.json")))
                assertTrue(third.client.listSessions(projectDir.absolutePath).toList().isEmpty())
                third.client.deleteSession(sessionId)
                println("[ok] session/delete removes the record and is idempotent")
            } finally {
                third.close()
            }

            assertEquals(2, llmMock.requestCount.toInt())
            println("[ok] LLM called exactly 2 times across both prompts (load/resume make no LLM calls)")
        }
    }

    /**
     * Black-box update_plan flow across two agent processes:
     *
     *   process 1: prompt with an update_plan tool-call turn -> plan update + persisted plan
     *   process 2: session/load -> replay ends with the plan update
     */
    @Test
    fun `e2e update_plan - plan update, persistence and replay`() = runBlocking {
        val llmMock = MockOpenAiServer("unused", planMode = true)
        withE2eAgent("plan", llmMock) {
            val sessionId: SessionId
            val first = connect()
            try {
                first.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(first.client, projectDir, ops)
                sessionId = session.sessionId
                val events = collectPrompt(session, listOf(ContentBlock.Text("Plan the fix")))
                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

                val planUpdates = updates.filterIsInstance<SessionUpdate.PlanUpdate>()
                assertEquals(1, planUpdates.size)
                val entries = planUpdates.single().entries
                assertEquals(
                    listOf("Investigate the bug", "Fix the bug"),
                    entries.map { it.content },
                )
                assertEquals(
                    listOf(PlanEntryStatus.IN_PROGRESS, PlanEntryStatus.PENDING),
                    entries.map { it.status },
                )
                println("[ok] update_plan emitted a plan update with 2 entries")

                val toolCall = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
                assertEquals("update_plan", toolCall.title)
                assertEquals(ToolKind.THINK, toolCall.kind)
                assertEquals(
                    ToolCallStatus.COMPLETED,
                    updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last().status
                )
                assertTrue(
                    ops.permissionRequests.isEmpty(),
                    "update_plan is non-mutating and must not request permission",
                )
                println("[ok] update_plan tool call (kind=think, no permission, completed)")
            } finally {
                first.close()
            }

            val store = SessionStore(sessionsStoreDir)
            val record = store.load(sessionId.value)
            assertNotNull(record, "expected persisted session record")
            assertEquals(2, record.plan.size)
            println("[ok] plan persisted in the session record")

            val second = connect()
            try {
                second.client.initialize(testClientInfo())
                val loadOps = TestClientOperations()
                second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                awaitUntil(5_000) { loadOps.notifications.any { it is SessionUpdate.PlanUpdate } }
                assertEquals(
                    listOf("Investigate the bug", "Fix the bug"),
                    loadOps.notifications.filterIsInstance<SessionUpdate.PlanUpdate>()
                        .single().entries.map { it.content },
                )
                assertTrue(
                    loadOps.notifications.last() is SessionUpdate.PlanUpdate,
                    "the plan update must be replayed after the history",
                )
                val replayedResultUpdates = loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertEquals(1, replayedResultUpdates.size)
                assertEquals(
                    ToolCallStatus.COMPLETED,
                    replayedResultUpdates.single().status,
                    "a successful tool call must replay as COMPLETED",
                )
                println("[ok] session/load replays the persisted plan and the completed tool call")
            } finally {
                second.close()
            }

            assertEquals(2, llmMock.requestCount.toInt())
            println("[ok] LLM called for the plan turn + final text turn (load/replay makes no LLM calls)")
        }
    }

    /**
     * Black-box denied tool calls across two agent processes:
     *
     *   process 1 (plan mode, rejecting permission client): one prompt whose
     *     first LLM iteration calls read_file out-of-project (permission prompt
     *     -> reject -> FAILED) and whose second iteration calls an unknown tool
     *     (refused -> FAILED); the third iteration ends the turn with text.
     *   process 2: session/load -> replay must render both as FAILED (not COMPLETED)
     */
    @Test
    fun `e2e denied and unknown tool calls replay as FAILED after load`() = runBlocking {
        val llmMock = MockOpenAiServer(
            "unused",
            toolCalls = listOf(
                MockToolCall(
                    "read_file",
                    buildJsonObject { put("path", JsonPrimitive("/etc/acp-agent-does-not-exist")) },
                ),
                MockToolCall("no_such_tool", buildJsonObject { }),
            ),
        )
        withE2eAgent("denied-replay", llmMock) {
            val sessionId: SessionId
            val first = connect()
            try {
                first.client.initialize(testClientInfo())
                val ops = RejectingClientOperations()
                val session = newSession(first.client, projectDir, ops)
                sessionId = session.sessionId

                val events = collectPrompt(session, listOf(ContentBlock.Text("Read the config")))
                assertEndTurn(events)
                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .associateBy { it.toolCallId.value }

                val deniedResult = resultUpdates.getValue("call_read_file")
                assertEquals(ToolCallStatus.FAILED, deniedResult.status)
                assertTrue(
                    deniedResult.rawOutput.toString().contains("Permission denied"),
                    "expected the permission denial, got: ${deniedResult.rawOutput}",
                )
                assertEquals(1, ops.permissionRequests.size, "the out-of-project read must ask permission")
                println("[ok] out-of-project read denied live (FAILED, no execution)")

                val unknownResult = resultUpdates.getValue("call_no_such_tool")
                assertEquals(ToolCallStatus.FAILED, unknownResult.status)
                assertTrue(
                    (unknownResult.rawOutput ?: unknownResult.content.orEmpty().firstOrNull())
                        .toString().contains("unknown tool"),
                    "expected the unknown-tool error, got: ${unknownResult.rawOutput}",
                )
                val introductions = updates.filterIsInstance<SessionUpdate.ToolCall>()
                assertEquals(
                    listOf("call_read_file"),
                    introductions.map { it.toolCallId.value },
                    "a known tool denied at permission still introduces its call live; " +
                            "an unknown tool is refused before any introduction",
                )
                println("[ok] unknown tool call refused live (FAILED, no introduction)")
            } finally {
                first.close()
            }

            val record = SessionStore(sessionsStoreDir).load(sessionId.value)
            assertNotNull(record)
            assertEquals(
                mapOf("call_read_file" to "failed", "call_no_such_tool" to "failed"),
                record.toolOutcomes,
                "both denied outcomes must be persisted with the record",
            )
            println("[ok] denied outcomes persisted (toolOutcomes)")

            val second = connect()
            try {
                second.client.initialize(testClientInfo())
                val loadOps = TestClientOperations()
                second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                awaitUntil(5_000) {
                    loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                        .any { it.toolCallId.value == "call_no_such_tool" }
                }
                val resultUpdates = loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .associateBy { it.toolCallId.value }
                assertEquals(
                    ToolCallStatus.FAILED,
                    resultUpdates.getValue("call_read_file").status,
                    "a permission-denied call must replay as FAILED (was COMPLETED before the fix)",
                )
                assertEquals(
                    ToolCallStatus.FAILED,
                    resultUpdates.getValue("call_no_such_tool").status,
                    "an unknown-tool call must replay as FAILED",
                )
                val denialText = resultUpdates.getValue("call_read_file").content.orEmpty()
                    .filterIsInstance<ToolCallContent.Content>()
                    .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                    .joinToString("\n")
                assertTrue(
                    denialText.contains("Permission denied"),
                    "the replayed update must carry the denial text, got: $denialText",
                )
                assertTrue(
                    resultUpdates.values.none { it.status == ToolCallStatus.COMPLETED },
                    "no replayed tool result may claim COMPLETED in this scenario",
                )
                println("[ok] session/load replays denied + unknown tool calls as FAILED with their denial text")
            } finally {
                second.close()
            }

            assertEquals(3, llmMock.requestCount.toInt())
            println("[ok] LLM called 3 times (denied read + unknown tool + final text; load/replay makes none)")
        }
    }
}