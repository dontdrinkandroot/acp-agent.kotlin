package net.dontdrinkandroot.acpagent.agent

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart
import net.dontdrinkandroot.acpagent.llm.models.ResponseContentPart.InputText

/**
 * One entry of the conversation history, in the Responses dialect.
 *
 * The distinction that matters: items the agent **constructs** are typed
 * ([UserMessage], [SystemMessage], [ToolOutput]), while items **captured**
 * from a model response ([Captured]) are kept as their raw wire object so a
 * replay is byte-identical - an assistant message carries `annotations`,
 * `status` and its own id, a `reasoning` item carries provider-specific
 * `encrypted_content`/`signature`/`summary` fields, and a typed round-trip
 * would silently drop them (issue #75).
 *
 * The history is the request body: [toWire] is applied 1:1, so what the model
 * saw is what it sees again.
 */
internal sealed interface HistoryItem {

    data class UserMessage(val parts: List<ResponseContentPart>) : HistoryItem

    data class SystemMessage(val text: String) : HistoryItem

    data class ToolOutput(val callId: String, val output: String) : HistoryItem

    data class Captured(val raw: JsonObject) : HistoryItem
}

/** The wire form of one history item, replayed verbatim for [HistoryItem.Captured]. */
internal fun HistoryItem.toWire(): JsonObject = when (this) {
    is HistoryItem.UserMessage -> buildJsonObject {
        put("type", "message")
        put("role", "user")
        put("content", llmWireJson.encodeToJsonElement(ListSerializer(ResponseContentPart.serializer()), parts))
    }

    is HistoryItem.SystemMessage -> buildJsonObject {
        put("type", "message")
        put("role", "system")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "input_text"); put("text", text) })
        })
    }

    is HistoryItem.ToolOutput -> buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }

    is HistoryItem.Captured -> raw
}

/**
 * Parses a persisted wire item back into a [HistoryItem]. Constructed shapes
 * are recognized and re-typed; everything else (assistant messages,
 * `function_call`, `reasoning`, and any future item type) stays
 * [HistoryItem.Captured] verbatim.
 */
internal fun historyItemFromWire(raw: JsonObject): HistoryItem = when (raw.stringField("type")) {
    "message" -> when (raw.stringField("role")) {
        "user" -> HistoryItem.UserMessage(parseInputParts(raw)).retypedOrCaptured(raw)
        "system" -> HistoryItem.SystemMessage(raw.textContent().orEmpty()).retypedOrCaptured(raw)
        else -> HistoryItem.Captured(raw)
    }

    // `output` is a string or an array of content parts (multimodal tool
    // outputs); only the string form is modeled, so an array stays captured.
    "function_call_output" -> (raw["output"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.let {
            HistoryItem.ToolOutput(
                callId = raw.stringField("call_id").orEmpty(),
                output = it.content,
            ).retypedOrCaptured(raw)
        }
        ?: HistoryItem.Captured(raw)

    else -> HistoryItem.Captured(raw)
}

/**
 * The parse rule: re-type an item only if the typed value encodes back to the
 * very item it came from. Anything the typed model would lose (an unmodeled
 * member, a prompt image part, a multimodal `function_call_output.output`
 * array) fails the identity and stays [HistoryItem.Captured], so a restore can
 * never rewrite or drop wire data. `JsonObject` equality is order-insensitive,
 * so member order does not matter - only content does.
 */
private fun HistoryItem.retypedOrCaptured(raw: JsonObject): HistoryItem =
    if (toWire() == raw) this else HistoryItem.Captured(raw)

private fun parseInputParts(raw: JsonObject): List<ResponseContentPart> {
    val content = raw["content"] as? JsonArray ?: return emptyList()
    return content.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        when (part.stringField("type")) {
            "input_text" -> InputText(part.stringField("text").orEmpty())
            else -> null
        }
    }
}

/** The `type` discriminator of a captured item. */
internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The concatenated text of a message item's `input_text`/`output_text` parts. */
internal fun JsonObject.textContent(): String? {
    val content = this["content"] as? JsonArray ?: return null
    val texts = content.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        when (part.stringField("type")) {
            "input_text", "output_text" -> part.stringField("text")
            else -> null
        }
    }
    return texts.joinToString("\n").takeIf { texts.isNotEmpty() }
}

/** True for a captured assistant message item. */
internal val JsonObject.isAssistantMessage: Boolean
    get() = stringField("type") == "message" && stringField("role") == "assistant"

/** True for a captured user or system (input) message item. */
internal val JsonObject.isInputMessage: Boolean
    get() = stringField("type") == "message" && stringField("role") in INPUT_ROLES

private val INPUT_ROLES = setOf("user", "system", "developer")

/** True for a captured `function_call` item. */
internal val JsonObject.isFunctionCall: Boolean
    get() = stringField("type") == "function_call"

/** True for a captured `reasoning` item (never persisted, replayed in-turn only). */
internal val JsonObject.isReasoning: Boolean
    get() = stringField("type") == "reasoning"

/** The text of a user message, typed or captured (lossless parse). */
internal fun HistoryItem.userTextOrNull(): String? = when (this) {
    is HistoryItem.UserMessage -> textOrNull()
    is HistoryItem.Captured -> raw.takeIf { it.isInputMessage && it.stringField("role") == "user" }?.textContent()
    else -> null
}

/** The text of a mode status message, typed or captured (lossless parse). */
internal fun HistoryItem.systemTextOrNull(): String? = when (this) {
    is HistoryItem.SystemMessage -> text
    is HistoryItem.Captured -> raw.takeIf { it.isInputMessage && it.stringField("role") == "system" }?.textContent()
    else -> null
}

/** The call id of a tool result, typed or captured (lossless parse). */
internal fun HistoryItem.toolCallIdOrNull(): String? = when (this) {
    is HistoryItem.ToolOutput -> callId
    is HistoryItem.Captured -> raw.takeIf { it.stringField("type") == "function_call_output" }
        ?.stringField("call_id")

    else -> null
}

/** The output text of a tool result, or null for a non-text (content-part) output. */
internal fun HistoryItem.toolOutputTextOrNull(): String? = when (this) {
    is HistoryItem.ToolOutput -> output
    is HistoryItem.Captured -> raw.takeIf { it.stringField("type") == "function_call_output" }
        ?.let { toolOutput -> (toolOutput["output"] as? JsonPrimitive)?.takeIf { it.isString }?.content }

    else -> null
}

/** The parts of a user message as typed content parts. */
internal fun HistoryItem.UserMessage.textOrNull(): String? =
    parts.filterIsInstance<InputText>().joinToString("\n") { it.text }.takeIf { it.isNotEmpty() }

/** The text of an assistant message, typed or captured (lossless parse). */
internal fun HistoryItem.assistantTextOrNull(): String? = when (this) {
    is HistoryItem.Captured -> if (raw.isAssistantMessage) raw.textContent() else null
    else -> null
}

internal fun JsonObject.jsonObjectOrNull(name: String): JsonObject? = this[name] as? JsonObject
