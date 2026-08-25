package net.dontdrinkandroot.acpagent.mcp

import com.agentclientprotocol.model.ToolKind
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolResult

public class McpServerConnection(
    val name: String,
    private val client: Client,
    private val onClose: (suspend () -> Unit)? = null,
) {
    public suspend fun listTools(): List<Tool> {
        val result = client.listTools()
        return result.tools
    }

    public suspend fun callTool(toolName: String, arguments: JsonObject): McpCallResult {
        val argMap = arguments.toJsonValueMap()
        val result = client.callTool(name = toolName, arguments = argMap)
        val content = result.content.mapNotNull { block ->
            (block as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text
        }.joinToString("\n")
        return McpCallResult(content, result.isError == true)
    }

    public suspend fun close() {
        client.close()
        onClose?.invoke()
    }
}

public data class McpCallResult(
    val text: String,
    val isError: Boolean
)

public class McpTool(private val server: McpServerConnection, private val tool: Tool) : AgentTool {
    override val name = tool.name
    override val description = tool.description ?: "MCP tool '${tool.name}' from server '${server.name}'"
    override val kind = ToolKind.OTHER
    override val mutating = true
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        tool.inputSchema.properties?.let { put("properties", it) }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val result = server.callTool(name, arguments)
        return ToolResult(result.text, result.isError)
    }
}

public fun kotlinx.serialization.json.JsonObject.toJsonValueMap(): Map<String, Any?> = buildMap {
    for ((key, value) in this@toJsonValueMap) {
        put(key, value.toAny())
    }
}

private fun kotlinx.serialization.json.JsonElement.toAny(): Any? = when (this) {
    is kotlinx.serialization.json.JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        content.toIntOrNull() != null -> content.toInt()
        content.toDoubleOrNull() != null -> content.toDouble()
        else -> content
    }

    is kotlinx.serialization.json.JsonObject -> toJsonValueMap()
    is kotlinx.serialization.json.JsonArray -> map { it.toAny() }
    else -> null
}

public fun createMcpClientInfo(): Implementation = Implementation(name = "acp-agent", version = "0.1.0")
