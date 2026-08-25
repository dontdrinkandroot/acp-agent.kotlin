package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*

public class GrepTool : AgentTool {
    override val name = "grep"
    override val description = "Search file contents for a regex pattern under a root directory."
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
                put("description", JsonPrimitive("Regular expression matched against each line."))
            }
            putJsonObject("glob") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional glob filter; only files matching it are searched."))
            }
        })
        putJsonArray("required") { add(JsonPrimitive("pattern")) }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["root"]?.jsonPrimitive?.content

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val root = arguments["root"]?.jsonPrimitive?.content ?: context.cwd
        val pattern = arguments["pattern"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'pattern'", true)
        val glob = arguments["glob"]?.jsonPrimitive?.content
        return runCatching {
            val regex = Regex(pattern)
            val fileFilter = glob?.let { globToRegex(it) }
            val fs = SystemFileSystem
            val base = Path(root)
            if (!fs.exists(base)) return@runCatching ToolResult("Root not found: $root", true)
            val results = mutableListOf<String>()
            walk(fs, base, 0) { f ->
                if (fileFilter != null) {
                    val rel = f.toString().removePrefix(root.trimEnd('/') + "/")
                    if (!fileFilter.matches(rel)) return@walk
                }
                runCatching {
                    val content = fs.source(f).buffered().use { it.readString() }
                    val rel = f.toString().removePrefix(root.trimEnd('/') + "/")
                    content.split('\n').forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            results += "$rel:${idx + 1}:$line"
                        }
                    }
                }
            }
            ToolResult(results.take(500).joinToString("\n").ifEmpty { "No matches" })
        }.getOrElse { ToolResult("Grep failed: ${it.message}", true) }
    }
}