package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionId
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the view-boundary wiring of the pairing repair (issue #38): every
 * consumer of the session history - LLM requests via [SessionState.historySnapshot],
 * load replay via [SessionState.replaySnapshot], persisted records via
 * [SessionState.buildRecord] - must see dangling tool calls closed by the
 * synthetic failed result, while the in-memory history itself stays untouched.
 */
class SessionStateRepairTest {

    private fun danglingHistory() = listOf(
        userMessage("Run the config"),
        functionCall("call_1", "run", "{\"config\":\"validate\"}"),
        userMessage("you were too slow"),
    )

    private fun stateWith(history: List<HistoryItem>, outcomes: Map<String, String> = emptyMap()) = SessionState(
        sessionId = SessionId("sess_repairtest000001"),
        cwd = "/project",
        toolRegistry = ToolRegistry(),
        config = Config("k", "m", "http://127.0.0.1:1"),
        restored = SessionRecord(
            sessionId = "sess_repairtest000001",
            cwd = "/project",
            mode = "build",
            title = "t",
            updatedAt = 0,
            history = history.map { it.toWire() },
            toolOutcomes = outcomes,
        ),
        sessionStore = null,
        closeResources = {},
    )

    @Test
    fun `historySnapshot closes a dangling call without mutating the stored history`() {
        val state = stateWith(danglingHistory())

        val snapshot = state.historySnapshot

        assertEquals(4, snapshot.size)
        assertEquals(CANCELLED_TOOL_RESULT_TEXT, assertIs<HistoryItem.ToolOutput>(snapshot[2]).output)
        // The stored history itself is not repaired: appending continues after
        // the original last item, and the record repairs on the way out.
        state.appendToHistory(userMessage("next"))
        val record = state.buildRecord()
        assertEquals(5, record.history.size)
        assertEquals(
            "next",
            historyItemFromWire(record.history[4]).let { assertIs<HistoryItem.UserMessage>(it).textOrNull() },
            "the append landed on the stored tail, not after the synthetic result",
        )
    }

    @Test
    fun `replaySnapshot pairs the synthetic result with a failed outcome`() {
        val state = stateWith(danglingHistory())

        val (history, _, outcomes) = state.replaySnapshot()

        assertEquals(4, history.size)
        assertEquals(TOOL_OUTCOME_FAILED, outcomes["call_1"])
    }

    @Test
    fun `buildRecord persists the repaired view`() {
        val state = stateWith(danglingHistory())

        val record = state.buildRecord()

        assertEquals(4, record.history.size)
        assertEquals(TOOL_OUTCOME_FAILED, record.toolOutcomes["call_1"])
    }

    @Test
    fun `a real outcome beats the repair - pairing only synthesizes missing results`() {
        // call_1 has a result but no outcome entry (legacy blank-out): the
        // replay contract is fail-open COMPLETED, not a synthesized FAILED.
        val state = stateWith(
            listOf(
                userMessage("Do it"),
                functionCall("call_1", "bash", "{}"),
                toolOutput("call_1", "real result"),
            ),
        )

        val (history, _, outcomes) = state.replaySnapshot()

        assertEquals(3, history.size, "no synthetic result is inserted for an answered call")
        assertEquals(emptyMap<String, String>(), outcomes, "outcomes are not invented")
    }

    @Test
    fun `a restored history reproduces the record wire items exactly`() {
        // Round-trip identity: an item the typed model cannot express without
        // losing data (a prompt image, a multimodal tool output) must survive a
        // restore+persist cycle byte-for-byte instead of being rewritten.
        val imagePrompt = buildJsonObject {
            put("type", "message")
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", "what is this") })
                    add(
                        buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:image/png;base64,AAAA")
                        }
                    )
                },
            )
        }
        val multimodalToolOutput = buildJsonObject {
            put("type", "function_call_output")
            put("call_id", "call_1")
            put(
                "output",
                buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", "shot") })
                    add(
                        buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:image/png;base64,BBBB")
                        }
                    )
                },
            )
        }
        val history = listOf(
            imagePrompt,
            assistantMessage("done").toWire(),
            functionCall("call_1", "screenshot", "{}").toWire(),
            multimodalToolOutput,
        )
        val state = SessionState(
            sessionId = SessionId("sess_roundtrip000001"),
            cwd = "/project",
            toolRegistry = ToolRegistry(),
            config = Config("k", "test-model", "http://127.0.0.1:1"),
            restored = SessionRecord(
                sessionId = "sess_roundtrip000001",
                cwd = "/project",
                mode = "build",
                title = "t",
                updatedAt = 0,
                history = history,
            ),
            sessionStore = null,
            closeResources = {},
        )

        assertEquals(history, state.buildRecord().history, "no wire item may be rewritten or dropped by a restore")
    }
}
