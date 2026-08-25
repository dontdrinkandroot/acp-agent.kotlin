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
        arguments["root"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val root = arguments["root"]?.jsonPrimitive?.content ?: context.cwd
        val pattern = arguments["pattern"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'pattern'", true)
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
            ToolResult(results.sorted().joinToString("\n").ifEmpty { "No matches" })
        }.getOrElse { ToolResult("Glob failed: ${it.message}", true) }
    }
}

internal fun walk(
    fs: FileSystem,
    dir: Path,
    depth: Int,
    visit: (Path) -> Unit
) {
    if (depth > 64) return
    val entries = runCatching { fs.list(dir) }.getOrNull() ?: return
    for (entry in entries) {
        val meta = fs.metadataOrNull(entry)
        if (meta?.isDirectory == true) {
            walk(fs, entry, depth + 1, visit)
        } else {
            visit(entry)
        }
    }
}

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