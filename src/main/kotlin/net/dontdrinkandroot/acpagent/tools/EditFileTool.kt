package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.*

public class EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description = "Replace an exact substring in a file (old_string must match exactly once)."
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
            putJsonObject("old_string") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Exact substring to replace; must match exactly once in the file."))
            }
            putJsonObject("new_string") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Replacement text; empty removes the old_string."))
            }
        })
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("old_string"))
            add(JsonPrimitive("new_string"))
        }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val oldString =
            arguments["old_string"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'old_string'", true)
        if (oldString.isEmpty()) return ToolResult("old_string must not be empty", true)
        val newString = arguments["new_string"]?.jsonPrimitive?.content ?: ""

        return runCatching {
            val content = context.fileStore.readFile(path, null, null)
            // Count non-overlapping occurrences via indexOf so the number
            // matches exactly what String.replace replaces: overlapping
            // occurrences (e.g. old_string "aa" in "aaaa") are counted as
            // replace counts them, not as windowed would.
            var count = 0
            var occurrence = content.indexOf(oldString)
            while (occurrence != -1) {
                count++
                occurrence = content.indexOf(oldString, occurrence + oldString.length)
            }
            if (count == 0) return@runCatching ToolResult("old_string not found in $path", true)
            if (count > 1) return@runCatching ToolResult(
                "old_string matches $count times in $path; make it unique",
                true
            )
            context.fileStore.writeFile(path, content.replace(oldString, newString))
            ToolResult("Edited $path")
        }.getOrElse { ToolResult("Edit failed: ${it.message}", true) }
    }
}