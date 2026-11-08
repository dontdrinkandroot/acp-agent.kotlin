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