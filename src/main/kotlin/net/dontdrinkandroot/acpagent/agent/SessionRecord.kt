package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.PlanEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The durable state of one session, written to disk by [SessionStore] after
 * every prompt turn and on mode changes. The history is stored in the
 * Responses dialect (issue #75) as raw item objects, serialized by the shared
 * [net.dontdrinkandroot.acpagent.llm.llmWireJson]; `reasoning` items are
 * deliberately absent - reasoning state is live-only for the running turn and
 * never persisted (see [SessionState.buildRecord]).
 *
 * [toolOutcomes] records the live terminal status of every tool call
 * (toolCallId -> "completed" | "failed") so `session/load` replay can render
 * the real outcome instead of guessing it from the role-only history shape.
 * Values are lenient strings: an unknown value falls back to COMPLETED at
 * replay time, so a record written by a newer agent version still loads.
 */
@Serializable
internal data class SessionRecord(
    val sessionId: String,
    val cwd: String,
    val mode: String,
    val title: String,
    val updatedAt: Long,
    val history: List<JsonObject> = emptyList(),
    val model: String = "",
    val reasoning: String = "",
    val provider: String = "",
    val plan: List<PlanEntry> = emptyList(),
    val toolOutcomes: Map<String, String> = emptyMap(),
)
