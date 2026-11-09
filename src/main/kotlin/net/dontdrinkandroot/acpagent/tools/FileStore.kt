package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ClientCapabilities
import kotlinx.coroutines.CancellationException
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

/** The shared "start line beyond the last line" error text of both file stores. */
private fun pastEndOfFileMessage(line: Int, totalLines: Int): String =
    "line $line is past the end of the file ($totalLines line${if (totalLines == 1) "" else "s"})"

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
            throw FileStoreException(pastEndOfFileMessage(line, visualCount))
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
        } catch (e: CancellationException) {
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
            throw FileStoreException(pastEndOfFileMessage(line, whole.size))
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

/**
 * Selects the file backend for the session: the client fs proxy when it is
 * available (both read and write capabilities) and enabled, otherwise a local
 * store. Selection is independent of the permission flow, which governs where
 * a call is allowed to touch.
 */
internal fun selectFileStore(
    client: ClientSessionOperations?,
    capabilities: ClientCapabilities,
    fsProxyEnabled: Boolean,
): FileStore {
    val fs = capabilities.fs
    val proxyCapable = fs != null && fs.readTextFile == true && fs.writeTextFile == true
    return if (fsProxyEnabled && client != null && proxyCapable) {
        ClientFileStore(client)
    } else {
        LocalFileStore()
    }
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