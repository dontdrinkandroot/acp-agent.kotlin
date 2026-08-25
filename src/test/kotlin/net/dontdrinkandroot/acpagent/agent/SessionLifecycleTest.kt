package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import java.nio.file.Files
import kotlin.test.*

class SessionLifecycleTest {

    private val storeDir = Files.createTempDirectory("acp-agent-lifecycle-test")
    private val store = SessionStore(storeDir)

    private fun session(sessionId: String, cwd: String, restored: SessionRecord? = null, replay: Boolean = false) =
        AgentSessionImpl(
            sessionId = SessionId(sessionId),
            cwd = cwd,
            toolRegistry = ToolRegistry(),
            config = Config("k", "m", "http://127.0.0.1:1"),
            llm = LlmClient("k", "http://127.0.0.1:1", "m"),
            todayProvider = { "2026-09-04" },
            sessionStore = store,
            restored = restored,
            replayOnInitialize = replay,
        )

    private fun record(
        sessionId: String = "sess_0000000000000001",
        cwd: String = "/project",
        mode: String = "plan",
        title: String = "Fix the bug",
    ) = SessionRecord(sessionId = sessionId, cwd = cwd, mode = mode, title = title, updatedAt = 1_700_000_000_000)

    private fun support(): Pair<AgentSupportImpl, DelegatingSessionFactory> {
        val factory = DelegatingSessionFactory { sessionId, cwd, restored, replay ->
            session(sessionId, cwd, restored, replay)
        }
        return AgentSupportImpl(factory, store) to factory
    }

    @Test
    fun `initialize advertises loadSession, list, resume and delete capabilities`() {
        runBlocking {
            val (support, _) = support()
            val info = support.initialize(
                ClientInfo(
                    protocolVersion = 1,
                    implementation = Implementation(name = "test", version = "0"),
                )
            )
            assertTrue(info.capabilities.loadSession)
            val capabilities = info.capabilities.sessionCapabilities
            assertNotNull(capabilities.list)
            assertNotNull(capabilities.resume)
            assertNotNull(capabilities.delete)
        }
    }

    @Test
    fun `mode changes persist the session record`() = runBlocking {
        val (support, _) = support()
        val created = support.createSession(SessionCreationParameters(cwd = "/project", mcpServers = emptyList()))
        created.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"), null)

        val record = store.load(created.sessionId.value)
        assertNotNull(record)
        assertEquals("/project", record.cwd)
        assertEquals("build", record.mode)
        assertEquals("m", record.model)
    }

    @Test
    fun `restored mode and title are kept in later persists`() = runBlocking {
        val (support, _) = support()
        store.save(record())
        val restored = support.loadSession(
            SessionId("sess_0000000000000001"),
            SessionCreationParameters(cwd = "/project", mcpServers = emptyList()),
        )
        val option = restored.configOptions.single() as SessionConfigOption.Select
        assertEquals("plan", option.currentValue.value)

        restored.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bash"), null)
        val record = store.load("sess_0000000000000001")
        assertNotNull(record)
        assertEquals("bash", record.mode)
        assertEquals("Fix the bug", record.title)
        assertEquals("/project", record.cwd)
    }

    @Test
    fun `restored mode falls back to plan for a corrupt record`() {
        val s = session("sess_0000000000000001", "/project", restored = record(mode = "nonsense"))
        val option = s.configOptions.single() as SessionConfigOption.Select
        assertEquals("plan", option.currentValue.value)
    }

    @Test
    fun `delete removes the record, closes resources and suppresses later persists`() = runBlocking {
        val (support, _) = support()
        val created = support.createSession(SessionCreationParameters(cwd = "/project", mcpServers = emptyList()))
        created.setMode(SessionModeId("build"), null)
        assertNotNull(store.load(created.sessionId.value))

        support.deleteSession(SessionId(created.sessionId.value), null)
        assertEquals(null, store.load(created.sessionId.value))

        created.setMode(SessionModeId("plan"), null)
        assertEquals(null, store.load(created.sessionId.value))
        assertTrue(support.listSessions(null, null, null).toList().isEmpty())
    }

