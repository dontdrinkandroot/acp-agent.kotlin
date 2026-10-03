package net.dontdrinkandroot.acpagent.e2e

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.agent.SessionRecord
import net.dontdrinkandroot.acpagent.agent.SessionStore
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

    private fun assertPairComplete(body: JsonObject, context: String) {
        val messages = body["messages"]!!.jsonArray
        val callIds = messages.asSequence()
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .flatMap { (it.jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())).jsonArray }
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .toList()
        val resultIds = messages
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
            .map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content }
        assertTrue(
            callIds.subtract(resultIds.toSet()).isEmpty(),
            "$context: every tool call in the chat request must carry a tool output " +
                    "(dangling=${callIds.subtract(resultIds.toSet())}, calls=$callIds, results=$resultIds)",
        )
    }

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
                            history = listOf(
                                OpenAIMessage.User(Content.Text("Run the config")),
                                OpenAIMessage.Assistant(
                                    content = Content.Text(""),
                                    toolCalls = listOf(
                                        OpenAIToolCall(
                                            "call_poisoned",
                                            OpenAIFunction("run", "{\"config\":\"validate\"}")
                                        )
                                    ),
                                ),
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
                            ClientOperationsFactory { _, _ -> loadOps },
                        )
                        awaitUntil(5_000) {
                            loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                                .any { it.toolCallId.value == "call_poisoned" }
                        }
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
                val first = connect(extraEnv = mapOf("FS_PROXY_ENABLED" to "0"))
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

                    // The cancel persists nothing itself; the repaired view
                    // ships with the very next persist of this process. Run a
                    // short second turn so the record on disk is fresh, then
                    // verify it is pair-complete with the synthetic result.
                    val secondTurn = collectPrompt(session, listOf(ContentBlock.Text("Continue")))
                    assertEndTurn(secondTurn)
                } finally {
                    first.close()
                }

                val record = SessionStore(sessionsStoreDir).load(sessionId.value)
                assertNotNull(record)
                val calls = record.history
                    .filterIsInstance<OpenAIMessage.Assistant>()
                    .flatMap { it.toolCalls.orEmpty() }
                    .map { it.id }
                val results = record.history
                    .filterIsInstance<OpenAIMessage.Tool>()
                    .map { it.toolCallId }
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
                        ClientOperationsFactory { _, _ -> loadOps },
                    )
                    awaitUntil(5_000) {
                        loadOps.notifications.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                            .any { it.toolCallId.value == "call_bash" }
                    }
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
