package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

public class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Write text content to a file (creates or overwrites)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = listOf(SessionModeId("build"), SessionModeId("bash"))
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "File path, absolute or relative to the working directory."),
        required("content", PropType.STRING, "Full file content; replaces existing content."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    /**
     * A human-readable title so the model (and the permission prompt) shows what
     * is being replaced: a whole-file write with the content size, not a bare
     * "write_file". The size signals the overwrite scope (e.g. a rewrite of a
     * large file) without needing to I/O in the title.
     */
    override fun title(arguments: JsonObject): String? {
        val path = arguments.stringArg("path") ?: return null
        val content = arguments.stringArg("content") ?: return null
        return formatToolTitle(name, buildJsonObject {
            put("path", JsonPrimitive(path))
            put("size", JsonPrimitive("${content.length} chars"))
        })
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments.stringArg("path") ?: return ToolResult(arguments.argError("path"), true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val content = arguments.stringArg("content") ?: return ToolResult(arguments.argError("content"), true)
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
 * skips the diff. The client fs proxy already renders the modification itself,
 * so the diff is skipped there; oversized content is skipped to keep the wire
 * payload sane.
 */
private suspend fun writeResultDiff(path: String, newText: String, context: ToolContext): ToolResultDiff? {
    if (context.fileStore is ClientFileStore) return null
    val oldText = try {
        context.fileStore.readRaw(path)
    } catch (e: FileTooLargeException) {
        return null
    } catch (e: FileStoreException) {
        // A missing file is a new-file write: the diff shows oldText = null.
        null
    } catch (e: Exception) {
        return null
    }
    if (oldText != null && oldText.length > MAX_DIFF_CONTENT_LENGTH) return null
    return ToolResultDiff(path, newText, oldText)
}
