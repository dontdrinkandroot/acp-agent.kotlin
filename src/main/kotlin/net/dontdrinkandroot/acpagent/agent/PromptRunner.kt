package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.*
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.github.f4b6a3.uuid.UuidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import net.dontdrinkandroot.acpagent.llm.ChatCompleter
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry

private val logger = KotlinLogging.logger {}

/**
 * Runs one prompt turn: the LLM tool-calling loop capped at [maxTurnRequests]
 * iterations and, when the whole budget is consumed while the model kept
 * requesting tools, a final text-only synthesis pass. Streamed reasoning and
 * reply chunks are relayed immediately; tool-call deltas are accumulated and
 * executed sequentially by [ToolCallExecutor].
 *
 * Truncated or empty completions (a `length` finish reason, or an iteration
 * with no text and no tool calls) are recovered from once per turn:the partial
 * text (if any) is kept, all tool calls are dropped (never executed —the
 * model re-issues them after the continuation), a synthetic user "continue"
 * prompt is appended, and the loop runs one more iteration. A second
 * truncation ends the turn with an honest text note instead of an error dialog.


 * A `content_filter` finish reason is not retried:the turn ends immediately
 * with the partial text and a notice. Both cases are logged to stderr so the
 * problem stays visible. Transport-level failures (a stream ending without
 * `[DONE]` / finish reason) remain loud `LlmException`s. */
