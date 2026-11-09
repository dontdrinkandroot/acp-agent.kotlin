package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.github.f4b6a3.uuid.UuidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.llm.*
import net.dontdrinkandroot.acpagent.llm.models.FunctionTool
import net.dontdrinkandroot.acpagent.llm.models.ResponsesResponse
import net.dontdrinkandroot.acpagent.llm.models.ResponsesUsage
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry

private val logger = KotlinLogging.logger {}

/**
 * Runs one prompt turn over the Responses API: the tool-calling loop capped
 * at [maxTurnRequests] iterations and, when the whole budget is consumed while
 * the model kept requesting tools, a final text-only synthesis pass. Streamed
 * reasoning and reply chunks are relayed immediately; `function_call` items are
 * accumulated and executed sequentially by [ToolCallExecutor].
 *
 * **Reasoning continuity (issue #75)**: every output item of an iteration
 * (reasoning, assistant message, function calls) is appended to the history in
 * production order, so the next iteration's request - built 1:1 from the
 * history - carries the reasoning that produced the previous calls. The items
 * are live-only: they are dropped when the turn ends and are filtered out of
 * the persisted record, so the next prompt starts from a clean reasoning state
 * and no chain-of-thought reaches disk.
 *
 * Truncated or empty completions (`incomplete` with a token-limit reason, or an
 * iteration with no text and no tool calls) are recovered from once per turn:
 * the partial text (if any) is kept, all tool calls are dropped (never
 * executed - the model re-issues them after the continuation), a synthetic user
 * "continue" prompt is appended, and the loop runs one more iteration. A second
 * truncation ends the turn with an honest text note instead of an error dialog.
 *
 * A `content_filter` completion is not retried: the turn ends immediately with
 * the partial text and a notice. Transport-level failures (a stream ending
 * without `response.done`/`[DONE]`, a `response.failed` event) remain loud
 * `LlmException`s. Abnormal cases are logged to stderr with the full unfiltered
 * completion so the problem stays visible.
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
        toolCallExecutor.onTurnStart()
        try {
            runTurn(emitter, mode, instructions, toolContext)
        } finally {
            // Reasoning is live-only for the running turn (issue #75): drop it on
            // every exit, including cancellation, so a cancelled turn's reasoning
            // can never be replayed into the next prompt.
            state.dropLiveReasoning()
        }
    }

    private suspend fun runTurn(
        emitter: FlowCollector<Event>,
        mode: SessionModeId,
        instructions: AgentsInstructions?,
        toolContext: ToolContext,
    ) {
        var usage: ResponsesUsage? = null
        var iterations = 0
        var continuationUsed = false
        var continuationPending = false
        while (iterations < maxTurnRequests || continuationPending) {
            continuationPending = false
            iterations++
            val messageId = newMessageId()
            val tools = toolRegistry.availableForMode(mode).map { tool ->
                FunctionTool(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters,
                )
            }

            val iteration = streamIteration(emitter, tools, messageId, instructions)
            usage = iteration.usage ?: usage

            when {
                iteration.truncated && !continuationUsed -> {
                    continuationUsed = true
                    continuationPending = true
                    val partial = iteration.text.takeIf { it.isNotEmpty() }
                    logAbnormalRetry(iteration, iterations)
                    // All tool calls are dropped - including complete ones - so a
                    // truncated call is never executed and the model re-issues them.
                    if (partial != null) {
                        state.appendToHistory(assistantMessageItem(partial))
                    }
                    state.appendToHistory(
                        userMessageItem(if (partial == null) CONTINUE_EMPTY_PROMPT else CONTINUE_TRUNCATED_PROMPT)
                    )
                }

                iteration.truncated -> {
                    logAbnormalEnd(iteration, "survived the retry")
                    endTurn(emitter, usage, INTERRUPTED_NOTE, emitChunk = true)
                    return
                }

                iteration.contentFiltered -> {
                    logger.warn { "Response blocked by content filtering; ending the turn" }
                    val partial = iteration.text.takeIf { it.isNotEmpty() }
                    if (partial != null) {
                        state.appendToHistory(assistantMessageItem(partial))
                    }
                    endTurn(emitter, usage, "The response was blocked by content filtering.", emitChunk = true)
                    return
                }

                iteration.functionCalls.isEmpty() -> {
                    // The text was already streamed live by the delta relay, so
                    // no chunk is emitted here - only history + the turn end.
                    endTurn(emitter, usage, iteration.text, emitChunk = false)
                    return
                }

                else -> {
                    // Capture every output item verbatim, in production order,
                    // so the next iteration replays the reasoning that produced
                    // these calls (issue #75).
                    iteration.outputItems.forEach { state.appendToHistory(HistoryItem.Captured(it)) }
                    for (call in iteration.functionCalls) {
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
        state.appendToHistory(userMessageItem(WIND_DOWN_PROMPT))
        val windDown = streamIteration(emitter, emptyList(), windDownMessageId, instructions)
        usage = windDown.usage ?: usage

        if (windDown.truncated) {
            logAbnormalEnd(windDown, "in the wind-down pass")
            endTurn(emitter, usage, INTERRUPTED_NOTE, emitChunk = true, stopReason = StopReason.MAX_TURN_REQUESTS)
            return
        }

        endTurn(emitter, usage, windDown.text, emitChunk = false, stopReason = StopReason.MAX_TURN_REQUESTS)
    }

    /**
     * Ends a turn from one of the terminal loop branches: appends the final
     * assistant message to the history, persists the turn's end state, emits
     * the remaining message content, reports the usage indicator and emits the
     * prompt response - the exit ceremony every terminal branch must run, in
     * one place.
     *
     * The order is the turn's delivery invariant: content first, then the
     * usage indicator, then the response. The chunk is emitted only when the
     * text was NOT already streamed live by the delta relay (the synthesized
     * notes); re-emitting streamed text would duplicate it in the client's
     * message (pinned by the e2e messageId-per-iteration contract).
     *
     * Persisting before delivering keeps the record durable even when a client
     * dies mid-ceremony; live reasoning is already gone - `run`'s `finally`
     * drops it on every exit path, so a persisted turn never carries it either
     * (issue #75).
     */
    private suspend fun endTurn(
        emitter: FlowCollector<Event>,
        usage: ResponsesUsage?,
        finalText: String,
        emitChunk: Boolean,
        stopReason: StopReason = StopReason.END_TURN,
    ) {
        state.appendToHistory(assistantMessageItem(finalText))
        state.persist()
        if (emitChunk && finalText.isNotEmpty()) {
            emitTextChunk(emitter, finalText, newMessageId())
        }
        emitUsageUpdate(usage)
        emitter.emit(Event.PromptResponseEvent(PromptResponse(stopReason = stopReason)))
    }

    /**
     * Streams one model iteration and relays its deltas: reasoning as thought
     * chunks, text as message chunks, function calls accumulated by call id.
     * The output items the response reports are captured verbatim for the
     * history (reasoning continuity), the usage comes from `response.done`.
     */
    private suspend fun streamIteration(
        emitter: FlowCollector<Event>,
        tools: List<FunctionTool>,
        messageId: MessageId,
        instructions: AgentsInstructions?,
    ): StreamedIteration {
        val rawContent = StringBuilder()
        var contentSeen = false
        val rawReasoning = StringBuilder()
        var reasoningSeen = false
        val reasoningItemAccum = mutableMapOf<Int, JsonObject>()
        val messageItemAccum = mutableMapOf<Int, JsonObject>()
        val functionCallAccum = mutableMapOf<Int, MutableFunctionCall>()
        val orderedOutput = mutableListOf<JsonObject>()
        var usage: ResponsesUsage? = null
        var status: String? = null
        var incompleteReason: String? = null
        var contentFiltered = false
        val effectiveProvider = when (val slug = sessionConfigOptions.effectiveProviderSlug()) {
            "" -> providerRouting?.providerFor(state.currentModel)
            else -> ProviderPreferences(order = listOf(slug))
        }

        val systemMessage = HistoryItem.SystemMessage(systemPrompt.build(instructions)).toWire()
        val input = listOf(systemMessage) + state.historyWireSnapshot

        chatCompleter.responsesStream(
            input = input,
            tools = tools,
            reasoning = sessionConfigOptions.effectiveReasoning(),
            model = state.currentModel,
            provider = effectiveProvider,
        ).collect { event ->
            when (event.stringField("type")) {
                "response.created" -> Unit

                "response.output_item.added", "response.output_item.done" -> {
                    val index = event.intField("output_index") ?: 0
                    val item = event.jsonObjectOrNull("item") ?: return@collect
                    accumulateOutputItem(item, index, reasoningItemAccum, messageItemAccum, functionCallAccum)
                }

                "response.reasoning.delta", "response.reasoning_summary_text.delta",
                "response.reasoning_text.delta" -> {
                    relayDeltas(
                        emitter,
                        delta = event.stringField("delta"),
                        raw = rawReasoning,
                        mark = { reasoningSeen = true },
                    ) { text -> SessionUpdate.AgentThoughtChunk(ContentBlock.Text(text), messageId) }
                }

                "response.content_part.delta", "response.output_text.delta" -> {
                    relayDeltas(
                        emitter,
                        delta = event.stringField("delta"),
                        raw = rawContent,
                        mark = { contentSeen = true },
                    ) { text -> SessionUpdate.AgentMessageChunk(ContentBlock.Text(text), messageId) }
                }

                "response.function_call_arguments.delta" -> {
                    val index = event.intField("output_index") ?: 0
                    event.stringField("delta")?.let { delta ->
                        functionCallAccum.getOrPut(index) { MutableFunctionCall() }.arguments += delta
                    }
                }

                // `response.done` is deliberately not in the transport's terminal
                // set (the documented name does not occur on the live endpoint -
                // verified 2026-11-06); it is still read here so a provider that
                // emits it alongside a terminal event contributes usage/status.
                "response.completed", "response.incomplete", "response.done" -> {
                    val response = event.jsonObjectOrNull("response") ?: return@collect
                    val decoded = decodeResponse(response)
                    usage = decoded.usage ?: usage
                    status = decoded.status
                    incompleteReason = decoded.incompleteDetails?.reason
                    // The Responses API reports filtering as `status: "incomplete"`
                    // with `incomplete_details.reason: "content_filter"` - not as
                    // an error object (`error.code` carries transport categories
                    // like `invalid_prompt`/`server_error` only).
                    contentFiltered = incompleteReason.equals(CONTENT_FILTER_REASON, ignoreCase = true)
                }

                else -> Unit // unknown/irrelevant event types are skipped
            }
        }

        // The output items in production order: reasoning items first (as the
        // provider produced them), then the assistant message and the calls.
        // A message item the provider reported is captured as-is; the rebuilt
        // fallback only covers providers that stream deltas without an item.
        reasoningItemAccum.toSortedMap().values.forEach { orderedOutput.add(it) }
        val messageItem = messageItemAccum.toSortedMap().values.firstOrNull()
            ?: rawContent.toString().takeIf { contentSeen && it.isNotEmpty() }
                ?.let { assistantMessageItem(it).raw }
        messageItem?.let { orderedOutput.add(it) }
        val calls = functionCallAccum.toSortedMap().values.map { it.toStreamToolCall() }
        calls.forEach { call ->
            orderedOutput.add(functionCallItem(call.id, call.name, call.arguments).raw)
        }

        return StreamedIteration(
            rawContent = rawContent.toString().takeIf { contentSeen },
            functionCalls = calls,
            usage = usage,
            status = status,
            incompleteReason = incompleteReason,
            contentFiltered = contentFiltered,
            rawReasoning = rawReasoning.toString().takeIf { reasoningSeen },
            outputItems = orderedOutput,
        )
    }

    /**
     * Files one streamed output item under its `output_index`: reasoning and
     * assistant message items are kept as the provider's own object (replayed
     * verbatim, so `id`, `status` and `annotations` stay intact), function
     * calls are merged into their per-index accumulator.
     */
    private fun accumulateOutputItem(
        item: JsonObject,
        index: Int,
        reasoningItems: MutableMap<Int, JsonObject>,
        messageItems: MutableMap<Int, JsonObject>,
        functionCalls: MutableMap<Int, MutableFunctionCall>,
    ) {
        when {
            item.isReasoning -> reasoningItems[index] = item
            item.isAssistantMessage -> messageItems[index] = item
            item.isFunctionCall -> functionCalls.getOrPut(index) { MutableFunctionCall() }.applyItem(item)
        }
    }

    private fun decodeResponse(response: JsonObject): ResponsesResponse =
        llmWireJson.decodeFromJsonElement(ResponsesResponse.serializer(), response)

    /**
     * Reports the context window usage of the last completed model call. The
     * update is skipped when the model reports no usage or context length, so
     * the client keeps its previous indicator.
     */
    private suspend fun emitUsageUpdate(usage: ResponsesUsage?) {
        val client = clientOrNull() ?: return
        val used = usage?.inputTokens ?: return
        if (used <= 0) return
        val size = models.forModel(state.currentModel)?.contextLength ?: return
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

    /**
     * Relays one delta stream (reasoning or reply): accumulates the raw text
     * (empty deltas included, for the verbatim abnormal-completion log) and
     * emits every non-empty delta as a session update built by [update].
     */
    private suspend fun relayDeltas(
        emitter: FlowCollector<Event>,
        delta: String?,
        raw: StringBuilder,
        mark: () -> Unit,
        update: (String) -> SessionUpdate,
    ) {
        delta?.let { text ->
            mark()
            raw.append(text)
            if (text.isNotEmpty()) {
                emitter.emit(Event.SessionUpdateEvent(update(text)))
            }
        }
    }

    private class MutableFunctionCall {
        var callId: String = ""
        var name: String = ""
        var arguments: String = ""

        fun applyItem(item: JsonObject) {
            item.stringField("call_id")?.takeIf { it.isNotBlank() }?.let { callId = it }
            item.stringField("name")?.takeIf { it.isNotBlank() }?.let { name = it }
            item.stringField("arguments")?.takeIf { it.isNotEmpty() && arguments.isEmpty() }?.let { arguments = it }
        }

        fun toStreamToolCall() = StreamToolCall(callId, name, arguments)
    }
}

