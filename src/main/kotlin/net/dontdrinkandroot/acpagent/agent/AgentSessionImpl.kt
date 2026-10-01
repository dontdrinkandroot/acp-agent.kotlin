package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.agentclientprotocol.agent.AgentSession
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
import net.dontdrinkandroot.acpagent.llm.forModel
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
        toolRegistry = toolRegistry,
        config = config,
        restored = restored,
        sessionStore = sessionStore,
        closeResources = { runBlocking { closeResources() } },
    )

    private val systemPrompt = SystemPromptBuilder(
        cwd,
        todayProvider,
        runConfigsProvider = { loadRunConfigs(cwd) },
        trustedReadPaths = config.extraMounts,
        excludedFileGlobs = FileAccessExclusions.DEFAULT.globs(),
    )

    private fun modelInfo(): OpenRouterModel? = models.forModel(state.currentModel)

    override val availableModes: List<SessionMode> = listOf(
        SessionMode(MODE_PLAN, "Plan", "Read-only: research the code and present an implementation plan"),
        SessionMode(MODE_BUILD, "Build", "Read, write, move and delete files to implement the task"),
        SessionMode(MODE_BASH, "Bash", "Build plus a bash tool; every command asks the user for permission"),
    )

    private val sessionConfigOptions = SessionConfigOptions(availableModes, models, state)

    private val toolCallExecutor = ToolCallExecutor(toolRegistry, state, config.extraMounts)

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
        // persist() records the governing-mode change only; the mode status
        // message is written at the next prompt start (issue #36).
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
        // A pending mode is never announced before it is applied at turn end
        // (the flush announces it); announcing it here would put the client
        // ahead of the loop's tool gating. Everything else (model, reasoning)
        // applies immediately and is announced immediately.
        if (state.hasPendingMode()) return
        val client = clientOrNull() ?: return
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
        val client = clientOrNull() ?: return
        val (history, plan, toolOutcomes) = state.replaySnapshot()
        buildReplayUpdates(history, plan, toolOutcomes, toolRegistry).forEach { client.notify(it) }
    }

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
        val client = clientOrNull()
        val clientCapabilities = runCatching { currentCoroutineContext().clientInfo.capabilities }.getOrNull()
            ?: com.agentclientprotocol.model.ClientCapabilities()
        val instructions = loadAgentsInstructions(cwd)
        val userContent = contentBlocksToLlmContentTopLevel(
            blocks = content,
            modelSupportsImage = modelInfo()?.architecture?.inputModalities?.contains("image") == true,
        )
        // The single writer of mode status messages: before the user message
        // is appended, state the mode iff it changed since the last indicated
        // one (first prompt, or any switch - idle or flushed - since then).
        // Runs before `mode` is captured so the stated mode and the turn's
        // governing mode are captured together.
        state.indicateCurrentMode()
        state.appendToHistory(OpenAIMessage.User(userContent))
        val mode = state.currentMode

        val toolContext = ToolContext(
            cwd = cwd,
            client = client,
            clientCapabilities = clientCapabilities,
            sessionId = sessionId,
            updatePlan = { entries -> setPlan(entries, client) },
            fileStore = selectFileStore(client, clientCapabilities, config.fsProxyEnabled),
            bashTimeoutSeconds = config.bashTimeoutSeconds,
            webFetchAllowPrivate = config.webFetchAllowPrivate,
            modeStatusText = { state.modeStatusTextForCurrent() },
        )

        state.setPromptActive(true)
        try {
            promptRunner.run(this, mode, instructions, toolContext)
            // The turn handed control back: apply any mode requested while it
            // ran (latest wins) as a single flush, then persist + announce it.
            // The flush flips the mode only - its status message waits for the
            // next prompt start (indicateCurrentMode).
            val changed = state.flushPendingMode()
            state.persist()
            if (changed) {
                // The turn is over - clear the active flag so the flush is
                // announced (notifyModeState skips it while a turn is active).
                state.setPromptActive(false)
                notifyModeState()
            }
        } finally {
            // Drop whatever is still pending (a cancelled turn must not leak its
            // requested mode into the next turn, where it would apply uninvited)
            // and clear the active flag even when the flow is cancelled.
            state.cancelPrompt()
            state.setPromptActive(false)
        }
    }

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
 * outright; non-mutating reads whose targets all lie inside a trusted read
 * path (`ACP_EXTRA_MOUNTS`, e.g. the extra docker mounts) are allowed too.
 * Anything else that is mutating (bash) or reaches outside the project
 * (path-scoped reads, writes, searches, moves) requires permission.
 */
internal fun permissionNeeded(
    cwd: String,
    tool: AgentTool,
    arguments: JsonObject,
    trustedReadPaths: List<String> = emptyList(),
): Boolean {
    val targets = tool.targetPaths(arguments)
    if (targets.isNotEmpty() && targets.all { isWithin(cwd, it) }) return false
    if (!tool.mutating && trustedReadPaths.isNotEmpty() &&
        targets.isNotEmpty() && targets.all { isWithinAnyRoot(cwd, trustedReadPaths, it) }
    ) {
        return false
    }
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
