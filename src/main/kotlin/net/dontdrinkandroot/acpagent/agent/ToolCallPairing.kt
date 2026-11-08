package net.dontdrinkandroot.acpagent.agent

/**
 * One assistant tool call id whose `function_call_output` item was never
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
internal data class PairingRepair(val history: List<HistoryItem>, val danglingIds: Set<String>)

/**
 * Closes dangling tool calls (issue #38): a `function_call` item whose
 * `function_call_output` was never appended (the turn was cancelled mid-call)
 * must not reach any history consumer - LLM requests, load replay, persisted
 * records - with its call dangling, because strict providers reject the whole
 * request with 400 "No tool output found for function call <id>". Every
 * repaired id is closed by one synthetic FAILED result.
 *
 * The repair is per call id, so a batch whose earlier calls completed keeps
 * their real results and only the never-answered ones are synthesized. The
 * synthetic result is inserted after the batch's already-present results (or
 * directly after the call when none arrived), so history order stays
 * chronologically faithful: the interruption happened last.
 *
 * `reasoning` items are not calls and are never repaired - they are simply
 * carried along (and dropped at turn end, see [SessionState]).
 */
internal fun repairToolCallPairing(history: List<HistoryItem>): PairingRepair {
    val dangling = danglingToolCalls(history)
    if (dangling.isEmpty()) return PairingRepair(history, emptySet())

    val insertions = mutableMapOf<Int, MutableList<String>>()
    dangling.forEach { (callIndex, callId) ->
        insertions.getOrPut(pairingInsertionIndex(history, callIndex)) { mutableListOf() }.add(callId)
    }
    val repaired = buildList {
        history.forEachIndexed { index, item ->
            add(item)
            insertions[index]?.forEach { callId -> add(syntheticCancelledResult(callId)) }
        }
    }
    return PairingRepair(repaired, dangling.map { it.callId }.toSet())
}

/**
 * The index whose item the synthetic result follows: the `function_call`
 * itself, or the last of the tool outputs directly following it (the results
 * of the same batch that did arrive).
 */
private fun pairingInsertionIndex(history: List<HistoryItem>, callIndex: Int): Int {
    var index = callIndex
    while (index + 1 < history.size && history[index + 1] is HistoryItem.ToolOutput) index++
    return index
}

/**
 * Finds every `function_call` id that has no matching tool output in
 * [history] - set-difference semantics (call id minus result ids), independent
 * of where in the history the items sit.
 */
private fun danglingToolCalls(history: List<HistoryItem>): List<DanglingToolCall> {
    val answered = history.mapNotNullTo(mutableSetOf()) { it.toolCallIdOrNull() }
    val dangling = mutableListOf<DanglingToolCall>()
    history.forEachIndexed { index, item ->
        val call = item.capturedFunctionCall() ?: return@forEachIndexed
        if (call.callId !in answered) dangling += DanglingToolCall(index, call.callId)
    }
    return dangling
}

private fun syntheticCancelledResult(callId: String) = HistoryItem.ToolOutput(
    callId = callId,
    output = CANCELLED_TOOL_RESULT_TEXT,
)

internal const val CANCELLED_TOOL_RESULT_TEXT =
    "Tool call was interrupted before a result was produced; any effects may already be applied."

/**
 * The repaired view SessionState serves to its consumers: the pair-complete
 * history plus the tool outcomes as they must be observed (a repaired id
 * carries a FAILED outcome even though it has no recorded one in memory).
 */
internal data class RepairedView(
    val history: List<HistoryItem>,
    val outcomes: Map<String, String>,
)

/** A captured `function_call` item's id and payload, if this item is one. */
internal data class CapturedFunctionCall(val callId: String, val name: String, val arguments: String)

internal fun HistoryItem.capturedFunctionCall(): CapturedFunctionCall? {
    val raw = (this as? HistoryItem.Captured)?.raw ?: return null
    if (!raw.isFunctionCall) return null
    return CapturedFunctionCall(
        callId = raw.stringField("call_id").orEmpty(),
        name = raw.stringField("name").orEmpty(),
        arguments = raw.stringField("arguments").orEmpty(),
    )
}
