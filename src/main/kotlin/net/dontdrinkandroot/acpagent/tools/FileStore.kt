package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * File backend for the path-scoped file tools (read_file, write_file, edit_file).
 * Implementations come in two flavors: local disk access and delegation to the
 * client's fs methods (which see unsaved editor state and render reviewable
 * diffs). Containment is the agent's policy, applied on top by the permission
 * flow; the backend itself is a thin transport.
 */
internal interface FileStore {
    /**
     * True when the store's write path already renders the modification to the
     * user (the client fs proxy shows reviewable diffs and unsaved editor
     * state), so the file tools must not duplicate it: result diffs are
     * skipped and the wire stays lean. The local store renders nothing, so the
     * tools carry their own `ToolResultDiff`.
     */
    val rendersChange: Boolean
        get() = false

    /**
     * Reads the requested line window of the file at [path]. Returns the
     * selected window's raw text (each line with its own terminator, the same
     * representation the client fs proxy passes through), whether the
     * selection covers the whole file ([ReadResult.complete]) and the file's
     * full visual line count ([ReadResult.total]); the callers (ReadFileTool)
     * combine these to build the numbered, footer-anchored output and to
     * decide when raw content can be returned untouched.
     *
     * Display-oriented: lines over the per-line cap are truncated with a
     * marker. For exact bytes (edit_file matching, diff payloads) use
     * [readRaw].
     */
    suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult

    suspend fun writeFile(path: String, content: String)

    /**
     * Reads the whole file exactly (no per-line truncation) for edit_file and
     * diff pre-reads. Throws [FileAbsentException] when there is no such file
     * (the caller's "new file" signal), [FileTooLargeException] when it
     * exceeds the size cap and [FileStoreException] when it exists but cannot
     * be read - the two must stay distinguishable, because a diff's
     * `oldText = null` claims the file is new (ACP) and must never be used
     * for an unreadable existing file. The default is a loud unsupported error
     * so stores that cannot serve raw bytes fail the call instead of silently
     * returning wrong content.
     */
    suspend fun readRaw(path: String): String =
        throw FileStoreException("raw reads are unsupported by this file store")
}

internal data class ReadResult(
    val content: String,
    val complete: Boolean = false,
    val total: Int? = null,
)

internal class LocalFileStore : FileStore {
    private val fs = SystemFileSystem

    override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult {
        val file = Path(path)
        if (!fs.exists(file)) throw FileStoreException("file not found: $path")
        val metadata = fs.metadataOrNull(file) ?: throw FileStoreException("not a regular file: $path")
        // Only regular files are opened: a FIFO or device reports size -1 and
        // then blocks on open(2) or streams without end, hanging the turn.
        if (!metadata.isRegularFile) throw FileStoreException("not a regular file: $path")
        // No size cap here (issue #24): the windowing below streams and stops
        // at the window end, so even a huge file serves a small window - the
        // cap stays on [readRaw], which materializes the whole content.
        val visualCount = countVisualLines(file)
        if (line != null && line > visualCount) {
            throw FileStoreException(
                "line $line is past the end of the file ($visualCount line${if (visualCount == 1) "" else "s"})"
            )
        }
        val startLine = line ?: 1
        val maxWindowLines = limit ?: Int.MAX_VALUE
        val scanner = StreamingLineReader(file)
        val selected = StringBuilder()
        var windowLineCount = 0
        try {
            // Lines before the window are walked (there is no index), but the
            // scan early-exits as soon as the window is filled - everything
            // after it is never decoded.
            while (windowLineCount < maxWindowLines) {
                // readLine() advances lineNumber past the line it returns, so
                // the number of the line being read must be captured first.
                val lineNumber = scanner.lineNumber
                val scannedLine = scanner.readLine() ?: break
                if (lineNumber < startLine) continue
                if (scannedLine.truncated || scannedLine.text.length > MAX_LINE_CHARS) {
                    // Per-line cap: see [truncateLine]. A reader-clamped line
                    // ([SourceLine.truncated]) is always marked even though its
                    // kept prefix exceeds the display cap anyway.
                    selected.append(scannedLine.text, 0, minOf(MAX_LINE_CHARS, scannedLine.text.length))
                    selected.append(TRUNCATED_LINE_SUFFIX)
                } else {
                    selected.append(scannedLine.text)
                }
                // The window keeps each line's own terminator (the same
                // representation the client fs proxy passes through), so a
                // single empty line is "\n", not "" - visualLines can then
                // count and render the window unambiguously.
                if (scannedLine.terminated) selected.append('\n')
                windowLineCount++
            }
        } finally {
            scanner.close()
        }
        // The window covers the whole file when it reaches at least the last
        // visible line (a trailing newline is a terminator, not an extra
        // empty line).
        val complete = startLine + windowLineCount - 1 >= visualCount
        return ReadResult(content = selected.toString(), complete = complete, total = visualCount)
    }

    override suspend fun writeFile(path: String, content: String) {
        val fs = SystemFileSystem
        fs.createDirectories(Path(path).parent ?: Path("."))
        fs.sink(Path(path)).buffered().use { it.writeString(content) }
    }

