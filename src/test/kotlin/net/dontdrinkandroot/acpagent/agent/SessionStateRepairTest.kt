package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import com.agentclientprotocol.model.SessionId
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
        OpenAIMessage.User(Content.Text("Run the config")),
        OpenAIMessage.Assistant(
            content = Content.Text(""),
            toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("run", "{\"config\":\"validate\"}"))),
        ),
        OpenAIMessage.User(Content.Text("you were too slow")),
    )

    private fun stateWith(history: List<OpenAIMessage>, outcomes: Map<String, String> = emptyMap()) = SessionState(
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
            history = history,
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
        assertEquals(
            CANCELLED_TOOL_RESULT_TEXT,
            (assertIs<OpenAIMessage.Tool>(snapshot[2]).content as Content.Text).text(),
        )
        // The stored history itself is not repaired: appending continues after
        // the original last message, and the record repairs on the way out.
        state.appendToHistory(OpenAIMessage.User(Content.Text("next")))
        val record = state.buildRecord()
        assertEquals(5, record.history.size)
        assertEquals(
            "next",
            (assertIs<OpenAIMessage.User>(record.history[4]).content as Content.Text).text(),
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
                OpenAIMessage.User(Content.Text("Do it")),
                OpenAIMessage.Assistant(
                    content = Content.Text(""),
                    toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("bash", "{}"))),
                ),
                OpenAIMessage.Tool(Content.Text("real result"), toolCallId = "call_1"),
            ),
        )

        val (history, _, outcomes) = state.replaySnapshot()

        assertEquals(3, history.size, "no synthetic result is inserted for an answered call")
        assertEquals(emptyMap<String, String>(), outcomes, "outcomes are not invented")
    }
}
