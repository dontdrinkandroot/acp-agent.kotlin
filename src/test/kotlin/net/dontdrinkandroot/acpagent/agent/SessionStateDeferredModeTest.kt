package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deferred mode switching: a mode request while a prompt turn is in flight
 * must not apply (and must not render or notify) until the turn hands control
 * back. Since the client is never told about a pending mode, a cancelled turn
 * drops the request with no flip and no residue.
 */
class SessionStateDeferredModeTest {

    private fun stateAndRegistry(): Triple<SessionState, ToolRegistry, List<String>> {
        val messages = mutableListOf<String>()
        val registry = ToolRegistry().apply {
            register(ToolStub("read_file", mutating = false, modes = emptyList(), messages))
        }
        val state = SessionState(
            sessionId = com.agentclientprotocol.model.SessionId("sess_deferred0001"),
            cwd = "/tmp",
            toolRegistry = registry,
            config = Config("k", "test-model", "http://127.0.0.1:1"),
            restored = null,
            sessionStore = null,
            closeResources = {},
        )
        return Triple(state, registry, messages)
    }

    private fun modeMessages(state: SessionState): List<String> =
        state.historySnapshot.filterIsInstance<OpenAIMessage.System>()
            .mapNotNull { (it.content as? Content.Text)?.text() }
            .filter { it.startsWith("Mode: ") }

    private fun statusText(state: SessionState): String =
        state.historySnapshot
            .filterIsInstance<OpenAIMessage.System>()
            .mapNotNull { (it.content as? Content.Text)?.text() }
            .first { it.startsWith("Mode: ") }

    @Test
    fun `requesting a mode during a prompt defers it - current mode, history and notifications unchanged`() {
        val (state, _, _) = stateAndRegistry()
        state.setPromptActive(true)
        state.requestMode(SessionModeId("build"))

        // The turn continues under the old mode: current mode, history and
        // the mode config option all still report plan.
        assertEquals(SessionModeId("plan"), state.currentMode)
        assertEquals(
            listOf("Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file."),
            modeMessages(state),
        )
        assertEquals(SessionModeId("plan"), state.modeConfigValue())
        state.setPromptActive(false)
    }

    @Test
    fun `flush applies the latest pending mode exactly once`() {
        val (state, _, _) = stateAndRegistry()
        state.setPromptActive(true)
        state.requestMode(SessionModeId("bash"))
        state.requestMode(SessionModeId("build")) // latest change wins

        state.flushPendingMode()

        assertEquals(SessionModeId("build"), state.currentMode)
        assertEquals(SessionModeId("build"), state.modeConfigValue())
        assertEquals(
            listOf(
                "Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file.",
                "Mode: build. Read-write: read, write, edit, move and delete files to implement the task. Available tools: read_file.",
            ),
            modeMessages(state),
        )
        // Flushing again (no pending) is a no-op.
        state.flushPendingMode()
        assertEquals(2, modeMessages(state).size)
    }

    @Test
    fun `cancelling a prompt drops the pending mode without applying or rendering anything`() {
        val (state, _, _) = stateAndRegistry()
        state.setPromptActive(true)
        state.requestMode(SessionModeId("bash"))

        state.cancelPrompt()

        assertEquals(SessionModeId("plan"), state.currentMode)
        assertEquals(SessionModeId("plan"), state.modeConfigValue())
        assertEquals(1, modeMessages(state).size)
        // The next prompt starts clean again; flush afterwards is a no-op.
        state.setPromptActive(false)
        state.flushPendingMode()
        assertEquals(
            listOf("Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file."),
            modeMessages(state),
        )
    }

    @Test
    fun `requesting a mode while idle applies it immediately`() {
        val (state, _, _) = stateAndRegistry()
        state.requestMode(SessionModeId("bash"))

        assertEquals(SessionModeId("bash"), state.currentMode)
        assertTrue(
            modeMessages(state).last().startsWith("Mode: bash."),
            "idle switch must render its status message immediately, got: ${modeMessages(state)}",
        )
    }

    @Test
    fun `same-value mode requests are no-ops`() {
        val (state, _, _) = stateAndRegistry()
        state.requestMode(SessionModeId("plan")) // idle, same as current
        assertEquals(
            listOf("Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file."),
            modeMessages(state),
        )
        state.setPromptActive(true)
        state.requestMode(SessionModeId("plan"))
        state.flushPendingMode()
        assertEquals(
            listOf("Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file."),
            modeMessages(state),
        )
    }

    @Test
    fun `the initial seed message includes the mode description`() {
        val (state, _, _) = stateAndRegistry()
        assertEquals(
            "Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file.",
            statusText(state),
        )
    }

    private class ToolStub(
        override val name: String,
        override val mutating: Boolean,
        override val modes: List<SessionModeId>,
        private val messages: MutableList<String>,
    ) : AgentTool {
        override val description = "test tool"
        override val parameters: JsonObject = buildJsonObject { }
        override val kind: ToolKind = ToolKind.OTHER
        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            messages += "ran"
            return ToolResult("ran")
        }
    }
}