    override suspend fun readRaw(path: String): String {
        val file = Path(path)
        if (!fs.exists(file)) throw FileAbsentException("file not found: $path")
        val metadata = fs.metadataOrNull(file) ?: throw FileStoreException("not a regular file: $path")
        // See readFile: never open a FIFO/device (it would block or stream
        // forever - this guard also protects the edit/diff pre-reads).
        if (!metadata.isRegularFile) throw FileStoreException("not a regular file: $path")
        val size = metadata.size
        if (size > MAX_FILE_SIZE_BYTES) throw FileTooLargeException(fileTooLargeMessage(size))
        return fs.source(file).buffered().use { it.readString() }
    }

    internal companion object {
        const val MAX_FILE_SIZE_BYTES: Long = 20L * 1024 * 1024

        fun fileTooLargeMessage(bytes: Long): String =
            "file too large (${formatBytes(bytes)}; limit ${formatBytes(MAX_FILE_SIZE_BYTES)}); " +
                    "use bash (e.g. grep/head/tail) to inspect it"
    }
}

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

/**
 * Backs file tools with the ACP client's fs methods (host-privileged, but
 * renders modifications as reviewable diffs and sees unsaved editor state).
 */
internal class ClientFileStore(
    private val client: ClientSessionOperations,
) : FileStore {
    override val rendersChange: Boolean = true

    override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult {
        val content = client.fsReadTextFile(
            path = path,
            line = line?.toUInt(),
            limit = limit?.toUInt(),
        ).content
        val startLine = line ?: 1
        val windowLines = rawVisualLines(content).size
        // Issue #6: a client that ignores `line`/`limit` (the ACP TypeScript
        // reference returns the whole file) delivers more lines than the
        // requested window - its response is not a window at all, so the
        // requested window is sliced locally from the whole file.
        if (limit != null && windowLines > limit) return wholeFileWindow(path, line, limit)
        // The response shape alone cannot say whether the window is complete:
        // an exact-`limit` window leaves EOF unknown, and a window starting
        // past line 1 cannot prove its own numbering to a limit-ignoring
        // client. Ambiguous windows are settled by asking for the single line
        // right after the window (issue #6).
        val ambiguous = startLine > 1 || (limit != null && windowLines == limit)
        if (!ambiguous) return ReadResult(content = content, complete = true, total = null)
        // The probe is best-effort: when it cannot settle the window, the read
        // still succeeds but claims truncated (conservative, issue #6) - a
        // phantom footer beats a silently missing one. Cancellation still
        // propagates.
        val probe = try {
            client.fsReadTextFile(
                path = path,
                line = (startLine.toLong() + windowLines).toUInt(),
                limit = 1u,
            ).content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReadResult(content = content, complete = false, total = null)
        }
        val probeLines = rawVisualLines(probe)
        return when {
            // Nothing after the window: it covers the rest of the file.
            probeLines.isEmpty() -> ReadResult(content = content, complete = true, total = null)
            // A conforming client returns at most one line for `limit = 1`;
            // more - or the whole file again - means `limit` was ignored.
            probe == content || probeLines.size > 1 -> wholeFileWindow(path, line, limit)
            // Exactly one different line follows the window.
            else -> ReadResult(content = content, complete = false, total = null)
        }
    }

    /**
     * The whole-file fallback for limit-ignoring clients: the full content is
     * re-read and the requested window is sliced locally (each line with its
     * own terminator), so `complete` and `total` are computed from the real
     * line count instead of guessed from the response shape.
     */
    private suspend fun wholeFileWindow(path: String, line: Int?, limit: Int?): ReadResult {
        val whole = rawVisualLines(client.fsReadTextFile(path = path, line = null, limit = null).content)
        val startLine = line ?: 1
        if (line != null && line > whole.size) {
            throw FileStoreException(
                "line $line is past the end of the file (${whole.size} line${if (whole.size == 1) "" else "s"})"
            )
        }
        val window = whole.drop(startLine - 1).let { if (limit != null) it.take(limit) else it }
        return ReadResult(
            content = window.joinToString(""),
            complete = startLine + window.size - 1 >= whole.size,
            total = whole.size,
        )
    }

    override suspend fun writeFile(path: String, content: String) {
        client.fsWriteTextFile(path = path, content = content)
    }

    override suspend fun readRaw(path: String): String =
        client.fsReadTextFile(path = path, line = null, limit = null).content
}

internal open class FileStoreException(message: String) : Exception(message)

/**
 * Raised by [FileStore.readRaw] when there is no file at the path. The
 * dedicated type lets diff pre-reads tell genuine absence (a new file, so the
 * diff's `oldText` is null) apart from a file that exists but cannot be read.
 */
internal class FileAbsentException(message: String) : FileStoreException(message)

/** Raised by [FileStore.readRaw] when the file exceeds the size cap. */
internal class FileTooLargeException(message: String) : FileStoreException(message)

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
