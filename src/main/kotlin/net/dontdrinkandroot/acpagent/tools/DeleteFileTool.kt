package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

/**
 * Deletes a file. The result carries the removed content as a diff so clients
 * can render the deletion without the client fs proxy. Directories and
 * symlinks are refused: deleting a symlink would silently remove a link the
 * user may not have seen, and directory deletion belongs to
 * `delete_directory`.
 */
public class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description = "Delete a file (refuses directories and symlinks; use delete_directory for directories)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = BUILD_AND_BASH_MODES
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "File to delete, absolute or relative to the working directory."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments.stringArg("path") ?: return ToolResult(arguments.argError("path"), true)
        val path = absoluteToolPath(context.cwd, rawPath)
        return runCatching {
            val fs = SystemFileSystem
            val target = Path(path)
            val meta = fs.metadataOrNull(target)
                ?: return@runCatching ToolResult("Path not found: $path", true)
            if (meta.isDirectory) return@runCatching ToolResult("Is a directory: $path (use delete_directory)", true)
            if (Files.isSymbolicLink(java.nio.file.Path.of(path))) {
                return@runCatching ToolResult("Refusing to delete a symlink: $path", true)
            }
            val diff = deleteResultDiff(path, context)
            fs.delete(target, mustExist = true)
            ToolResult("Deleted $path", diff = diff)
        }.getOrElse { ToolResult("Delete failed: ${it.message}", true) }
    }
}

/**
 * Best-effort read of the deleted file so the result can carry a `Diff`
 * content block showing the removed content. Skipped when the client fs proxy
 * is active (the client renders the modification itself) and when the content
 * exceeds [MAX_DIFF_CONTENT_LENGTH] to keep the wire payload sane.
 */
private suspend fun deleteResultDiff(path: String, context: ToolContext): ToolResultDiff? {
    if (context.fileStore is ClientFileStore) return null
    val oldText = try {
        context.fileStore.readRaw(path)
    } catch (e: Exception) {
        return null
    }
    if (oldText.length > MAX_DIFF_CONTENT_LENGTH) return null
    return ToolResultDiff(path, "", oldText)
}
