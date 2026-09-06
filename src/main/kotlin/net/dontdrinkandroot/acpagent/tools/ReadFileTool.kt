package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.*

public class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file. When the client supports it, reads via the IDE client (sees unsaved editor state). " +
                "Lines are prefixed with 1-based line numbers (cat -n style); the prefix is display-only — edit_file " +
                "matches raw content, so strip the numbers before using a line in old_string/new_string. " +
                "When the returned window does not cover the whole file a footer shows the shown range and the " +
                "'line' to continue from. Read the whole file by leaving line unset and using a limit at least " +
                "the file's size (all lines are numbered), or page with line/limit."
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
                    JsonPrimitive("Maximum number of lines to return (1-$MAX_READ_LIMIT).")
                )
            }
        })
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("limit"))
        }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val line = arguments["line"]?.jsonPrimitive?.longOrNull
        val limit = arguments["limit"]?.jsonPrimitive?.longOrNull
        if (line != null && line < 1) return ToolResult("'line' must be a positive integer (1-based)", true)
        if (limit == null) return ToolResult("Missing 'limit'", true)
        if (limit < 1 || limit > MAX_READ_LIMIT) {
            return ToolResult("'limit' must be between 1 and $MAX_READ_LIMIT", true)
        }
        return runCatching {
            val start = line?.toInt() ?: 1
            val read = context.fileStore.readFile(path, line?.toInt(), limit.toInt())
            if (read.complete) {
                // The window covers the whole file: numbered, no footer
                // (nothing below the window).
                ToolResult(formatRead(read.content, start, read.total))
            } else {
                // Truncated window: numbered with a footer stating the shown
                // range and where to continue.
                ToolResult(formatRead(read.content, start, read.total, footer = true))
            }
        }.getOrElse { ToolResult("Read failed: ${it.message}", true) }
    }

    /**
     * Renders the read window as 1-indexed numbered lines (cat -n style),
     * stripping CRLF carriage returns and the phantom trailing empty line
     * (a trailing newline is a line terminator, not an extra empty line) so
     * CRLF and trailing-newline files render by visual lines. When [footer]
     * is set appends a footer stating the shown range and how to continue,
     * so a truncated read is unambiguous and the model can page forward.
     */
    private fun formatRead(content: String, start: Int, total: Int?, footer: Boolean = false): String {
        var lines = content.split('\n')
        if (content.endsWith("\n") && lines.last().isEmpty()) lines = lines.dropLast(1)
        val body = lines.mapIndexed { index, text ->
            val cleaned = if (text.endsWith("\r")) text.dropLast(1) else text
            "${(start + index).toString().padStart(4)}  $cleaned"
        }.joinToString("\n")
        return if (footer) {
            val shownEnd = start + lines.size - 1
            val totalText = total?.toString() ?: "?"
            "$body\n\n(Showing lines $start-$shownEnd of $totalText. Use line=${shownEnd + 1} and limit to continue.)"
        } else {
            body
        }
    }

    private companion object {
        const val MAX_READ_LIMIT = 2000
    }
}
