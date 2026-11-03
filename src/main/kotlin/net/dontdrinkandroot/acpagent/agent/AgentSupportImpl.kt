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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.text.HexFormat
import kotlin.text.toHexString

/**
 * The `_meta` marker [AgentSupportImpl.listSessions] attaches to sessions that
 * are open in this process. [SessionInfo] has no liveness field and the spec
 * forbids adding one, so the marker rides the `_meta` extension point; its
 * absence means "not open here" (issue #27). Reverse-DNS key per the ACP
 * extensibility conventions.
 */
internal const val ACTIVE_SESSION_META_KEY = "net.dontdrinkandroot/active"

internal val ACTIVE_SESSION_META: JsonObject = JsonObject(mapOf(ACTIVE_SESSION_META_KEY to JsonPrimitive(true)))

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
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : AgentSupport {

    /**
     * The sessions open in this process. An entry is dropped when its session
     * ends - closed, deleted, or a failed load replay - so it never stays
     * "already active" (issue #27). Also the liveness source for [listSessions].
     */
    private val sessions = ConcurrentHashMap<SessionId, AgentSessionImpl>()

    @OptIn(UnstableApi::class)
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        return AgentInfo(
            capabilities = AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(
                    list = SessionListCapabilities(),
                    resume = SessionResumeCapabilities(),
                    delete = SessionDeleteCapabilities(),
                    close = SessionCloseCapabilities(),
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
        return register(sessionFactory.create(sessionParameters))
    }

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        return register(restore(sessionId, sessionParameters, replay = true))
    }

    @OptIn(UnstableApi::class)
    override suspend fun resumeSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession {
        return register(restore(sessionId, sessionParameters, replay = false))
    }

    /**
     * Tracks [session] as open until it releases itself ([AgentSessionImpl]):
     * closing, deleting or failing its load replay removes the entry, so the id
     * becomes loadable again instead of reporting "already active" (issue #27).
     * The removal is value-conditional, so a callback from an earlier instance
     * of the same id cannot drop the current one.
     */
    private fun register(session: AgentSessionImpl): AgentSessionImpl {
        val sessionId = session.sessionId
        sessions[sessionId] = session
        session.onSessionReleased { sessions.remove(sessionId, session) }
        return session
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
        // Snapshot the live ids once: a session opened while the client pages
        // through the list must not flip its marker mid-listing.
        val openSessions = sessions.keys.toSet()
        return sessionStore.list()
            .asSequence()
            .filter { record -> cwd.isNullOrEmpty() || record.cwd == cwd }
            .sortedByDescending { it.updatedAt }
            .map { record ->
                val sessionId = SessionId(record.sessionId)
                SessionInfo(
                    sessionId = sessionId,
                    cwd = record.cwd,
                    title = record.title.takeIf { it.isNotEmpty() },
                    updatedAt = formatSessionUpdatedAt(record.updatedAt, zoneId),
                    // SessionInfo has no liveness field and the spec forbids
                    // adding one, so the marker travels in _meta (absent = not
                    // open in this process, issue #27).
                    _meta = if (sessionId in openSessions) ACTIVE_SESSION_META else null,
                )
            }
    }

    /**
     * Flushes every session open in this process to its record. Called once by
     * `runAgent` before the protocol closes, so a turn the shutdown cancelled
     * cannot be lost to a coroutine that never got to its own persist (issue
     * #17). Persist failures are logged by [SessionState] and never propagate.
     */
    suspend fun persistAllSessions() {
        sessions.values.forEach { session -> session.persistState() }
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

private val SESSION_UPDATED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT)

/**
 * Renders a session record's epoch millis as an RFC 3339 timestamp with a numeric
 * offset (`2026-09-23T15:37:02.123+02:00`), never a `Z` suffix: clients render
 * `SessionInfo.updatedAt` as wall time, so a UTC stamp shifts it by the local
 * offset (issue #22). Seconds and milliseconds are always present - `toString()`
 * on the java.time types drops them when zero, which is not a valid RFC 3339 time.
 */
internal fun formatSessionUpdatedAt(epochMillis: Long, zoneId: ZoneId): String =
    SESSION_UPDATED_AT_FORMAT.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

/**
 * A fresh session id: `sess_` + 16 lowercase hex digits, exactly the id set
 * [SessionStore.isValidSessionId] accepts.
 */
public fun randomSessionId(): String = "sess_" + Random.nextBytes(8).toHexString(HexFormat.Default)
