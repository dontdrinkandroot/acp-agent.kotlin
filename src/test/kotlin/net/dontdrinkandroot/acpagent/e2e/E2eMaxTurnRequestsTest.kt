package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box coverage of the per-prompt tool iteration cap and its wind-down
 * synthesis pass: with a low `ACP_MAX_TURN_REQUESTS` and a model that always
 * calls tools, the agent executes exactly cap tool turns, then streams one final
 * text-only summary and ends with `MAX_TURN_REQUESTS`.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eMaxTurnRequestsTest : E2eAgentTest() {

    @Test
    fun `e2e max turn requests - cap tool turns then wind down text-only`() = runBlocking {
        withE2eAgent("max-turns", { projectDir ->
            MockOpenAiServer(
                targetPath = File(projectDir, "phase4.txt").absolutePath,
                alwaysToolCall = true,
            )
        }) {
            val connection = connect(extraEnv = mapOf("ACP_MAX_TURN_REQUESTS" to "2"))
            val operations = TestClientOperations()
            try {
                connection.client.initialize(testClientInfo())
                val session = newSession(connection.client, projectDir, operations)
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                val events = collectPrompt(
                    session,
                    listOf(ContentBlock.Text("Use the write_file tool repeatedly until told to stop.")),
                    timeoutMs = 120_000,
                )
                println("[ok] prompt completed (cap 2 -> wind-down)")

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
                assertEquals(2, toolCalls.size, "exactly the capped number of tool calls must run")

                val failedToolResults = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.status == ToolCallStatus.FAILED }
                assertEquals(
                    0,
                    failedToolResults.size,
                    "both capped tool calls must succeed, got: $failedToolResults",
                )
                println("[ok] both capped tool-call turns executed and completed")

                val textChunks = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                assertTrue(
                    textChunks.isNotEmpty(),
                    "the wind-down pass must stream assistant text, got none",
                )
                assertTrue(
                    textChunks.joinToString("").contains("phase4 done"),
                    "wind-down text must come from the final text-only request, got: ${textChunks.joinToString("")}",
                )
                println("[ok] final text-only wind-down pass streamed assistant text")

                assertEquals(3, llmMock.requestCount.toInt(), "cap tool turns + 1 wind-down request")
                val windDownBody = llmMock.requestBodies.last()
                assertTrue(
                    !windDownBody.contains("\"tools\""),
                    "the wind-down request must omit tools so the model cannot call any, got: $windDownBody",
                )
                println("[ok] wind-down request carries no tools")

                val promptResponse = events.filterIsInstance<Event.PromptResponseEvent>().single()
                assertEquals(StopReason.MAX_TURN_REQUESTS, promptResponse.response.stopReason)


                println("[ok] prompt response (stopReason=max_turn_requests)")
            } finally {
                connection.close()
            }
        }
    }
}
