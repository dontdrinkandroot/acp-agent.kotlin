package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*

public class GlobTool : AgentTool {
    override val name = "glob"
    override val description = "Find files matching a glob pattern (e.g. src/**/*.kt) under a root directory."
    override val kind = ToolKind.SEARCH
    override val mutating = false
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("root") {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Directory to search, absolute or relative; defaults to the working directory."),
                )
            }
            putJsonObject("pattern") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Glob pattern (e.g. src/**/*.kt); ** crosses directory boundaries."))
            }
        })
        putJsonArray("required") { add(JsonPrimitive("pattern")) }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("root")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("root")) return ToolResult(arguments.argError("root"), true)
        val rawRoot = arguments.stringArg("root") ?: context.cwd
        val root = absoluteToolPath(context.cwd, rawRoot)
        val pattern = arguments.stringArg("pattern") ?: return ToolResult(arguments.argError("pattern"), true)
        return runCatching {
            val regex = globToRegex(pattern)
            val fs = SystemFileSystem
            val base = Path(root)
            if (!fs.exists(base)) return@runCatching ToolResult("Root not found: $root", true)
            val results = mutableListOf<String>()
            walk(fs, base, 0) { f ->
                val rel = f.toString().removePrefix(root.trimEnd('/') + "/")
                if (regex.matches(rel)) results += rel
            }
            val sorted = results.sorted()
            val listing = sorted.take(MAX_LISTING_ENTRIES).joinToString("\n")
            ToolResult(
                if (listing.isEmpty()) {
                    "No matches"
                } else if (sorted.size > MAX_LISTING_ENTRIES) {
                    "$listing\n...(${sorted.size - MAX_LISTING_ENTRIES} more entries omitted)"
                } else {
                    listing
                }
            )
        }.getOrElse { ToolResult("Glob failed: ${it.message}", true) }
    }
}

private const val MAX_LISTING_ENTRIES = 500

/**
 * Walks [dir] recursively, visiting files. Symlinks are deliberately skipped
 * (both files and directories): kotlinx-io follows links by default, so a link
 * inside the search root could otherwise smuggle reads or listings outside the
 * project the permission flow approved. `.git` directories are skipped at any
 * depth (packed object files would flood a content search with binary noise).
 */
internal fun walk(
    fs: FileSystem,
    dir: Path,
    depth: Int,
    visit: (Path) -> Unit
) {
    if (depth > MAX_WALK_DEPTH) return
    val entries = runCatching { fs.list(dir) }.getOrNull() ?: return
    for (entry in entries) {
        if (isSymbolicLink(entry)) continue
        if (entry.name == GIT_DIR) continue
        val meta = fs.metadataOrNull(entry)
        if (meta?.isDirectory == true) {
            walk(fs, entry, depth + 1, visit)
        } else {
            visit(entry)
        }
    }
}

internal const val MAX_WALK_DEPTH = 64
internal const val GIT_DIR = ".git"

private fun isSymbolicLink(path: Path): Boolean =
    runCatching { java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(path.toString())) }.getOrDefault(false)

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