package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        userMessage("Do it"),
        functionCall("call_1", toolName, "{\"path\":\"a.txt\"}"),
        toolOutput("call_1", "result text"),
    )

    private fun updates(
        history: List<HistoryItem>,
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
    fun `an item kept verbatim by the lossless parse replays like its typed counterpart`() {
        // A prompt with an image part is not re-typed (the typed model drops the
        // image), so it arrives as HistoryItem.Captured - replay must still show
        // the user text, purely from the item's meaning.
        val imagePrompt = historyItemFromWire(
            buildJsonObject {
                put("type", "message")
                put("role", "user")
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "input_text"); put("text", "look at this") })
                        add(
                            buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:image/png;base64,AAAA")
                            }
                        )
                    },
                )
            }
        )
        val multimodalResult = historyItemFromWire(
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", "call_1")
                put("output", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "x") }) })
            }
        )

        val updates = updates(
            listOf(imagePrompt, functionCall("call_1", "bash", "{}"), multimodalResult),
            toolOutcomes = mapOf("call_1" to TOOL_OUTCOME_COMPLETED),
        )

        assertEquals(
            listOf("look at this"),
            updates.filterIsInstance<SessionUpdate.UserMessageChunk>()
                .map { (it.content as ContentBlock.Text).text },
        )
        val result = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().single()
        assertEquals(ToolCallId("call_1"), result.toolCallId, "the captured result still pairs and replays")
        assertEquals(ToolCallStatus.COMPLETED, result.status)
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
                userMessage("hello"),
                assistantMessage("hi there"),
            ),
        )

        assertEquals(1, updates.filterIsInstance<SessionUpdate.UserMessageChunk>().size)
        assertEquals(1, updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().size)
    }

    @Test
    fun `plan replays after the history`() {
        val plan = listOf(PlanEntry(content = "Investigate", priority = PlanEntryPriority.HIGH, status = PlanEntryStatus.PENDING))
        val updates = updates(
            listOf(userMessage("hello")),
            plan = plan,
        )

        assertEquals(plan, updates.filterIsInstance<SessionUpdate.PlanUpdate>().single().entries)
        assertTrue(updates.last() is SessionUpdate.PlanUpdate, "the plan update must come after the history")
    }

    @Test
    fun `every replayed chunk carries a message id`() {
        val updates = updates(
            listOf(
                userMessage("hello"),
                assistantMessage("reply"),
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
