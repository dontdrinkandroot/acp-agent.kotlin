package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.tools.*

/**
 * Executes one streamed tool call: mode gating (a tool disabled in the current
 * mode is refused before permission or execution), unknown-tool handling, the
 * path-aware permission decision, execution and the tool-call updates emitted
 * to the client. All outcomes (failure, denial, result) are appended to the
 * history so the model sees them on the next iteration.
 */
@OptIn(UnstableApi::class)
internal class ToolCallExecutor(
    private val toolRegistry: ToolRegistry,
    private val state: SessionState,
    /**
     * Absolute read-trusted paths (`ACP_EXTRA_MOUNTS`): non-mutating,
     * path-scoped calls whose targets all lie inside one of them skip the
     * permission prompt. Empty by default, so the default stays prompt-gated.
     */
    private val trustedReadPaths: List<String> = emptyList(),
) {

    /**
     * The last *executed* call as raw wire data (tool name + the argument JSON
     * string exactly as streamed), the signature the repeat guard compares
     * against. Turn-scoped: cleared by [onTurnStart] at every prompt start, so
     * repeating an identical call in a later turn is always allowed. Denial
     * paths (disabled/unknown tool, permission denied, the repeat refusal
     * itself) never set it - a denied call made no observable change a repeat
     * could be blind to.
     *
     * Intermediate degenerate-loop guard for issue #39: a weak model repeating
     * the identical call gets an error result that names the repetition
     * instead of a fresh copy of an identical output. Deliberately conservative:
     * the raw string is compared verbatim, so a formatting variant of the same
     * parsed arguments (spacing, key order) is NOT refused - and the alternating
     * two-variant cycle from the #39 session stays unguarded (the full
     * per-signature fix tracks that issue).
     */
    private var lastExecutedSignature: RepeatSignature? = null

    /**
     * Discards the repeat guard's turn state. Called once per prompt turn
     * before the loop, so state cannot leak between turns: the guard only
     * ever compares calls within the turn it was armed for, whatever the
     * previous turn did (including a cancelled one) is discarded here.
     */
    fun onTurnStart() {
        lastExecutedSignature = null
    }

    /**
     * Runs one tool call against the given mode and [toolContext] (whose
     * client drives the permission prompts). Turn-scoped: the repeat guard
     * compares each call against the previously *executed* call of this turn
     * (see [lastExecutedSignature]); no other per-call state is held on the
     * instance, so the same executor can serve any session thread.
     */
    suspend fun execute(
        mode: SessionModeId,
        toolContext: ToolContext,
        emitter: FlowCollector<Event>,
        call: StreamToolCall,
    ) {
        val toolCallId = ToolCallId(call.id)

        toolRegistry.disabledInMode(call.name, mode)?.let { disabled ->
            val msg = disabledToolMessage(disabled, mode)
            emitDenied(
                emitter = emitter,
                toolCallId = toolCallId,
                title = "Disabled in current mode",
                message = msg,
            )
            return
        }

        val tool = toolRegistry.get(call.name)
        if (tool == null) {
            val msg = "Error: unknown tool \"${call.name}\""
            emitDenied(
                emitter = emitter,
                toolCallId = toolCallId,
                title = "Unknown tool",
                message = msg,
            )
            return
        }

        val arguments = parseArguments(call.arguments)

        if (lastExecutedSignature == RepeatSignature(call.name, call.arguments)) {
            emitDenied(
                emitter = emitter,
                toolCallId = toolCallId,
                title = "Repeated tool call",
                message = repeatedCallMessage(call.name, call.arguments),
            )
            return
        }

        val title = tool.title(arguments) ?: tool.name
        emitter.emit(
            Event.SessionUpdateEvent(
                SessionUpdate.ToolCall(
                    toolCallId = toolCallId,
                    title = title,
                    kind = tool.kind,
                    status = ToolCallStatus.IN_PROGRESS,
                    locations = toolLocations(tool, arguments),
                    rawInput = arguments,
                )
            )
        )

        val allowed = shouldAllow(tool, toolCallId, arguments, title, toolContext.client, toolContext)
        if (!allowed) {
            val msg = "Permission denied for tool ${tool.name}"
            emitDenied(
                emitter = emitter,
                toolCallId = toolCallId,
                title = title,
                message = msg,
                rawOutput = JsonPrimitive(msg),
            )
            return
        }

        val result = executeSafely("Tool ${tool.name} failed") {
            tool.execute(arguments, toolContext)
        }

        emitter.emit(
            Event.SessionUpdateEvent(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    title = title,
                    status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                    content = toolCallContent(result),
                    rawOutput = JsonPrimitive(result.text),
                )
            )
        )
        state.appendToolResult(call.id, Content.Text(result.text), result.isError)
        state.persist()
        lastExecutedSignature = RepeatSignature(call.name, call.arguments)
    }

    private suspend fun emitDenied(
        emitter: FlowCollector<Event>,
        toolCallId: ToolCallId,
        title: String,
        message: String,
        rawOutput: JsonPrimitive? = null,
    ) {
        emitter.emit(
            Event.SessionUpdateEvent(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    title = title,
                    kind = ToolKind.OTHER,
                    status = ToolCallStatus.FAILED,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text(message))),
                    rawOutput = rawOutput,
                )
            )
        )
        state.appendToolResult(toolCallId.value, Content.Text(message), isError = true)
        state.persist()
    }

    private suspend fun shouldAllow(
        tool: AgentTool,
        toolCallId: ToolCallId,
        arguments: JsonObject,
        title: String,
        client: ClientSessionOperations?,
        toolContext: ToolContext,
    ): Boolean {
        if (!permissionNeeded(toolContext.cwd, tool, arguments, trustedReadPaths)) return true
        if (client == null) return true
        state.permanentPermissions[tool.name]?.let { return it }
        val options = listOf(
            "allow_once" to PermissionOptionKind.ALLOW_ONCE,
            "allow_always" to PermissionOptionKind.ALLOW_ALWAYS,
            "reject_once" to PermissionOptionKind.REJECT_ONCE,
            "reject_always" to PermissionOptionKind.REJECT_ALWAYS,
        ).map { (id, kind) ->
            PermissionOption(
                PermissionOptionId(id),
                id.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } },
                kind,
            )
        }
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = toolCallId,
            title = title,
            kind = tool.kind,
            status = ToolCallStatus.IN_PROGRESS,
            locations = toolLocations(tool, arguments),
            rawInput = arguments,
        )
        return try {
            val response = client.requestPermissions(toolCall = update, permissions = options)
            when (val outcome = response.outcome) {
                is RequestPermissionOutcome.Selected -> {
                    when (outcome.optionId.value) {
                        "allow_always" -> state.permanentPermissions[tool.name] = true
                        "reject_always" -> state.permanentPermissions[tool.name] = false
                    }
                    outcome.optionId.value.startsWith("allow")
                }

                is RequestPermissionOutcome.Cancelled -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun disabledToolMessage(tool: AgentTool, mode: SessionModeId): String {
        val modes = tool.modes.joinToString(", ") { it.value }
        return "Error: tool \"${tool.name}\" is disabled in ${mode.value} mode (it is only available in: $modes) " +
                "and was not executed. It may have been available earlier in this conversation while a different mode " +
                "was active, but it is not available now. Do not attempt it again; ask the user to switch to one " +
                "of those modes to apply this action."
    }

    private fun toolCallContent(result: ToolResult): List<ToolCallContent> = buildList {
        add(ToolCallContent.Content(ContentBlock.Text(result.text)))
        result.diff?.let { add(ToolCallContent.Diff(it.path, it.newText, it.oldText)) }
    }

    /**
     * The model-facing refusal text: states the fact, identifies the repeated
     * call verbatim (raw arguments, exactly as the model emitted them) and
     * redirects to real progress. The verbatim arguments are the payload the
     * model itself wrote, so no formatting is lost on the way back.
     */
    private fun repeatedCallMessage(toolName: String, rawArguments: String): String =
        "Error: repeated tool call - the immediately preceding call was also \"$toolName\" with arguments " +
                "$rawArguments, and the tool produced a result for it that is already in the conversation. " +
                "Repeating an identical call cannot yield new information. Do not repeat this call; instead " +
                "use different arguments (e.g. read a different range) or proceed with the task."

    /**
     * The repeat guard's comparison key: the tool name plus the raw argument
     * string exactly as streamed. A data class so a call matches only when
     * both parts are byte-identical.
     */
    private data class RepeatSignature(val toolName: String, val rawArguments: String)
}

/**
 * Parses a tool-call argument JSON string into an object. Malformed or
 * non-object arguments degrade to `{"arguments": "<raw>"}` so a bad call still
 * produces a tool error instead of crashing the turn. `ToolCallExecutor`
 * parses exactly once per call and passes the object to title, permission and
 * execution alike, so the arguments a permission decision approved are
 * structurally the arguments the tool receives.
 */
internal fun parseArguments(arguments: String): JsonObject {
    return runCatching { ACPJson.parseToJsonElement(arguments) as? JsonObject }
        .getOrNull()
        ?: buildJsonObject { put("arguments", JsonPrimitive(arguments)) }
}

/**
 * File locations of a path-scoped tool call, driving the client's
 * "follow the agent" surface (tool_call creation, permission prompts and
 * load replay all carry them).
 */
internal fun toolLocations(tool: AgentTool, arguments: JsonObject): List<ToolCallLocation> =
    tool.targetPaths(arguments).map { ToolCallLocation(it) }