@OptIn(UnstableApi::class)
internal class PromptRunner(
    private val state: SessionState,
    private val systemPrompt: SystemPromptBuilder,
    private val chatCompleter: ChatCompleter,
    private val providerRouting: ProviderRouting?,
    private val sessionConfigOptions: SessionConfigOptions,
    private val toolRegistry: ToolRegistry,
    private val toolCallExecutor: ToolCallExecutor,
    private val maxTurnRequests: Int,
    private val models: List<OpenRouterModel>,
) {

    suspend fun run(
        emitter: FlowCollector<Event>,
        mode: SessionModeId,
        instructions: AgentsInstructions?,
        toolContext: ToolContext,
    ) {
        var usage: OpenAIUsage? = null
        var iterations = 0
        var continuationUsed = false
        var continuationPending = false
        while (iterations < maxTurnRequests || continuationPending) {
            continuationPending = false
            iterations++
            val messageId = newMessageId()
            val messages = buildList {
                add(OpenAIMessage.System(Content.Text(systemPrompt.build(instructions))))
                addAll(state.historySnapshot)
            }
            val tools = toolRegistry.availableForMode(mode).map { tool ->
                OpenAITool(
                    function = OpenAIToolFunction(
                        name = tool.name,
                        description = tool.description,
                        parameters = tool.parameters,
                    ),
                )
            }

            val iteration = streamChat(emitter, messages, tools, messageId)
            usage = iteration.usage ?: usage

            when {
                iteration.truncated && !continuationUsed -> {
                    continuationUsed = true
                    continuationPending = true
                    val reason = iteration.finishReason ?: "empty"
                    val empty = iteration.text.isEmpty()
                    val partial = iteration.text.takeIf { it.isNotEmpty() }
                    if (partial == null) {
                        logger.warn {
                            "Truncated/empty chat completion ($reason; empty, iteration $iterations): continuing with one retry"
                        }
                    } else {
                        logger.warn {
                            "Truncated/empty chat completion ($reason; ${partial.length} chars, iteration $iterations): continuing with one retry"
                        }
                    }
                    // All tool calls are dropped - including complete ones - so a
                    // truncated call is never executed and the model re-issues them.


                    if (partial != null) {
                        state.appendToHistory(OpenAIMessage.Assistant(content = Content.Text(partial)))
                    }
                    state.appendToHistory(
                        OpenAIMessage.User(Content.Text(if (empty) CONTINUE_EMPTY_PROMPT else CONTINUE_TRUNCATED_PROMPT))
                    )
                }

                iteration.truncated -> {

                    val reason = iteration.finishReason ?: "empty"
                    logger.error {
                        "Truncated/empty chat completion ($reason) survived the retry: ending the turn with an incomplete-response note"
                    }
                    val note = "The response was interrupted before completion;the task may need a new prompt."
                    state.appendToHistory(OpenAIMessage.Assistant(Content.Text(note)))
                    state.persist()
                    emitUsageUpdate(usage)
                    emitTextChunk(emitter, note, newMessageId())
                    emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
                    return
                }

                iteration.finishReason == "content_filter" -> {
                    logger.warn { "Chat completion blocked by content filtering; ending the turn" }
                    val partial = iteration.text.takeIf { it.isNotEmpty() }
                    val note = "The response was blocked by content filtering."
                    if (partial != null) {
                        state.appendToHistory(OpenAIMessage.Assistant(Content.Text(partial)))
                    }
                    state.appendToHistory(OpenAIMessage.Assistant(Content.Text(note)))
                    state.persist()
                    emitUsageUpdate(usage)
                    if (partial != null) emitTextChunk(emitter, note, newMessageId())
                    emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
                    return
                }

                iteration.toolCalls.isEmpty() -> {
                    state.appendToHistory(OpenAIMessage.Assistant(content = Content.Text(iteration.text)))
                    state.persist()
                    emitUsageUpdate(usage)
                    emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
                    return
                }

                else -> {
                    state.appendToHistory(
                        OpenAIMessage.Assistant(
                            content = Content.Text(iteration.text),
                            toolCalls = iteration.toolCalls.map { call ->
                                OpenAIToolCall(call.id, OpenAIFunction(call.name, call.arguments))
                            },
                        )
                    )

                    for (call in iteration.toolCalls) {
                        toolCallExecutor.execute(mode, toolContext, emitter, call)
                    }
                }
            }
        }

        // The loop exits when the tool-iteration budget is consumed. A truncation
        // in the last allowed iteration gets its one continuation pass regardless,
        // so the retry never falls victim to the cap. Run one final text-only
        // synthesis pass (tools omitted, so no tool call is possible) so the user
        // gets a summary of what was done and what remains instead of an abrupt stop.


        val windDownMessageId = newMessageId()
        val windDownMessages = buildList {
            add(OpenAIMessage.System(Content.Text(systemPrompt.build(instructions))))
            addAll(state.historySnapshot)
            add(OpenAIMessage.User(Content.Text(WIND_DOWN_PROMPT)))
        }
        val windDown = streamChat(emitter, windDownMessages, emptyList(), windDownMessageId)
        usage = windDown.usage ?: usage

        if (windDown.truncated) {


            val reason = windDown.finishReason ?: "empty"
            logger.error {
                "Truncated/empty chat completion ($reason) in the wind-down pass: ending the turn with an incomplete-response note"
            }
            val note = "The response was interrupted before completion;the task may need a new prompt."
            state.appendToHistory(OpenAIMessage.Assistant(Content.Text(note)))
            emitTextChunk(emitter, note, newMessageId())
            state.persist()
            emitUsageUpdate(usage)
            emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.MAX_TURN_REQUESTS)))
            return
        }

        state.appendToHistory(OpenAIMessage.Assistant(content = Content.Text(windDown.text)))
        state.persist()
        emitUsageUpdate(usage)
        emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.MAX_TURN_REQUESTS)))
    }

    /**
     * Streams one chat completion and relays its deltas: reasoning as
     * thought chunks, text as message chunks (accumulating the reply)and
     * tool-call deltas merged into a per-index accumulator. The first choice's
     * last non-null finish reason is captured so the caller can distinguish a
     * truncated or blocked completion from a natural stop. (OpenRouter repeats
     * the reason in the final chunk, so the last value is authoritative.)
     */
    private suspend fun streamChat(
        emitter: FlowCollector<Event>,
        messages: List<OpenAIMessage>,
        tools: List<OpenAITool>,
        messageId: MessageId,
    ): StreamedIteration {
        val text = StringBuilder()
        val toolCallAccum = mutableMapOf<Int, MutableStreamToolCall>()
        var usage: OpenAIUsage? = null
        var finishReason: String? = null
        chatCompleter.chatCompletion(
            messages = messages,
            tools = tools,
            reasoning = sessionConfigOptions.effectiveReasoning(),
            model = state.currentModel,
            provider = providerRouting?.providerFor(state.currentModel),
        ).collect { chunk ->
            chunk.usage?.let { usage = it }
            chunk.choices.firstOrNull()?.let { choice ->
                choice.finishReason?.let { finishReason = it }
                choice.delta.reasoning?.takeIf { it.isNotEmpty() }?.let { reasoning ->
                    emitter.emit(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentThoughtChunk(
                                ContentBlock.Text(reasoning),
                                messageId
                            )
                        )
                    )
                }
                choice.delta.content?.takeIf { it.isNotEmpty() }?.let { chunkText ->
                    text.append(chunkText)
                    emitter.emit(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(
                                ContentBlock.Text(chunkText),
                                messageId
                            )
                        )
                    )
                }
                choice.delta.toolCalls?.forEach { tc ->
                    val acc = toolCallAccum.getOrPut(tc.index) { MutableStreamToolCall() }
                    tc.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                    tc.function?.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                    tc.function?.arguments?.let { acc.arguments += it }
                }
            }
        }
        return StreamedIteration(
            text.toString(),
            toolCallAccum.values.map { it.toToolCall() },
            usage,
            finishReason,
        )
    }

    /**
     * Reports the context window usage of the last completed model call. The
     * update is skipped when the model reports no usage or context length, so
     * the client keeps its previous indicator.


     */
    private suspend fun emitUsageUpdate(usage: OpenAIUsage?) {
        val client = runCatching { currentCoroutineContext().client }.getOrNull() ?: return
        val used = usage?.promptTokens ?: return
        if (used <= 0) return
        val size = models.firstOrNull { it.id == state.currentModel }?.contextLength ?: return
        if (size <= 0) return
        client.notify(SessionUpdate.UsageUpdate(used = used.toLong(), size = size.toLong()))
    }

    private suspend fun emitTextChunk(emitter: FlowCollector<Event>, text: String, messageId: MessageId) {

        emitter.emit(
            Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(ContentBlock.Text(text), messageId)
            )
        )
    }

    private class MutableStreamToolCall {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
        fun toToolCall() = StreamToolCall(id, name, arguments)
    }
}

