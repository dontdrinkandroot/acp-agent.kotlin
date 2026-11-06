package net.dontdrinkandroot.acpagent.llm.models

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Wire types for the OpenRouter chat-completion traffic and the persisted
 * conversation history, owned by this project instead of Koog. The shapes and
 * the serialization behavior are byte-equivalent to the Koog 1.3.0 types they
 * replace (pinned by [net.dontdrinkandroot.acpagent.llm.LlmWireGoldensTest]):
 * same snake-case spelling under the shared [llmWireJson], same
 * `encodeDefaults` semantics for the body-property `type = "function"`
 * defaults, same string-or-parts [Content] serializer.
 *
 * Only the surface this project actually consumes is reproduced - Koog's
 * unused members (audio/refusal/annotations on the assistant message,
 * audio/file content parts, developer role, tool-choice and response-format
 * variants) are deliberately absent, and `ignoreUnknownKeys` absorbs them on
 * decode.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
public sealed interface OpenAIMessage {
    public val content: Content?

    @Serializable
    @SerialName("system")
    public class System(override val content: Content, public val name: String? = null) : OpenAIMessage

    @Serializable
    @SerialName("user")
    public class User(override val content: Content, public val name: String? = null) : OpenAIMessage

    @Serializable
    @SerialName("assistant")
    public class Assistant(
        override val content: Content? = null,
        public val toolCalls: List<OpenAIToolCall>? = null,
    ) : OpenAIMessage

    @Serializable
    @SerialName("tool")
    public class Tool(override val content: Content, public val toolCallId: String) : OpenAIMessage
}

/**
 * The content of a message: a JSON string or an array of typed parts. The
 * custom serializer reproduces Koog's: a primitive must be a string, an array
 * must be non-empty.
 */
@Serializable(with = ContentSerializer::class)
public sealed interface Content {
    public fun text(): String

    @Serializable
    @JvmInline
    public value class Text(public val value: String) : Content {
        override fun text(): String = value
    }

    @Serializable
    @JvmInline
    public value class Parts(public val value: List<OpenAIContentPart>) : Content {
        override fun text(): String = value
            .filterIsInstance<OpenAIContentPart.Text>()
            .joinToString("\n") { it.text }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface OpenAIContentPart {
    @Serializable
    @SerialName("text")
    public class Text(public val text: String) : OpenAIContentPart

    @Serializable
    @SerialName("image_url")
    public class Image(public val imageUrl: ImageUrl) : OpenAIContentPart

    @Serializable
    public class ImageUrl(public val url: String, public val detail: String? = null)
}

@Serializable
public class OpenAIToolCall(
    public val id: String,
    public val function: OpenAIFunction,
) {
    public val type: String = "function"
}

@Serializable
public class OpenAIStreamToolCall(
    public val index: Int,
    public val id: String?,
    public val function: OpenAIStreamFunction?,
    public val type: String? = "function",
)

@Serializable
public class OpenAIFunction(
    public val name: String,
    public val arguments: String = "",
)

@Serializable
public class OpenAIStreamFunction(
    public val name: String?,
    public val arguments: String?,
)

/**
 * The function tool definition (`{"type": "function", "function": {...}}`).
 * The body-property `type` default is load-bearing: `llmWireJson` has
 * `encodeDefaults = true`, so it lands on the wire exactly like Koog's.
 */
@Serializable
public class OpenAITool(public val function: OpenAIToolFunction) {
    public val type: String = "function"
}

@Serializable
public class OpenAIToolFunction(
    public val name: String,
    public val description: String? = null,
    public val parameters: JsonObject? = null,
    public val strict: Boolean? = null,
)

@Serializable
public class OpenAIUsage(
    public val promptTokens: Int? = null,
    public val completionTokens: Int? = null,
    public val totalTokens: Int? = null,
    public val completionTokensDetails: CompletionTokensDetails? = null,
    public val promptTokensDetails: PromptTokensDetails? = null,
)

@Serializable
public class CompletionTokensDetails(
    public val acceptedPredictionTokens: Int? = null,
    public val audioTokens: Int? = null,
    public val reasoningTokens: Int? = null,
    public val rejectedPredictionTokens: Int? = null,
)

@Serializable
public class PromptTokensDetails(
    public val audioTokens: Int? = null,
    public val cachedTokens: Int? = null,
)

@Serializable
public class ErrorResponse(
    public val code: Int,
    public val message: String,
    public val metadata: Map<String, String>? = null,
)

@Serializable
public class OpenRouterStreamChoice(
    public val delta: OpenRouterStreamDelta,
    public val finishReason: String? = null,
    public val nativeFinishReason: String? = null,
    public val error: ErrorResponse? = null,
)

@Serializable
public class OpenRouterStreamDelta(
    public val content: String? = null,
    public val role: String? = null,
    public val toolCalls: List<OpenAIStreamToolCall>? = null,
    public val reasoning: String? = null,
    /**
     * OpenRouter's structured reasoning details, verbatim: not interpreted
     * here, only accumulated for abnormal-completion diagnostics (and Phase 2
     * reasoning replay).
     */
    public val reasoningDetails: List<JsonElement>? = null,
)

/**
 * The streaming chat-completion chunk. Response types are decode-only for
 * this project, so the envelope keeps only the members the agent consumes;
 * unknown members are absorbed by `ignoreUnknownKeys`.
 */
@Serializable
public class OpenRouterChatCompletionStreamResponse(
    public val choices: List<OpenRouterStreamChoice> = emptyList(),
    public val created: Long = 0L,
    public val id: String = "",
    public val model: String = "",
    @SerialName("object")
    public val objectType: String = "chat.completion.chunk",
    public val usage: OpenAIUsage? = null,
)

internal object ContentSerializer : KSerializer<Content> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("Content", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: Content) {
        when (value) {
            is Content.Text -> encoder.encodeString(value.value)
            is Content.Parts -> encoder.encodeSerializableValue(
                ListSerializer(OpenAIContentPart.serializer()),
                value.value,
            )
        }
    }

    override fun deserialize(decoder: Decoder): Content {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Content can only be deserialized from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException(
                        "Expected string for text content, but got: ${element.contentOrNull}"
                    )
                }
                Content.Text(element.content)
            }

            is JsonArray -> {
                if (element.isEmpty()) {
                    throw SerializationException("Content array cannot be empty")
                }
                Content.Parts(
                    jsonDecoder.json.decodeFromJsonElement(
                        ListSerializer(OpenAIContentPart.serializer()),
                        element,
                    )
                )
            }

            else -> throw SerializationException(
                "Content must be either a string or an array of content parts. " +
                        "Got: ${element::class.simpleName}"
            )
        }
    }
}
