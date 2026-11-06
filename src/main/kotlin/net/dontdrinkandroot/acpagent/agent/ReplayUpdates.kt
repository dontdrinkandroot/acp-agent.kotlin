package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.*
import net.dontdrinkandroot.acpagent.tools.ToolRegistry

/**
 * Builds the `session/load` replay updates from the persisted state snapshot:
 * user/agent text as message chunks, `function_call` items as PENDING
 * `tool_call` introductions, tool outputs as terminal `tool_call_update`s and
 * the plan as its update. Pure mapping, pinned by [ReplayHistoryTest].
 *
 * A tool result's status comes from the persisted outcome map, so denied,
 * disabled, unknown and failed calls replay as FAILED (the status they had
 * live) instead of the historical blanket COMPLETED. Records without outcomes
 * (or with an unknown outcome value) replay COMPLETED fail-open.
 *
 * `reasoning` items never reach a record, so replay never renders them.
 */
internal fun buildReplayUpdates(
    history: List<HistoryItem>,
    plan: List<PlanEntry>,
    toolOutcomes: Map<String, String>,
    toolRegistry: ToolRegistry,
): List<SessionUpdate> = buildList {
    // Emission is driven by what an item *means*, not by which variant it is:
    // an item the lossless parse kept verbatim (a prompt image, a multimodal
    // tool output) must still replay exactly like its typed counterpart.
    history.forEach { item ->
        item.userTextOrNull()?.takeIf { it.isNotEmpty() }?.let {
            add(SessionUpdate.UserMessageChunk(ContentBlock.Text(it), newMessageId()))
        }
        item.assistantTextOrNull()?.takeIf { it.isNotEmpty() }?.let {
            add(SessionUpdate.AgentMessageChunk(ContentBlock.Text(it), newMessageId()))
        }
        item.toolCallIdOrNull()?.let { callId ->
            val failed = toolOutcomes[callId] == TOOL_OUTCOME_FAILED
            add(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId(callId),
                    status = if (failed) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                    content = listOf(
                        ToolCallContent.Content(ContentBlock.Text(item.toolOutputTextOrNull().orEmpty()))
                    ),
                )
            )
        }
        item.capturedFunctionCall()?.let { call ->
            val args = parseArguments(call.arguments)
            val tool = toolRegistry.get(call.name)
            add(
                SessionUpdate.ToolCall(
                    toolCallId = ToolCallId(call.callId),
                    title = tool?.title(args) ?: call.name,
                    kind = tool?.kind ?: ToolKind.OTHER,
                    status = ToolCallStatus.PENDING,
                    locations = tool?.let { toolLocations(it, args) } ?: emptyList(),
                    rawInput = args,
                )
            )
        }
    }
    plan.takeIf { it.isNotEmpty() }?.let { add(SessionUpdate.PlanUpdate(it)) }
}
