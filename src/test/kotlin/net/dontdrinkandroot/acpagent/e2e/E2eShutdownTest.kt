package net.dontdrinkandroot.acpagent.e2e

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.agent.SessionStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box graceful-shutdown contract (issue #17): a turn that is interrupted
 * before it completes must still reach the session record. Two interrupt paths
 * are pinned - the client closing stdin (normal editor shutdown, which the
 * agent answers by cancelling the in-flight prompt and flushing every live
 * session before the JVM exits) and `session/cancel` (which persists the
 * partial turn itself).
 */
@OptIn(UnstableApi::class)
class E2eShutdownTest : E2eAgentTest() {

    @Test
    fun `e2e closing stdin mid-turn persists the interrupted turn`() = runBlocking {
        val targetDir = Files.createTempDirectory("acp-agent-e2e-shutdown-target").toFile()
        val outsidePath = File(targetDir, "outside.txt").absolutePath
        val llmMock = MockOpenAiServer(
            targetDir.absolutePath,
            toolCall = MockToolCall(
                "write_file",
                buildJsonObject {
                    put("path", JsonPrimitive(outsidePath))
                    put("content", JsonPrimitive("phase4"))
                },
            ),
        )
        try {
            withE2eAgent("shutdown", llmMock) {
                val connection = connect()
                val sessionId: SessionId
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    sessionId = session.sessionId
                    // build mode: write_file exists; the out-of-project target makes it
                    // prompt, which is the deterministic suspension point between the
                    // tool call's append and its result.
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    val promptJob = async {
                        runCatching { collectPrompt(session, listOf(ContentBlock.Text("Write the file"))) }
                    }
                    withTimeout(60_000) { ops.permissionRequestStarted.await() }
                    // The editor goes away: stdin closes, the agent must not drop the turn.
                    connection.process.outputStream.close()
                    assertTrue(
                        connection.process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS),
                        "the agent must exit after its stdin closed",
                    )
                    promptJob.await()
                } finally {
                    connection.close()
                }

                val record = assertNotNull(
                    SessionStore(sessionsStoreDir).load(sessionId.value),
                    "closing stdin mid-turn must persist the in-flight turn (issue #17)",
                )
                val userTexts = record.history.filterIsInstance<OpenAIMessage.User>()
                    .mapNotNull { (it.content as? Content.Text)?.text() }
                assertTrue(
                    "Write the file" in userTexts,
                    "the interrupted turn's user message must survive, got $userTexts",
                )
                assertPairComplete(record)
                println("[ok] stdin closed mid-turn: the interrupted turn is on disk and pair-complete")
            }
        } finally {
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun `e2e session cancel mid-turn persists the interrupted turn immediately`() = runBlocking {
        val targetDir = Files.createTempDirectory("acp-agent-e2e-cancel-persist-target").toFile()
        val llmMock = MockOpenAiServer(
            targetDir.absolutePath,
            toolCall = MockToolCall(
                "write_file",
                buildJsonObject {
                    put("path", JsonPrimitive(File(targetDir, "outside.txt").absolutePath))
                    put("content", JsonPrimitive("phase4"))
                },
            ),
        )
        try {
            withE2eAgent("cancel-persist", llmMock) {
                val connection = connect()
                val sessionId: SessionId
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    sessionId = session.sessionId
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    val promptJob = async {
                        runCatching { collectPrompt(session, listOf(ContentBlock.Text("Write the file"))) }
                    }
                    withTimeout(60_000) { ops.permissionRequestStarted.await() }
                    session.cancel()
                    promptJob.await()
                } finally {
                    connection.close()
                }

                // No follow-up turn: the cancel itself must have persisted the
                // partial turn (issue #17).
                val record = assertNotNull(
                    SessionStore(sessionsStoreDir).load(sessionId.value),
                    "session/cancel mid-turn must persist the in-flight turn (issue #17)",
                )
                val userTexts = record.history.filterIsInstance<OpenAIMessage.User>()
                    .mapNotNull { (it.content as? Content.Text)?.text() }
                assertTrue(
                    "Write the file" in userTexts,
                    "the cancelled turn's user message must survive, got $userTexts",
                )
                assertPairComplete(record)
                println("[ok] session/cancel mid-turn: the cancelled turn is on disk without a follow-up turn")
            }
        } finally {
            targetDir.deleteRecursively()
        }
    }

    /** The issue #38 pairing invariant, asserted on the persisted record. */
    private fun assertPairComplete(record: net.dontdrinkandroot.acpagent.agent.SessionRecord) {
        val callIds = record.history.filterIsInstance<OpenAIMessage.Assistant>()
            .flatMap { it.toolCalls.orEmpty() }
            .map { it.id }
        val resultIds = record.history.filterIsInstance<OpenAIMessage.Tool>().map { it.toolCallId }
        assertTrue(
            callIds.subtract(resultIds.toSet()).isEmpty(),
            "the persisted record must be pair-complete (calls=$callIds, results=$resultIds)",
        )
    }
}
