package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ReasoningCapability
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

    private fun options(state: SessionState) = SessionConfigOptions(modes, listOf(model()), state)

    private fun state() = SessionState(
        sessionId = com.agentclientprotocol.model.SessionId("sess_configtest0001"),
        cwd = "/tmp",
        toolRegistry = net.dontdrinkandroot.acpagent.tools.ToolRegistry(),
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        restored = null,
        sessionStore = null,
        closeResources = {},
    )

    @Test
    fun `mode switches append mode status messages with per-mode tool lists`() {
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
        // Fresh session seeds the current (default) mode status message.

        assertEquals(
            "You are now in plan mode. Available tools: read_file.",
            s.historySnapshot.single()
                .let { (it as ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage.System).content as Content.Text }
                .text(),
        )
        // Switching modes appends a new status message with the new mode's tools.

        s.switchMode(SessionModeId("build"))
        assertEquals(
            "You are now in build mode. Available tools: read_file, write_file.",
            s.historySnapshot.last()
                .let { (it as ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage.System).content as Content.Text }
                .text(),
        )
        // Same-value re-set appends nothing.

        s.switchMode(SessionModeId("build"))
        assertEquals(2, s.historySnapshot.size, "same-value mode re-set must not append a status message")
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
