package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.acpFail
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Creates fresh sessions and restores persisted ones. Implemented in `Main.kt`
 * on top of the shared MCP/LlmClient wiring.
 */
internal interface AgentSessionFactory {
    public suspend fun create(parameters: SessionCreationParameters): AgentSessionImpl

    public suspend fun restore(
        record: SessionRecord,
        parameters: SessionCreationParameters,
        replay: Boolean
    ): AgentSessionImpl
}

internal class AgentSupportImpl(
    private val sessionFactory: AgentSessionFactory,
    private val sessionStore: SessionStore,
) : AgentSupport {

    private val sessions = ConcurrentHashMap<SessionId, AgentSessionImpl>()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        return AgentInfo(
            capabilities = AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(
                    list = SessionListCapabilities(),
                    resume = SessionResumeCapabilities(),
                    delete = SessionDeleteCapabilities()
                ),
                promptCapabilities = PromptCapabilities(
                    audio = false,
                    image = true,
                    embeddedContext = true,
                ),
                mcpCapabilities = McpCapabilities(http = true, sse = true),
            ),
            implementation = Implementation(
                name = "acp-agent.kotlin",
                version = "0.1.0",
                title = "ACP Agent (Kotlin)"
            )
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        return sessionFactory.create(sessionParameters).also { sessions[it.sessionId] = it }
    }

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        return restore(sessionId, sessionParameters, replay = true).also { sessions[it.sessionId] = it }
    }

    @OptIn(UnstableApi::class)
    override suspend fun resumeSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession {
        return restore(sessionId, sessionParameters, replay = false).also { sessions[it.sessionId] = it }
    }

    private suspend fun restore(
        sessionId: SessionId,
        parameters: SessionCreationParameters,
        replay: Boolean
    ): AgentSessionImpl {
        val id = sessionId.value
        if (!isValidSessionId(id)) {
            jsonRpcInvalidParams("invalid sessionId \"$id\"")
        }
        val record = runCatching { sessionStore.load(id) }
            .getOrElse { acpFail("load session \"$id\": ${it.message}") }
            ?: jsonRpcInvalidParams("unknown session \"$id\"")
        if (record.cwd != parameters.cwd) {
            jsonRpcInvalidParams("session \"$id\" belongs to \"${record.cwd}\", not \"${parameters.cwd}\"")
        }
        if (sessions.containsKey(sessionId)) {
            jsonRpcInvalidParams("session \"$id\" is already active")
        }
        return sessionFactory.restore(record, parameters, replay)
    }

    @OptIn(UnstableApi::class)
    override suspend fun listSessions(
        cwd: String?,
        additionalDirectories: List<String>?,
        _meta: JsonElement?
    ): Sequence<SessionInfo> {
        return sessionStore.list()
            .asSequence()
            .filter { record -> cwd.isNullOrEmpty() || record.cwd == cwd }
            .sortedByDescending { it.updatedAt }
            .map { record ->
                SessionInfo(
                    sessionId = SessionId(record.sessionId),
                    cwd = record.cwd,
                    title = record.title.takeIf { it.isNotEmpty() },
                    updatedAt = Instant.ofEpochMilli(record.updatedAt).toString(),
                )
            }
    }

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        val id = sessionId.value
        if (!isValidSessionId(id)) {
            jsonRpcInvalidParams("invalid sessionId \"$id\"")
        }
        sessions.remove(sessionId)?.delete()
            ?: sessionStore.delete(id)
        return DeleteSessionResponse()
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xFF
        append(HEX_CHARS[v ushr 4])
        append(HEX_CHARS[v and 0x0F])
    }
}

public fun randomSessionId(): String = "sess_${Random.nextBytes(8).toHex()}"