private data class StreamedIteration(
    val text: String,
    val toolCalls: List<StreamToolCall>,
    val usage: OpenAIUsage?,
    val finishReason: String? = null,
) {
    /**
     * The iteration produced no text and no tool calls, or ended with a truncation
     * finish reason (`length`, or any reason that is neither a natural stop nor
     * content filtering). Both cases mean the response is incomplete and the
     * turn must not end normally on it.
     */
    val truncated: Boolean
        get() = when {
            finishReason == "content_filter" -> false
            finishReason == "length" -> true
            finishReason != null && finishReason != "stop" && finishReason != "tool_calls" -> true
            else -> text.isEmpty() && toolCalls.isEmpty()
        }
}

private const val WIND_DOWN_PROMPT =
    "The per-prompt tool iteration limit has been reached. Summarize what has been accomplished " +
            "so far and what remains to be done; do not call any tools."

private const val CONTINUE_TRUNCATED_PROMPT =
    "Your previous response was cut off before it was complete. Continue where you left off."

private const val CONTINUE_EMPTY_PROMPT =
    "Your previous response was empty. If the task is done, briefly say so; otherwise continue."

/**
 * Mints a fresh [`MessageId`] for one LLM iteration:the reasoning deltas and the
 * assistant text of the same iteration share one id so the client groups them into a
 * single message, while consecutive iterations get distinct ids. UUIDv7 (time-ordered,
 * RFC 9562) so ids sort chronologically.


 */
internal fun newMessageId(): MessageId = MessageId(UuidCreator.getTimeOrderedEpoch().toString())
