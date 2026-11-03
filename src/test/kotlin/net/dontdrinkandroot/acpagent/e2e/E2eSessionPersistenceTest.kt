package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.agent.SessionStore
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.*

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
                // The derived title is announced to the client (issue #73): exactly
                // one session_info_update, carrying only the new title.
                val infoUpdates = events.filterIsInstance<Event.SessionUpdateEvent>()
                    .map { it.update }
                    .filterIsInstance<SessionUpdate.SessionInfoUpdate>()
                assertEquals(
                    listOf("Say hello"),
                    infoUpdates.map { it.title },
                    "the derived title must be announced exactly once",
                )
                assertNull(infoUpdates.single().updatedAt, "only changed fields belong in the update")
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                println("[ok] session/new + prompt + mode switch on first agent")
            } finally {
                first.close()
            }
            // Everything after this point is load/resume bookkeeping: it must not
            // reach the LLM, so each step is pinned as a delta against this count.
            val requestsAfterPrompt = llmMock.requestCount.get()

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
                // updatedAt is an RFC 3339 stamp with a numeric offset (issue #22); the
                // instant must round-trip exactly, whatever the agent's zone is.
                val updatedAt = assertNotNull(listed.first().updatedAt)
                assertTrue(
                    Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}$""").matches(updatedAt),
                    "expected a numeric-offset RFC 3339 timestamp, got '$updatedAt'",
                )
                assertEquals(record.updatedAt, OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli())
                println("[ok] session/list after restart (cwd filter, recency)")

                val loadOps = TestClientOperations()
                // The early-delivery overload takes the ready operations instead of a
                // factory, so updates arriving during the agent's replay are delivered
                // as they arrive rather than buffered until the answer.
                val loaded = second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    loadOps,
                )
                assertEquals(SessionModeId("build"), loaded.currentMode.value)
                assertEquals(
                    requestsAfterPrompt,
                    llmMock.requestCount.get(),
                    "session/load must replay without calling the LLM",
                )
                // Since acp 0.34.0 the agent replays from `AgentSession.replayHistory`,
                // which the SDK runs before the load response (spec MUST; was issue #59):
                // the updates must already be delivered when the call returns.
                val userChunks = loadOps.notifications.filterIsInstance<SessionUpdate.UserMessageChunk>()
                assertEquals(1, userChunks.size, "replay must arrive before the load response")
                assertEquals("Say hello", (userChunks.single().content as ContentBlock.Text).text)
                val agentText = loadOps.notifications.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                    .joinToString("")
                assertTrue(agentText.contains("phase4 done"), "expected replayed agent text, got '$agentText'")
                // Raw-wire pin of the spec MUST (issue #59): every replayed session/update
                // for this session must precede the session/load response line, not follow it.
                assertReplayPrecedesLoadResponse(second, sessionId)
                println("[ok] session/load replays user + agent chunks before the response (wire order), mode=build")

                val continuation = collectPrompt(loaded, listOf(ContentBlock.Text("Continue")))
                assertEndTurn(continuation)
                // The restored title was already delivered by session/load, so the
                // loaded session must not re-announce it (issue #73).
                assertTrue(
                    continuation.filterIsInstance<Event.SessionUpdateEvent>()
                        .none { it.update is SessionUpdate.SessionInfoUpdate },
                    "a loaded session must not re-announce its restored title",
                )
                assertTrue(
                    llmMock.requestCount.get() > requestsAfterPrompt,
                    "the continuation prompt on the loaded session must reach the LLM",
                )
                println("[ok] prompt on loaded session (history continuity)")
            } finally {
                second.close()
            }
            val requestsAfterContinuation = llmMock.requestCount.get()

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
                assertEquals(
                    requestsAfterContinuation,
                    llmMock.requestCount.get(),
                    "session/resume must restore without calling the LLM",
                )
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

            println("[ok] load/resume/delete across restarts issued no LLM requests")
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
            val requestsAfterPrompt = llmMock.requestCount.get()

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
                    loadOps,
                )
                // Replay arrives before the load response (issue #59), so the plan update
                // is already delivered here - no polling needed.
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

            assertEquals(
                requestsAfterPrompt,
                llmMock.requestCount.get(),
                "session/load replay must not call the LLM",
            )
            println("[ok] load/replay issued no LLM requests")
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
            val requestsAfterPrompt = llmMock.requestCount.get()

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
                    loadOps,
                )
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

            assertEquals(
                requestsAfterPrompt,
                llmMock.requestCount.get(),
                "session/load replay must not call the LLM",
            )
            println("[ok] load/replay issued no LLM requests")
        }
    }

    /**
     * Pins the spec MUST on the raw wire (issue #59): for the `session/load` the
     * agent answered last on [connection], every `session/update` notification of
     * [sessionId] must appear *before* the response line carrying the `session/load`
     * result - not after it (the deviation the SDK's `postInitialize()` hook forced
     * before `acp` 0.34.0).
     */
    private fun assertReplayPrecedesLoadResponse(connection: AgentConnection, sessionId: SessionId) {
        val lines = connection.wireLines.toList()
        val loadResponseIndex = lines.indexOfLast { line ->
            line.contains("\"id\"") &&
                    line.contains("\"result\"") &&
                    !line.contains("\"sessionUpdate\"") &&
                    isLoadResponse(line)
        }
        assertTrue(loadResponseIndex >= 0, "no session/load response found on the wire")
        val replayIndexes = lines.withIndex()
            .filter { (_, line) -> line.contains("\"sessionUpdate\"") && line.contains(sessionId.value) }
            .map { it.index }
        assertTrue(replayIndexes.isNotEmpty(), "no replayed session/update found on the wire")
        assertTrue(
            replayIndexes.all { it < loadResponseIndex },
            "every replayed session/update must precede the session/load response " +
                    "(replay=$replayIndexes, response=$loadResponseIndex)",
        )
        println("[wire ok] ${replayIndexes.size} replayed updates all precede the load response")
    }

    private fun isLoadResponse(line: String): Boolean =
        line.contains("\"configOptions\"") || line.contains("\"modes\"")
}
