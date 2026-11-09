package net.dontdrinkandroot.acpagent.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.*

private val logger = KotlinLogging.logger {}

internal const val TEMP_SUFFIX = ".tmp"

/**
 * Age after which a matching temp file is considered orphaned by a crash and
 * swept. The `finally` of the atomic write covers in-process failures only -
 * a SIGKILL between write and move strands the temp file forever.
 */
internal const val STALE_TEMP_AGE_MILLIS = 60L * 60L * 1000L

/**
 * Deletes the crash orphans of atomic writes in [directory]: regular files
 * named `<namePrefix><random>.tmp` (the naming of [writeAtomically]) last
 * modified more than [STALE_TEMP_AGE_MILLIS] before [nowMillis]. Fresh temps
 * of concurrent writers, symlinks and any other names are untouched.
 * Best-effort: cleanup must never fail the write that triggered it.
 */
internal fun sweepStaleTempFiles(directory: Path, namePrefix: String, nowMillis: Long = System.currentTimeMillis()) {
    if (!Files.isDirectory(directory)) return
    val cutoffMillis = nowMillis - STALE_TEMP_AGE_MILLIS
    runCatching {
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                runCatching { deleteIfStaleTempFile(entry, namePrefix, cutoffMillis) }
                    .onFailure { logger.debug(it) { "Skipping stale temp sweep candidate $entry" } }
            }
        }
    }.onFailure { logger.warn(it) { "Could not sweep stale temp files in $directory" } }
}

private fun deleteIfStaleTempFile(entry: Path, namePrefix: String, cutoffMillis: Long) {
    val name = entry.fileName.toString()
    if (!name.startsWith(namePrefix) || !name.endsWith(TEMP_SUFFIX)) return
    if (name.length <= namePrefix.length + TEMP_SUFFIX.length) return
    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis() > cutoffMillis) return
    Files.deleteIfExists(entry)
}

/**
 * Writes [content] to [target] atomically: into a temp file next to it, then
 * moved over it (ATOMIC_MOVE when supported, else a plain move). The temp
 * file is removed in `finally`, so a failed write never leaves debris - a
 * crashed write does, so every call first sweeps [target]'s stale temp files.
 * Unthrottled on purpose: the only caller writes `.ai/run.json` on the rare
 * run-config changes, not per turn like [net.dontdrinkandroot.acpagent.agent.SessionStore].
 */
internal fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    sweepStaleTempFiles(target.parent, target.fileName.toString())
    val temp = Files.createTempFile(target.parent, target.fileName.toString(), TEMP_SUFFIX)
    try {
        Files.writeString(temp, content)
        moveAtomically(temp, target)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/**
 * Moves [source] over [target] atomically when the filesystem supports it,
 * falling back to a plain replacing move.
 */
internal fun moveAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * True when [path] is a symbolic link; unprobeable paths are not links.
 */
internal fun isSymbolicLink(path: String): Boolean = Files.isSymbolicLink(Path.of(path))
