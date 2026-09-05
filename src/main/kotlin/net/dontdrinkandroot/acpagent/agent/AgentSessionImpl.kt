package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.*
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.agent.clientInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.rpc.ACPJson
import com.github.f4b6a3.uuid.UuidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.BuildInfo
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.*
import kotlin.concurrent.Volatile

internal class AgentSessionImpl(
    override val sessionId: SessionId,
    private val cwd: String,
    private val toolRegistry: ToolRegistry,
    private val config: Config,
    private val llm: LlmClient,
    private val providerRouting: ProviderRouting? = null,
    private val todayProvider: () -> String,
    private val closeResources: suspend () -> Unit = {},
    private val sessionStore: SessionStore? = null,
    private val restored: SessionRecord? = null,
    private val replayOnInitialize: Boolean = false,
    private val models: List<OpenRouterModel> = emptyList(),
) : AgentSession {

    private val logger = KotlinLogging.logger {}

    private val history = mutableListOf<OpenAIMessage>().apply { restored?.let { addAll(it.history) } }
    private val historyLock = Any()
    private var plan: List<PlanEntry> = restored?.plan ?: emptyList()
    private val permanentPermissions = mutableMapOf<String, Boolean>()

    /**
     * Serializes the record lifecycle: persist (deleted check through save) and
     * delete (mark deleted through record removal) share this mutex, so a delete
     * cannot interleave with an in-flight persist and resurrect the record.
     */
    private val persistMutex = Mutex()

    /**
     * Mints a fresh [`MessageId`] for one LLM iteration: the reasoning deltas and the
     * assistant text of the same iteration share one id so the client groups them into a
     * single message, while consecutive iterations get distinct ids. UUIDv7 (time-ordered,
     * RFC 9562) so ids sort chronologically.
     */
    private fun newMessageId(): MessageId = MessageId(UuidCreator.getTimeOrderedEpoch().toString())

    @Volatile
    private var deleted = false

    private var title: String? = restored?.title?.takeIf { it.isNotEmpty() }

    /**
     * A restored session starts in its persisted mode, which is also reported
     * as the default mode of the restored session.
     */
    private val initialMode: SessionModeId = restoredModeOrDefault()

    @Volatile
    private var currentMode: SessionModeId = initialMode

    private fun restoredModeOrDefault(): SessionModeId {
        val restoredMode = restored?.mode?.let { SessionModeId(it) }
            ?.takeIf { candidate -> candidate == MODE_BUILD || candidate == MODE_PLAN || candidate == MODE_BASH }
        return restoredMode ?: DEFAULT_MODE
    }

    @Volatile
    private var currentModel: String = restored?.model?.takeIf { it.isNotBlank() } ?: config.openRouterModel

    /**
     * The selected reasoning effort ("" = not yet chosen; the effective
     * effort falls back to the model's default, "none" = reasoning off).
     */
    private var reasoningSelection: String = restored?.reasoning?.takeIf { it.isNotBlank() } ?: ""

    private fun modelInfo(): OpenRouterModel? = models.firstOrNull { it.id == currentModel }

    private fun reasoningOptionState(): ReasoningSelector? {
        val capability = modelInfo()?.reasoning ?: return null
        val options = (capability.supportedEfforts?.takeIf { it.isNotEmpty() } ?: DEFAULT_REASONING_LEVELS)
            .map { level -> ReasoningOption(level, reasoningEffortName(level)) }
            .toMutableList()
        if (!capability.mandatory) {
            options += ReasoningOption(REASONING_OFF, "Off", "Disable reasoning; falls back to the model default")
        }
        var current = reasoningSelection
        if (options.none { it.value == current }) {
            current = capability.defaultEffort ?: ""
            if (options.none { it.value == current }) {
                current = REASONING_OFF
                if (options.none { it.value == current }) {
                    current = options.first().value
                }
            }
        }
        return ReasoningSelector(options, current)
    }

    /**
     * The reasoning effort to send on the next chat request: `null` when
     * reasoning is off or unsupported (the request field is omitted).
     */
    private fun effectiveReasoning(): String? {
        val selector = reasoningOptionState() ?: return null
        return selector.current.takeIf { it != REASONING_OFF }
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
        val size = modelInfo()?.contextLength ?: return
        if (size <= 0) return
        client.notify(SessionUpdate.UsageUpdate(used = used.toLong(), size = size.toLong()))
    }

    override val availableModes: List<SessionMode> = listOf(
        SessionMode(MODE_BUILD, "Build", "Read and modify files to implement the task"),
        SessionMode(MODE_PLAN, "Plan", "Read-only: research the code and present an implementation plan"),
        SessionMode(MODE_BASH, "Bash", "Build plus a bash tool; every command asks the user for permission"),
    )

    override val defaultMode: SessionModeId = initialMode

    @OptIn(UnstableApi::class)
    override val configOptions: List<SessionConfigOption>
        get() {
            val options = mutableListOf<SessionConfigOption>(
                SessionConfigOption.select(
                    id = "mode",
                    name = "Session Mode",
                    currentValue = currentMode.value,
                    options = SessionConfigSelectOptions.Flat(
                        availableModes.map { mode ->
                            SessionConfigSelectOption(
                                value = SessionConfigValueId(mode.id.value),
                                name = mode.name,
                                description = mode.description,
                            )
                        }
                    ),
                    description = "Build modifies files; Plan is read-only; Bash is build plus a permission-gated shell",
                    category = SessionConfigOptionCategory.MODE,
                )
            )
            if (models.isNotEmpty()) {
                options += SessionConfigOption.select(
                    id = "model",
                    name = "Model",
                    currentValue = currentModel,
                    options = SessionConfigSelectOptions.Flat(
                        models.map { model ->
                            SessionConfigSelectOption(
                                value = SessionConfigValueId(model.id),
                                name = model.name ?: model.id,
                                description = model.description,
                            )
                        }
                    ),
                    description = "OpenRouter model used for this session",
                    category = SessionConfigOptionCategory.MODEL,
                )
            }
            val reasoningSelector = reasoningOptionState()
            if (reasoningSelector != null) {
                options += SessionConfigOption.select(
                    id = "reasoning",
                    name = "Reasoning",
                    currentValue = reasoningSelector.current,
                    options = SessionConfigSelectOptions.Flat(
                        reasoningSelector.options.map { option ->
                            SessionConfigSelectOption(
                                value = SessionConfigValueId(option.value),
                                name = option.name,
                                description = option.description,
                            )
                        }
                    ),
                    description = "Reasoning effort applied to the selected model",
                    category = SessionConfigOptionCategory.THOUGHT_LEVEL,
                )
            }
            return options
        }

    @OptIn(UnstableApi::class)
    override val availableModels: List<ModelInfo>
        get() = models.map { model ->
            ModelInfo(
                modelId = ModelId(model.id),
                name = model.name ?: model.id,
                description = model.description,
            )
        }

    @OptIn(UnstableApi::class)
    override val defaultModel: ModelId
        get() = ModelId(currentModel)

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        applyMode(modeId)
        notifyModeState()
        persistSession()
        return SetSessionModeResponse()
    }

    @OptIn(UnstableApi::class)
    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?
    ): SetSessionConfigOptionResponse {
        when (configId.value) {
            "mode" -> {
                val modeValue = value as? SessionConfigOptionValue.StringValue
                    ?: jsonRpcInvalidParams("config option \"mode\" expects a string value")
                applyMode(SessionModeId(modeValue.value))
            }

            "model" -> {
                val modelValue = value as? SessionConfigOptionValue.StringValue
                    ?: jsonRpcInvalidParams("config option \"model\" expects a string value")
                applyModel(modelValue.value)
            }

            "reasoning" -> {
                val reasoningValue = value as? SessionConfigOptionValue.StringValue
                    ?: jsonRpcInvalidParams("config option \"reasoning\" expects a string value")
                applyReasoning(reasoningValue.value)
            }

            else -> jsonRpcInvalidParams("unknown config option \"${configId.value}\"")
        }
        notifyModeState()
        persistSession()
        return SetSessionConfigOptionResponse(configOptions)
    }

    @OptIn(UnstableApi::class)
    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        applyModel(modelId.value)
        notifyModeState()
        persistSession()
        return SetSessionModelResponse()
    }

    private fun applyModel(modelId: String) {
        if (modelId.isBlank()) jsonRpcInvalidParams("model id must not be empty")
        currentModel = modelId
        reasoningSelection = ""
    }

    private fun applyReasoning(value: String) {
        val selector = reasoningOptionState()
            ?: jsonRpcInvalidParams("model \"$currentModel\" does not expose a reasoning option")
        if (selector.options.none { it.value == value }) {
            jsonRpcInvalidParams("unknown reasoning value \"$value\"")
        }
        reasoningSelection = value
    }

    private fun applyMode(modeId: SessionModeId) {
        if (availableModes.none { it.id == modeId }) {
            jsonRpcInvalidParams("unknown mode \"${modeId.value}\"")
        }
        currentMode = modeId
    }

    @OptIn(UnstableApi::class)
    private suspend fun notifyModeState() {
        val client = runCatching { currentCoroutineContext().client }.getOrNull() ?: return
        client.notify(SessionUpdate.CurrentModeUpdate(currentMode))
        client.notify(SessionUpdate.ConfigOptionUpdate(configOptions))
    }

    /**
     * Replays a restored conversation to the client after `session/load`. The
     * SDK runs this hook inside the session context (client operations
     * available), right after the load response has been dispatched.
     */
    override suspend fun postInitialize() {
        if (!replayOnInitialize) return
        val client = runCatching { currentCoroutineContext().client }.getOrNull() ?: return
        replayHistory().forEach { client.notify(it) }
    }

    private fun replayHistory(): List<SessionUpdate> {
        val updates = mutableListOf<SessionUpdate>()
        synchronized(historyLock) {
            history.forEach { message ->
                when (message) {
                    is OpenAIMessage.User ->
                        message.content.textOrNull()?.takeIf { it.isNotEmpty() }?.let {
                            updates += SessionUpdate.UserMessageChunk(ContentBlock.Text(it), newMessageId())
                        }

                    is OpenAIMessage.Assistant -> {
                        message.content.textOrNull()?.takeIf { it.isNotEmpty() }?.let {
                            updates += SessionUpdate.AgentMessageChunk(ContentBlock.Text(it), newMessageId())
                        }
                        message.toolCalls.orEmpty().forEach { call ->
                            val toolName = call.function?.name ?: ""
                            val args = parseArguments(call.function?.arguments ?: "{}")
                            val tool = toolRegistry.get(toolName)
                            updates += SessionUpdate.ToolCall(
                                toolCallId = ToolCallId(call.id),
                                title = tool?.title(args) ?: toolName,
                                kind = tool?.kind ?: ToolKind.OTHER,
                                status = ToolCallStatus.PENDING,
                                rawInput = args,
                            )
                        }
                    }

                    is OpenAIMessage.Tool -> updates += SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId(message.toolCallId),
                        status = ToolCallStatus.COMPLETED,
                        content = listOf(
                            ToolCallContent.Content(ContentBlock.Text(message.content.textOrNull().orEmpty()))
                        ),
                    )

                    else -> Unit
                }
            }
            plan.takeIf { it.isNotEmpty() }?.let { updates += SessionUpdate.PlanUpdate(it) }
        }
        return updates
    }

    private fun Content?.textOrNull(): String? = this?.text()

    /**
     * Stores the execution plan (for persistence and replay) and forwards it
     * to the client as a plan update.
     */
    private suspend fun setPlan(
        entries: List<PlanEntry>,
        client: com.agentclientprotocol.common.ClientSessionOperations?
    ) {
        synchronized(historyLock) { plan = entries }
        client?.notify(SessionUpdate.PlanUpdate(entries))
    }

    private fun appendToHistory(message: OpenAIMessage) {
        synchronized(historyLock) { history.add(message) }
    }

    private fun historySnapshot(): List<OpenAIMessage> = synchronized(historyLock) { history.toList() }

    /**
     * Writes the session record to disk. Failures are logged to stderr but
     * never propagate, so persistence never blocks or fails session work.
     */
    private suspend fun persistSession() {
        val store = sessionStore ?: return
        persistMutex.withLock {
            if (deleted) return
            runCatching { store.save(buildRecord()) }
                .onFailure { logger.warn(it) { "Failed to persist session ${sessionId.value}" } }
        }
    }

    /**
     * Flags the session as deleted and removes its record under the persist
     * mutex, so no in-flight persist can write the file back afterwards.
     */
    internal suspend fun delete() {
        persistMutex.withLock {
            deleted = true
            val store = sessionStore ?: return@withLock
            runCatching { store.delete(sessionId.value) }
                .onFailure { logger.warn(it) { "Failed to delete session record ${sessionId.value}" } }
        }
        closeResources()
    }

    private fun buildRecord(): SessionRecord {
        val snapshot = historySnapshot()
        val recordTitle = title
            ?: deriveTitle(snapshot)?.also { title = it }
            ?: ""
        return SessionRecord(
            sessionId = sessionId.value,
            cwd = cwd,
            mode = currentMode.value,
            title = recordTitle,
            updatedAt = System.currentTimeMillis(),
            history = snapshot,
            model = currentModel,
            reasoning = reasoningSelection,
            plan = synchronized(historyLock) { plan },
        )
    }

    private fun deriveTitle(history: List<OpenAIMessage>): String? =
        history.filterIsInstance<OpenAIMessage.User>()
            .firstOrNull()
            ?.content
            ?.textOrNull()
            ?.trim()
            ?.let { trimmed -> truncatedTitle(trimmed.ifEmpty { "(empty message)" }) }

    private fun truncatedTitle(title: String): String =
        if (title.codePointCount(0, title.length) <= MAX_TITLE_LENGTH) title
        else title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_LENGTH)) + "…"

    private fun systemPrompt(mode: SessionModeId, instructions: AgentsInstructions?): String = buildString {
        appendLine("You are acp-agent, a fast and compact coding agent embedded in the user's IDE via the Agent Client Protocol.")
        appendLine("Agent build: ${BuildInfo.commit}")
        appendLine()
        appendLine("Session working directory: $cwd")
        appendLine("Today's date: ${todayProvider()}")
        appendLine("Current mode: ${mode.value}. ${modeDescription(mode)}")
        appendLine()
        appendLine("Operating rules:")
        appendLine("- Use the provided tools; do not claim to have run tools you have not called.")
        appendLine("- When several tool calls are independent, request them together in one block.")
        appendLine("- Create the execution plan with update_plan before starting work and keep its statuses current.")
        appendLine("- Implement exactly what was asked; do not add features, abstractions, or refactors beyond the task.")
        appendLine("- Cite code as file_path:line_number where it helps navigation.")
        appendLine("- Keep responses terse; skip preamble and filler.")
        appendLine("- After finishing, summarize the result concisely in Markdown.")
        append(instructionsSection(instructions))
    }

    private fun modeDescription(mode: SessionModeId): String = when (mode.value) {
        "build" -> "You may read and modify files to implement the user's task."
        "bash" ->
            "You may read, modify files, and run shell commands via the 'bash' tool. " +
                    "Every command is confirmed by the user first; do not retry a rejected command. " +
                    "Commands run in the session working directory."

        else ->
            "You are in PLAN mode. You must not modify files: research, evaluate, and analyze the " +
                    "codebase, presenting findings or a concise implementation plan in Markdown as the task demands. " +
                    "Do not call write tools even if offered."
    }

    @OptIn(UnstableApi::class)
    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val context = currentCoroutineContext()
        val client = runCatching { context.client }.getOrNull()
        val clientCapabilities = runCatching { context.clientInfo.capabilities }.getOrNull()
            ?: com.agentclientprotocol.model.ClientCapabilities()
        val mode = currentMode
        val instructions = loadAgentsInstructions(cwd)

        val userContent = contentBlocksToLlmContent(
            blocks = content,
            modelSupportsImage = modelInfo()?.architecture?.inputModalities?.contains("image") == true,
        )
        appendToHistory(OpenAIMessage.User(userContent))

        val toolContext = ToolContext(
            cwd = cwd,
            client = client,
            clientCapabilities = clientCapabilities,
            sessionId = sessionId,
            updatePlan = { entries -> setPlan(entries, client) },
            fileStore = selectFileStore(client, clientCapabilities, sessionId, config.fsProxyEnabled),
            bashTimeoutSeconds = config.bashTimeoutSeconds,
        )

        var iterations = 0
        var usage: OpenAIUsage? = null
        while (iterations < 20) {
            iterations++
            val iterationMessageId = newMessageId()
            val messages = listOf(OpenAIMessage.System(Content.Text(systemPrompt(mode, instructions)))) + history
            val tools = toolRegistry.availableForMode(mode).map { tool ->
                OpenAITool(
                    function = OpenAIToolFunction(
                        name = tool.name,
                        description = tool.description,
                        parameters = tool.parameters,
                    ),
                )
            }

            val assistantText = StringBuilder()
            val toolCallAccum = mutableMapOf<Int, MutableStreamToolCall>()

            llm.chatCompletion(
                messages = messages,
                tools = tools,
                reasoning = effectiveReasoning(),
                model = currentModel,
                provider = providerRouting?.providerFor(currentModel),
            ).collect { chunk ->
                chunk.usage?.let { usage = it }
                chunk.choices.firstOrNull()?.let { choice ->
                    choice.delta.reasoning?.takeIf { it.isNotEmpty() }?.let { reasoning ->
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text(reasoning), iterationMessageId)))
                    }
                    choice.delta.content?.takeIf { it.isNotEmpty() }?.let { text ->
                        assistantText.append(text)
                        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(text), iterationMessageId)))
                    }
                    choice.delta.toolCalls?.forEach { tc ->
                        val acc = toolCallAccum.getOrPut(tc.index) { MutableStreamToolCall() }
                        tc.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        tc.function?.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                        tc.function?.arguments?.let { acc.arguments += it }
                    }
                }
            }

            if (toolCallAccum.isEmpty()) {
                appendToHistory(OpenAIMessage.Assistant(content = Content.Text(assistantText.toString())))
                persistSession()
                emitUsageUpdate(usage)
                emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
                return@flow
            }

            val calls = toolCallAccum.values.map { it.toToolCall() }
            appendToHistory(
                OpenAIMessage.Assistant(
                    content = Content.Text(assistantText.toString()),
                    toolCalls = calls.map { tc ->
                        OpenAIToolCall(tc.id, OpenAIFunction(tc.name, tc.arguments))
                    },
                )
            )

            for (call in calls) {
                val toolCallId = ToolCallId(call.id)
                val tool = toolRegistry.get(call.name)
                if (tool == null) {
                    val disabled = toolRegistry.disabledInMode(call.name, mode)
                    if (disabled != null) {
                        val msg = disabledToolMessage(disabled, mode)
                        emit(
                            Event.SessionUpdateEvent(
                                SessionUpdate.ToolCallUpdate(
                                    toolCallId = toolCallId,
                                    title = "Disabled in current mode",
                                    kind = ToolKind.OTHER,
                                    status = ToolCallStatus.FAILED,
                                    content = listOf(
                                        ToolCallContent.Content(ContentBlock.Text(msg))
                                    ),
                                )
                            )
                        )
                        appendToHistory(OpenAIMessage.Tool(Content.Text(msg), toolCallId = call.id))
                        continue
                    }
                    val msg = "Error: unknown tool \"${call.name}\""
                    emit(
                        Event.SessionUpdateEvent(
                            SessionUpdate.ToolCallUpdate(
                                toolCallId = toolCallId,
                                title = "Unknown tool",
                                kind = ToolKind.OTHER,
                                status = ToolCallStatus.FAILED,
                                content = listOf(
                                    ToolCallContent.Content(ContentBlock.Text(msg))
                                ),
                            )
                        )
                    )
                    appendToHistory(OpenAIMessage.Tool(Content.Text(msg), toolCallId = call.id))
                    continue
                }

                val arguments = parseArguments(call.arguments)
                emit(
                    Event.SessionUpdateEvent(
                        SessionUpdate.ToolCall(
                            toolCallId = toolCallId,
                            title = tool.title(arguments) ?: tool.name,
                            kind = tool.kind,
                            status = ToolCallStatus.IN_PROGRESS,
                            rawInput = arguments,
                        )
                    )
                )

                val allowed = shouldAllow(tool, client, toolCallId, arguments)
                if (!allowed) {
                    val msg = "Permission denied for tool ${tool.name}"
                    emit(
                        Event.SessionUpdateEvent(
                            SessionUpdate.ToolCallUpdate(
                                toolCallId = toolCallId,
                                title = tool.name,
                                status = ToolCallStatus.FAILED,
                                rawOutput = JsonPrimitive(msg)
                            )
                        )
                    )
                    appendToHistory(OpenAIMessage.Tool(Content.Text(msg), toolCallId = call.id))
                    continue
                }

                val result = try {
                    tool.execute(parseArguments(call.arguments), toolContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult("Tool ${tool.name} failed: ${e.message}", true)
                }

                emit(
                    Event.SessionUpdateEvent(
                        SessionUpdate.ToolCallUpdate(
                            toolCallId = toolCallId,
                            title = tool.name,
                            status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                            rawOutput = JsonPrimitive(result.text),
                        )
                    )
                )
                appendToHistory(OpenAIMessage.Tool(Content.Text(result.text), toolCallId = call.id))
            }
        }

        persistSession()
        emitUsageUpdate(usage)
        emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.MAX_TURN_REQUESTS)))
    }

    /**
     * Decides whether the tool call may run. Path-scoped tools inside the
     * session working directory are allowed outright; anything else that is
     * mutating (bash) or reaches outside the project (path-scoped reads,
     * writes, searches) asks the user for permission.
     */
    private suspend fun shouldAllow(
        tool: AgentTool,
        client: com.agentclientprotocol.common.ClientSessionOperations?,
        toolCallId: ToolCallId,
        arguments: JsonObject,
    ): Boolean {
        if (!permissionNeeded(cwd, tool, arguments)) return true
        if (client == null) return true
        permanentPermissions[tool.name]?.let { return it }
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
            rawInput = arguments,
        )
        return try {
            val response = client.requestPermissions(toolCall = update, permissions = options)
            when (val outcome = response.outcome) {
                is RequestPermissionOutcome.Selected -> {
                    when (outcome.optionId.value) {
                        "allow_always" -> permanentPermissions[tool.name] = true
                        "reject_always" -> permanentPermissions[tool.name] = false
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

    private class MutableStreamToolCall {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
        fun toToolCall() = StreamToolCall(id, name, arguments)
    }

    private fun parseArguments(arguments: String): JsonObject {
        return runCatching { ACPJson.parseToJsonElement(arguments) as? JsonObject }
            .getOrNull()
            ?: buildJsonObject { put("arguments", JsonPrimitive(arguments)) }
    }

    /**
     * Converts prompt content blocks into LLM message content. Plain text stays
     * a flat string; any multimodal block switches the message to an array of
     * content parts. Images are forwarded as base64 data URIs only when the
     * session model accepts image input, otherwise the block degrades to a text
     * placeholder so the model learns it was omitted.
     */
    private fun contentBlocksToLlmContent(blocks: List<ContentBlock>, modelSupportsImage: Boolean): Content =
        contentBlocksToLlmContentTopLevel(blocks, modelSupportsImage)

    override suspend fun cancel() {
        // The flow is cancelled via coroutine cancellation.
    }

    @OptIn(UnstableApi::class)
    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        closeResources()
        return CloseSessionResponse()
    }
}

internal data class StreamToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Decides whether a tool call needs a user permission prompt. Path-scoped
 * calls whose target lies inside the session working directory are allowed
 * outright; anything else that is mutating (bash) or reaches outside the
 * project (path-scoped reads, writes, searches) requires permission.
 */
internal fun permissionNeeded(cwd: String, tool: AgentTool, arguments: JsonObject): Boolean {
    val target = tool.targetPath(arguments)
    if (target != null && isWithin(cwd, target)) return false
    return tool.mutating || target != null
}

/**
 * Selects the file backend for the session: the client fs proxy when it is
 * available (both read and write capabilities) and enabled, otherwise a local
 * store. Selection is independent of the permission flow, which governs where
 * a call is allowed to touch.
 */
internal fun selectFileStore(
    client: com.agentclientprotocol.common.ClientSessionOperations?,
    capabilities: com.agentclientprotocol.model.ClientCapabilities,
    sessionId: com.agentclientprotocol.model.SessionId,
    fsProxyEnabled: Boolean,
): FileStore {
    val fs = capabilities.fs
    return if (fsProxyEnabled && client != null && fs?.readTextFile == true && fs?.writeTextFile == true) {
        ClientFileStore(client, sessionId)
    } else {
        LocalFileStore()
    }
}

/**
 * Converts prompt content blocks into LLM message content. Plain text stays a
 * flat string; any multimodal block switches the message to an array of content
 * parts. Images are forwarded as base64 data URIs only when the session model
 * accepts image input, otherwise the block degrades to a text placeholder so
 * the model learns it was omitted.
 */
internal fun contentBlocksToLlmContentTopLevel(
    blocks: List<ContentBlock>,
    modelSupportsImage: Boolean,
): Content {
    val text = StringBuilder()
    val parts = mutableListOf<OpenAIContentPart>()
    fun writeLine(line: String) {
        if (line.isEmpty()) return
        if (text.isNotEmpty()) text.append('\n')
        text.append(line)
    }

    fun flush() {
        if (text.isEmpty()) return
        parts += OpenAIContentPart.Text(text.toString())
        text.clear()
    }

    for (block in blocks) {
        when (block) {
            is ContentBlock.Text -> writeLine(block.text)
            is ContentBlock.ResourceLink -> writeLine(resourceLinkText(block))
            is ContentBlock.Resource -> when (val resource = block.resource) {
                is EmbeddedResourceResource.TextResourceContents ->
                    writeLine("Resource ${resource.uri}:\n${resource.text}")

                is EmbeddedResourceResource.BlobResourceContents -> {
                    flush()
                    parts += OpenAIContentPart.Text("(binary resource ${resource.uri} omitted)")
                }
            }

            is ContentBlock.Image -> {
                flush()
                parts += imagePart(block, modelSupportsImage)
            }

            is ContentBlock.Audio -> {
                flush()
                parts += OpenAIContentPart.Text("(audio content is not supported)")
            }
        }
    }
    if (parts.isNotEmpty()) {
        flush()
        return Content.Parts(parts)
    }
    if (text.isEmpty()) return Content.Text("(empty message)")
    return Content.Text(text.toString())
}

private fun resourceLinkText(block: ContentBlock.ResourceLink): String {
    val label = block.title?.takeIf { it.isNotBlank() }
        ?: block.name.takeIf { it.isNotBlank() }
        ?: return block.uri
    return "[$label](${block.uri})"
}

private fun imagePart(block: ContentBlock.Image, modelSupportsImage: Boolean): OpenAIContentPart {
    if (!modelSupportsImage) {
        return OpenAIContentPart.Text("(image omitted: the selected model does not support image input)")
    }
    if (block.data.isEmpty()) {
        return OpenAIContentPart.Text("(image omitted: no data provided)")
    }
    val mime = block.mimeType.ifEmpty { "image/png" }
    return OpenAIContentPart.Image(OpenAIContentPart.ImageUrl("data:$mime;base64,${block.data}"))
}

private val MODE_BUILD = SessionModeId("build")
private val MODE_PLAN = SessionModeId("plan")
private val MODE_BASH = SessionModeId("bash")
private val DEFAULT_MODE = MODE_PLAN
private const val MAX_TITLE_LENGTH = 72

private const val REASONING_OFF = "none"
private val DEFAULT_REASONING_LEVELS = listOf("max", "xhigh", "high", "medium", "low", "minimal")
private val REASONING_NAMES = mapOf(
    "max" to "Max",
    "xhigh" to "Extra high",
    "high" to "High",
    "medium" to "Medium",
    "low" to "Low",
    "minimal" to "Minimal",
)

private fun reasoningEffortName(effort: String): String = REASONING_NAMES[effort] ?: effort

private data class ReasoningOption(
    val value: String,
    val name: String,
    val description: String? = null,
)

private data class ReasoningSelector(
    val options: List<ReasoningOption>,
    val current: String,
)
