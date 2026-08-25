package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.*

public class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Write text content to a file (creates or overwrites)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = listOf(SessionModeId("build"), SessionModeId("bash"))
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("path") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("File path, absolute or relative to the working directory."))
            }
            putJsonObject("content") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Full file content; replaces existing content."))
            }
        })
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("content"))
        }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val content = arguments["content"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'content'", true)
        return runCatching {
            context.fileStore.writeFile(path, content)
            ToolResult("Written $path")
        }.getOrElse { ToolResult("Write failed: ${it.message}", true) }
    }
}