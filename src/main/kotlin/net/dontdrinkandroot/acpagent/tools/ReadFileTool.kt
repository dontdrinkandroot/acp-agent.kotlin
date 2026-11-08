package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

public class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file. When the client supports it, reads via the IDE client (sees unsaved editor state). " +
                "Each line is prefixed with a fixed-width 1-based line number followed by a '│'; the content — " +
                "including its leading indentation — is verbatim after the '│'. The prefix is display-only — " +
                "edit_file matches raw content, so strip the 'number│' prefix before using a line in " +
                "old_string/new_string. " +
                "When the returned window does not cover the whole file a footer shows the shown range and the " +
                "'line' to continue from. Read the whole file by leaving line unset and using a limit at least " +
                "the file's size (all lines are numbered), or page with line/limit. " +
                "Files matching an exclusion rule (the .env*.local secret files and .ai/run.json) are refused."
    override val kind = ToolKind.READ
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "File path, absolute or relative to the working directory."),
        required("limit", PropType.INTEGER, "Maximum number of lines to return (1-$MAX_WINDOW_LINES)."),
        optional("line", PropType.INTEGER, "First line to return (1-based). Defaults to the start of the file."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = when (val target = resolveToolTarget(context, arguments)) {
            is ToolTarget.Rejected -> return target.error
            is ToolTarget.Resolved -> target.path
        }
        if (arguments.isNullArg("line")) return ToolResult(arguments.argError("line", "an integer"), true)
        val line = arguments.longArg("line")
        val limit = arguments.longArg("limit")
        lineWindowStartError("line", line)?.let { return ToolResult(it, true) }
        if (limit == null) return ToolResult(arguments.argError("limit", "an integer"), true)
        if (limit < 1 || limit > MAX_WINDOW_LINES) {
            return ToolResult("'limit' must be between 1 and $MAX_WINDOW_LINES", true)
        }
        return executeSafely("Read failed") {
            val start = line?.toInt() ?: 1
            val read = context.fileStore.readFile(path, line?.toInt(), limit.toInt())
            // The window is rendered by the shared numbered-window convention
            // (issue #81): the footer appears only when the window does not
            // cover the whole file (nothing below it to continue from).
            ToolResult(
                renderNumberedWindow(
                    lines = visualLines(read.content),
                    startLine = start,
                    total = read.total,
                    complete = read.complete,
                    lineArg = "line",
                    limitArg = "limit",
                )
            )
        }
    }
}
