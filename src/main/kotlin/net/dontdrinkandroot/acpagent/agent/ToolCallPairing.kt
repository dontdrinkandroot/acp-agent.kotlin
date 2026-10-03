package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage

/**
 * One assistant tool call id whose `role:"tool"` result message was never
 * appended - the turn died between the call's append and its terminal result
 * (a cancelled tool call, the issue #38 corruption).
 */
internal data class DanglingToolCall(val assistantIndex: Int, val callId: String)

/**
 * A repaired history view: the input history with every [DanglingToolCall]
 * closed by a synthetic terminal tool result, plus the ids that were repaired
 * so the caller can record their FAILED outcome. Pure and idempotent: a
 * pair-complete history passes through unchanged.
 */
internal data class PairingRepair(val history: List<OpenAIMessage>, val danglingIds: Set<String>)

/**
 * Closes dangling tool calls (issue #38): an assistant tool call whose
 * `role:"tool"` result was never appended (the turn was cancelled mid-call)
 * must not reach any history consumer - LLM requests, load replay, persisted
 * records - with its call dangling, because strict providers (Azure/OpenAI)
 * reject the whole request with 400 "No tool output found for function call
 * <id>". Every repaired id is closed by one synthetic FAILED result.
 *
 * The repair is per call id, so a batch whose earlier calls completed keeps
 * their real results and only the never-answered ones are synthesized. The
 * synthetic result is inserted after the batch's already-present results (or
 * directly after the assistant message when none arrived), so history order
 * stays chronologically faithful: the interruption happened last.
 */
internal fun repairToolCallPairing(history: List<OpenAIMessage>): PairingRepair {
    val dangling = danglingToolCalls(history)
    if (dangling.isEmpty()) return PairingRepair(history, emptySet())

    val insertions = mutableMapOf<Int, MutableList<String>>()
    dangling.forEach { (assistantIndex, callId) ->
        insertions.getOrPut(pairingInsertionIndex(history, assistantIndex)) { mutableListOf() }.add(callId)
    }
    val repaired = buildList {
        history.forEachIndexed { index, message ->
            add(message)
            insertions[index]?.forEach { callId -> add(syntheticCancelledResult(callId)) }
        }
    }
    return PairingRepair(repaired, dangling.map { it.callId }.toSet())
}

/**
 * The index whose message the synthetic result follows: the assistant message
 * itself, or the last of the tool results directly following it (the results
 * of the same batch that did arrive).
 */
private fun pairingInsertionIndex(history: List<OpenAIMessage>, assistantIndex: Int): Int {
    var index = assistantIndex
    while (index + 1 < history.size && history[index + 1] is OpenAIMessage.Tool) index++
    return index
}

/**
 * Finds every assistant tool-call id that has no matching tool result message
 * in [history] - set-difference semantics (call id minus result ids),
 * independent of where in the history the messages sit.
 */
private fun danglingToolCalls(history: List<OpenAIMessage>): List<DanglingToolCall> {
    val answered = history.filterIsInstance<OpenAIMessage.Tool>().mapTo(mutableSetOf()) { it.toolCallId }
    val dangling = mutableListOf<DanglingToolCall>()
    history.forEachIndexed { index, message ->
        if (message !is OpenAIMessage.Assistant) return@forEachIndexed
        message.toolCalls.orEmpty().forEach { call ->
            if (call.id !in answered) dangling += DanglingToolCall(index, call.id)
        }
    }
    return dangling
}

private fun syntheticCancelledResult(callId: String) = OpenAIMessage.Tool(
    Content.Text(CANCELLED_TOOL_RESULT_TEXT),
    toolCallId = callId,
)

internal const val CANCELLED_TOOL_RESULT_TEXT =
    "Tool call was interrupted before a result was produced; any effects may already be applied."

/**
 * The repaired view SessionState serves to its consumers: the pair-complete
 * history plus the tool outcomes as they must be observed (a repaired id
 * carries a FAILED outcome even though it has no recorded one in memory).
 */
internal data class RepairedView(
    val history: List<OpenAIMessage>,
    val outcomes: Map<String, String>,
)