/**
 * One streamed model iteration: the accumulated unfiltered deltas, the
 * function calls, the output items to capture and the completion state.
 * [rawContent]/[rawReasoning] are null when the provider sent no such delta at
 * all, "" when it sent only empty ones, and the concatenated text otherwise, so
 * an empty-but-present field stays distinguishable from an absent one.
 */
internal data class StreamedIteration(
    val rawContent: String?,
    val functionCalls: List<StreamToolCall>,
    val usage: ResponsesUsage?,
    val status: String? = null,
    val incompleteReason: String? = null,
    val contentFiltered: Boolean = false,
    val rawReasoning: String? = null,
    val outputItems: List<JsonObject> = emptyList(),
) {
    val text: String
        get() = rawContent.orEmpty()

    /**
     * The iteration was cut short: `incomplete` with a token-limit reason, or
     * an iteration with no usable output at all (issue #16: an undocumented
     * status with usable output is a natural completion).
     */
    val truncated: Boolean
        get() = when {
            contentFiltered -> false
            incompleteReason?.lowercase() in TOKEN_LIMIT_REASONS -> true
            status == "incomplete" -> true
            else -> nothingUsable
        }

    private val nothingUsable: Boolean
        get() = text.isEmpty() && functionCalls.isEmpty()

    fun renderLog(): String = buildString {
        append("status=").append(status ?: "<none>")
        append(" incompleteReason=").append(incompleteReason ?: "<none>")
        append(" content=").append(rawContent.asLogValue())
        append(" reasoning=").append(rawReasoning.asLogValue())
        append(" functionCalls=").append(functionCalls)
    }
}

