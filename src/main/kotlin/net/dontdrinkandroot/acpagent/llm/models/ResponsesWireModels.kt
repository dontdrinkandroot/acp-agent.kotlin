package net.dontdrinkandroot.acpagent.llm.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.llm.ProviderPreferences

/**
 * Wire types for OpenRouter's Responses API (`POST /api/v1/responses`), the
 * stateless OpenAI-compatible endpoint the agent speaks (issue #75: reasoning
 * continuity in the tool loop). Stateless-only: `store: true` and
 * `previous_response_id` are rejected with 400, so every request carries the
 * full conversation history as input items; the shared [llmWireJson]
 * (snake-case naming, `explicitNulls = false`, `encodeDefaults = true`) keeps
 * absent members off the wire.
 *
 * Design: items are transported as raw [JsonObject]s, not as a polymorphic
 * type hierarchy. Two reasons. First, reasoning items must be replayed
 * byte-verbatim - OpenRouter normalizes the provider-native reasoning formats
 * (Anthropic thinking, Gemini thought signatures, OpenAI reasoning items,
 * ...) into the one `reasoning` item shape, and any typed interpretation
 * risks dropping fields the originating provider requires; their
 * `summary`/`content` members even differ per provider in the documented
 * examples. A raw-object layer makes verbatim replay the identity operation.
 * (kotlinx-serialization additionally does not register custom-serializer
 * subclasses in sealed polymorphic scopes, so a typed `reasoning` member is
 * not expressible there.) Second, the SSE event stream carries more event
 * types than the agent consumes; raw objects let the transport skip unknown
 * event types instead of failing on an unknown discriminator. The items and
 * events the agent *constructs* or *consumes field-wise* are typed below;
 * the raw layer only transports.
 *
 * Only the surface this project consumes is modeled; unknown members are
 * absorbed on decode by `ignoreUnknownKeys`.
 */

/**
 * A user/system message item the agent constructs. `type` is a body property
 * (serializes last, present because `llmWireJson` has `encodeDefaults = true`)
 * - the same pattern as [FunctionTool] and the chat wire types before.
 */
@Serializable
public class InputMessage(
    public val role: String,
    public val content: List<ResponseContentPart>,
) {
    public val type: String = "message"
}

/**
 * A tool result item the agent constructs. Only `type`, `call_id` and
 * `output` are required; `id` is optional on replay (the docs state it can be
 * omitted).
 */
@Serializable
public class FunctionCallOutput(
    public val callId: String,
    public val output: String,
    public val id: String? = null,
) {
    public val type: String = "function_call_output"
}

/**
 * Content of a message item. Input content uses `input_text`/`input_image`,
 * assistant replay uses `output_text`; the discriminator is `type`.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
public sealed interface ResponseContentPart {
    @Serializable
    @SerialName("input_text")
    public class InputText(public val text: String) : ResponseContentPart

    @Serializable
    @SerialName("output_text")
    public class OutputText(public val text: String) : ResponseContentPart

    @Serializable
    @SerialName("reasoning_text")
    public class ReasoningText(public val text: String) : ResponseContentPart

    @Serializable
    @SerialName("input_image")
    public class InputImage(public val imageUrl: String) : ResponseContentPart
}

/**
 * The flat function tool definition of the Responses API: `type`, `name`,
 * `description` and `parameters` are top-level (Chat Completions nested them
 * under `function`). The constructor-property `type` default is load-bearing:
 * `llmWireJson` has `encodeDefaults = true`, so it lands on the wire.
 */
@Serializable
public class FunctionTool(
    public val name: String,
    public val description: String? = null,
    public val parameters: JsonObject? = null,
) {
    public val type: String = "function"
}

/**
 * The request-level reasoning parameter. Only `effort` is sent: the
 * `reasoning.context` knob is an OpenAI GPT-5.6+ extension that open-weight
 * models do not understand, and under the agent's live-only reasoning
 * accumulation every request carries current-turn reasoning items only, so
 * the model default context mode is already the desired behavior on every
 * model.
 */
@Serializable
public class ReasoningRequest(
    public val effort: String,
)

/**
 * The chat request. `input` carries the full conversation history as raw
 * item objects (see the class doc): user/system [InputMessage]s and tool
 * [FunctionCallOutput]s the agent constructed, assistant message/
 * `function_call`/`reasoning` items captured verbatim from previous
 * iterations' output.
 */
@Serializable
public class ResponsesRequest(
    public val model: String,
    public val input: List<JsonObject>,
    public val tools: List<FunctionTool>? = null,
    public val stream: Boolean = true,
    public val reasoning: ReasoningRequest? = null,
    public val provider: ProviderPreferences? = null,
)

/** The `usage` object of the response (input/output naming, not prompt/completion). */
@Serializable
public class ResponsesUsage(
    public val inputTokens: Int? = null,
    public val outputTokens: Int? = null,
    public val totalTokens: Int? = null,
    public val outputTokensDetails: OutputTokensDetails? = null,
)

@Serializable
public class OutputTokensDetails(
    public val reasoningTokens: Int? = null,
)

/**
 * The Responses API error object (`{"code", "message"}`). Codes are string
 * enums (`invalid_prompt`, `server_error`, ...), not HTTP numbers.
 */
@Serializable
public class ResponsesError(
    public val code: String? = null,
    public val message: String? = null,
)

/**
 * The response object: the non-streaming body and the payload of the
 * `response.done`/`response.failed` stream events. `output` carries the
 * produced items verbatim (assistant message, function calls, reasoning) so
 * they can be appended to the history and replayed unchanged.
 */
@Serializable
public class ResponsesResponse(
    public val id: String = "",
    public val model: String = "",
    public val status: String? = null,
    public val output: List<JsonObject> = emptyList(),
    public val usage: ResponsesUsage? = null,
    public val error: ResponsesError? = null,
    @SerialName("error_type")
    public val errorType: String? = null,
    @SerialName("incomplete_details")
    public val incompleteDetails: IncompleteDetails? = null,
)

@Serializable
public class IncompleteDetails(
    public val reason: String? = null,
)
