package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject

public class GrepTool : AgentTool {
    override val name = "grep"
    override val description =
        "Search file contents for a regex pattern under a root directory. " +
                "Files matching an exclusion rule (currently .env*.local) are skipped."
    override val kind = ToolKind.SEARCH
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("pattern", PropType.STRING, "Regular expression matched against each line."),
        optional(
            "root",
            PropType.STRING,
            "Directory to search, absolute or relative; defaults to the working directory."
        ),
        optional("glob", PropType.STRING, "Optional glob filter; only files matching it are searched."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("root")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("root")) return ToolResult(arguments.argError("root"), true)
        val rawRoot = arguments.stringArg("root") ?: context.cwd
        val root = absoluteToolPath(context.cwd, rawRoot)
        val pattern = arguments.stringArg("pattern") ?: return ToolResult(arguments.argError("pattern"), true)
        val glob = arguments.stringArg("glob")
        if (arguments.isNullArg("glob")) return ToolResult(arguments.argError("glob"), true)
        return executeSafely("Grep failed") {
            val regex = Regex(pattern)
            val fileFilter = glob?.let { globToRegex(it) }
            val base = Path(root)
            if (!SystemFileSystem.exists(base)) return@executeSafely ToolResult("Root not found: $root", true)
            var skippedBinaryOrOversized = 0
            val matches = mutableListOf<GrepMatch>()
            walk(base, 0) { f ->
                if (context.fileExclusions.matchingRuleForPath(context.cwd, f.toString()) != null) {
                    skippedBinaryOrOversized++
                    return@walk
                }
                if (fileFilter != null && !fileFilter.matches(relativeToRoot(root, f.toString()))) return@walk
                val size = SystemFileSystem.metadataOrNull(f)?.size ?: 0
                if (size > MAX_GREP_FILE_BYTES) {
                    skippedBinaryOrOversized++
                    return@walk
                }
                readMatchingLines(f)?.let { fileContent ->
                    if (fileContent.contains('\u0000')) {
                        // Binary file (NUL sniff): regex matches here would be
                        // mojibake noise for the model.
                        skippedBinaryOrOversized++
                        return@walk
                    }
                    val rel = relativeToRoot(root, f.toString())
                    fileContent.split('\n').forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) matches += GrepMatch(rel, idx + 1, line)
                    }
                }
            }
            val rendered = matches
                .sortedWith(compareBy({ it.path }, { it.line }))
                .take(MAX_MATCHES)
                .map { "${it.path}:${it.line}:${truncateMatchLine(it.text)}" }
            val suffix = if (skippedBinaryOrOversized > 0) {
                "\n...($skippedBinaryOrOversized binary, oversized or excluded files skipped)"
            } else ""
            ToolResult(rendered.joinToString("\n").ifEmpty { "No matches" } + suffix)
        }
    }

    /**
     * Reads [file] as text; null when it cannot be read. The read failure
     * stays silent: unreadable files are skipped like the oversized ones.
     */
    private fun readMatchingLines(file: Path): String? = runCatching {
        SystemFileSystem.source(file).buffered().use { it.readString() }
    }.getOrNull()
}

private data class GrepMatch(val path: String, val line: Int, val text: String)

private const val MAX_MATCHES = 500
private const val MAX_MATCH_LINE_CHARS = 500
private const val MAX_GREP_FILE_BYTES = 1L * 1024 * 1024

private fun truncateMatchLine(line: String): String =
    if (line.length > MAX_MATCH_LINE_CHARS) line.take(MAX_MATCH_LINE_CHARS) + "..." else line
