package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.*

public class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file. When the client supports it, reads via the IDE client (sees unsaved editor state)."
    override val kind = ToolKind.READ
    override val mutating = false
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("path") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("File path, absolute or relative to the working directory."))
            }
            putJsonObject("line") {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("First line to return (1-based). Defaults to the start of the file."))
            }
            putJsonObject("limit") {
                put("type", JsonPrimitive("integer"))
                put(
                    "description",
                    JsonPrimitive("Maximum number of lines to return. Defaults to the rest of the file.")
                )
            }
        })
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val line = arguments["line"]?.jsonPrimitive?.longOrNull
        val limit = arguments["limit"]?.jsonPrimitive?.longOrNull
        return runCatching {
            ToolResult(context.fileStore.readFile(path, line?.toInt(), limit?.toInt()))
        }.getOrElse { ToolResult("Read failed: ${it.message}", true) }
    }
}
