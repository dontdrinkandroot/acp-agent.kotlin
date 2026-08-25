package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentSessionModesTest {

    private fun session(): AgentSessionImpl = AgentSessionImpl(
        sessionId = SessionId("sess_test"),
        cwd = "/tmp",
        toolRegistry = ToolRegistry(),
        config = Config("test-key", "test-model", "http://127.0.0.1:1"),
        llm = LlmClient("test-key", "http://127.0.0.1:1", "test-model"),
        todayProvider = { "2026-09-03" },
    )

    @Test
    fun `modes are advertised with plan as default`() {
        val s = session()
        assertEquals(listOf("build", "plan", "bash"), s.availableModes.map { it.id.value })
        assertEquals(SessionModeId("plan"), s.defaultMode)
        assertTrue(s.availableModes.all { it.description != null })
        assertTrue(s.availableModes.all { it.name.isNotBlank() })
    }

    @Test
    fun `config options expose the mode select option`() {
        val s = session()
        val option = s.configOptions.single() as SessionConfigOption.Select
        assertEquals("mode", option.id.value)
        assertEquals(SessionConfigOptionCategory.MODE, option.category)
        assertEquals("plan", option.currentValue.value)
        val flat = option.options as SessionConfigSelectOptions.Flat
        assertEquals(listOf("build", "plan", "bash"), flat.options.map { it.value.value })
    }

    @Test
    fun `setConfigOption switches mode and returns updated options`() = runBlocking {
        val s = session()
        val response = s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"), null)
        val option = response.configOptions.single() as SessionConfigOption.Select
        assertEquals("build", option.currentValue.value)
        assertEquals("build", s.configOptions.single().let { it as SessionConfigOption.Select }.currentValue.value)
    }

    @Test
    fun `legacy setMode switches mode`() = runBlocking {
        val s = session()
        s.setMode(SessionModeId("bash"), null)
        val option = s.configOptions.single() as SessionConfigOption.Select
        assertEquals("bash", option.currentValue.value)
    }

    @Test
    fun `setConfigOption rejects unknown config option`() = runBlocking {
        val s = session()
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("bogus"), SessionConfigOptionValue.of("x"), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `setConfigOption rejects unknown mode value`() = runBlocking {
        val s = session()
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bogus"), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
        assertEquals("plan", s.configOptions.single().let { it as SessionConfigOption.Select }.currentValue.value)
    }

    @Test
    fun `setConfigOption rejects non-string value for mode`() = runBlocking {
        val s = session()
        val e = assertFailsWith<JsonRpcException> {
            s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of(true), null)
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }

    @Test
    fun `legacy setMode rejects unknown mode`() {
        val s = session()
        val e = assertFailsWith<JsonRpcException> {
            runBlocking { s.setMode(SessionModeId("research"), null) }
        }
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS.code, e.code)
    }
}
