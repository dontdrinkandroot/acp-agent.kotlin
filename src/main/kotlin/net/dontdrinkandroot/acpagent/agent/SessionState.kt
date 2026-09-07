package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.concurrent.Volatile

internal val MODE_BUILD = SessionModeId("build")
internal val MODE_PLAN = SessionModeId("plan")
internal val MODE_BASH = SessionModeId("bash")
internal val DEFAULT_MODE = MODE_PLAN
private const val MAX_TITLE_LENGTH = 72

/**
 * The single source of truth for a session's mutable state and its record
 * lifecycle: conversation history, plan, title, mode/model/reasoning
 * selection, permanent permissions and the persisted [SessionRecord]. The
 * [AgentSessionImpl] facade keeps the SDK-facing wiring; everything that
 * changes between turns lives here so it is testable in isolation.
 */
internal class SessionState(
    private val sessionId: SessionId,
    private val cwd: String,
    private val toolRegistry: ToolRegistry,
    config: Config,
    restored: SessionRecord?,
    private val sessionStore: SessionStore?,
    private val closeResources: () -> Unit,
) {

    private val logger = KotlinLogging.logger {}

    private val history = mutableListOf<OpenAIMessage>().apply { restored?.let { addAll(it.history) } }
    private val historyLock = Any()
    private var plan: List<PlanEntry> = restored?.plan ?: emptyList()

    /** Persistent allow/reject decisions, keyed by tool name. */
    val permanentPermissions = mutableMapOf<String, Boolean>()

    /**
     * Serializes the record lifecycle: persist (deleted check through save) and
     * delete (mark deleted through record removal) share this mutex, so a delete
     * cannot interleave with an in-flight persist and resurrect the record.
     */
    private val persistMutex = Mutex()

    @Volatile
    private var deleted = false

    private var title: String? = restored?.title?.takeIf { it.isNotEmpty() }

    /**
     * A restored session starts in its persisted mode, which is also reported
     * as the default mode of the restored session.
     */
    val initialMode: SessionModeId = restoredModeOrDefault(restored)

    var currentMode: SessionModeId = initialMode

    var currentModel: String = restored?.model?.takeIf { it.isNotBlank() } ?: config.openRouterModel

    /**
     * The selected reasoning effort ("" = not yet chosen; the effective
     * effort falls back to the model's default, "none" = reasoning off).
     */
    var reasoningSelection: String = restored?.reasoning?.takeIf { it.isNotBlank() } ?: ""

    val historySnapshot: List<OpenAIMessage> get() = synchronized(historyLock) { history.toList() }

    fun appendToHistory(message: OpenAIMessage) {
        synchronized(historyLock) { history.add(message) }
    }

    val planSnapshot: List<PlanEntry> get() = synchronized(historyLock) { plan }

    fun setPlan(entries: List<PlanEntry>) {
        synchronized(historyLock) { plan = entries }
    }

    /**
     * An atomic combination of history and plan for load replay: both reads
     * happen under the same lock so replay sees one consistent picture.
     */
    fun replaySnapshot(): Pair<List<OpenAIMessage>, List<PlanEntry>> =
        synchronized(historyLock) { history.toList() to plan }

    private fun restoredModeOrDefault(restored: SessionRecord?): SessionModeId {
        val restoredMode = restored?.mode?.let { SessionModeId(it) }
            ?.takeIf { candidate -> candidate == MODE_BUILD || candidate == MODE_PLAN || candidate == MODE_BASH }
        return restoredMode ?: DEFAULT_MODE
    }

    /**
     * The "mode status" message that states the current mode and the tools
     * available in it. It lives in the conversation history as an
     * [OpenAIMessage.System] entry and is LLM-internal (never rendered by the
     * client); the client UI gets its own mode notifications.
     */
    private fun modeStatusText(mode: SessionModeId): String {
        val names = toolRegistry.availableForMode(mode).joinToString(", ") { tool -> tool.name }
        return "You are now in ${mode.value} mode. Available tools: $names."
    }

    /**
     * Appends the mode status message for [mode] to the history. Same-value
     * mode re-sets (a no-op that should never render a message) are skipped by
     * the caller; this method only appends what it is told to.
     */
    fun appendModeStatusMessage(mode: SessionModeId) {
        appendToHistory(OpenAIMessage.System(Content.Text(modeStatusText(mode))))
    }

    /**
     * Sets the current mode and, when it actually changes, appends the mode
     * status message to the history (so the model always sees the mode at the
     * point it changed). Same-value re-sets are a no-op and render no message.
     */
    fun switchMode(mode: SessionModeId) {
        synchronized(historyLock) {
            if (mode == currentMode) return
            currentMode = mode
            appendModeStatusMessage(mode)
        }
    }

    init {
        // A fresh session starts with a status message stating its initial
        // (default) mode and the tools available in it, so the model always
        // knows the mode from the start of the conversation. Restored sessions
        // reuse their persisted trail as-is (a trail without mode status
        // messages, i.e. a session created before this feature, stays silent).
        if (restored == null) {
            appendModeStatusMessage(initialMode)
        }
    }

    fun buildRecord(): SessionRecord {
        val snapshot = historySnapshot
        val recordTitle = title
            ?: deriveTitle(snapshot)?.also { title = it }
            ?: ""
        return SessionRecord(
            sessionId = sessionId.value,
            cwd = cwd,
            mode = currentMode.value,
            title = recordTitle,
            updatedAt = System.currentTimeMillis(),
            history = snapshot,
            model = currentModel,
            reasoning = reasoningSelection,
            plan = planSnapshot,
        )
    }

    /**
     * Writes the session record to disk. Failures are logged but never
     * propagate, so persistence never blocks or fails session work.
     */
    suspend fun persist() {
        val store = sessionStore ?: return
        persistMutex.withLock {
            if (deleted) return
            runCatching { store.save(buildRecord()) }
                .onFailure { logger.warn(it) { "Failed to persist session ${sessionId.value}" } }
        }
    }

    /**
     * Flags the session as deleted and removes its record under the persist
     * mutex, so no in-flight persist can write the file back afterwards.
     */
    suspend fun delete() {
        persistMutex.withLock {
            deleted = true
            val store = sessionStore ?: return@withLock
            runCatching { store.delete(sessionId.value) }
                .onFailure { logger.warn(it) { "Failed to delete session record ${sessionId.value}" } }
        }
        closeResources()
    }

    fun close() {
        closeResources()
    }

    private fun deriveTitle(history: List<OpenAIMessage>): String? =
        history.filterIsInstance<OpenAIMessage.User>()
            .firstOrNull()
            ?.content
            ?.textOrNull()
            ?.trim()
            ?.let { trimmed -> truncatedTitle(trimmed.ifEmpty { "(empty message)" }) }

    private fun truncatedTitle(title: String): String =
        if (title.codePointCount(0, title.length) <= MAX_TITLE_LENGTH) title
        else title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH)) + "…"

    private fun Content?.textOrNull(): String? = this?.text()
}