package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*

public class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description = "List the contents of a directory."
    override val kind = ToolKind.READ
    override val mutating = false
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("path") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Directory path, absolute or relative to the working directory."))
            }
        })
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        return runCatching {
            val fs = SystemFileSystem
            val dir = Path(path)
            val meta = fs.metadataOrNull(dir)
            if (meta == null || !meta.isDirectory) return@runCatching ToolResult("Not a directory: $path", true)
            ToolResult(fs.list(dir).map { it.name }.sorted().joinToString("\n"))
        }.getOrElse { ToolResult("List failed: ${it.message}", true) }
    }
}