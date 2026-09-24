package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

/**
 * `web_fetch`: fetches a URL and returns its content as numbered, line-paged
 * text (same rendering as read_file). Prompt-free in every mode (no
 * filesystem targets), so the URL always leads in the tool-call title.
 */
public class WebFetchTool : AgentTool {
    override val name = "web_fetch"
    override val description =
        "Fetches a web page or resource over HTTP(S) and returns it as text. HTML is converted to plain " +
                "text (scripts/styles stripped, block elements as lines); other text types (JSON, markdown, " +
                "source code) pass through. Each line is prefixed with a fixed-width 1-based line number and " +
                "a '│' delimiter. When the window does not cover the whole content a footer shows the shown " +
                "range and the 'startLine' to continue from. Non-text content (PDF, images, archives) is " +
                "refused with a hint to download it via bash instead. Private/loopback hosts are refused " +
                "unless ACP_WEB_FETCH_ALLOW_PRIVATE=1."
    override val kind = ToolKind.FETCH
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("url", PropType.STRING, "The http(s) URL to fetch."),
        optional("startLine", PropType.INTEGER, "First line to return (1-based). Defaults to the start."),
        optional(
            "maxLines",
            PropType.INTEGER,
            "Maximum number of lines to return (1-$MAX_LINES). Defaults to $DEFAULT_LINES.",
        ),
    )

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val url = arguments.stringArg("url") ?: return ToolResult(arguments.argError("url"), true)
        if (arguments.isNullArg("startLine")) return ToolResult(arguments.argError("startLine", "an integer"), true)
        if (arguments.isNullArg("maxLines")) return ToolResult(arguments.argError("maxLines", "an integer"), true)
        val startLine = arguments.longArg("startLine")
        val maxLines = arguments.longArg("maxLines")
        if (startLine != null && startLine < 1) return ToolResult("'startLine' must be a positive integer (1-based)", true)
        if (maxLines != null && (maxLines < 1 || maxLines > MAX_LINES)) {
            return ToolResult("'maxLines' must be between 1 and $MAX_LINES", true)
        }
        return executeSafely("Fetch failed") {
            val result = WebFetcher.fetch(url, allowPrivate = context.webFetchAllowPrivate)
            formatLines(result, (startLine ?: 1L).toInt(), (maxLines ?: DEFAULT_LINES).toInt())
        }
    }

    /**
     * Renders the fetched lines as a numbered window with the read_file
     * conventions: fixed-width 1-based line numbers, `│` delimiter, per-line
     * truncation marker and the continue footer when the window does not
     * cover everything.
     */
    private fun formatLines(result: WebFetchResult, start: Int, limit: Int): ToolResult {
        val all = result.lines
        // A successful fetch that converts to zero lines (e.g. an empty HTML
        // body) is a complete, valid read - not a past-EOF error.
        if (all.isEmpty()) return ToolResult("(empty content)")
        if (start > all.size) {
            return ToolResult("startLine $start is past the end of the content (${all.size} lines)", true)
        }
        val window = all.drop(start - 1).take(limit)
        val body = window.mapIndexed { index, line ->
            "${(start + index).toString().padStart(4)}│${truncateLine(line)}"
        }.joinToString("\n")
        val complete = start + window.size - 1 >= all.size
        val text = if (complete) {
            body
        } else {
            val shownEnd = start + window.size - 1
            "$body\n\n(Showing lines $start-$shownEnd of ${all.size}. Use startLine=${shownEnd + 1} and maxLines to continue.)"
        }
        return ToolResult(text)
    }

    private companion object {
        const val MAX_LINES = 2000
        const val DEFAULT_LINES = 500
    }
}
