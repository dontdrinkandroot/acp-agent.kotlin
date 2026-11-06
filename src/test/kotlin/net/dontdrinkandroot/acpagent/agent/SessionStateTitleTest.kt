package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionId
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.test.*

/**
 * The title is derived lazily from the first user message and announced to the
 * client exactly once (issue #73), so the client sees a `session_info_update`
 * after the turn that derived it instead of having to re-list the sessions.
 * A restored record's title counts as already announced - `session/load`,
 * `session/resume` and `session/list` carry it - so only a title that is new
 * to the client is announced.
 */
class SessionStateTitleTest {

    private fun state(restored: SessionRecord? = null) = SessionState(
        sessionId = SessionId("sess_titletest000001"),
        cwd = "/project",
        toolRegistry = ToolRegistry(),
        config = Config("k", "m", "http://127.0.0.1:1"),
        restored = restored,
        sessionStore = null,
        closeResources = {},
    )

    private fun record(title: String) = SessionRecord(
        sessionId = "sess_titletest000001",
        cwd = "/project",
        mode = "plan",
        title = title,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun `the derived title is announced exactly once`() {
        val state = state()
        state.appendToHistory(userMessage("Fix the build"))

        assertEquals("Fix the build", state.takeTitleAnnouncement())
        assertNull(state.takeTitleAnnouncement(), "the title must be announced only once")
    }

    @Test
    fun `a restored title is not announced again`() {
        val state = state(record("Fix the bug"))
        state.appendToHistory(userMessage("Continue"))

        assertNull(state.takeTitleAnnouncement(), "the client already knows the restored title")
    }

    @Test
    fun `a restored record without a title announces it at the next turn`() {
        val state = state(record(""))
        state.appendToHistory(userMessage("Fix the build"))

        assertEquals("Fix the build", state.takeTitleAnnouncement())
    }

    @Test
    fun `the announced title is capped at 72 code points`() {
        val state = state()
        state.appendToHistory(userMessage("x".repeat(100)))

        val announced = assertNotNull(state.takeTitleAnnouncement())

        assertEquals(73, announced.length, "72 code points plus the ellipsis")
        assertTrue(announced.endsWith("…"), announced)
    }
}
