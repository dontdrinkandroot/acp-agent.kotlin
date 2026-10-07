package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalcToolTest {

    private val tool = CalcTool()

    private val context = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_calc0000000001"),
    )

    @Test
    fun `tool is other kind, non-mutating and available in every mode`() {
        assertEquals("calc", tool.name)
        assertEquals(ToolKind.OTHER, tool.kind)
        assertFalse(tool.mutating)
        assertTrue(tool.modes.isEmpty())
        assertTrue(tool.targetPaths(JsonObject(emptyMap())).isEmpty(), "not path-scoped")
        assertEquals(
            "calc(expression: 2 + 3 * 4)",
            tool.title(buildJsonObject { put("expression", JsonPrimitive("2 + 3 * 4")) }),
        )
    }

    @Test
    fun `schema pins the expression parameter`() {
        assertEquals(
            """{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression to evaluate, e.g. '2 + 3 * 4' or 'sqrt(min(9, 16))'."}},"required":["expression"]}""",
            llmWireJson.encodeToString(tool.parameters),
        )
    }

    @Test
    fun `execute evaluates the expression and renders the result`() = runBlocking {
        val result = tool.execute(
            buildJsonObject { put("expression", JsonPrimitive("2 + 3 * 4")) },
            context,
        )
        assertFalse(result.isError)
        assertEquals("14", result.text)
    }

    @Test
    fun `argument validation follows the strict null conventions`() = runBlocking {
        val missing = tool.execute(buildJsonObject {}, context)
        assertTrue(missing.isError)
        assertEquals("Missing 'expression'", missing.text)

        val explicitNull = tool.execute(buildJsonObject { put("expression", JsonNull) }, context)
        assertTrue(explicitNull.isError)
        assertEquals("'expression' must not be null", explicitNull.text)

        val wrongType =
            tool.execute(buildJsonObject { put("expression", buildJsonArray { add(JsonPrimitive(1)) }) }, context)
        assertTrue(wrongType.isError)
        assertEquals("'expression' must be a string", wrongType.text)
    }

    @Test
    fun `evaluation errors surface the parse message`() = runBlocking {
        val result = tool.execute(
            buildJsonObject { put("expression", JsonPrimitive("(2 + 3 *))")) },
            context,
        )
        assertTrue(result.isError)
        assertEquals("Cannot evaluate '(2 + 3 *))': Unexpected token ')' at position 8", result.text)
    }
}
