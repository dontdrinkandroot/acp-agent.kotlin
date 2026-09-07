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

/**
 * An [AgentTool] backed by a single MCP tool. The server's behavior
 * annotations ([Tool.annotations]) drive the display and, when
 * [trustAnnotations] is set, the permission decision: `readOnlyHint: true`
 * marks the tool non-mutating so it runs without a permission prompt, while
 * absent/unset hints keep the pessimistic default (mutating, always prompts).
 * Annotations are untrusted hints per the MCP spec - trusting them is an
 * explicit operator decision (env `MCP_TRUST_ANNOTATIONS`, default enabled).
 */
public class McpTool(
    private val server: McpServerConnection,
    private val tool: Tool,
    private val trustAnnotations: Boolean,
) : AgentTool {
    override val name = tool.name
    override val description = tool.description ?: "MCP tool '${tool.name}' from server '${server.name}'"
    override val kind: ToolKind = annotationsKind(tool, trustAnnotations)
    override val mutating: Boolean = !(trustAnnotations && tool.annotations?.readOnlyHint == true)
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        tool.inputSchema.properties?.let { put("properties", it) }
    }

    /**
     * The server-provided display name, when annotated; null (bare tool name)
     * otherwise. MCP tool names are frequently prefixed machine names
     * (`mcp__github__create_issue`), so the annotation carries real value in
     * permission prompts - the JetBrains client renders only the title.
     */
    override fun title(arguments: JsonObject): String? = when {
        trustAnnotations -> tool.annotations?.title?.takeIf { it.isNotBlank() }
        else -> null
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val result = server.callTool(name, arguments)
        return ToolResult(result.text, result.isError)
    }
}

/**
 * Maps the server's behavior hints onto an ACP tool kind for display only.
 * A mutating tool that may be destructive (`destructiveHint != false`, the
 * spec default) shows as [ToolKind.DELETE]; a mutating additive tool as
 * [ToolKind.EDIT]; a read-only tool as [ToolKind.OTHER] (the agent has no
 * evidence about what it reads). Untrusted or absent hints degrade to
 * [ToolKind.OTHER].
 */
private fun annotationsKind(tool: Tool, trustAnnotations: Boolean): ToolKind {
    if (!trustAnnotations) return ToolKind.OTHER
    val annotations = tool.annotations ?: return ToolKind.OTHER
    if (annotations.readOnlyHint == true) return ToolKind.OTHER
    return if (annotations.destructiveHint == false) ToolKind.EDIT else ToolKind.DELETE
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
}

public fun createMcpClientInfo(): Implementation = Implementation(name = "acp-agent", version = "0.1.0")
