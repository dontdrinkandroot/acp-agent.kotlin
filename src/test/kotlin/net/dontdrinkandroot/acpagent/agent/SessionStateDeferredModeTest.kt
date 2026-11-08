package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.config.Secret
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mode status messages are written exclusively at prompt start: a mode
 * request (idle or deferred mid-turn) flips the governing mode but touches
 * no history; the single [SessionState.indicateCurrentMode] writer asks
 * [ModeState] which mode must be stated and appends the `Mode:` message when -
 * and only when - the mode changed since the last indicated one. The trail
 * therefore never contains a mode that did not govern a turn (issue #36).
 *
 * Deferred mid-turn switching keeps its latest-wins pending slot: the switch
 * applies (and notifies the client) at turn end, but its message also waits
 * for the next prompt start. The machine lives in [SessionState.mode]
 * (issue #83); the trail stays on [SessionState].
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
            config = Config(Secret("k"), "test-model", "http://127.0.0.1:1"),
            restored = null,
            sessionStore = null,
            closeResources = {},
        )
        return Triple(state, registry, messages)
    }

    private fun modeMessages(state: SessionState): List<String> =
        state.historySnapshot.filterIsInstance<HistoryItem.SystemMessage>()
            .map { it.text }
            .filter { it.startsWith("Mode: ") }

    @Test
    fun `a fresh session writes no seed message - the first prompt indicates the mode`() {
        val (state, _, _) = stateAndRegistry()
        assertEquals(emptyList(), modeMessages(state), "no seed: indication happens at prompt start")
        state.indicateCurrentMode()
        assertEquals(
            listOf(
                "Mode: plan. Read-only: research, analyze and plan; do not modify files. " +
                        "Available tools: read_file.",
            ),
            modeMessages(state),
        )
    }

    @Test
    fun `requesting a mode during a prompt defers it - current mode, history and notifications unchanged`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.setPromptActive(true)
        state.mode.requestMode(SessionModeId("build"))

        // The turn continues under the old mode: current mode defers, history
        // untouched (no message until the next prompt start in any case).
        assertEquals(SessionModeId("plan"), state.mode.currentMode)
        assertEquals(emptyList(), modeMessages(state))
        assertTrue(state.mode.hasPendingMode(), "the request must be held as pending, not applied")
        state.mode.setPromptActive(false)
    }

    @Test
    fun `idle switches flip the mode without appending history`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.requestMode(SessionModeId("build"))
        state.mode.requestMode(SessionModeId("bash"))

        assertEquals(SessionModeId("bash"), state.mode.currentMode)
        assertEquals(emptyList(), modeMessages(state), "idle switches only flip - the message waits for prompt start")
    }

    @Test
    fun `prompt start indicates the latest mode once after multiple idle switches`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.requestMode(SessionModeId("build"))
        state.mode.requestMode(SessionModeId("bash"))

        state.indicateCurrentMode()

        assertEquals(
            listOf(
                "Mode: bash. Build plus a permission-gated shell; every command is confirmed by the user first. " +
                        "Available tools: read_file.",
            ),
            modeMessages(state),
        )
    }

    @Test
    fun `flipping back to the last indicated mode adds no message at prompt start`() {
        val (state, _, _) = stateAndRegistry()
        state.indicateCurrentMode() // plan is in the trail
        state.mode.requestMode(SessionModeId("build"))
        state.mode.requestMode(SessionModeId("plan")) // blip back to the indicated mode

        state.indicateCurrentMode()

        assertEquals(
            1,
            modeMessages(state).size,
            "the blip never governed a turn - the trail keeps the original plan message",
        )

        // A genuine change after the blip still appends exactly one message.
        state.mode.requestMode(SessionModeId("bash"))
        state.indicateCurrentMode()
        assertEquals(2, modeMessages(state).size)
        assertTrue(modeMessages(state).last().startsWith("Mode: bash."))
    }

    @Test
    fun `flush applies the latest pending mode exactly once - message still waits for prompt start`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.setPromptActive(true)
        state.mode.requestMode(SessionModeId("bash"))
        state.mode.requestMode(SessionModeId("build")) // latest change wins

        assertTrue(state.mode.flushPendingMode(), "flush must report the applied switch")
        assertEquals(SessionModeId("build"), state.mode.currentMode)
        assertTrue(!state.mode.hasPendingMode(), "the flush must consume the pending switch")
        assertEquals(emptyList(), modeMessages(state), "flush flips, only prompt start writes the message")

        state.indicateCurrentMode()
        assertEquals(
            listOf(
                "Mode: build. Read-write: read, write, edit, move and delete files to implement the task. " +
                        "Available tools: read_file.",
            ),
            modeMessages(state),
        )
        // Flushing again (no pending) is a no-op.
        assertTrue(!state.mode.flushPendingMode())
        state.indicateCurrentMode()
        assertEquals(1, modeMessages(state).size)
    }

    @Test
    fun `cancelling a prompt drops the pending mode without applying or rendering anything`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.setPromptActive(true)
        state.mode.requestMode(SessionModeId("bash"))

        state.mode.cancelPrompt()

        assertEquals(SessionModeId("plan"), state.mode.currentMode)
        assertTrue(!state.mode.hasPendingMode(), "cancel must drop the pending switch")
        assertEquals(emptyList(), modeMessages(state))
        // The next prompt starts clean again; flush afterwards is a no-op.
        state.mode.setPromptActive(false)
        assertTrue(!state.mode.flushPendingMode())
        state.indicateCurrentMode()
        assertEquals(
            listOf(
                "Mode: plan. Read-only: research, analyze and plan; do not modify files. " +
                        "Available tools: read_file.",
            ),
            modeMessages(state),
        )
    }

    @Test
    fun `requesting a mode while idle applies it immediately without rendering`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.requestMode(SessionModeId("bash"))

        assertEquals(SessionModeId("bash"), state.mode.currentMode)
        assertEquals(emptyList(), modeMessages(state))
    }

    @Test
    fun `same-value mode requests are no-ops`() {
        val (state, _, _) = stateAndRegistry()
        state.mode.requestMode(SessionModeId("plan")) // idle, same as current
        state.indicateCurrentMode()
        assertEquals(
            listOf(
                "Mode: plan. Read-only: research, analyze and plan; do not modify files. " +
                        "Available tools: read_file.",
            ),
            modeMessages(state),
        )
        state.mode.setPromptActive(true)
        state.mode.requestMode(SessionModeId("plan"))
        state.mode.setPromptActive(false)
        assertTrue(!state.mode.flushPendingMode())
        state.indicateCurrentMode()
        assertEquals(1, modeMessages(state).size)
    }

    @Test
    fun `restore parses the last indicated mode from the trail - a switch after the last message self-heals`() {
        val trail = listOf(
            systemMessage("Mode: plan. Read-only. Available tools: read_file."),
            userMessage("first prompt"),
            systemMessage("Mode: build. Read-write. Available tools: read_file, write_file."),
        )
        val state = SessionState(
            sessionId = com.agentclientprotocol.model.SessionId("sess_deferred0002"),
            cwd = "/tmp",
            toolRegistry = ToolRegistry().apply {
                register(ToolStub("read_file", mutating = false, modes = emptyList(), mutableListOf()))
            },
            config = Config(Secret("k"), "test-model", "http://127.0.0.1:1"),
            restored = SessionRecord(
                sessionId = "sess_deferred0002",
                cwd = "/tmp",
                mode = "build",
                title = "t",
                updatedAt = 0L,
                history = trail.map { it.toWire() },
            ),
            sessionStore = null,
            closeResources = {},
        )

        // The record says build (a turn-end flush persisted it) but the trail's
        // last message still says plan: the next prompt appends the corrective
        // build message instead of staying silent.
        state.indicateCurrentMode()
        val messages = modeMessages(state)
        assertEquals(2, messages.size)
        assertTrue(
            messages.last().startsWith("Mode: build."),
            "crash after a turn-end flush must self-heal at the next prompt start, got: $messages",
        )

        // A trail already matching the record's mode adds nothing.
        state.indicateCurrentMode()
        assertEquals(2, modeMessages(state).size)
    }

    @Test
    fun `a legacy record without mode messages gets one indication at the next prompt`() {
        val state = SessionState(
            sessionId = com.agentclientprotocol.model.SessionId("sess_deferred0003"),
            cwd = "/tmp",
            toolRegistry = ToolRegistry().apply {
                register(ToolStub("read_file", mutating = false, modes = emptyList(), mutableListOf()))
            },
            config = Config(Secret("k"), "test-model", "http://127.0.0.1:1"),
            restored = SessionRecord(
                sessionId = "sess_deferred0003",
                cwd = "/tmp",
                mode = "plan",
                title = "t",
                updatedAt = 0L,
                history = listOf(userMessage("legacy prompt")).map { it.toWire() },
            ),
            sessionStore = null,
            closeResources = {},
        )

        // Legacy records stayed silent forever before; now the next prompt
        // start writes one message for the governing mode.
        state.indicateCurrentMode()
        val messages = modeMessages(state)
        assertEquals(1, messages.size)
        assertTrue(messages.single().startsWith("Mode: plan."))
    }

    @Test
    fun `the initial seed message includes the mode description`() {
        val (state, _, _) = stateAndRegistry()
        state.indicateCurrentMode()
        assertEquals(
            "Mode: plan. Read-only: research, analyze and plan; do not modify files. Available tools: read_file.",
            modeMessages(state).single(),
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