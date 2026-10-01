package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.model.*
import net.dontdrinkandroot.acpagent.tools.ToolRegistry

/**
 * Builds the `session/load` replay updates from the persisted state snapshot:
 * user/agent text as message chunks, assistant tool calls as PENDING
 * `tool_call` introductions, tool results as terminal `tool_call_update`s and
 * the plan as its update. Pure mapping, pinned by [ReplayHistoryTest].
 *
 * A tool result's status comes from the persisted outcome map, so denied,
 * disabled, unknown and failed calls replay as FAILED (the status they had
 * live) instead of the historical blanket COMPLETED. Legacy records without
 * outcomes (or with an unknown outcome value) replay COMPLETED fail-open, so
 * old sessions render exactly as before.
 */
internal fun buildReplayUpdates(
    history: List<OpenAIMessage>,
    plan: List<PlanEntry>,
    toolOutcomes: Map<String, String>,
    toolRegistry: ToolRegistry,
): List<SessionUpdate> = buildList {
    history.forEach { message ->
        when (message) {
            is OpenAIMessage.User ->
                message.content.textOrNull()?.takeIf { it.isNotEmpty() }?.let {
                    add(SessionUpdate.UserMessageChunk(ContentBlock.Text(it), newMessageId()))
                }

            is OpenAIMessage.Assistant -> {
                message.content.textOrNull()?.takeIf { it.isNotEmpty() }?.let {
                    add(SessionUpdate.AgentMessageChunk(ContentBlock.Text(it), newMessageId()))
                }
                message.toolCalls.orEmpty().forEach { call ->
                    val toolName = call.function.name
                    val args = parseArguments(call.function.arguments)
                    val tool = toolRegistry.get(toolName)
                    add(
                        SessionUpdate.ToolCall(
                            toolCallId = ToolCallId(call.id),
                            title = tool?.title(args) ?: toolName,
                            kind = tool?.kind ?: ToolKind.OTHER,
                            status = ToolCallStatus.PENDING,
                            locations = tool?.let { toolLocations(it, args) } ?: emptyList(),
                            rawInput = args,
                        )
                    )
                }
            }

            is OpenAIMessage.Tool -> {
                val failed = toolOutcomes[message.toolCallId] == TOOL_OUTCOME_FAILED
                add(
                    SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId(message.toolCallId),
                        status = if (failed) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                        content = listOf(
                            ToolCallContent.Content(ContentBlock.Text(message.content.textOrNull().orEmpty()))
                        ),
                    )
                )
            }

            else -> Unit
        }
    }
    plan.takeIf { it.isNotEmpty() }?.let { add(SessionUpdate.PlanUpdate(it)) }
}
