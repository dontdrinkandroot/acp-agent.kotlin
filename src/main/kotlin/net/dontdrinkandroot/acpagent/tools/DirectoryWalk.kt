package net.dontdrinkandroot.acpagent.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.Path as NioPath

private val logger = KotlinLogging.logger {}

/**
 * The shared search/list/tree-walk infrastructure of the path tools
 * (glob/grep/list_dir plus the tree-diff walkers of the move/delete tools):
 * the walker, the glob matcher, search-root validation and the capped listing
 * convention. Tool classes call these; the policies they implement live here
 * so the directory-consuming tools cannot drift apart (issue #82).
 */

internal const val MAX_LISTING_ENTRIES = 500

/**
 * Renders [results] as the capped listing convention shared by the
 * listing/search tools: sorted, at most [MAX_LISTING_ENTRIES] entries, a
 * `...(N more entries omitted)` suffix when entries were dropped, "No matches"
 * when empty.
 */
internal fun formatCappedListing(results: Collection<String>): String {
    val sorted = results.sorted()
    val listing = sorted.take(MAX_LISTING_ENTRIES).joinToString("\n")
    return when {
        listing.isEmpty() -> "No matches"
        sorted.size > MAX_LISTING_ENTRIES -> "$listing\n...(${sorted.size - MAX_LISTING_ENTRIES} more entries omitted)"
        else -> listing
    }
}

/**
 * Renders [path] relative to [root]; the form the glob patterns and the
 * file-filter match against. Absolute results (a symlinked root resolved
 * outside) keep their absolute form so the match still sees the escape.
 */
internal fun relativeToRoot(root: String, path: String): String {
    val rootPath = NioPath.of(root).toAbsolutePath().normalize()
    val abs = NioPath.of(path).toAbsolutePath().normalize()
    return if (abs.startsWith(rootPath)) rootPath.relativize(abs).toString() else abs.toString()
}

/**
 * Validates a search root for the directory-consuming tools (glob/grep, and
 * list_dir with the same convention) so a wrong root fails loudly instead of
 * silently producing "No matches": missing, a file, or OS-unreadable.
 */
internal fun searchRootError(root: String): String? {
    val meta = SystemFileSystem.metadataOrNull(Path(root)) ?: return "Path not found: $root"
    if (!meta.isDirectory) return "Not a directory: $root"
    return if (!Files.isReadable(NioPath.of(root))) "Not readable: $root" else null
}

/**
 * Walks [dir] recursively, visiting files. Symlinks are deliberately skipped
 * (both files and directories): kotlinx-io follows links by default, so a link
 * inside the search root could otherwise smuggle reads or listings outside the
 * project the permission flow approved. `.git` directories are skipped at any
 * depth (packed object files would flood a content search with binary noise).
 */
internal fun walk(
    dir: Path,
    depth: Int,
    visit: (Path) -> Unit
) {
    if (depth > MAX_WALK_DEPTH) return
    val entries = runCatching { SystemFileSystem.list(dir) }.getOrNull() ?: return
    for (entry in entries) {
        if (isSymbolicLink(entry.toString())) continue
        if (entry.name == GIT_DIR) continue
        val meta = SystemFileSystem.metadataOrNull(entry)
        if (meta?.isDirectory == true) {
            walk(entry, depth + 1, visit)
        } else {
            visit(entry)
        }
    }
}

internal const val MAX_WALK_DEPTH = 64
internal const val GIT_DIR = ".git"

internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> when {
                i + 1 < glob.length && glob[i + 1] == '*' && i + 2 < glob.length && glob[i + 2] == '/' -> {
                    sb.append("(?:.*/)?")
                    i += 3
                    continue
                }

                i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    continue
                }

                else -> sb.append("[^/]*")
            }

            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append("$")
    return Regex(sb.toString())
}

/**
 * The regular files under [root] for tree diffs, in deterministic sorted path
 * order (so the capped reported prefix is stable across runs and platforms).
 * Symlinks are skipped and never followed, like the search walker.
 *
 * `.git` entries are skipped too (issue #79), with the same name-based policy
 * as [walk]: VCS metadata is not change evidence a client can roll back, and
 * because it sorts before most source paths it would otherwise consume the
 * bounded diff budget the real files need. This is an *evidence* skip
 * only - the callers still scan and delete/move the whole tree, so a symlink
 * inside `.git` is still caught by the deletion's symlink guard.
 */
internal fun treeFilesSorted(fs: FileSystem, root: Path, depth: Int = 0): List<String> {
    if (depth > MAX_WALK_DEPTH) return emptyList()
    return fs.list(root)
        .flatMap { entry ->
            when {
                entry.name == GIT_DIR -> {
                    logger.warn { "diff omitted for $entry: VCS metadata is not change evidence" }
                    emptyList()
                }

                isSymbolicLink(entry.toString()) -> emptyList()
                fs.metadataOrNull(entry)?.isDirectory == true -> treeFilesSorted(fs, entry, depth + 1)
                fs.metadataOrNull(entry)?.isRegularFile == true -> listOf(entry.toString())
                else -> emptyList()
            }
        }
        .sorted()
}