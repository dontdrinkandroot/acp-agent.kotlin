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
import net.dontdrinkandroot.acpagent.tools.*
import kotlin.concurrent.Volatile

internal val DEFAULT_MODE = MODE_PLAN
private const val MAX_TITLE_LENGTH = 72
internal const val TOOL_OUTCOME_COMPLETED = "completed"
internal const val TOOL_OUTCOME_FAILED = "failed"

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

    /**
     * Live terminal tool-call outcomes (toolCallId -> "completed" | "failed"),
     * recorded when the tool result is appended to the history and persisted
     * with the record so load replay renders the real outcome. Lenient at
     * replay time: unknown values fall back to COMPLETED (legacy records carry
     * no outcomes at all and replay everything COMPLETED).
     */
    private val toolOutcomes = mutableMapOf<String, String>().apply { restored?.let { putAll(it.toolOutcomes) } }

    /** Persistent allow/reject decisions, keyed by tool name. */
    val permanentPermissions = mutableMapOf<String, Boolean>()

    /**
     * Dangling tool-call ids already warned about: the repaired view warns
     * once per id, not once per snapshot read (a cancelled call stays in the
     * stored history until the next persist, so snapshots repeat it).
     */
    private val warnedDanglingIds = mutableSetOf<String>()

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
     * The mode whose [modeStatusText] is currently in the conversation
     * history. Restored sessions parse it from the trail's last `Mode:`
     * message (nothing new is persisted; the record's [SessionRecord.mode]
     * stays the source of truth for the governing mode), so a switch that was
     * applied after the last message - e.g. a turn-end flush persisted but a
     * crash before the next prompt - self-heals: the next prompt start writes
     * the corrective message.
     */
    private var lastIndicatedMode: SessionModeId? = lastIndicatedTrailMode(restored?.history.orEmpty())

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

    var currentModel: String = initialModelId(restored, config)

    /**
     * The selected reasoning effort ("" = not yet chosen; the effective
     * effort falls back to the model's default, "none" = reasoning off).
     */
    var reasoningSelection: String = restored?.reasoning?.takeIf { it.isNotBlank() } ?: ""

    /**
     * The selected OpenRouter provider slug ("" = auto; the manual pick sends
     * `provider.order` on chat requests). Persisted alongside model/reasoning.
     */
    var providerSelection: String = restored?.provider?.takeIf { it.isNotBlank() } ?: ""

    val historySnapshot: List<OpenAIMessage>
        get() = synchronized(historyLock) { repairedViewLocked().history }

    fun appendToHistory(message: OpenAIMessage) {
        synchronized(historyLock) { history.add(message) }
    }

    /**
     * Appends a tool result to the history and records its terminal outcome
     * under the same lock, so a replay snapshot can never pair a result
     * message with a missing outcome.
     */
    fun appendToolResult(toolCallId: String, content: Content.Text, isError: Boolean) {
        synchronized(historyLock) {
            history.add(OpenAIMessage.Tool(content, toolCallId = toolCallId))
            toolOutcomes[toolCallId] = if (isError) TOOL_OUTCOME_FAILED else TOOL_OUTCOME_COMPLETED
        }
    }

    val planSnapshot: List<PlanEntry> get() = synchronized(historyLock) { plan }

    fun setPlan(entries: List<PlanEntry>) {
        synchronized(historyLock) { plan = entries }
    }

    /**
     * An atomic combination of history, plan and tool outcomes for load
     * replay: all reads happen under the same lock so replay sees one
     * consistent picture.
     */
    fun replaySnapshot(): Triple<List<OpenAIMessage>, List<PlanEntry>, Map<String, String>> =
        synchronized(historyLock) {
            val repaired = repairedViewLocked()
            Triple(repaired.history, plan, repaired.outcomes)
        }

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
     * flips the current mode immediately (the client is notified by the
     * caller). Same-value requests are no-ops in both cases. Neither branch
     * touches the history - the `Mode:` message is written once, at prompt
     * start, by [indicateCurrentMode] (issue #36).
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
            }
        }
    }

    /**
     * Applies the pending mode (if any) exactly once: flips the current mode
     * and clears the pending. Returns true when a mode was actually applied
     * (so the caller knows to announce+persist it). The mode status message
     * is NOT written here - it waits for the next prompt start
     * ([indicateCurrentMode]), like every other switch (issue #36). No-op and
     * false when nothing is pending.
     */
    fun flushPendingMode(): Boolean {
        synchronized(historyLock) {
            val pending = pendingMode ?: return false
            pendingMode = null
            if (pending == currentMode) return false
            currentMode = pending
            return true
        }
    }

    /**
     * The single writer of mode status messages: appends the `Mode:` message
     * for the current mode iff it changed since the last indicated one (the
     * trail's last message, parsed from the record at restore). Called at
     * prompt start before the user message is appended, so every `Mode:`
     * message in the trail is immediately followed by a turn that ran under
     * it and no trail ever contains a mode that governed nothing (issue #36).
     */
    fun indicateCurrentMode() {
        synchronized(historyLock) {
            if (currentMode == lastIndicatedMode) return
            appendModeStatusMessage(currentMode)
            lastIndicatedMode = currentMode
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

    /**
     * Parses the mode whose status message was appended last from a
     * persisted trail (`Mode: <id>.` prefix, per [modeStatusText]); null when
     * the trail carries no mode message (a fresh session, or a legacy record
     * from before the feature). The record's [SessionRecord.mode] stays the
     * source of truth for the governing mode - this only seeds
     * [lastIndicatedMode] so the next prompt start does not append a
     * duplicate message.
     */
    private fun lastIndicatedTrailMode(history: List<OpenAIMessage>): SessionModeId? =
        history.filterIsInstance<OpenAIMessage.System>()
            .mapNotNull { (it.content as? Content.Text)?.text() }
            .lastOrNull { it.startsWith(MODE_TRAIL_PREFIX) }
            ?.let { text ->
                text.removePrefix(MODE_TRAIL_PREFIX).substringBefore('.').let { raw ->
                    SessionModeId(raw).takeIf { candidate -> candidate in ALL_MODES }
                }
            }

    private companion object {
        const val MODE_TRAIL_PREFIX = "Mode: "
    }

    fun buildRecord(): SessionRecord {
        val snapshot: List<OpenAIMessage>
        val outcomes: Map<String, String>
        synchronized(historyLock) {
            val repaired = repairedViewLocked()
            snapshot = repaired.history
            outcomes = repaired.outcomes
        }
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
            provider = providerSelection,
            plan = planSnapshot,
            toolOutcomes = outcomes,
        )
    }

    /**
     * One repaired view of the current history: dangling tool calls (a turn
     * that died between appending a call and its result - cancelled turns,
     * issue #38) are closed by the synthetic failed result and mapped to a
     * FAILED outcome, so no consumer - LLM request, replay or record - ever
     * sees the dangling state. Pure: the stored history is never mutated by
     * the repair. Must hold [historyLock].
     */
    private fun repairedViewLocked(): RepairedView {
        val repair = repairToolCallPairing(history)
        repair.danglingIds.forEach { callId ->
            if (warnedDanglingIds.add(callId)) {
                logger.warn {
                    "Session ${sessionId.value}: dangling tool call '$callId' closed with a " +
                            "synthetic interrupted result (issue #38)"
                }
            }
        }
        val outcomes = repair.danglingIds.fold(toolOutcomes.toMap()) { acc, callId ->
            acc + (callId to TOOL_OUTCOME_FAILED)
        }
        return RepairedView(repair.history, outcomes)
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
}

/** The session's initial model: the persisted pick or the configured OpenRouter default. */
internal fun initialModelId(restored: SessionRecord?, config: Config): String =
    restored?.model?.takeIf { it.isNotBlank() } ?: config.openRouterModel
