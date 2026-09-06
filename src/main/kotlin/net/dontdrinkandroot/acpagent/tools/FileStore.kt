package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.SessionId
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
    suspend fun readFile(path: String, line: Int?, limit: Int?): String

    suspend fun writeFile(path: String, content: String)
}

internal class LocalFileStore : FileStore {
    private val fs = SystemFileSystem

    override suspend fun readFile(path: String, line: Int?, limit: Int?): String {
        val file = Path(path)
        if (!fs.exists(file)) throw FileStoreException("file not found: $path")
        val content = fs.source(file).buffered().use { it.readString() }
        if (line == null && limit == null) return content
        // Split on '\n' and strip the trailing '\r' so CRLF files slice by
        // visual lines (the IDE's line/limit are 1-based, '\r\n' = one line).
        val lines = content.split('\n').map { lineText ->
            if (lineText.endsWith("\r")) lineText.dropLast(1) else lineText
        }
        val from = line?.let { it - 1 } ?: 0
        val to = limit?.let { from + it } ?: lines.size
        return lines.subList(from.coerceIn(0, lines.size), to.coerceIn(0, lines.size)).joinToString("\n")
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
    private val sessionId: SessionId,
) : FileStore {
    override suspend fun readFile(path: String, line: Int?, limit: Int?): String {
        return client.fsReadTextFile(
            path = path,
            line = line?.toUInt(),
            limit = limit?.toUInt(),
        ).content
    }

    override suspend fun writeFile(path: String, content: String) {
        client.fsWriteTextFile(path = path, content = content)
    }
}

internal class FileStoreException(message: String) : Exception(message)