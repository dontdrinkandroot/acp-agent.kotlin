package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
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
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.ToolResult

/**
 * Executes one streamed tool call: mode gating (a tool disabled in the current
 * mode is refused before permission or execution), unknown-tool handling, the
 * path-aware permission decision, execution and the tool-call updates emitted
 * to the client. All outcomes (failure, denial, result) are appended to the
 * history so the model sees them on the next iteration.
 */
@OptIn(UnstableApi::class)
internal class ToolCallExecutor(
    private val cwd: String,
    private val toolRegistry: ToolRegistry,
    private val state: SessionState,
) {

    /**
     * Runs one tool call against the given mode and [toolContext] (whose
     * client drives the permission prompts). Stateless: no per-call state is
     * held on the instance, so the same executor can serve any session thread.
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
        emit(
            emitter,
            Event.SessionUpdateEvent(
                SessionUpdate.ToolCall(
                    toolCallId = toolCallId,
                    title = tool.title(arguments) ?: tool.name,
                    kind = tool.kind,
                    status = ToolCallStatus.IN_PROGRESS,
                    locations = toolLocations(tool, arguments),
                    rawInput = arguments,
                )
            )
        )

        val allowed = shouldAllow(tool, toolCallId, arguments, toolContext.client)
        if (!allowed) {
            val msg = "Permission denied for tool ${tool.name}"
            emitDenied(
                emitter = emitter,
                toolCallId = toolCallId,
                title = tool.title(arguments) ?: tool.name,
                message = msg,
                rawOutput = JsonPrimitive(msg),
            )
            return
        }

        val result = try {
            tool.execute(parseArguments(call.arguments), toolContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("Tool ${tool.name} failed: ${e.message}", true)
        }

        emit(
            emitter,
            Event.SessionUpdateEvent(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = toolCallId,
                    title = tool.title(arguments) ?: tool.name,
                    status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                    content = toolCallContent(result),
                    rawOutput = JsonPrimitive(result.text),
                )
            )
        )
        state.appendToHistory(OpenAIMessage.Tool(Content.Text(result.text), toolCallId = call.id))
    }

    private suspend fun emit(emitter: FlowCollector<Event>, event: Event) {
        emitter.emit(event)
    }

    private suspend fun emitDenied(
        emitter: FlowCollector<Event>,
        toolCallId: ToolCallId,
        title: String,
        message: String,
        rawOutput: JsonPrimitive? = null,
    ) {
        emit(
            emitter,
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
        state.appendToHistory(OpenAIMessage.Tool(Content.Text(message), toolCallId = toolCallId.value))
    }

    private suspend fun shouldAllow(
        tool: AgentTool,
        toolCallId: ToolCallId,
        arguments: JsonObject,
        client: ClientSessionOperations?,
    ): Boolean {
        if (!permissionNeeded(cwd, tool, arguments)) return true
        if (client == null) return true
        state.permanentPermissions[tool.name]?.let { return it }
        val options = listOf(
            PermissionOption(PermissionOptionId("allow_once"), "Allow once", PermissionOptionKind.ALLOW_ONCE),
            PermissionOption(
                PermissionOptionId("allow_always"),
                "Always allow",
                PermissionOptionKind.ALLOW_ALWAYS
            ),
            PermissionOption(
                PermissionOptionId("reject_once"),
                "Reject once",
                PermissionOptionKind.REJECT_ONCE
            ),
            PermissionOption(
                PermissionOptionId("reject_always"),
                "Always reject",
                PermissionOptionKind.REJECT_ALWAYS
            ),
        )
        val update = SessionUpdate.ToolCallUpdate(
            toolCallId = toolCallId,
            title = tool.title(arguments) ?: tool.name,
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
}

/**
 * Parses a tool-call argument JSON string into an object. Malformed or
 * non-object arguments degrade to `{"arguments": "<raw>"}` so a bad call still
 * produces a tool error instead of crashing the turn.
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
