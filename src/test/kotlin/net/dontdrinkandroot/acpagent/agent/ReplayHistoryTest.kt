package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.WriteFileTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the `session/load` replay mapping ([buildReplayUpdates]): tool results
 * must replay the outcome they had live (denied/disabled/unknown/failed ->
 * FAILED, successes -> COMPLETED) instead of the historical blanket COMPLETED.
 */
class ReplayHistoryTest {

    private fun toolCallHistory(toolName: String) = listOf(
        OpenAIMessage.User(Content.Text("Do it")),
        OpenAIMessage.Assistant(
            content = Content.Text(""),
            toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction(toolName, "{\"path\":\"a.txt\"}"))),
        ),
        OpenAIMessage.Tool(Content.Text("result text"), toolCallId = "call_1"),
    )

    private fun updates(
        history: List<OpenAIMessage>,
        toolOutcomes: Map<String, String> = emptyMap(),
        plan: List<PlanEntry> = emptyList(),
        registry: ToolRegistry = ToolRegistry(),
    ): List<SessionUpdate> = buildReplayUpdates(history, plan, toolOutcomes, registry)

    @Test
    fun `persisted failed outcome replays as FAILED with the denial text`() {
        val updates = updates(
            toolCallHistory("bash"),
            toolOutcomes = mapOf("call_1" to TOOL_OUTCOME_FAILED),
        )

        val update = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single()
        assertEquals(ToolCallId("call_1"), update.toolCallId)
        assertEquals(ToolCallStatus.FAILED, update.status)
        assertEquals(
            "result text",
            (update.content.orEmpty().filterIsInstance<ToolCallContent.Content>().single().content
                as ContentBlock.Text).text,
        )
    }

    @Test
    fun `persisted completed outcome replays as COMPLETED`() {
        val updates = updates(
            toolCallHistory("write_file"),
            toolOutcomes = mapOf("call_1" to TOOL_OUTCOME_COMPLETED),
        )

        assertEquals(ToolCallStatus.COMPLETED, updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single().status)
    }

    @Test
    fun `legacy record without outcomes replays COMPLETED fail-open`() {
        val updates = updates(toolCallHistory("write_file"))

        assertEquals(ToolCallStatus.COMPLETED, updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single().status)
    }

    @Test
    fun `unknown outcome value falls back to COMPLETED`() {
        val updates = updates(
            toolCallHistory("write_file"),
            toolOutcomes = mapOf("call_1" to "bogus-value"),
        )

        assertEquals(ToolCallStatus.COMPLETED, updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single().status)
    }

    @Test
    fun `assistant tool call intro carries fallback title for an unknown tool`() {
        val updates = updates(toolCallHistory("no_such_tool"))

        val intro = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertEquals("no_such_tool", intro.title)
        assertEquals(ToolKind.OTHER, intro.kind)
        assertEquals(ToolCallStatus.PENDING, intro.status)
    }

    @Test
    fun `known tool intro carries title, kind and locations`() {
        val updates = updates(
            toolCallHistory("write_file"),
            registry = ToolRegistry().apply { register(WriteFileTool()) },
        )

        val intro = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertTrue(intro.title.contains("write_file"), "expected an argument-bearing title, got ${intro.title}")
        assertEquals(ToolKind.EDIT, intro.kind)
        assertEquals(listOf(ToolCallLocation("a.txt")), intro.locations)
    }

    @Test
    fun `user and agent text replay as message chunks`() {
        val updates = updates(
            listOf(
                OpenAIMessage.User(Content.Text("hello")),
                OpenAIMessage.Assistant(content = Content.Text("hi there")),
                OpenAIMessage.Assistant(content = Content.Text(""), toolCalls = emptyList()),
            ),
        )

        assertEquals(1, updates.filterIsInstance<SessionUpdate.UserMessageChunk>().size)
        assertEquals(1, updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().size)
    }

    @Test
    fun `plan replays after the history`() {
        val plan = listOf(PlanEntry(content = "Investigate", priority = PlanEntryPriority.HIGH, status = PlanEntryStatus.PENDING))
        val updates = updates(
            listOf(OpenAIMessage.User(Content.Text("hello"))),
            plan = plan,
        )

        assertEquals(plan, updates.filterIsInstance<SessionUpdate.PlanUpdate>().single().entries)
        assertTrue(updates.last() is SessionUpdate.PlanUpdate, "the plan update must come after the history")
    }

    @Test
    fun `every replayed chunk carries a message id`() {
        val updates = updates(
            listOf(
                OpenAIMessage.User(Content.Text("hello")),
                OpenAIMessage.Assistant(content = Content.Text("reply")),
            ),
        )

        val chunks = updates.mapNotNull { chunk ->
            when (chunk) {
                is SessionUpdate.UserMessageChunk -> chunk.messageId
                is SessionUpdate.AgentMessageChunk -> chunk.messageId
                else -> null
            }
        }
        assertEquals(2, chunks.size, "both text chunks replay as message chunks carrying a messageId")
    }
}