    @Test
    fun `loadSession rejects invalid, unknown and mismatched requests`() {
        runBlocking {
            val (support, _) = support()
            store.save(record())

            fun error(block: suspend () -> Unit) {
                val e = assertFailsWith<JsonRpcException> { runBlocking { block() } }
                assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
            }

            error {
                support.loadSession(
                    SessionId("sess_not-a-valid-id"),
                    SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
                )
            }
            error {
                support.loadSession(
                    SessionId("sess_9999999999999999"),
                    SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
                )
            }
            error {
                support.loadSession(
                    SessionId("sess_0000000000000001"),
                    SessionCreationParameters(cwd = "/elsewhere", mcpServers = emptyList())
                )
            }

            support.loadSession(
                SessionId("sess_0000000000000001"),
                SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
            )
            error {
                support.loadSession(
                    SessionId("sess_0000000000000001"),
                    SessionCreationParameters(cwd = "/project", mcpServers = emptyList())
                )
            }
        }
    }

    @Test
    fun `resumeSession restores without replay and rejects the same cases`() = runBlocking {
        val (support, factory) = support()
        store.save(record())

        val resumed = support.resumeSession(
            SessionId("sess_0000000000000001"),
            SessionCreationParameters(cwd = "/project", mcpServers = emptyList()),
        )
        val recorded = factory.lastRestore
        assertNotNull(recorded)
        assertEquals(false, recorded.second)
        assertEquals(SessionId("sess_0000000000000001"), resumed.sessionId)

        val e = assertFailsWith<JsonRpcException> {
            runBlocking {
                support.resumeSession(
                    SessionId("sess_0000000000000001"),
                    SessionCreationParameters(cwd = "/project", mcpServers = emptyList()),
                )
            }
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `listSessions filters by cwd and sorts most recent first`() = runBlocking {
        store.save(record("sess_0000000000000001", cwd = "/project-a", title = "Old").copy(updatedAt = 1_000))
        store.save(record("sess_0000000000000002", cwd = "/project-a", title = "New").copy(updatedAt = 2_000))
        store.save(record("sess_0000000000000003", cwd = "/project-b", title = "Other").copy(updatedAt = 3_000))

        val (support, _) = support()

        val all = support.listSessions(null, null, null).toList()
        assertEquals(
            listOf("sess_0000000000000003", "sess_0000000000000002", "sess_0000000000000001"),
            all.map { it.sessionId.value })

        val filtered = support.listSessions("/project-a", null, null).toList()
        assertEquals(listOf("sess_0000000000000002", "sess_0000000000000001"), filtered.map { it.sessionId.value })
        assertEquals("/project-a", filtered.first().cwd)
        assertEquals("New", filtered.first().title)
        assertNotNull(filtered.first().updatedAt)

        assertTrue(support.listSessions("/elsewhere", null, null).toList().isEmpty())
    }

    @Test
    fun `listSessions tolerates a missing state directory`() = runBlocking {
        val emptyStore = SessionStore(Files.createTempDirectory("acp-agent-empty").resolve("does-not-exist"))
        val support = AgentSupportImpl(
            DelegatingSessionFactory { sessionId, cwd, restored, replay -> session(sessionId, cwd, restored, replay) },
            emptyStore,
        )
        assertTrue(support.listSessions(null, null, null).toList().isEmpty())
    }
}

private class DelegatingSessionFactory(
    private val newSession: (sessionId: String, cwd: String, restored: SessionRecord?, replay: Boolean) -> AgentSessionImpl,
) : AgentSessionFactory {

    var lastRestore: Pair<SessionRecord, Boolean>? = null
    var lastCreated: AgentSessionImpl? = null

    override suspend fun create(parameters: SessionCreationParameters): AgentSessionImpl {
        val session = newSession(randomSessionId(), parameters.cwd, null, false)
        lastCreated = session
        return session
    }

    override suspend fun restore(
        record: SessionRecord,
        parameters: SessionCreationParameters,
        replay: Boolean
    ): AgentSessionImpl {
        lastRestore = record to replay
        return newSession(record.sessionId, record.cwd, record, replay)
    }
}
