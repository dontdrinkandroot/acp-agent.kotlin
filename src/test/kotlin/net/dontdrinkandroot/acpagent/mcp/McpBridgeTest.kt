package net.dontdrinkandroot.acpagent.mcp

import com.agentclientprotocol.model.ToolKind
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import kotlin.test.*

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
            trustAnnotations = true,
        )
        val parameters = mcpTool.parameters
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertNotNull(parameters["properties"])
    }

    private fun mcpTool(
        annotations: ToolAnnotations? = null,
        trustAnnotations: Boolean = true,
    ): McpTool = McpTool(
        McpServerConnection("srv", Client(clientInfo = createMcpClientInfo())),
        Tool(name = "tool", inputSchema = ToolSchema(), annotations = annotations),
        trustAnnotations = trustAnnotations,
    )

    @Test
    fun `trusted read only hint makes the tool non mutating and other kinded`() {
        val mcpTool = mcpTool(annotations = ToolAnnotations(readOnlyHint = true))
        assertEquals(false, mcpTool.mutating)
        assertEquals(ToolKind.OTHER, mcpTool.kind)
    }

    @Test
    fun `untrusted read only hint keeps the pessimistic mutating default`() {
        val mcpTool = mcpTool(annotations = ToolAnnotations(readOnlyHint = true), trustAnnotations = false)
        assertEquals(true, mcpTool.mutating)
        assertEquals(ToolKind.OTHER, mcpTool.kind)
        assertNull(mcpTool.title(buildJsonObject { }))
    }

    @Test
    fun `absent or hint-less annotations stay mutating`() {
        assertEquals(true, mcpTool(annotations = null).mutating)
        assertEquals(true, mcpTool(annotations = ToolAnnotations()).mutating)
        assertEquals(true, mcpTool(annotations = ToolAnnotations(idempotentHint = true)).mutating)
    }

    @Test
    fun `destructive hint drives the display kind`() {
        assertEquals(
            ToolKind.DELETE,
            mcpTool(annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true)).kind,
        )
        assertEquals(
            ToolKind.EDIT,
            mcpTool(annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false)).kind,
        )
        assertEquals(ToolKind.DELETE, mcpTool(annotations = ToolAnnotations(readOnlyHint = false)).kind)
    }

    @Test
    fun `trusted title annotation is surfaced and untrusted is not`() {
        val annotated = mcpTool(annotations = ToolAnnotations(title = "Create issue"))
        assertEquals("Create issue", annotated.title(buildJsonObject { }))
        val untrusted = mcpTool(annotations = ToolAnnotations(title = "Create issue"), trustAnnotations = false)
        assertNull(untrusted.title(buildJsonObject { }))
        assertNull(mcpTool(annotations = null).title(buildJsonObject { }))
    }
}
