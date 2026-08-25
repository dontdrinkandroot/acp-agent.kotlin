package net.dontdrinkandroot.acpagent.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpBridgeTest {

    @Test
    fun `primitives map to scalars`() {
        val json = buildJsonObject {
            put("s", "hello")
            put("i", 42)
            put("d", 3.5)
            put("t", true)
            put("f", false)
            put("n", JsonNull)
        }
        val map = json.toJsonValueMap()
        assertEquals("hello", map["s"])
        assertEquals(42, map["i"])
        assertEquals(3.5, map["d"])
        assertEquals(true, map["t"])
        assertEquals(false, map["f"])
        assertNull(map["n"])
    }

    @Test
    fun `string numbers stay strings`() {
        val map = buildJsonObject { put("v", JsonPrimitive("123")) }.toJsonValueMap()
        assertEquals("123", map["v"])
    }

    @Test
    fun `nested objects and arrays recurse`() {
        val json = buildJsonObject {
            put(
                "obj",
                buildJsonObject {
                    put("a", 1)
                },
            )
            put(
                "arr",
                buildJsonArray {
                    add(JsonPrimitive("x"))
                    add(JsonPrimitive(false))
                    add(
                        buildJsonObject {
                            put("b", 2)
                        },
                    )
                },
            )
        }
        val map = json.toJsonValueMap()
        val obj = assertIs<Map<*, *>>(map["obj"])
        assertEquals(1, obj["a"])
        val arr = assertIs<List<*>>(map["arr"])
        assertEquals("x", arr[0])
        assertEquals(false, arr[1])
        assertEquals(2, (arr[2] as Map<*, *>)["b"])
    }

    @Test
    fun `empty containers`() {
        val map = buildJsonObject {
            put("o", JsonObject(emptyMap()))
            put("a", JsonArray(emptyList()))
        }.toJsonValueMap()
        assertEquals(emptyMap<String, Any?>(), map["o"])
        assertEquals(emptyList<Any?>(), map["a"])
    }

    @Test
    fun `mcp tool parameters keep object type wrapper`() {
        val tool = Tool(
            name = "echo",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("msg", buildJsonObject { put("type", "string") })
                },
            ),
        )
        val mcpTool = McpTool(
            McpServerConnection("srv", Client(clientInfo = createMcpClientInfo())),
            tool,
        )
        val parameters = mcpTool.parameters
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertNotNull(parameters["properties"])
    }
}