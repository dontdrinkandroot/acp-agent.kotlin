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
     */
    suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult

    suspend fun writeFile(path: String, content: String)
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
        val content = fs.source(file).buffered().use { it.readString() }
        val all = content.split('\n')
        // A trailing newline is a line terminator, not an extra empty line:
        // "a\nb\n" is 2 visual lines, not 3 (a\r\nb\r\nc\r\n = 3, not 4).
        val visualCount = if (content.endsWith("\n")) all.size - 1 else all.size
        val from = (line?.let { it - 1 } ?: 0).coerceIn(0, all.size)
        val toBounded = (limit?.let { from + it } ?: all.size).coerceIn(0, all.size)
        val selected = all.subList(from, toBounded)
        // The window covers the whole file when it reaches at least the last
        // visible line; the phantom trailing element ("a\nb\n" -> ["a","b",""])
        // is a terminator, not content, so "line=2,limit=1" of a 2-line file
        // is complete even though toBounded (2) < all.size (3).
        val complete = toBounded >= visualCount
        return ReadResult(
            content = selected.joinToString("\n"),
            complete = complete,
            total = visualCount,
        )
    }

    override suspend fun writeFile(path: String, content: String) {
        val fs = SystemFileSystem
        fs.createDirectories(Path(path).parent ?: Path("."))
        fs.sink(Path(path)).buffered().use { it.writeString(content) }
    }
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
            // came back smaller than the limit is assumed truncated.
            complete = limit == null || content.count { it == '\n' } < limit,
            total = null,
        )
    }

    override suspend fun writeFile(path: String, content: String) {
        client.fsWriteTextFile(path = path, content = content)
    }
}

internal class FileStoreException(message: String) : Exception(message)