private fun logAbnormalRetry(iteration: StreamedIteration, iterations: Int) {
    logger.warn {
        "Truncated/empty completion (iteration $iterations): continuing with one retry: ${iteration.renderLog()}"
    }
}

private fun logAbnormalEnd(iteration: StreamedIteration, context: String) {
    logger.error {
        "Truncated/empty completion $context: ${iteration.renderLog()}"
    }
}

private fun String?.asLogValue(): String = when {
    this == null -> "<absent>"
    isEmpty() -> "<empty>"
    else -> replace("\r", "\\r").replace("\n", "\\n")
}

private const val WIND_DOWN_PROMPT =
    "The per-prompt tool iteration limit has been reached. Summarize what has been accomplished " +
            "so far and what remains to be done; do not call any tools."

/** Shown to the user when a completion was cut off and no retry is left. */
private const val INTERRUPTED_NOTE =
    "The response was interrupted before completion; the task may need a new prompt."

private const val CONTINUE_TRUNCATED_PROMPT =
    "Your previous response was cut off before it was complete. Continue where you left off."

private const val CONTINUE_EMPTY_PROMPT =
    "Your previous response was empty. If the task is done, briefly say so; otherwise continue."

/** Completion reasons that mean the completion was cut off at a token limit. */
private val TOKEN_LIMIT_REASONS = setOf("max_output_tokens")

/** The `incomplete_details.reason` the Responses API reports for a filtered completion. */
private const val CONTENT_FILTER_REASON = "content_filter"

/**
 * Mints a fresh [`MessageId`] for one LLM iteration: the reasoning deltas and the
 * assistant text of the same iteration share one id so the client groups them into a
 * single message, while consecutive iterations get distinct ids. UUIDv7 (time-ordered,
 * RFC 9562) so ids sort chronologically.
 */
internal fun newMessageId(): MessageId = MessageId(UuidCreator.getTimeOrderedEpoch().toString())
