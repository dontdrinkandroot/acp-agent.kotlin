package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

public class EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description = "Replace an exact substring in a file (old_string must match exactly once). " +
            "Matching is raw: a CRLF file contains \\r\\n line breaks, so old_string spanning lines must include them."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = listOf(SessionModeId("build"), SessionModeId("bash"))
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "File path, absolute or relative to the working directory."),
        required("old_string", PropType.STRING, "Exact substring to replace; must match exactly once in the file."),
        required("new_string", PropType.STRING, "Replacement text; empty removes the old_string."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments.stringArg("path") ?: return ToolResult(arguments.argError("path"), true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val oldString =
            arguments.stringArg("old_string") ?: return ToolResult(arguments.argError("old_string"), true)
        if (oldString.isEmpty()) return ToolResult("old_string must not be empty", true)
        val newString = arguments.stringArg("new_string") ?: ""

        return runCatching {
            val content = context.fileStore.readRaw(path)
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
            val updated = content.replace(oldString, newString)
            context.fileStore.writeFile(path, updated)
            ToolResult("Edited $path", diff = editResultDiff(path, content, updated, context))
        }.getOrElse { ToolResult("Edit failed: ${it.message}", true) }
    }
}

/**
 * The edit result carries a whole-file `Diff` content block (old/new full
 * content), consistent with write_file and delete_file, so clients can render
 * the change as a file diff. Skipped when the client fs proxy is active (the
 * client renders the modification itself) and when either side exceeds
 * [MAX_DIFF_CONTENT_LENGTH] to keep the wire payload sane.
 */
private suspend fun editResultDiff(
    path: String,
    oldContent: String,
    newContent: String,
    context: ToolContext,
): ToolResultDiff? {
    if (context.fileStore is ClientFileStore) return null
    if (oldContent.length > MAX_DIFF_CONTENT_LENGTH || newContent.length > MAX_DIFF_CONTENT_LENGTH) return null
    return ToolResultDiff(path, newContent, oldContent)
}
