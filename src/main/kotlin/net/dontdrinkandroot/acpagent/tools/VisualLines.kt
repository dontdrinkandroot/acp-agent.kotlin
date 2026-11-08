package net.dontdrinkandroot.acpagent.tools

/**
 * The shared per-line cap for rendered tool output (read_file, web_fetch):
 * a huge single line (minified bundle, generated JSON) must not flood the
 * context; the marker tells the model the line was shortened.
 */
internal const val MAX_LINE_CHARS = 2000
internal const val TRUNCATED_LINE_SUFFIX = "... [truncated]"

internal fun truncateLine(line: String): String =
    if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + TRUNCATED_LINE_SUFFIX else line

/**
 * Splits [content] into visual lines by the [StreamingLineReader] convention:
 * a trailing newline is a line *terminator*, not an extra empty line
 * (`"a\n"` is one line), while an empty line between terminators is real
 * (`"a\n\n"` is two lines) and an empty file has zero lines. Consumers that
 * number lines (grep matches, read_file and web_fetch rendering) must use
 * this so their numbers agree and no phantom line past EOF can match or
 * render.
 */
internal fun visualLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split('\n')
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/**
 * The [visualLines] split keeping each line's own terminator (`"a\nb"` ->
 * `["a\n", "b"]`, `"a\nb\n"` -> `["a\n", "b\n"]`). Window slices joined from
 * these keep the exact terminator placement [ReadResult.content] promises, so
 * an unterminated final line never grows a phantom newline.
 */
internal fun rawVisualLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
        val newline = content.indexOf('\n', start)
        if (newline == -1) {
            lines += content.substring(start)
            break
        }
        lines += content.substring(start, newline + 1)
        start = newline + 1
    }
    return lines
}

/**
 * The shared paging cap of the numbered-window tools: `read_file`'s `limit`
 * and `web_fetch`'s `maxLines` bound both (issue #81 - one constant, so the
 * two paging surfaces cannot drift apart).
 */
internal const val MAX_WINDOW_LINES = 2000

/**
 * Renders [lines] as the numbered window both `read_file` and `web_fetch`
 * return (issue #81): a fixed-width 1-based line number, a `│` delimiter and
 * the line content verbatim - the single implementation of the convention
 * that must keep all line numbers in agreement.
 *
 * A trailing carriage return is stripped per line so CRLF content renders by
 * visual lines, and every line is clamped at [MAX_LINE_CHARS] via
 * [truncateLine] (the display cap belongs at the render boundary, so the
 * client fs proxy - which passes lines through verbatim - is clamped exactly
 * like the local store). An empty window renders the explicit
 * `(empty content)` marker instead of a phantom numbered line.
 *
 * Unless [complete], a footer states the shown range and the argument that
 * continues the paging, using the caller's own argument names ([lineArg] and
 * [limitArg], the wire contract of each tool); an unknown [total] renders as
 * `?`.
 */
internal fun renderNumberedWindow(
    lines: List<String>,
    startLine: Int,
    total: Int?,
    complete: Boolean,
    lineArg: String,
    limitArg: String,
): String {
    if (lines.isEmpty()) return "(empty content)"
    val body = lines.mapIndexed { index, text ->
        val cleaned = if (text.endsWith("\r")) text.dropLast(1) else text
        "${(startLine + index).toString().padStart(4)}│${truncateLine(cleaned)}"
    }.joinToString("\n")
    if (complete) return body
    val shownEnd = startLine + lines.size - 1
    val totalText = total?.toString() ?: "?"
    return "$body\n\n(Showing lines $startLine-$shownEnd of $totalText. " +
            "Use $lineArg=${shownEnd + 1} and $limitArg to continue.)"
}

/**
 * The start-line bounds guard shared by the numbered-window tools (issue
 * #45): null when [value] is unset or usable, the user-facing error otherwise.
 * A 1-based start line must be positive, and a value outside the Int range
 * would overflow at the `toInt()` narrowing and defeat the window and
 * past-EOF guards, so it is refused here instead.
 */
internal fun lineWindowStartError(argName: String, value: Long?): String? = when {
    value == null -> null
    value < 1 -> "'$argName' must be a positive integer (1-based)"
    value > Int.MAX_VALUE -> "'$argName' must be at most ${Int.MAX_VALUE}"
    else -> null
}