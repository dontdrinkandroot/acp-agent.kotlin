package net.dontdrinkandroot.acpagent.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputText

/**
 * Fixture builders for tests that need a conversation history in the
 * Responses dialect. Kept in one place so the wire shapes a test constructs
 * stay recognizable as "the same shapes the runner produces".
 */

internal fun userMessage(text: String): HistoryItem =
    HistoryItem.UserMessage(listOf(InputText(text)))

internal fun systemMessage(text: String): HistoryItem = HistoryItem.SystemMessage(text)

internal fun toolOutput(callId: String, output: String): HistoryItem =
    HistoryItem.ToolOutput(callId = callId, output = output)

/** A captured assistant text message, as the runner records it. */
internal fun assistantMessage(text: String): HistoryItem = assistantMessageItem(text)

/** A captured `function_call` item, as the runner records it. */
internal fun functionCall(callId: String, name: String, arguments: String): HistoryItem =
    functionCallItem(callId, name, arguments)

/** A captured assistant message carrying text and one or more tool calls. */
internal fun assistantWithCalls(
    text: String?,
    vararg calls: Triple<String, String, String>,
): List<HistoryItem> = buildList {
    if (text != null) add(assistantMessage(text))
    calls.forEach { (id, name, args) -> add(functionCall(id, name, args)) }
}

/** A captured reasoning item (live-only; never persisted). */
internal fun reasoningItem(summary: String = "thinking"): HistoryItem = HistoryItem.Captured(
    buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_test")
        put("summary", summary)
    }
)

/** The text of a captured assistant message, for assertions. */
internal fun HistoryItem.assistantText(): String? =
    (this as? HistoryItem.Captured)?.assistantTextOrNull()

/** The wire form of a fixture history, for store round-trip assertions. */
internal fun wireHistory(vararg items: HistoryItem): List<JsonObject> = items.map { it.toWire() }

/** The parts of a user-message fixture. */
internal fun HistoryItem.userParts(): List<ResponseContentPart> =
    (this as HistoryItem.UserMessage).parts
