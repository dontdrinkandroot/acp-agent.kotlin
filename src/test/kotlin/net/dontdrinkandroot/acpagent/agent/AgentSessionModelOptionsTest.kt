package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ReasoningCapability
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentSessionModelOptionsTest {

    private fun model(reasoning: ReasoningCapability? = null) = OpenRouterModel(
        id = "test-model",
        name = "Test Model",
        description = "A test model",
        supportedParameters = listOf("tools"),
        contextLength = 16384,
        reasoning = reasoning,
    )

    private fun session(models: List<OpenRouterModel>, restored: SessionRecord? = null) = AgentSessionImpl(
        sessionId = SessionId("sess_modeltest000001"),
        cwd = "/tmp",
        toolRegistry = ToolRegistry(),
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        llm = LlmClient("k", "http://127.0.0.1:1", "test-model"),
        todayProvider = { "2026-09-04" },
        restored = restored,
        models = models,
    )

    private fun selectOption(options: List<SessionConfigOption>, id: String) =
        options.filterIsInstance<SessionConfigOption.Select>().first { it.id.value == id }

    @Test
    fun `config options advertise model and reasoning selects`() {
        val s = session(listOf(model(ReasoningCapability(listOf("high", "medium"), "high"))))
        val modelOption = selectOption(s.configOptions, "model")
        assertEquals(SessionConfigOptionCategory.MODEL, modelOption.category)
        assertEquals("test-model", modelOption.currentValue.value)
        val reasoningOption = selectOption(s.configOptions, "reasoning")
        assertEquals(SessionConfigOptionCategory.THOUGHT_LEVEL, reasoningOption.category)
        assertEquals(
            listOf("high", "medium", "none"),
            (reasoningOption.options as SessionConfigSelectOptions.Flat).options.map { it.value.value },
        )
        assertEquals("high", reasoningOption.currentValue.value)
    }

    @Test
    fun `reasoning select is hidden for models without a reasoning block`() {
        val s = session(listOf(model(reasoning = null)))
        assertNull(s.configOptions.firstOrNull { it.id.value == "reasoning" })
    }

    @Test
    fun `mandatory reasoning models drop the off option`() {
        val s = session(listOf(model(ReasoningCapability(null, "high", mandatory = true))))
        val reasoningOption = selectOption(s.configOptions, "reasoning")
        assertEquals(
            listOf("max", "xhigh", "high", "medium", "low", "minimal"),
            (reasoningOption.options as SessionConfigSelectOptions.Flat).options.map { it.value.value },
        )
        assertEquals("high", reasoningOption.currentValue.value)
    }

    @Test
    fun `model switch resets reasoning to the new model default`() = runBlocking {
        val other = OpenRouterModel(
            id = "other-model", name = "Other", supportedParameters = listOf("tools"),
            reasoning = ReasoningCapability(listOf("low", "minimal"), "low"),
        )
        val s = session(listOf(model(ReasoningCapability(listOf("high"), "high")), other))
        s.setConfigOption(SessionConfigId("reasoning"), SessionConfigOptionValue.of("high"), null)

        val response = s.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.of("other-model"), null)
        assertEquals("other-model", selectOption(response.configOptions, "model").currentValue.value)
        assertEquals("low", selectOption(response.configOptions, "reasoning").currentValue.value)
    }

    @Test
    fun `reasoning selection is validated against the model options`() = runBlocking {
        val s = session(listOf(model(ReasoningCapability(listOf("high", "medium"), "high"))))
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("reasoning"), SessionConfigOptionValue.of("bogus"), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `reasoning selection fails for models without reasoning`() = runBlocking {
        val s = session(listOf(model(reasoning = null)))
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("reasoning"), SessionConfigOptionValue.of("high"), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `empty model id is rejected`() = runBlocking {
        val s = session(listOf(model()))
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.of(""), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `restored model and reasoning survive`() {
        val s = session(
            listOf(model(ReasoningCapability(listOf("high", "medium"), "high"))),
            restored = SessionRecord(
                sessionId = "sess_modeltest000001", cwd = "/tmp", mode = "plan", title = "",
                updatedAt = 0, model = "test-model", reasoning = "medium",
            ),
        )
        assertEquals("medium", selectOption(s.configOptions, "reasoning").currentValue.value)
        assertEquals("test-model", selectOption(s.configOptions, "model").currentValue.value)
    }
}
