package net.dontdrinkandroot.acpagent.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal const val SESSION_ID_PREFIX = "sess_"
internal const val SESSION_ID_LENGTH = SESSION_ID_PREFIX.length + 16
private const val STATE_DIR_NAME = "state"
private const val APP_STATE_DIR = "ddr-acp-agent"
private const val SESSIONS_DIR = "sessions"

/**
 * Reports whether [id] is a session id this agent could have minted
 * ([randomSessionId]: `sess_` + 16 lowercase hex digits). Anything else is
 * rejected before it reaches the filesystem: the id is interpolated into a
 * file path, so a hostile client-supplied id (`session/load`, `session/resume`,
 * `session/delete`) must not be able to traverse out of the sessions directory
 * or smuggle in separators.
 */
internal fun isValidSessionId(id: String): Boolean =
    id.length == SESSION_ID_LENGTH &&
            id.startsWith(SESSION_ID_PREFIX) &&
            id.drop(SESSION_ID_PREFIX.length).all { it in "0123456789abcdef" }

/**
 * Persists session records under the XDG state directory, one file per session.
 * Writes are atomic (temp file + move) and best-effort POSIX-restricted.
 */
internal class SessionStore(private val sessionsDir: Path) {

    constructor() : this(resolveSessionsDir())

    private val logger = KotlinLogging.logger {}

    fun save(record: SessionRecord) {
        val id = record.sessionId
        require(isValidSessionId(id)) { "invalid session id \"$id\"" }
        Files.createDirectories(sessionsDir)
        applyPosixPermissions(sessionsDir, "rwx------")
        val target = path(id)
        val temp = Files.createTempFile(sessionsDir, "session-", ".tmp")
        try {
            applyPosixPermissions(temp, "rw-------")
            Files.writeString(temp, llmWireJson.encodeToString(record))
            moveAtomically(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Reads the record for a session id; `null` when the session does not
     * exist. A corrupt record throws [SerializationException] so callers can
     * distinguish "unknown" from "unusable".
     */
    fun load(id: String): SessionRecord? {
        require(isValidSessionId(id)) { "invalid session id \"$id\"" }
        val file = path(id)
        if (!Files.isRegularFile(file)) return null
        return llmWireJson.decodeFromString<SessionRecord>(Files.readString(file))
    }

    /**
     * Returns every readable record. Corrupt records are skipped with a warning.
     */
    fun list(): List<SessionRecord> {
        if (!Files.isDirectory(sessionsDir)) return emptyList()
        val records = mutableListOf<SessionRecord>()
        Files.newDirectoryStream(sessionsDir).use { entries ->
            for (entry in entries) {
                if (!Files.isRegularFile(entry) || !entry.fileName.toString().endsWith(".json")) continue
                runCatching { llmWireJson.decodeFromString<SessionRecord>(Files.readString(entry)) }
                    .onSuccess { records += it }
                    .onFailure { logger.warn(it) { "Skipping corrupt session record $entry" } }
            }
        }
        return records
    }

    /**
     * Removes the record. A missing session succeeds silently.
     */
    fun delete(id: String) {
        require(isValidSessionId(id)) { "invalid session id \"$id\"" }
        Files.deleteIfExists(path(id))
    }

    private fun path(id: String): Path = sessionsDir.resolve("$id.json")

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun applyPosixPermissions(path: Path, permissions: String) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        }
    }
}

private fun resolveSessionsDir(): Path {
    val xdgStateHome = System.getenv("XDG_STATE_HOME")
    val root = if (xdgStateHome.isNullOrBlank()) {
        Path.of(System.getProperty("user.home"), ".local", STATE_DIR_NAME)
    } else {
        Path.of(xdgStateHome)
    }
    return root.resolve(APP_STATE_DIR).resolve(SESSIONS_DIR)
}