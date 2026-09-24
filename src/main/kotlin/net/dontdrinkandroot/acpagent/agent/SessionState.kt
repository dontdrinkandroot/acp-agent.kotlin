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
import net.dontdrinkandroot.acpagent.tools.ALL_MODES
import net.dontdrinkandroot.acpagent.tools.MODE_PLAN
import net.dontdrinkandroot.acpagent.tools.MODE_BUILD
import net.dontdrinkandroot.acpagent.tools.MODE_BASH
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.concurrent.Volatile

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

    /**
     * The mode the client requested while a prompt turn is in flight. It does
     * not govern anything during the turn (the turn keeps the mode it started
     * with, so tool gating stays consistent); the latest request wins and it
     * is applied when the agent turns control back to the user.
     */
    @Volatile
    private var pendingMode: SessionModeId? = null

    /**
     * True while a prompt turn is running; mode flushes are deferred until the
     * turn ends, so a switch never applies mid-iteration.
     */
    @Volatile
    private var promptActive = false

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
            ?.takeIf { candidate -> candidate in ALL_MODES }
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
        return "Mode: ${mode.value}. ${modeDescription(mode)} Available tools: $names."
    }

    private fun modeDescription(mode: SessionModeId): String = when (mode) {
        MODE_PLAN -> "Read-only: research, analyze and plan; do not modify files."
        MODE_BUILD -> "Read-write: read, write, edit, move and delete files to implement the task."
        MODE_BASH -> "Build plus a permission-gated shell; every command is confirmed by the user first."
        else -> ""
    }

    /**
     * The same text the modal status messages carry: the current mode, its
     * semantics and the tools available in it. Surfaced to the model via the
     * `get_current_mode` tool so it can verify the governing mode directly
     * (e.g. in a restored session whose history trail lacks status messages)
     * instead of inferring it from tool availability.
     */
    fun modeStatusTextForCurrent(): String = modeStatusText(currentMode)

    /**
     * Applies the mode status message for [mode] to the history.
     */
    private fun appendModeStatusMessage(mode: SessionModeId) {
        appendToHistory(OpenAIMessage.System(Content.Text(modeStatusText(mode))))
    }

    /**
     * The mode reported by the "mode" config option: the currently governing
     * mode, never a still-pending one (the client is not told about a pending
     * switch, so it is never ahead of the loop's tool gating).
     */
    fun modeConfigValue(): SessionModeId = currentMode

    /**
     * Starts/ends a prompt turn. While active, [requestMode] defers the switch
     * until [flushPendingMode] (turn end) or drops it on [cancelPrompt].
     */
    fun setPromptActive(active: Boolean) {
        promptActive = active
    }

    /**
     * True while a mode request is deferred (a turn is running and the switch
     * has not been flushed yet).
     */
    fun hasPendingMode(): Boolean = pendingMode != null

    /**
     * Records a mode request. While a prompt turn is running the request only
     * updates the pending mode (latest wins) and applies nothing; idle, it
     * applies immediately (no turn to keep consistent). Same-value requests
     * are no-ops in both cases.
     */
    fun requestMode(mode: SessionModeId) {
        synchronized(historyLock) {
            if (mode == currentMode) {
                pendingMode = null
                return
            }
            if (promptActive) {
                pendingMode = mode
            } else {
                currentMode = mode
                appendModeStatusMessage(mode)
            }
        }
    }

    /**
     * Applies the pending mode (if any) exactly once: flips the current mode,
     * appends its status message and clears the pending. Returns true when a
     * mode was actually applied (so the caller knows to announce+persist it).
     * No-op and false when nothing is pending.
     */
    fun flushPendingMode(): Boolean {
        synchronized(historyLock) {
            val pending = pendingMode ?: return false
            pendingMode = null
            if (pending == currentMode) return false
            currentMode = pending
            appendModeStatusMessage(pending)
            return true
        }
    }

    /**
     * Drops a pending mode without applying it. Called when a turn is
     * cancelled: the client was never told about the pending switch, so no
     * update needs to be undone.
     */
    fun cancelPrompt() {
        synchronized(historyLock) {
            pendingMode = null
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