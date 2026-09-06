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
     * Reads the requested line window of the file at [path]. Returns the
     * selected content, the raw number of lines it contains, whether the
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
     * diff pre-reads. Throws [FileTooLargeException] when the file exceeds the
     * size cap and [FileStoreException] when it cannot be read. The default is
     * a loud unsupported error so stores that cannot serve raw bytes fail the
     * call instead of silently returning wrong content.
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
        val size = fs.metadataOrNull(file)?.size ?: throw FileStoreException("not a regular file: $path")
        if (size > MAX_FILE_SIZE_BYTES) throw FileStoreException(fileTooLargeMessage(size))
        val startLine = line ?: 1
        val scanner = StreamingLineReader(file)
        val selected = StringBuilder()
        var windowLineCount = 0
        try {
            while (true) {
                // readLine() advances lineNumber past the line it returns, so
                // the number of the line being read must be captured first.
                val lineNumber = scanner.lineNumber
                val text = scanner.readLine() ?: break
                if (lineNumber < startLine || (limit != null && lineNumber >= startLine + limit)) continue
                if (windowLineCount > 0) selected.append('\n')
                if (text.length > MAX_LINE_CHARS) {
                    // Per-line cap: a huge single-line file (minified bundle,
                    // generated JSON) must not flood the context; the marker
                    // tells the model the line was shortened.
                    selected.append(text, 0, MAX_LINE_CHARS)
                    selected.append(TRUNCATED_LINE_SUFFIX)
                } else {
                    selected.append(text)
                }
                windowLineCount++
            }
        } finally {
            scanner.close()
        }
        val visualCount = scanner.lineCount
        if (line != null && line > visualCount) {
            throw FileStoreException(
                "line $line is past the end of the file ($visualCount line${if (visualCount == 1) "" else "s"})"
            )
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
        if (!fs.exists(file)) throw FileStoreException("file not found: $path")
        val size = fs.metadataOrNull(file)?.size ?: throw FileStoreException("not a regular file: $path")
        if (size > MAX_FILE_SIZE_BYTES) throw FileTooLargeException(fileTooLargeMessage(size))
        return fs.source(file).buffered().use { it.readString() }
    }

    internal companion object {
        const val MAX_FILE_SIZE_BYTES: Long = 20L * 1024 * 1024
        const val MAX_LINE_CHARS = 2000
        const val TRUNCATED_LINE_SUFFIX = "... [truncated]"

        fun fileTooLargeMessage(bytes: Long): String =
            "file too large (${formatBytes(bytes)}; limit ${formatBytes(MAX_FILE_SIZE_BYTES)}); " +
                    "use bash (e.g. grep/head/tail) to inspect it"

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }
    }
}

/**
 * Streams the file at [path] line by line without materializing it. Bytes are
 * split on `\n` (0x0A) at the byte level and each line is decoded separately,
 * so no JDK decoder state machine is involved (a chunked InputStreamReader
 * loop was observed to spin forever on zero-char reads under JDK 25). Line
 * terminators are `\n` only (raw `\r` is preserved so edit_file keeps
 * matching raw content); a trailing newline is a terminator, not an extra
 * empty line ([lineCount] counts visual lines). The final line counts even
 * when the file does not end with a newline. Memory stays bounded by the
 * longest single line.
 */
internal class StreamingLineReader(path: Path) {
    private val input = java.io.BufferedInputStream(java.io.FileInputStream(path.toString()), 8192)
    private val byteBuffer = ByteArray(8192)
    private var bufferPos = 0
    private var bufferLen = 0
    private var current = java.io.ByteArrayOutputStream()
    private var currentChars: String? = null
    private var pendingPartial = false

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
     * Returns the next line without its terminator, or null at EOF.
     * `\r` before `\n` is kept (the display layer strips it).
     */
    fun readLine(): String? {
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
                    val text = decode(current)
                    current.reset()
                    pendingPartial = false
                    lineNumber++
                    return text
                }
            }
            val newline = byteBuffer.indexOf(b = '\n'.code.toByte(), from = bufferPos, to = bufferLen)
            if (newline == -1) {
                current.write(byteBuffer, bufferPos, bufferLen - bufferPos)
                bufferPos = bufferLen
                pendingPartial = true
                continue
            }
            current.write(byteBuffer, bufferPos, newline - bufferPos)
            bufferPos = newline + 1
            val text = decode(current)
            current.reset()
            pendingPartial = false
            lineNumber++
            return text
        }
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
 * Backs file tools with the ACP client's fs methods (host-privileged, but
 * renders modifications as reviewable diffs and sees unsaved editor state).
 */
internal class ClientFileStore(
    private val client: ClientSessionOperations,
) : FileStore {
    override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult {
        val content = client.fsReadTextFile(
            path = path,
            line = line?.toUInt(),
            limit = limit?.toUInt(),
        ).content
        return ReadResult(
            content = content,
            // The proxy returns only the window we asked for; a window that
            // came back smaller than the limit is assumed truncated. A window
            // of exactly `limit` lines that ends with a newline reaches EOF
            // (the final newline is a terminator, not a phantom extra line).
            complete = when {
                limit == null -> true
                content.count { it == '\n' } < limit -> true
                else -> content.endsWith("\n")
            },
            total = null,
        )
    }

    override suspend fun writeFile(path: String, content: String) {
        client.fsWriteTextFile(path = path, content = content)
    }

    override suspend fun readRaw(path: String): String =
        client.fsReadTextFile(path = path, line = null, limit = null).content
}

internal open class FileStoreException(message: String) : Exception(message)

/** Raised by [FileStore.readRaw] when the file exceeds the size cap. */
internal class FileTooLargeException(message: String) : FileStoreException(message)
