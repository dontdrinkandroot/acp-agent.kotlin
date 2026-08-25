package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSupportImplTest {

    @Test
    fun `randomSessionId has expected shape`() {
        repeat(20) {
            val id = randomSessionId()
            assertTrue(isValidSessionId(id), id)
        }
    }

    @Test
    fun `initialize advertises prompt and mcp capabilities`() = runBlocking {
        val storeDir = Files.createTempDirectory("acp-agent-support-test")
        val session = AgentSessionImpl(
            sessionId = SessionId("sess_0000000000000001"),
            cwd = "/tmp",
            toolRegistry = ToolRegistry(),
            config = Config("k", "m", "http://127.0.0.1:1"),
            llm = LlmClient("k", "http://127.0.0.1:1", "m"),
            todayProvider = { "2026-09-04" },
        )
        val support = AgentSupportImpl(StaticSessionFactory(session), SessionStore(storeDir))
        val agentInfo = support.initialize(
            ClientInfo(protocolVersion = 1, implementation = Implementation(name = "test", version = "0.0.1"))
        )
        val capabilities = agentInfo.capabilities
        assertTrue(capabilities.loadSession, "loadSession must be advertised")
        assertTrue(capabilities.promptCapabilities.image, "image prompt capability must be advertised")
        assertTrue(capabilities.promptCapabilities.embeddedContext, "embeddedContext must be advertised")
        assertFalse(capabilities.promptCapabilities.audio, "audio must not be advertised")
        assertTrue(capabilities.mcpCapabilities.http, "streamable http MCP capability must be advertised")
        assertTrue(capabilities.mcpCapabilities.sse, "classic sse MCP capability must be advertised")
    }

    @Test
    fun `deleteSession closes session resources and is idempotent`() = runBlocking {
        val storeDir = Files.createTempDirectory("acp-agent-support-test")
        var closed = false
        val session = AgentSessionImpl(
            sessionId = SessionId("sess_0000000000000001"),
            cwd = "/tmp",
            toolRegistry = ToolRegistry(),
            config = Config("k", "m", "http://127.0.0.1:1"),
            llm = LlmClient("k", "http://127.0.0.1:1", "m"),
            todayProvider = { "2026-09-04" },
            closeResources = { closed = true },
            sessionStore = SessionStore(storeDir),
        )
        val support = AgentSupportImpl(StaticSessionFactory(session), SessionStore(storeDir))
        support.createSession(SessionCreationParameters(cwd = "/tmp", mcpServers = emptyList()))

        support.deleteSession(SessionId("sess_0000000000000001"), null)
        assertTrue(closed, "expected session resources to be closed")

        val repeatDelete = support.deleteSession(SessionId("sess_0000000000000001"), null)
        val unknownDelete = support.deleteSession(SessionId("sess_0000000000000002"), null)
        assertEquals(repeatDelete, unknownDelete)
    }
}

private class StaticSessionFactory(private val session: AgentSessionImpl) : AgentSessionFactory {
    override suspend fun create(parameters: SessionCreationParameters): AgentSessionImpl = session

    override suspend fun restore(
        record: SessionRecord,
        parameters: SessionCreationParameters,
        replay: Boolean
    ): AgentSessionImpl = session
}
