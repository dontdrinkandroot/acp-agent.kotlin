package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionId
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the persisted lifecycle of the `provider` selection ("auto" default,
 * restored on load, reset on model switch). Validation against the current
 * model's endpoints feed is pinned in [SessionConfigOptionsTest]; here the
 * selection is a plain string, "" meaning auto.
 */
class SessionStateProviderSelectionTest {

    private fun state(restored: SessionRecord? = null) = SessionState(
        sessionId = SessionId("sess_providertest0001"),
        cwd = "/project",
        toolRegistry = ToolRegistry(),
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        restored = restored,
        sessionStore = null,
        closeResources = {},
    )

    @Test
    fun `fresh selection is auto`() {
        assertEquals("", state().providerSelection)
    }

    @Test
    fun `selection round-trips through the record`() {
        val s = state()
        s.providerSelection = "azure"
        assertEquals("azure", s.buildRecord().provider)
        assertEquals(
            "azure",
            state(
                SessionRecord(
                    sessionId = "sess_providertest0001",
                    cwd = "/project",
                    mode = "plan",
                    title = "t",
                    updatedAt = 0,
                    model = "test-model",
                    provider = "azure",
                )
            ).providerSelection,
        )
    }

    @Test
    fun `legacy records without a provider field restore as auto`() {
        assertEquals(
            "", state(
                SessionRecord(
                    sessionId = "sess_providertest0001",
                    cwd = "/project",
                    mode = "plan",
                    title = "t",
                    updatedAt = 0,
                    model = "test-model",
                )
            ).providerSelection
        )
    }

    @Test
    fun `legacy wire records without a provider field restore as auto`() {
        // The legacy wire shape (llmWireJson = snake_case, provider did not exist yet).
        val record = llmWireJson.decodeFromString<SessionRecord>(
            """
                {
                  "session_id": "sess_providertest0001",
                  "cwd": "/project",
                  "mode": "plan",
                  "title": "t",
                  "updated_at": 0,
                  "model": "test-model",
                  "history": []
                }
            """.trimIndent(),
        )
        assertEquals("", state(record).providerSelection)
    }
}
