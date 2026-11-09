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
 * synthesis pass: with a low `DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS` and a model that always
 * calls tools, the agent consumes exactly cap tool iterations (the third
 * identical call with the same result is refused by the repeat guard, #39),
 * then streams one final text-only summary and ends with `MAX_TURN_REQUESTS`.
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
            val connection = connect(extraEnv = mapOf("DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS" to "3"))
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
                println("[ok] prompt completed (cap 3 -> wind-down)")

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
                // The mock emits the identical write_file call for all capped iterations; the
                // first two execute with the same result, the third is refused by the repeat
                // guard (#39), so only two calls reach a ToolCall creation + execution.
                assertEquals(2, toolCalls.size, "the third identical call is refused before a ToolCall creation")

                val failedToolResults = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.status == ToolCallStatus.FAILED }
                assertEquals(
                    1,
                    failedToolResults.size,
                    "the third identical call must fail with the guard's refusal, got: $failedToolResults",
                )
                assertTrue(
                    (failedToolResults.single().content.orEmpty().filterIsInstance<ToolCallContent.Content>()
                        .single().content as ContentBlock.Text).text.contains("repeated tool call"),
                    "the refusal must name the repetition so the model can react, got: ${failedToolResults.single()}",
                )
                println("[ok] first two capped tool calls executed, the identical third was refused")

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

                // The cap+wind-down shape is the contract, not a request total: every
                // capped iteration offers tools, and only the final synthesis pass
                // drops them so the model cannot call any.
                val bodies = llmMock.requestBodies
                assertTrue(
                    bodies.size >= 2,
                    "expected capped iterations plus the wind-down request, got ${bodies.size}"
                )
                assertTrue(
                    bodies.dropLast(1).all { it.contains("\"tools\"") },
                    "every capped tool iteration must offer tools",
                )
                val windDownBody = bodies.last()
                assertTrue(
                    !windDownBody.contains("\"tools\""),
                    "the wind-down request must omit tools so the model cannot call any, got: $windDownBody",
                )
                println("[ok] wind-down request carries no tools (cap iterations did)")

                val promptResponse = events.filterIsInstance<Event.PromptResponseEvent>().single()
                assertEquals(StopReason.MAX_TURN_REQUESTS, promptResponse.response.stopReason)


                println("[ok] prompt response (stopReason=max_turn_requests)")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e max turn requests - the interrupted note precedes the usage indicator`() = runBlocking {
        // The turn-end delivery order is a wire contract: the synthesized note
        // (content) first, then the usage indicator, then the prompt response.
        withE2eAgent("max-turns-note", { projectDir ->
            MockOpenAiServer(
                targetPath = File(projectDir, "phase4.txt").absolutePath,
                alwaysToolCall = true,
                incompleteWindDown = true,
            )
        }) {
            val connection = connect(extraEnv = mapOf("DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS" to "1"))
            val operations = TestClientOperations()
            try {
                connection.client.initialize(testClientInfo())
                val session = newSession(connection.client, projectDir, operations)
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                val events = collectPrompt(
                    session,
                    listOf(ContentBlock.Text("Use the write_file tool, then summarize.")),
                    timeoutMs = 120_000,
                )

                val noteText = "The response was interrupted before completion; the task may need a new prompt."
                val noteIndex = events.indexOfFirst { event ->
                    val update = (event as? Event.SessionUpdateEvent)?.update
                    update is SessionUpdate.AgentMessageChunk &&
                            (update.content as? ContentBlock.Text)?.text == noteText
                }
                assertTrue(noteIndex >= 0, "the truncated wind-down must emit the interrupted note, got: $events")
                val usageIndex = events.indexOfFirst { event ->
                    (event as? Event.SessionUpdateEvent)?.update is SessionUpdate.UsageUpdate
                }
                assertTrue(usageIndex >= 0, "the turn must report its usage, got: $events")
                val responseIndex = events.indexOfFirst { it is Event.PromptResponseEvent }
                assertTrue(responseIndex >= 0, "the turn must answer the prompt, got: $events")

                assertTrue(
                    noteIndex < usageIndex && usageIndex < responseIndex,
                    "expected note ($noteIndex) -> usage_update ($usageIndex) -> response ($responseIndex)",
                )
                assertEquals(
                    StopReason.MAX_TURN_REQUESTS,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
                )
                println("[ok] interrupted note precedes usage_update, which precedes the prompt response")
            } finally {
                connection.close()
            }
        }
    }
}
