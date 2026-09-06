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
        arguments.stringArg("root")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("root")) return ToolResult(arguments.argError("root"), true)
        val rawRoot = arguments.stringArg("root") ?: context.cwd
        val root = absoluteToolPath(context.cwd, rawRoot)
        val pattern = arguments.stringArg("pattern") ?: return ToolResult(arguments.argError("pattern"), true)
        val glob = arguments.stringArg("glob")
        if (arguments.isNullArg("glob")) return ToolResult(arguments.argError("glob"), true)
        return runCatching {
            val regex = Regex(pattern)
            val fileFilter = glob?.let { globToRegex(it) }
            val fs = SystemFileSystem
            val base = Path(root)
            if (!fs.exists(base)) return@runCatching ToolResult("Root not found: $root", true)
            val results = mutableListOf<Triple<String, Int, String>>()
            var skippedBinaryOrOversized = 0
            walk(fs, base, 0) { f ->
                if (fileFilter != null) {
                    val rel = f.toString().removePrefix(root.trimEnd('/') + "/")
                    if (!fileFilter.matches(rel)) return@walk
                }
                val meta = runCatching { fs.metadataOrNull(f) }.getOrNull()
                val size = meta?.size ?: 0
                if (size > MAX_GREP_FILE_BYTES) {
                    skippedBinaryOrOversized++
                    return@walk
                }
                runCatching {
                    val content = fs.source(f).buffered().use { it.readString() }
                    if (content.contains('\u0000')) {
                        // Binary file (NUL sniff): regex matches here would be
                        // mojibake noise for the model.
                        skippedBinaryOrOversized++
                        return@walk
                    }
                    val rel = f.toString().removePrefix(root.trimEnd('/') + "/")
                    content.split('\n').forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            results += Triple(rel, idx + 1, line)
                        }
                    }
                }
            }
            val matches = results
                .sortedWith(compareBy({ it.first }, { it.second }))
                .take(MAX_MATCHES)
                .map { "${it.first}:${it.second}:${truncateMatchLine(it.third)}" }
            val suffix = if (skippedBinaryOrOversized > 0) {
                "\n...($skippedBinaryOrOversized binary or oversized files skipped)"
            } else ""
            (matches.joinToString("\n").ifEmpty { "No matches" } + suffix)
                .let { ToolResult(it) }
        }.getOrElse { ToolResult("Grep failed: ${it.message}", true) }
    }
}

private const val MAX_MATCHES = 500
private const val MAX_MATCH_LINE_CHARS = 500
private const val MAX_GREP_FILE_BYTES = 1L * 1024 * 1024

private fun truncateMatchLine(line: String): String =
    if (line.length > MAX_MATCH_LINE_CHARS) line.take(MAX_MATCH_LINE_CHARS) + "..." else line