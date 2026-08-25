package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.model.PlanEntry
import kotlinx.serialization.Serializable

/**
 * The durable state of one session, written to disk by [SessionStore] after
 * every prompt turn and on mode changes. The history uses the Koog OpenAI
 * wire types directly (the internal LLM contract), serialized by the shared
 * [net.dontdrinkandroot.acpagent.llm.llmWireJson].
 */
@Serializable
internal data class SessionRecord(
    val sessionId: String,
    val cwd: String,
    val mode: String,
    val title: String,
    val updatedAt: Long,
    val history: List<OpenAIMessage> = emptyList(),
    val model: String = "",
    val reasoning: String = "",
    val plan: List<PlanEntry> = emptyList(),
)