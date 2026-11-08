package net.dontdrinkandroot.acpagent.tools

import kotlinx.io.files.Path

/**
 * A source line as read by [StreamingLineReader]: the line text without its
 * `\n`, plus whether it was terminated by one (the final line of a file
 * without a trailing newline is not) and whether it was clamped at
 * [MAX_LINE_ACCUMULATION_BYTES] ([truncated] means bytes were dropped).
 */
internal data class SourceLine(val text: String, val terminated: Boolean, val truncated: Boolean = false)

/**
 * Per-line accumulation cap of [StreamingLineReader] (issue #24): a line is
 * kept only up to this many bytes, the remainder is skipped to the next `\n`
 * and the line comes back with [SourceLine.truncated]. The cap bounds the
 * reader's memory for no-newline files (minified bundles, single-line JSON)
 * now that the whole-file size caps are gone; the rendered output never
 * shows the cut because the kept prefix is always at least MAX_LINE_CHARS/4
 * chars (UTF-8 is at most 4 bytes/char), so the display caps (2000/500 chars)
 * mark the line themselves.
 */
internal const val MAX_LINE_ACCUMULATION_BYTES: Int = 64 * 1024

/**
 * Streams the file at [path] line by line without materializing it. Bytes are
 * split on `\n` (0x0A) at the byte level and each line is decoded separately,
 * so no JDK decoder state machine is involved (a chunked InputStreamReader
 * loop was observed to spin forever on zero-char reads under JDK 25). Line
 * terminators are `\n` only (raw `\r` is preserved so edit_file keeps
 * matching raw content); a trailing newline is a terminator, not an extra
 * empty line ([lineCount] counts visual lines). The final line counts even
 * when the file does not end with a newline. Memory stays bounded by
 * [MAX_LINE_ACCUMULATION_BYTES] per line ([SourceLine.truncated] marks a
 * clamped line).
 */
internal class StreamingLineReader(path: Path) {
    private val input = java.io.BufferedInputStream(java.io.FileInputStream(path.toString()), 8192)
    private val byteBuffer = ByteArray(8192)
    private var bufferPos = 0
    private var bufferLen = 0
    private val current = java.io.ByteArrayOutputStream()
    private var currentChars: SourceLine? = null
    private var pendingPartial = false
    private var lineOverflow = false

    /**
     * Whether a NUL byte was seen in any byte scanned so far, including the
     * clamped tails [readLine] skips (issue #24: the binary sniff must cover
     * the whole file, not just the accumulated prefixes). Grep drops a file
     * with this set instead of surfacing mojibake matches.
     */
    var sawNul = false
        private set

    /** Number of the line [readLine] will return next (1-based). */
    var lineNumber = 1
        private set

    /**
     * Total number of visual lines seen so far (grows as lines are consumed).
     * A partially read line in [current] counts; once it has been returned it
     * does not ([pendingPartial] is cleared).
     */
    val lineCount: Int
        get() = lineNumber - 1 + if (pendingPartial) 1 else 0

    /**
     * Returns the next line with its terminator status ([SourceLine.terminated]
     * is false for the unterminated final line of a file without a trailing
     * newline), or null at EOF. The line text excludes the `\n`, but `\r`
     * before `\n` is kept (the display layer strips it).
     */
    fun readLine(): SourceLine? {
        currentChars?.let {
            currentChars = null
            return it
        }
        while (true) {
            if (bufferPos >= bufferLen) {
                bufferLen = input.read(byteBuffer)
                bufferPos = 0
                if (bufferLen == -1) {
                    // No trailing newline: the accumulated remainder is the
                    // last visual line (returned once, then EOF).
                    if (current.size() == 0) return null
                    return completedLine(terminated = false)
                }
            }
            val newline = byteBuffer.indexOf(b = '\n'.code.toByte(), from = bufferPos, to = bufferLen)
            if (newline == -1) {
                appendCapped(bufferPos, bufferLen)
                bufferPos = bufferLen
                pendingPartial = true
                continue
            }
            appendCapped(bufferPos, newline)
            bufferPos = newline + 1
            return completedLine(terminated = true)
        }
    }

    /**
     * Appends bytes `from until to` up to the accumulation cap, marking
     * overflow; every byte of the range - kept or skipped - feeds the NUL
     * sniff.
     */
    private fun appendCapped(from: Int, to: Int) {
        for (i in from until to) if (byteBuffer[i] == 0.toByte()) sawNul = true
        val room = MAX_LINE_ACCUMULATION_BYTES - current.size()
        val writable = minOf(to - from, room)
        if (writable > 0) current.write(byteBuffer, from, writable)
        if (to - from > writable) lineOverflow = true
    }

    /** Decodes the accumulated prefix into the finished line and resets for the next one. */
    private fun completedLine(terminated: Boolean): SourceLine {
        val text = decode(current)
        current.reset()
        val truncated = lineOverflow
        lineOverflow = false
        pendingPartial = false
        lineNumber++
        return SourceLine(text, terminated = terminated, truncated = truncated)
    }

    private fun decode(buffer: java.io.ByteArrayOutputStream): String =
        String(buffer.toByteArray(), Charsets.UTF_8)

    fun close() {
        input.close()
    }
}

private fun ByteArray.indexOf(b: Byte, from: Int, to: Int): Int {
    for (i in from until to) if (this[i] == b) return i
    return -1
}

/**
 * Counts the visual lines of the file at [path] by a raw byte scan (issue
 * #24): `\n` count plus one when the file is non-empty and does not end in a
 * newline - the exact [visualLines] convention (trailing newline = terminator,
 * blank lines real, empty file = 0). No line is decoded or materialized, so
 * `readFile` can report the exact footer total for a truncated window without
 * walking the whole file's lines.
 */
internal fun countVisualLines(path: Path): Int {
    val input = java.io.BufferedInputStream(java.io.FileInputStream(path.toString()), 8192)
    val buffer = ByteArray(8192)
    var newlines = 0
    var sawAny = false
    var endsWithNewline = false
    input.use {
        while (true) {
            val read = it.read(buffer)
            if (read == -1) break
            for (i in 0 until read) if (buffer[i] == NEWLINE_BYTE) newlines++
            sawAny = true
            endsWithNewline = buffer[read - 1] == NEWLINE_BYTE
        }
    }
    return newlines + if (sawAny && !endsWithNewline) 1 else 0
}

private val NEWLINE_BYTE = '\n'.code.toByte()