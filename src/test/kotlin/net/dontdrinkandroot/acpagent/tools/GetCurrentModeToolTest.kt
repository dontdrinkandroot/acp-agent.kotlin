package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetCurrentModeToolTest {

    private val tool = GetCurrentModeTool()

    private fun context(modeStatusText: () -> String = { "" }): ToolContext = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_getmode0000001"),
        modeStatusText = modeStatusText,
    )

    @Test
    fun `tool is other kind, non-mutating and available in every mode`() {
        assertEquals("get_current_mode", tool.name)
        assertEquals(ToolKind.OTHER, tool.kind)
        assertFalse(tool.mutating)
        assertTrue(tool.modes.isEmpty())
        assertTrue(tool.targetPaths(JsonObject(emptyMap())).isEmpty(), "not path-scoped")
    }

    @Test
    fun `schema pins the no-argument schema`() {
        assertEquals(
            """{"type":"object","properties":{},"required":[]}""",
            llmWireJson.encodeToString(tool.parameters),
        )
    }

    @Test
    fun `execute returns the mode status text from the context`() = runBlocking {
        val result = tool.execute(JsonObject(emptyMap()), context { "Mode: plan. Read-only: research." })
        assertFalse(result.isError)
        assertEquals("Mode: plan. Read-only: research.", result.text)
    }

    @Test
    fun `execute fails loudly when no mode status is available`() = runBlocking {
        val result = tool.execute(JsonObject(emptyMap()), context())
        assertTrue(result.isError)
        assertEquals("Current mode is unavailable in this context.", result.text)
    }

    @Test
    fun `execute ignores any arguments the model sends`() = runBlocking {
        val result = tool.execute(kotlinx.serialization.json.buildJsonObject {
            put("unexpected", kotlinx.serialization.json.JsonPrimitive("value"))
        }, context { "Mode: bash. Build plus a permission-gated shell." })
        assertFalse(result.isError)
        assertEquals("Mode: bash. Build plus a permission-gated shell.", result.text)
    }
}
