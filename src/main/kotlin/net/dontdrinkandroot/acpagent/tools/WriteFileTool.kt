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
        val rawPath = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val content = arguments["content"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'content'", true)
        return runCatching {
            val diff = writeResultDiff(path, content, context)
            context.fileStore.writeFile(path, content)
            ToolResult("Written $path", diff = diff)
        }.getOrElse { ToolResult("Write failed: ${it.message}", true) }
    }
}

/**
 * Best-effort pre-read of the target so the result can carry a `Diff` content
 * block for clients that render tool-call diffs without the client fs proxy.
 * A missing file yields a diff with `oldText = null`; any other read failure
 * skips the diff. Oversized content is skipped to keep the wire payload sane.
 */
private suspend fun writeResultDiff(path: String, newText: String, context: ToolContext): ToolResultDiff? {
    val oldText = try {
        context.fileStore.readFile(path, null, null)
    } catch (e: FileStoreException) {
        return ToolResultDiff(path, newText, null)
    } catch (e: Exception) {
        return null
    }
    if (oldText.length > MAX_DIFF_CONTENT_LENGTH) return null
    return ToolResultDiff(path, newText, oldText)
}