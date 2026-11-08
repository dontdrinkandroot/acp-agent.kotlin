package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject

/**
 * Deletes a file. The result carries the removed content as a diff so clients
 * can render the deletion without the client fs proxy. Directories and
 * symlinks are refused: deleting a symlink would silently remove a link the
 * user may not have seen, and directory deletion belongs to
 * `delete_directory`.
 */
public class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description =
        "Delete a file (refuses directories and symlinks; use delete_directory for directories). " +
                "Files matching an exclusion rule (the .env*.local secret files and .ai/run.json) are refused."
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
        val excludedRule = context.fileExclusions.matchingRuleForTarget(context.cwd, path)
        if (excludedRule != null) return ToolResult(exclusionError(path, excludedRule), true)
        return executeSafely("Delete failed") {
            val fs = SystemFileSystem
            val target = Path(path)
            val meta = fs.metadataOrNull(target)
                ?: return@executeSafely ToolResult("Path not found: $path", true)
            if (meta.isDirectory) return@executeSafely ToolResult("Is a directory: $path (use delete_directory)", true)
            if (isSymbolicLink(path)) {
                return@executeSafely ToolResult("Refusing to delete a symlink: $path", true)
            }
            val outcome = deleteDiffOutcome(path, context)
            fs.delete(target, mustExist = true)
            mutationResult("Deleted $path", path, outcome)
        }
    }
}

/**
 * Best-effort read of the deleted file so the result can carry a `Diff`
 * content block showing the removed content. The delete is local-disk-only
 * (ACP has no fs delete method), so no store-rendered suppression applies -
 * this diff is the client's only change evidence for the deletion
 * (issues #56/#58, the shared rule in `DiffPolicy.kt`). Content over
 * [MAX_DIFF_CONTENT_LENGTH] is dropped with a visible note to keep the wire
 * payload sane (issue #57). Any other read failure drops only the diff - with
 * its reason named - and the delete still proceeds, so an unreadable file
 * stays deletable.
 */
private suspend fun deleteDiffOutcome(path: String, context: ToolContext): DiffOutcome {
    val oldText = try {
        context.fileStore.readRaw(path)
    } catch (e: CancellationException) {
        // A cancelled turn must abort; the pre-read is best-effort but the
        // cancellation is not: swallowing it here would defeat the tool's
        // own rethrow.
        throw e
    } catch (e: Exception) {
        return DiffOutcome.Omit("cannot read original: ${e.message ?: e}")
    }
    return toolResultDiff(path, "", oldText)
}
