package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [content] to [target] atomically: into a temp file next to it, then
 * moved over it (ATOMIC_MOVE when supported, else a plain move). The temp
 * file is removed in `finally`, so a failed write never leaves debris.
 */
internal fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
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
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * True when [path] is a symbolic link; unprobeable paths are not links.
 */
internal fun isSymbolicLink(path: String): Boolean =
    java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(path))