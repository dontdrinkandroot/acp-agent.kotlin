package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ReasoningCapability
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
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        restored = null,
        sessionStore = null,
        closeResources = {},
    )

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