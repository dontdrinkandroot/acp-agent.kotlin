package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.*
import java.util.*
import kotlin.concurrent.Volatile

internal val DEFAULT_MODE = MODE_PLAN
private const val MAX_TITLE_LENGTH = 72
internal const val TOOL_OUTCOME_COMPLETED = "completed"
internal const val TOOL_OUTCOME_FAILED = "failed"

/**
 * The single source of truth for a session's mutable state and its record
 * lifecycle: conversation history, plan, title, model/reasoning selection,
 * the permission grants and the persisted [SessionRecord]. Mode selection and
 * its deferred switching live in the composed [ModeState] (issue #83); the
 * `Mode:` trail message and its status text stay here, since they are history
 * and tool-registry concerns. The [AgentSessionImpl] facade keeps the
 * SDK-facing wiring; everything that changes between turns lives here so it is
 * testable in isolation.
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

    private val history = mutableListOf<HistoryItem>().apply {
        restored?.history?.forEach { add(historyItemFromWire(it)) }
    }
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

    /**
     * Allow/reject decisions from the `allow_always`/`reject_always` permission
     * options, keyed by tool name (the keying is the over-grant of issue #3).
     * Session-memory only on purpose: unlike mode/model/reasoning this map is
     * not part of [SessionRecord] and evaporates on restart - pinned by
     * `ToolCallExecutorTest` so issue #3's redesign must change that pin
     * deliberately, not accidentally. Mutated only through [grantAlways] /
     * [rejectAlways] (issue #83): the exposed map is read-only so the
     * decision logic stays in one place.
     */
    private val permissionGrants = mutableMapOf<String, Boolean>()

    /** Read-only view of [permissionGrants] for diagnostics and the pins. */
    val permanentPermissions: Map<String, Boolean> = Collections.unmodifiableMap(permissionGrants)

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
     * The title the client already knows. A restored record's title counts as
     * announced - `session/load`, `session/resume` and `session/list` carry it -
     * so only a title that is new to the client is sent as a
     * `session_info_update` (issue #73).
     */
    private var announcedTitle: String? = title

    /**
     * The session's mode selection and deferred-switch state (issue #83). The
     * trail message and the status text stay here, because both are history
     * and tool-registry concerns; the machine only decides.
     */
    val mode = ModeState(
        initialMode = restoredModeOrDefault(restored),
        lastIndicatedMode = lastIndicatedTrailMode(restored?.history.orEmpty()),
    )

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

    val historySnapshot: List<HistoryItem>
        get() = synchronized(historyLock) { repairedViewLocked().history }

    /**
     * The wire form of the (repaired) history - the request input, 1:1.
     * Captured items are replayed verbatim, so a reasoning item produced by
     * the previous iteration reaches the next one unchanged (issue #75).
     */
    val historyWireSnapshot: List<JsonObject>
        get() = historySnapshot.map { it.toWire() }

    fun appendToHistory(item: HistoryItem) {
        synchronized(historyLock) { history.add(item) }
    }

    /**
     * Appends a tool result to the history and records its terminal outcome
     * under the same lock, so a replay snapshot can never pair a result
     * item with a missing outcome.
     */
    fun appendToolResult(toolCallId: String, content: String, isError: Boolean) {
        synchronized(historyLock) {
            history.add(HistoryItem.ToolOutput(callId = toolCallId, output = content))
            toolOutcomes[toolCallId] = if (isError) TOOL_OUTCOME_FAILED else TOOL_OUTCOME_COMPLETED
        }
    }

    /**
     * Drops the turn's live-only reasoning items from the history. Called when
     * a prompt turn ends (issue #75): reasoning continuity is a within-turn
     * property, so the next prompt starts from a clean reasoning state - and
     * nothing reasoning-shaped ever reaches the record.
     */
    fun dropLiveReasoning() {
        synchronized(historyLock) {
            history.removeAll { item -> item.isLiveReasoning() }
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
    fun replaySnapshot(): Triple<List<HistoryItem>, List<PlanEntry>, Map<String, String>> =
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
     * available in it. It lives in the conversation history as a
     * [HistoryItem.SystemMessage] entry and is LLM-internal (never rendered by the
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
    fun modeStatusTextForCurrent(): String = modeStatusText(mode.currentMode)

    /**
     * Applies the mode status message for [mode] to the history.
     */
    private fun appendModeStatusMessage(mode: SessionModeId) {
        appendToHistory(HistoryItem.SystemMessage(modeStatusText(mode)))
    }

    /**
     * The single writer of mode status messages: appends the `Mode:` message
     * for the governing mode iff it changed since the last indicated one (the
     * trail's last message, parsed from the record at restore). Called at
     * prompt start before the user message is appended, so every `Mode:`
     * message in the trail is immediately followed by a turn that ran under
     * it and no trail ever contains a mode that governed nothing (issue #36).
     *
     * The decision belongs to [ModeState] (issue #83); the append runs under
     * the history lock, so the trail and the indicated mode cannot drift apart
     * while a record snapshot is taken.
     */
    fun indicateCurrentMode() {
        synchronized(historyLock) {
            val modeToIndicate = mode.takeModeToIndicate() ?: return
            appendModeStatusMessage(modeToIndicate)
        }
    }

    /**
     * Parses the mode whose status message was appended last from a
     * persisted trail (`Mode: <id>.` prefix, per [modeStatusText]); null when
     * the trail carries no mode message (a fresh session, or a legacy record
     * from before the feature). The record's [SessionRecord.mode] stays the
     * source of truth for the governing mode - this only seeds
     * [ModeState]'s last-indicated mode so the next prompt start does not
     * append a duplicate message.
     */
    private fun lastIndicatedTrailMode(history: List<JsonObject>): SessionModeId? =
        history.map { historyItemFromWire(it) }
            .mapNotNull { it.systemTextOrNull() }
            .filter { it.startsWith(MODE_TRAIL_PREFIX) }
            .lastOrNull()
            ?.let { text ->
                text.removePrefix(MODE_TRAIL_PREFIX).substringBefore('.').let { raw ->
                    SessionModeId(raw).takeIf { candidate -> candidate in ALL_MODES }
                }
            }

    private companion object {
        const val MODE_TRAIL_PREFIX = "Mode: "
    }

    /**
     * The session title to announce to the client, or null when the client
     * already knows it. Derives the title from the first user message exactly
     * like [buildRecord] does and marks it announced, so the prompt flow sends
     * exactly one `session_info_update` per title (issue #73). Called at prompt
     * start - before the turn runs - so the update travels inside the prompt's
     * event stream instead of racing its response.
     */
    fun takeTitleAnnouncement(): String? = synchronized(historyLock) {
        val current = title ?: deriveTitle(history)?.also { title = it } ?: return null
        if (current == announcedTitle) return null
        announcedTitle = current
        current
    }

    fun buildRecord(): SessionRecord {
        val snapshot: List<HistoryItem>
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
            mode = mode.currentMode.value,
            title = recordTitle,
            updatedAt = System.currentTimeMillis(),
            // Reasoning items are live-only: the record is the history
            // projection without them, so no chain-of-thought ever reaches
            // disk (issue #75).
            history = snapshot.filterNot { item -> item.isLiveReasoning() }
                .map { it.toWire() },
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

    /** Records an `allow_always` decision for [toolName] (issue #83: the only writer). */
    fun grantAlways(toolName: String) {
        permissionGrants[toolName] = true
    }

    /** Records a `reject_always` decision for [toolName]. */
    fun rejectAlways(toolName: String) {
        permissionGrants[toolName] = false
    }

    /** The permanent decision for [toolName], or null when the tool was never granted or rejected. */
    fun permissionDecisionFor(toolName: String): Boolean? = permissionGrants[toolName]

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

    private fun deriveTitle(history: List<HistoryItem>): String? =
        history.asSequence()
            .mapNotNull { it.userTextOrNull() }
            .firstOrNull()
            ?.trim()
            ?.let { trimmed -> truncatedTitle(trimmed.ifEmpty { "(empty message)" }) }

    private fun truncatedTitle(title: String): String =
        if (title.codePointCount(0, title.length) <= MAX_TITLE_LENGTH) title
        else title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH)) + "…"

    /** True for the captured `reasoning` item, the only live-only item type (issue #75). */
    private fun HistoryItem.isLiveReasoning(): Boolean =
        (this as? HistoryItem.Captured)?.raw?.isReasoning == true
}

/** The session's initial model: the persisted pick or the configured OpenRouter default. */
internal fun initialModelId(restored: SessionRecord?, config: Config): String =
    restored?.model?.takeIf { it.isNotBlank() } ?: config.openRouterModel
