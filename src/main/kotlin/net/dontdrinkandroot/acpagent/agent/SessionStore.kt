package net.dontdrinkandroot.acpagent.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import net.dontdrinkandroot.acpagent.tools.STALE_TEMP_AGE_MILLIS
import net.dontdrinkandroot.acpagent.tools.TEMP_SUFFIX
import net.dontdrinkandroot.acpagent.tools.moveAtomically
import net.dontdrinkandroot.acpagent.tools.sweepStaleTempFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong

internal const val SESSION_ID_PREFIX = "sess_"
internal const val SESSION_ID_LENGTH = SESSION_ID_PREFIX.length + 16
private const val STATE_DIR_NAME = "state"
private const val APP_STATE_DIR = "ddr-acp-agent"
private const val SESSIONS_DIR = "sessions"
private const val SESSION_TEMP_PREFIX = "session-"

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
internal class SessionStore(
    private val sessionsDir: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    constructor() : this(resolveSessionsDir())

    private val logger = KotlinLogging.logger {}

    private val lastTempSweepAt = AtomicLong(0)

    fun save(record: SessionRecord) {
        val id = record.sessionId
        require(isValidSessionId(id)) { "invalid session id \"$id\"" }
        Files.createDirectories(sessionsDir)
        applyPosixPermissions(sessionsDir, "rwx------")
        sweepStaleTempFilesIfDue()
        val temp = Files.createTempFile(sessionsDir, SESSION_TEMP_PREFIX, TEMP_SUFFIX)
        try {
            applyPosixPermissions(temp, "rw-------")
            Files.writeString(temp, llmWireJson.encodeToString(record))
            moveAtomically(temp, path(id))
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
        sweepStaleTempFilesIfDue()
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

    /**
     * Sweeps crash-orphaned temp files at most once per [STALE_TEMP_AGE_MILLIS]:
     * `save` runs after every executed tool call, so an unconditional scan
     * would rescan the same debris on every persist. The throttle still
     * self-heals against temp files of crashed sibling processes sharing this
     * directory (host and container agents see one sessions directory).
     */
    private fun sweepStaleTempFilesIfDue() {
        val now = nowMillis()
        val last = lastTempSweepAt.get()
        if (now - last < STALE_TEMP_AGE_MILLIS) return
        if (lastTempSweepAt.compareAndSet(last, now)) {
            sweepStaleTempFiles(sessionsDir, SESSION_TEMP_PREFIX, now)
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
