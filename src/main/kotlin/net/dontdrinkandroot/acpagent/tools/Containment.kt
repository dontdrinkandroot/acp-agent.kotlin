package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readSymbolicLink

/**
 * Resolves a (possibly relative) tool path against the session working
 * directory - never the process cwd - producing the real location (symlinks
 * resolved, not-yet-existing trailing components walked up to an existing
 * ancestor). Returns null when the path cannot be resolved (symlink loop,
 * unresolvable ancestor), so callers fail closed.
 */
internal fun resolveAgainstSessionCwd(sessionCwd: String, path: String): Path? {
    val raw = Paths.get(path)
    val absolute = if (raw.isAbsolute) raw else Paths.get(sessionCwd).resolve(raw)
    return resolvedPath(absolute)
}

/**
 * Reports whether the (resolved) [path] lies inside the session working
 * directory. Symlinks on both sides are resolved so links escaping the
 * directory are detected; the final component may not exist yet (e.g. a file
 * about to be created).
 */
internal fun isWithin(sessionCwd: String, path: String): Boolean {
    val dir = resolvedPath(Paths.get(sessionCwd).toAbsolutePath()) ?: return false
    val target = resolveAgainstSessionCwd(sessionCwd, path) ?: return false
    return target.normalize().startsWith(dir.normalize())
}

/**
 * Absolutizes [p] and resolves symlinks, walking up until an existing ancestor
 * is found so that not-yet-existing final components (files being created)
 * still resolve to their real location. Dangling symlinks are followed manually
 * so they cannot smuggle a path outside the directory; symlink loops fail
 * closed (null).
 */
private fun resolvedPath(p: Path): Path? {
    val abs = p.toAbsolutePath().normalize()
    var suffix: Path? = null
    val seen = mutableSetOf<Path>()
    var probe: Path = abs
    while (true) {
        if (!seen.add(probe)) return null
        val resolved = runCatching { probe.toRealPath() }.getOrNull()
        if (resolved != null) {
            return if (suffix == null) resolved else resolved.resolve(suffix)
        }
        if (Files.isSymbolicLink(probe)) {
            val target = probe.readSymbolicLink()
            probe = (if (target.isAbsolute) target else probe.parent.resolve(target)).normalize()
            if (suffix != null) {
                probe = probe.resolve(suffix)
                suffix = null
            }
            continue
        }
        val parent = probe.parent
        if (parent == null || parent == probe) return null
        suffix = if (suffix == null) probe.fileName else probe.fileName.resolve(suffix)
        probe = parent
    }
}
