package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.OpenRouterEndpoint
import net.dontdrinkandroot.acpagent.llm.OpenRouterEndpointPricing
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ReasoningCapability
import net.dontdrinkandroot.acpagent.providerrouting.ProviderOption
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionConfigOptionsTest {

    private val modes = listOf(
        SessionMode(SessionModeId("build"), "Build", "desc"),
        SessionMode(SessionModeId("plan"), "Plan", "desc"),
        SessionMode(SessionModeId("bash"), "Bash", "desc"),
    )

    private fun model(reasoning: ReasoningCapability? = null) = OpenRouterModel(
        id = "test-model",
        name = "Test Model",
        supportedParameters = listOf("tools"),
        contextLength = 16384,
        reasoning = reasoning,
    )

    private fun endpoint(tag: String, name: String, price: String = "0.00002") = OpenRouterEndpoint(
        tag = tag,
        providerName = name,
        pricing = OpenRouterEndpointPricing(completion = price),
    )

    private fun options(state: SessionState) = SessionConfigOptions(modes, listOf(model()), state)

    private fun providerOptions(vararg endpoints: OpenRouterEndpoint) =
        endpoints.map { ProviderOption(it.tag, it.providerName) }

    /**
     * A fetcher stub replaying [endpoints]; [failures] returns the first N
     * calls with an empty feed (unavailable).
     */
    private class StubEndpointsFetcher(
        private val endpoints: List<OpenRouterEndpoint>,
        private var failures: Int = 0,
    ) : ProviderRouting.EndpointsFetcher {
        var calls = 0
        override suspend fun fetchEndpoints(modelId: String): List<OpenRouterEndpoint> {
            calls++
            if (failures > 0) {
                failures--
                return emptyList()
            }
            return endpoints
        }
    }

    private fun providerRouting(fetcher: StubEndpointsFetcher, enabled: Boolean = true): ProviderRouting =
        ProviderRouting.createForTesting(enabled, fetcher)

    @Test
    fun `provider option advertises auto plus the model's provider slugs`() {
        val s = state()
        val o = SessionConfigOptions(
            modes, listOf(model()), s,
            initialProviderOptions = providerOptions(endpoint("azure", "Azure"), endpoint("deepinfra", "DeepInfra")),
        )
        val option = o.options().firstOrNull { it.id.value == "provider" } as SessionConfigOption.Select?
        val flat = requireNotNull(option).options as SessionConfigSelectOptions.Flat
        assertEquals(
            listOf("auto", "azure", "deepinfra"),
            flat.options.map { it.value.value },
        )
        assertEquals("Auto", flat.options.first().name)
        assertEquals("Azure", flat.options[1].name)
        assertEquals("auto", option.currentValue.value)
        assertEquals(SessionConfigOptionCategory("provider"), option.category)
    }

    @Test
    fun `provider option is hidden without an endpoints snapshot`() {
        val s = state()
        val o = SessionConfigOptions(modes, listOf(model()), s)
        assertNull(o.options().firstOrNull { it.id.value == "provider" })
    }

    @Test
    fun `provider option skips blank tags and the auto sentinel`() {
        val s = state()
        val o = SessionConfigOptions(
            modes, listOf(model()), s,
            initialProviderOptions = listOf(
                ProviderOption("", "Blank"),
                ProviderOption("auto", "Auto-ish"),
                ProviderOption("azure", "Azure"),
            ),
        )
        val option = requireNotNull(o.options().firstOrNull { it.id.value == "provider" }) as SessionConfigOption.Select
        val flat = option.options as SessionConfigSelectOptions.Flat
        assertEquals(listOf("auto", "azure"), flat.options.map { it.value.value })
        // "auto" can only ever mean OpenRouter routing, never the provider tagged "auto".
        o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("auto"))
        assertEquals("", s.providerSelection)
    }

    @Test
    fun `provider option can be applied and validated`() {
        val s = state()
        val o = SessionConfigOptions(
            modes, listOf(model()), s,
            initialProviderOptions = providerOptions(endpoint("azure", "Azure"), endpoint("deepinfra", "DeepInfra")),
        )
        o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("azure"))
        assertEquals("azure", s.providerSelection)
        o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("auto"))
        assertEquals("", s.providerSelection)
        val e = assertFailsWith<JsonRpcException> {
            o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("bogus"))
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
        assertEquals("", s.providerSelection, "a failed apply must not change the selection")
    }

    @Test
    fun `provider apply fails when the option is hidden`() {
        val s = state()
        val o = SessionConfigOptions(modes, listOf(model()), s)
        val e = assertFailsWith<JsonRpcException> {
            o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("azure"))
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `model switch resets the provider selection`() = runBlocking {
        val fetcher = StubEndpointsFetcher(listOf(endpoint("azure", "Azure"), endpoint("deepinfra", "DeepInfra")))
        val s = state()
        val o = SessionConfigOptions(
            modes, listOf(model()), s, providerRouting(fetcher),
            initialProviderOptions = providerOptions(endpoint("azure", "Azure")),
        )
        o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("azure"))
        assertEquals("azure", s.providerSelection)
        o.applyModel("other-model")
        assertEquals("", s.providerSelection, "slugs are per-model; a switch resets to auto")
        assertNull(o.options().firstOrNull { it.id.value == "provider" }, "the stale snapshot hides until refresh")
        o.refreshProviderOptions()
        assertEquals(
            listOf("auto", "azure", "deepinfra"),
            ((o.options().first { it.id.value == "provider" }) as SessionConfigOption.Select)
                .let { (it.options as SessionConfigSelectOptions.Flat).options.map { opt -> opt.value.value } },
            "refresh re-fetches the new model's feed",
        )
    }

    @Test
    fun `a manual pick survives a later endpoints outage`() = runBlocking {
        val fetcher = StubEndpointsFetcher(emptyList())
        val s = state()
        val o = SessionConfigOptions(
            modes, listOf(model()), s, providerRouting(fetcher),
            initialProviderOptions = providerOptions(endpoint("azure", "Azure")),
        )
        o.apply(SessionConfigId("provider"), SessionConfigOptionValue.of("azure"))
        o.refreshProviderOptions()
        assertEquals("azure", o.effectiveProviderSlug(), "an explicit pick keeps applying while the feed is down")
        assertNull(o.options().firstOrNull { it.id.value == "provider" }, "the option itself stays hidden")
    }

    @Test
    fun `restore self-heals a stale persisted slug`() {
        // The feed no longer lists azure: the stale pick resets to auto.
        val s = state(
            SessionRecord(
                sessionId = "sess_configtest0001",
                cwd = "/tmp",
                mode = "plan",
                title = "t",
                updatedAt = 0,
                model = "test-model",
                provider = "azure",
            )
        )
        val o = SessionConfigOptions(
            modes, listOf(model()), s,
            initialProviderOptions = providerOptions(endpoint("deepinfra", "DeepInfra")),
        )
        assertEquals("", s.providerSelection, "a stale slug resets to auto")
        assertEquals(
            "auto",
            (requireNotNull(o.options().firstOrNull { it.id.value == "provider" }) as SessionConfigOption.Select)
                .currentValue.value,
        )
    }

    private fun state(restored: SessionRecord? = null) = SessionState(
        sessionId = com.agentclientprotocol.model.SessionId("sess_configtest0001"),
        cwd = "/tmp",
        toolRegistry = net.dontdrinkandroot.acpagent.tools.ToolRegistry(),
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        restored = restored,
        sessionStore = null,
        closeResources = {},
    )

    @Test
    fun `mode status messages are written only at prompt start`() {
        val registry = net.dontdrinkandroot.acpagent.tools.ToolRegistry().apply {
            register(ToolStub("read_file", mutating = false, modes = emptyList()))
            register(
                ToolStub(
                    "write_file",
                    mutating = true,
                    modes = listOf(SessionModeId("build"), SessionModeId("bash"))
                )
            )
            register(ToolStub("bash", mutating = true, modes = listOf(SessionModeId("bash"))))
        }
        val s = SessionState(
            sessionId = com.agentclientprotocol.model.SessionId("sess_configtest0001"),
            cwd = "/tmp",
            toolRegistry = registry,
            config = Config("k", "test-model", "http://127.0.0.1:1"),
            restored = null,
            sessionStore = null,
            closeResources = {},
        )
        // A fresh session writes no seed message; indication happens at prompt
        // start. A mode switch flips the governing mode without appending
        // history.

        assertEquals(0, s.historySnapshot.size, "no seed: indication happens at prompt start")

        s.requestMode(SessionModeId("build"))
        assertEquals(0, s.historySnapshot.size, "switches only flip - the message waits for prompt start")

        // At prompt start exactly one message for the latest mode is appended,
        // with the new mode's tools.

        s.indicateCurrentMode()
        assertEquals(
            "Mode: build. Read-write: read, write, edit, move and delete files to implement the task. Available tools: read_file, write_file.",
            s.historySnapshot.single()
                .let { (it as ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage.System).content as Content.Text }
                .text(),
        )
        // Re-indicating without a mode change appends nothing.

        s.indicateCurrentMode()
        assertEquals(1, s.historySnapshot.size, "same-mode re-indication must not append a status message")
    }

    @Test
    fun `mode option can be applied and validated`() {
        val s = state()
        val o = options(s)
        o.apply(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
        assertEquals(SessionModeId("build"), s.currentMode)
        val e = assertFailsWith<JsonRpcException> {
            o.apply(SessionConfigId("mode"), SessionConfigOptionValue.of("bogus"))
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `model switch resets the reasoning selection`() {
        val s = state()
        s.reasoningSelection = "high"
        val o = SessionConfigOptions(modes, listOf(model(ReasoningCapability(listOf("high"), "high"))), s)
        o.applyModel("other")
        assertEquals("other", s.currentModel)
        assertEquals("", s.reasoningSelection)
    }

    @Test
    fun `effectiveReasoning falls back to the model default and resumes the selection`() {
        val s = state()
        val o = SessionConfigOptions(modes, listOf(model(ReasoningCapability(listOf("high", "medium"), "medium"))), s)
        // "" is not yet chosen: the effective effort is the model default.
        assertEquals("medium", o.effectiveReasoning())
        s.reasoningSelection = "high"
        assertEquals("high", o.effectiveReasoning())
    }

    @Test
    fun `reasoning option is hidden for models without a reasoning block and effective falls back to null`() {
        val s = state()
        val o = SessionConfigOptions(modes, listOf(model(reasoning = null)), s)
        assertNull(o.options().firstOrNull { it.id.value == "reasoning" })
        assertNull(o.effectiveReasoning())
    }

    @Test
    fun `unknown config option is rejected`() {
        val s = state()
        val o = options(s)
        val e = assertFailsWith<JsonRpcException> {
            o.apply(SessionConfigId("bogus"), SessionConfigOptionValue.of("x"))
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }
}

private class ToolStub(
    override val name: String,
    override val mutating: Boolean,
    override val modes: List<SessionModeId>,
) : AgentTool {
    override val description = "test tool"
    override val parameters: JsonObject = buildJsonObject { }
    override val kind: ToolKind = ToolKind.OTHER
    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        ToolResult("ran")
}
