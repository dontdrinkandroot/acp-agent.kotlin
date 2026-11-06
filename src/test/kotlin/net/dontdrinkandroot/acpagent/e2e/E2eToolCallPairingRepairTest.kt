package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.agent.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box pairing-repair contract (issue #38): a cancelled turn can leave
 * its tool call in the history without a result, and a strict provider
 * (Azure/OpenAI) rejects any later request with 400 "No tool output found for
 * function call <id>". The agent must close dangling calls before any consumer
 * sees them: the next prompt's chat request (same process), the load replay of
 * a record poisoned before the fix, and the replayed call's outcome.
 */
class E2eToolCallPairingRepairTest : E2eAgentTest() {

    @Test
    fun `e2e a cancelled tool call is closed in the next prompt's chat request`() = runBlocking {
        val targetDir = Files.createTempDirectory("acp-agent-e2e-pairing-target").toFile()
        val llmMock = MockOpenAiServer(
            File(targetDir, "unused.txt").absolutePath,
            toolCall = MockToolCall("bash", buildJsonObject { put("command", JsonPrimitive("sleep 30")) }),
        )
        try {
            withE2eAgent("pairing-live", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    // bash exists in bash mode only, and its permission prompt
                    // is the deterministic suspension point between the call
                    // append and its result.
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bash"))

                    val promptJob = async {
                        runCatching { collectPrompt(session, listOf(ContentBlock.Text("Run it"))) }
                    }
                    withTimeout(60_000) { ops.permissionRequestStarted.await() }
                    session.cancel()
                    promptJob.await()

                    val secondTurn = collectPrompt(session, listOf(ContentBlock.Text("Continue")))
                    assertEndTurn(secondTurn)

                    assertTrue(llmMock.requestBodies.size >= 2, "a second chat request must have happened")
                    assertPairComplete(
                        llmMock.parseChatBody(llmMock.requestBodies.last()),
                        "the chat request after the cancelled turn",
                    )
                    println("[ok] chat request after a cancelled turn is pair-complete")
                } finally {
                    connection.close()
                }
            }
        } finally {
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun `e2e a record poisoned before the fix heals at load - replay FAILED, next request pair-complete`() =
        runBlocking {
            val projectDir = Files.createTempDirectory("acp-agent-e2e-pairing-legacy").toFile()
            val llmMock = MockOpenAiServer(File(projectDir, "unused").absolutePath, textOnly = true)
            try {
                withE2eAgent("pairing-legacy", { llmMock }) {
                    val sessionId = "sess_0f0e1d2c3b4a5968"
                    SessionStore(sessionsStoreDir).save(
                        SessionRecord(
                            sessionId = sessionId,
                            cwd = projectDir.absolutePath,
                            mode = "build",
                            title = "poisoned before the fix",
                            updatedAt = System.currentTimeMillis(),
                            model = "test-model",
                            history = wireHistory(
                                userMessage("Run the config"),
                                functionCall("call_poisoned", "run", "{\"config\":\"validate\"}"),
                            ),
                        ),
                    )

                    val connection = connect()
                    try {
                        connection.client.initialize(testClientInfo())
                        val loadOps = TestClientOperations()
                        val session = connection.client.loadSession(
                            SessionId(sessionId),
                            SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                            loadOps,
                        )
                        // Replay is delivered before the load response (issue #59).
                        assertEquals(
                            ToolCallStatus.FAILED,
                            loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                                .single { it.toolCallId.value == "call_poisoned" }.status,
                            "the dangling call must replay as a terminal FAILED, not stay PENDING",
                        )
                        println("[ok] poisoned record replays with a terminal FAILED update")

                        val events = collectPrompt(session, listOf(ContentBlock.Text("Continue")))
                        assertEndTurn(events)

                        assertPairComplete(
                            llmMock.parseChatBody(llmMock.requestBodies.single()),
                            "the chat request of the loaded poisoned session",
                        )
                        println("[ok] chat request of the loaded poisoned session is pair-complete")
                    } finally {
                        connection.close()
                    }
                }
            } finally {
                projectDir.deleteRecursively()
            }
        }

    @Test
    fun `e2e a mid-batch cancel freezes the dangling call on disk and load heals it`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-pairing-batch").toFile()
        val llmMock = MockOpenAiServer(
            File(projectDir, "phase4.txt").absolutePath,
            toolCalls = listOf(
                MockToolCall("write_file", buildJsonObject {
                    put("path", JsonPrimitive(File(projectDir, "phase4.txt").absolutePath))
                    put("content", JsonPrimitive("phase4"))
                }),
                MockToolCall("bash", buildJsonObject { put("command", JsonPrimitive("sleep 30")) }),
            ),
        )
        try {
            withE2eAgent("pairing-batch", llmMock) {
                var sessionId: SessionId? = null
                // bash mode: write_file executes prompt-free (local store,
                // fs proxy off), the bash call always prompts - the batch
                // then suspends between call 2's append and its result.
                val first = connect(extraEnv = mapOf("DDR_ACP_AGENT_TOOLS_FS_PROXY" to "0"))
                try {
                    first.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(first.client, projectDir, ops)
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bash"))

                    val promptJob = async {
                        runCatching { collectPrompt(session, listOf(ContentBlock.Text("Do both"))) }
                    }
                    withTimeout(60_000) { ops.permissionRequestStarted.await() }
                    session.cancel()
                    promptJob.await()
                    sessionId = session.sessionId

                    // The cancel persists the interrupted turn itself (issue #17):
                    // the record on disk is already pair-complete, with the
                    // synthetic result closing the dangling call.
                    val cancelledRecord = SessionStore(sessionsStoreDir).load(sessionId.value)
                    assertNotNull(cancelledRecord, "a cancelled turn must persist its record")
                    val cancelledItems = cancelledRecord.history.map { historyItemFromWire(it) }
                    val cancelledCalls = cancelledItems.filterIsInstance<HistoryItem.Captured>()
                        .mapNotNull { it.capturedFunctionCall()?.callId }
                    val cancelledResults = cancelledItems.filterIsInstance<HistoryItem.ToolOutput>()
                        .map { it.callId }
                    assertTrue(
                        "call_bash" in cancelledCalls && "call_bash" in cancelledResults,
                        "the record persisted by the cancel must be pair-complete " +
                                "(calls=$cancelledCalls, results=$cancelledResults)",
                    )

                    // The cancel now persists the interrupted turn itself (issue
                    // #17), so the repaired record is on disk without a follow-up
                    // turn. A short second turn keeps the scenario's original
                    // shape (the record is re-persisted on the way out) and pins
                    // that the healed pair survives a later persist unchanged.
                    val secondTurn = collectPrompt(session, listOf(ContentBlock.Text("Continue")))
                    assertEndTurn(secondTurn)
                } finally {
                    first.close()
                }

                val record = SessionStore(sessionsStoreDir).load(sessionId.value)
                assertNotNull(record)
                val items = record.history.map { historyItemFromWire(it) }
                val calls = items.filterIsInstance<HistoryItem.Captured>()
                    .mapNotNull { it.capturedFunctionCall()?.callId }
                val results = items.filterIsInstance<HistoryItem.ToolOutput>()
                    .map { it.callId }
                assertTrue(
                    "call_bash" in calls && "call_bash" in results,
                    "the persisted record must be pair-complete after the repaired turn " +
                            "(calls=$calls, results=$results)",
                )

                // ...and a later load closes it honestly: terminal FAILED
                // replay for the cancelled call, COMPLETED for the real one.
                val second = connect()
                try {
                    second.client.initialize(testClientInfo())
                    val loadOps = TestClientOperations()
                    val session = second.client.loadSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                        loadOps,
                    )
                    val replay = loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                        .associateBy { it.toolCallId.value }
                    assertEquals(
                        ToolCallStatus.COMPLETED,
                        replay.getValue("call_write_file").status,
                        "the answered call keeps its real outcome",
                    )
                    assertEquals(
                        ToolCallStatus.FAILED,
                        replay.getValue("call_bash").status,
                        "the dangling call must replay as a terminal FAILED",
                    )

                    val events = collectPrompt(session, listOf(ContentBlock.Text("Continue")))
                    assertEndTurn(events)
                    assertPairComplete(
                        llmMock.parseChatBody(llmMock.requestBodies.last()),
                        "the chat request of the loaded mid-batch-cancelled session",
                    )
                    println("[ok] mid-batch cancel: next persist ships the repaired pair-complete record; load replays FAILED+COMPLETED honestly")
                } finally {
                    second.close()
                }
            }
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
