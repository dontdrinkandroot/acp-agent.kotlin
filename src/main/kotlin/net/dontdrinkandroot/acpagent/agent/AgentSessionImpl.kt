package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIContentPart
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.agent.clientInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.*

internal class AgentSessionImpl(
    override val sessionId: SessionId,
    private val cwd: String,
    private val toolRegistry: ToolRegistry,
    private val config: Config,
    private val llm: LlmClient,
    private val providerRouting: ProviderRouting? = null,
    private val todayProvider: () -> String,
    closeResources: suspend () -> Unit = {},
    private val sessionStore: SessionStore? = null,
    private val restored: SessionRecord? = null,
    private val replayOnInitialize: Boolean = false,
    private val models: List<OpenRouterModel> = emptyList(),
) : AgentSession {

    private val state = SessionState(
        sessionId = sessionId,
        cwd = cwd,
        config = config,
        restored = restored,
        sessionStore = sessionStore,
        closeResources = { runBlocking { closeResources() } },
    )

    private val systemPrompt = SystemPromptBuilder(
        cwd,
        todayProvider,
        runConfigsProvider = { loadRunConfigs(cwd) },
    )

    private fun modelInfo(): OpenRouterModel? = models.firstOrNull { it.id == state.currentModel }

    override val availableModes: List<SessionMode> = listOf(
        SessionMode(MODE_BUILD, "Build", "Read, write, move and delete files to implement the task"),
        SessionMode(MODE_PLAN, "Plan", "Read-only: research the code and present an implementation plan"),
        SessionMode(MODE_BASH, "Bash", "Build plus a bash tool; every command asks the user for permission"),
    )

    private val sessionConfigOptions = SessionConfigOptions(availableModes, models, state)

    private val toolCallExecutor = ToolCallExecutor(cwd, toolRegistry, state)

    private val promptRunner = PromptRunner(
        state = state,
        systemPrompt = systemPrompt,
        chatCompleter = llm,
        providerRouting = providerRouting,
        sessionConfigOptions = sessionConfigOptions,
        toolRegistry = toolRegistry,
        toolCallExecutor = toolCallExecutor,
        maxTurnRequests = config.maxTurnRequests,
        models = models,
    )

    override val defaultMode: SessionModeId = state.initialMode

    @OptIn(UnstableApi::class)
    override val configOptions: List<SessionConfigOption>
        get() = sessionConfigOptions.options()

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
        get() = ModelId(state.currentModel)

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        sessionConfigOptions.apply(SessionConfigId("mode"), SessionConfigOptionValue.of(modeId.value))
        notifyModeState()
        state.persist()
        return SetSessionModeResponse()
    }

    @OptIn(UnstableApi::class)
    override suspend fun setConfigOption(
        configId: SessionConfigId,
        value: SessionConfigOptionValue,
        _meta: JsonElement?
    ): SetSessionConfigOptionResponse {
        sessionConfigOptions.apply(configId, value)
        notifyModeState()
        state.persist()
        return SetSessionConfigOptionResponse(configOptions)
    }

    @OptIn(UnstableApi::class)
    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        sessionConfigOptions.applyModel(modelId.value)
        notifyModeState()
        state.persist()
        return SetSessionModelResponse()
    }

    @OptIn(UnstableApi::class)
    private suspend fun notifyModeState() {
        val client = runCatching { currentCoroutineContext().client }.getOrNull() ?: return
        client.notify(SessionUpdate.CurrentModeUpdate(state.currentMode))
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
        val (history, plan) = state.replaySnapshot()
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
                                locations = tool?.let { toolLocations(it, args) } ?: emptyList(),
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
        state.setPlan(entries)
        client?.notify(SessionUpdate.PlanUpdate(entries))
    }

    @OptIn(UnstableApi::class)
    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val context = currentCoroutineContext()
        val client = runCatching { context.client }.getOrNull()
        val clientCapabilities = runCatching { context.clientInfo.capabilities }.getOrNull()
            ?: com.agentclientprotocol.model.ClientCapabilities()
        val mode = state.currentMode
        val instructions = loadAgentsInstructions(cwd)

        val userContent = contentBlocksToLlmContent(
            blocks = content,
            modelSupportsImage = modelInfo()?.architecture?.inputModalities?.contains("image") == true,
        )
        state.appendToHistory(OpenAIMessage.User(userContent))

        val toolContext = ToolContext(
            cwd = cwd,
            client = client,
            clientCapabilities = clientCapabilities,
            sessionId = sessionId,
            updatePlan = { entries -> setPlan(entries, client) },
            fileStore = selectFileStore(client, clientCapabilities, config.fsProxyEnabled),
            bashTimeoutSeconds = config.bashTimeoutSeconds,
        )

        promptRunner.run(this, mode, instructions, toolContext)
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
        state.close()
        return CloseSessionResponse()
    }

    internal suspend fun delete() {
        state.delete()
    }
}

internal data class StreamToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Decides whether a tool call needs a user permission prompt. Path-scoped
 * calls whose targets all lie inside the session working directory are allowed
 * outright; anything else that is mutating (bash) or reaches outside the
 * project (path-scoped reads, writes, searches, moves) requires permission.
 */
internal fun permissionNeeded(cwd: String, tool: AgentTool, arguments: JsonObject): Boolean {
    val targets = tool.targetPaths(arguments)
    if (targets.isNotEmpty() && targets.all { isWithin(cwd, it) }) return false
    return tool.mutating || targets.isNotEmpty()
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
    fsProxyEnabled: Boolean,
): FileStore {
    val fs = capabilities.fs
    return if (fsProxyEnabled && client != null && fs?.readTextFile == true && fs?.writeTextFile == true) {
        ClientFileStore(client)
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

private const val WIND_DOWN_PROMPT =
    "The per-prompt tool iteration limit has been reached. Summarize what has been accomplished " +
            "so far and what remains to be done; do not call any tools."
