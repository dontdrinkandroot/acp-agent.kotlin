package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionModeId

/**
 * The session's mode selection and its deferred-switch bookkeeping (issue #83:
 * extracted from `SessionState`, which keeps the history-side concerns - the
 * `Mode:` trail message and the status text).
 *
 * The machine is deliberately history-free: it decides *which* mode governs
 * and *which* mode still needs a status message, but never writes the
 * conversation. [SessionState.indicateCurrentMode] asks it via
 * [takeModeToIndicate] and appends the message under its history lock, so the
 * trail and the governing mode stay consistent.
 *
 * **Locking**: every compound transition takes its own short lock, so a
 * request and a flush cannot interleave. [currentMode] and [hasPendingMode]
 * are additionally `@Volatile` because they are read from the prompt flow
 * without holding it. Callers may hold the history lock while calling in
 * (SessionState does, to keep the trail append atomic with the indication);
 * this class never calls back, so the lock order is always
 * history-lock -> mode-lock and cannot deadlock.
 */
internal class ModeState(
    /**
     * The mode the session started in: the persisted mode of a restored
     * record, else [DEFAULT_MODE]. The session reports it as its default mode
     * (the SDK builds the session response from `defaultMode`, never from the
     * live mode).
     */
    val initialMode: SessionModeId,
    /**
     * The mode whose status message the restored trail already carries, parsed
     * from the record at restore ([SessionState.lastIndicatedTrailMode]);
     * null when the trail has no such message (a fresh session or a legacy
     * record), which makes the next prompt start write one.
     */
    lastIndicatedMode: SessionModeId?,
) {

    private val lock = Any()

    /** The governing mode: the one every tool gate and the record use. */
    @Volatile
    var currentMode: SessionModeId = initialMode
        private set

    private var lastIndicatedMode: SessionModeId? = lastIndicatedMode

    /**
     * The mode the client requested while a prompt turn is in flight. It does
     * not govern anything during the turn (the turn keeps the mode it started
     * with, so tool gating stays consistent); the latest request wins and it
     * is applied when the agent turns control back to the user.
     */
    @Volatile
    private var pendingMode: SessionModeId? = null

    /**
     * True while a prompt turn is running; mode requests are deferred until
     * the turn ends, so a switch never applies mid-iteration.
     */
    @Volatile
    private var promptActive = false

    /** Starts/ends a prompt turn; controls whether [requestMode] defers. */
    fun setPromptActive(active: Boolean) {
        promptActive = active
    }

    /** True while a mode request is deferred (a turn is running and it was not flushed yet). */
    fun hasPendingMode(): Boolean = pendingMode != null

    /**
     * Records a mode request. While a prompt turn is running the request only
     * updates the pending mode (latest wins) and applies nothing; idle, it
     * flips [currentMode] immediately (the client is notified by the caller).
     * Same-value requests are no-ops in both cases (and clear a stale pending
     * switch back to the governing mode).
     */
    fun requestMode(mode: SessionModeId) {
        synchronized(lock) {
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
     * Applies the pending mode (if any) exactly once: flips [currentMode] and
     * clears the pending. Returns true when a mode was actually applied (so
     * the caller knows to announce and persist it). No-op and false when
     * nothing is pending.
     */
    fun flushPendingMode(): Boolean = synchronized(lock) {
        val pending = pendingMode ?: return false
        pendingMode = null
        if (pending == currentMode) return false
        currentMode = pending
        true
    }

    /**
     * Drops a pending mode without applying it. Called when a turn is
     * cancelled: the client was never told about the pending switch, so no
     * update needs to be undone.
     */
    fun cancelPrompt() {
        synchronized(lock) { pendingMode = null }
    }

    /**
     * The single-writer decision for the `Mode:` trail message: returns the
     * governing mode when it differs from the last indicated one (and marks it
     * indicated), or null when the trail already states it. The caller writes
     * the message for a non-null result.
     */
    fun takeModeToIndicate(): SessionModeId? = synchronized(lock) {
        if (currentMode == lastIndicatedMode) return null
        lastIndicatedMode = currentMode
        currentMode
    }
}