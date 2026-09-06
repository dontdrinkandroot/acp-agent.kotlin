package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.*
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.github.f4b6a3.uuid.UuidCreator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import net.dontdrinkandroot.acpagent.llm.ChatCompleter
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry

/**
 * Runs one prompt turn: the LLM tool-calling loop capped at [maxTurnRequests]
 * iterations and, when the whole budget is consumed while the model kept
 * requesting tools, a final text-only synthesis pass. Streamed reasoning and
 * reply chunks are relayed immediately; tool-call deltas are accumulated and
 * executed sequentially by [ToolCallExecutor].
 */
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
        while (iterations < maxTurnRequests) {
            iterations++
            val messageId = newMessageId()
            val messages = buildList {
                add(OpenAIMessage.System(Content.Text(systemPrompt.build(mode, instructions))))
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

            if (iteration.toolCalls.isEmpty()) {
                state.appendToHistory(OpenAIMessage.Assistant(content = Content.Text(iteration.text)))
                state.persist()
                emitUsageUpdate(usage)
                emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
                return
            }

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

        // The turn has consumed the whole tool-iteration budget while the model kept
        // requesting tool calls. Run one final text-only synthesis pass (tools omitted, so
        // no tool call is possible) so the user gets a summary of what was done and what
        // remains instead of an abrupt stop.
        val windDownMessageId = newMessageId()
        val windDownMessages = buildList {
            add(OpenAIMessage.System(Content.Text(systemPrompt.build(mode, instructions))))
            addAll(state.historySnapshot)
            add(OpenAIMessage.User(Content.Text(WIND_DOWN_PROMPT)))
        }
        val windDown = streamChat(emitter, windDownMessages, emptyList(), windDownMessageId)
        usage = windDown.usage ?: usage

        state.appendToHistory(OpenAIMessage.Assistant(content = Content.Text(windDown.text)))
        state.persist()
        emitUsageUpdate(usage)
        emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.MAX_TURN_REQUESTS)))
    }

    /**
     * Streams one chat completion and relays its deltas: reasoning as
     * thought chunks, text as message chunks (accumulating the reply) and
     * tool-call deltas merged into a per-index accumulator.
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
        chatCompleter.chatCompletion(
            messages = messages,
            tools = tools,
            reasoning = sessionConfigOptions.effectiveReasoning(),
            model = state.currentModel,
            provider = providerRouting?.providerFor(state.currentModel),
        ).collect { chunk ->
            chunk.usage?.let { usage = it }
            chunk.choices.firstOrNull()?.let { choice ->
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
)

private const val WIND_DOWN_PROMPT =
    "The per-prompt tool iteration limit has been reached. Summarize what has been accomplished " +
            "so far and what remains to be done; do not call any tools."

/**
 * Mints a fresh [`MessageId`] for one LLM iteration: the reasoning deltas and the
 * assistant text of the same iteration share one id so the client groups them into a
 * single message, while consecutive iterations get distinct ids. UUIDv7 (time-ordered,
 * RFC 9562) so ids sort chronologically.
 */
internal fun newMessageId(): MessageId = MessageId(UuidCreator.getTimeOrderedEpoch().toString())
