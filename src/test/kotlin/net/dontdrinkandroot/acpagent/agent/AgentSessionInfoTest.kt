package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.config.Secret
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import java.nio.file.Files
import kotlin.test.*

/**
 * The `/info` slash command at the session level: answered from the session's
 * own state, never through the LLM, never persisted.
 */
class AgentSessionInfoTest {

    private val key = "sk-super-secret-value"

    /**
     * The LLM points at a dead port on purpose: a command that called it would
     * fail this test instead of silently spending tokens.
     */
    private fun session(store: SessionStore? = null) = AgentSessionImpl(
        sessionId = SessionId("sess_0000000000000001"),
        cwd = "/project",
        toolRegistry = ToolRegistry(),
        config = Config(Secret(key), "test-model", "http://127.0.0.1:1"),
        llm = LlmClient(key, "http://127.0.0.1:1"),
        todayProvider = { "2026-09-04" },
        sessionStore = store,
    )

    /** Collects the reply of one command invocation and pins the turn shape around it. */
    private suspend fun infoText(session: AgentSessionImpl, prompt: String = "/info"): String {
        val events = session.prompt(listOf(ContentBlock.Text(prompt))).toList()
        val chunks = events.filterIsInstance<Event.SessionUpdateEvent>()
            .map { it.update }
            .filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(1, chunks.size, "the dump must arrive as exactly one client message")
        assertEquals(
            StopReason.END_TURN,
            events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
        )
        return (chunks.single().content as ContentBlock.Text).text
    }

    @Test
    fun `the dump names this session's live values`() = runBlocking {
        val text = infoText(session())
        listOf(
            "- Id: sess_0000000000000001",
            "- Cwd: `/project`",
            "- Mode: plan",
            "- Session model: test-model",
            "- Base URL: http://127.0.0.1:1",
            "- Reasoning: not supported by this model",
            "- Provider: auto",
            "- Scratch: `/tmp/ddr-acp-agent`",
        ).forEach { expected -> assertContains(text, expected) }
    }

    @Test
    fun `the dump reports the governing mode, not the default one`() = runBlocking {
        val session = session()
        session.setMode(SessionModeId("build"), null)
        assertContains(infoText(session), "- Mode: build (Read, write, move and delete files")
    }

    @Test
    fun `the dump reports the prepared scratch directory the session actually trusts`() = runBlocking {
        val session = AgentSessionImpl(
            sessionId = SessionId("sess_0000000000000001"),
            cwd = "/project",
            toolRegistry = ToolRegistry(),
            config = Config(Secret(key), "test-model", "http://127.0.0.1:1", scratchDir = "/configured/scratch"),
            scratchDir = "/resolved/scratch",
            llm = LlmClient(key, "http://127.0.0.1:1"),
            todayProvider = { "2026-09-04" },
        )
        assertContains(infoText(session), "- Scratch: `/resolved/scratch`")
    }

    @Test
    fun `the dump never contains the API key`() = runBlocking {
        assertFalse(infoText(session()).contains(key), "the key value must never reach the client")
    }

    @Test
    fun `the info command leaves no session record behind`() = runBlocking {
        val dir = Files.createTempDirectory("acp-agent-info-test")
        val store = SessionStore(dir)
        infoText(session(store))
        assertTrue(store.list().isEmpty(), "a diagnostic turn must not become conversation state")
    }

    @Test
    fun `the info command reports discarded text`() = runBlocking {
        val text = infoText(session(), "/info now check the logs")
        assertTrue(
            text.startsWith("**Note:** `/info` ignores additional text; discarded: \"now check the logs\""),
            "the user must see that their extra text was ignored, got: $text",
        )
    }

    @Test
    fun `the info command refuses on a closed session`() = runBlocking {
        val session = session()
        session.close(null)
        val error = assertFailsWith<JsonRpcException> {
            session.prompt(listOf(ContentBlock.Text("/info"))).collect { }
        }
        assertEquals(JsonRpcErrorCode.RESOURCE_NOT_FOUND.code, error.code, "a closed session must answer -32002")
    }
}
