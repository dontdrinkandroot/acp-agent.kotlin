package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

public class GrepTool : AgentTool {
    override val name = "grep"
    override val description =
        "Search file contents for a regex pattern. The search path may be a directory (searched recursively) " +
                "or a single file (only that file is searched; the glob filter is ignored). " +
                "Files matching an exclusion rule are skipped; binary files are skipped."
    override val kind = ToolKind.SEARCH
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("pattern", PropType.STRING, "Regular expression matched against each line."),
        optional(
            "path",
            PropType.STRING,
            "Directory or single file to search, absolute or relative to the working directory; " +
                    "defaults to the working directory."
        ),
        optional(
            "glob",
            PropType.STRING,
            "Optional glob filter; only files matching it are searched. Ignored when the search path is a single file."
        ),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("path")) return ToolResult(arguments.argError("path"), true)
        val rawPath = arguments.stringArg("path") ?: context.cwd
        val path = absoluteToolPath(context.cwd, rawPath)
        val pattern = arguments.stringArg("pattern") ?: return ToolResult(arguments.argError("pattern"), true)
        val glob = arguments.stringArg("glob")
        if (arguments.isNullArg("glob")) return ToolResult(arguments.argError("glob"), true)
        return executeSafely("Grep failed") {
            val meta = SystemFileSystem.metadataOrNull(Path(path))
                ?: return@executeSafely ToolResult("Path not found: $path", true)
            if (meta.isDirectory) {
                searchRootError(path)?.let { return@executeSafely ToolResult(it, true) }
                val regex = Regex(pattern)
                searchDirectory(path, regex, glob, context)
            } else {
                val regex = Regex(pattern)
                searchSingleFile(path, rawPath, meta.size, regex, context)
            }
        }
    }

    /**
     * The recursive directory search. Unreadable subdirectories mid-walk are
     * silently pruned (the walk-time swallow is deliberate); only the root is
     * validated loudly.
     */
    private fun searchDirectory(root: String, regex: Regex, glob: String?, context: ToolContext): ToolResult {
        val fileFilter = glob?.let { globToRegex(it) }
        val base = Path(root)
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
        return ToolResult(rendered.joinToString("\n").ifEmpty { "No matches" } + skipSuffix(skippedBinaryOrOversized))
    }

    /**
     * Searches a single explicitly named file (ripgrep model): the glob filter
     * does not apply to an explicit file path; exclusion rules and symlinks
     * are refused; an unreadable, oversized or binary file is reported through
     * the shared skip note instead of a bare "No matches". Match lines echo
     * the path argument as given.
     */
    private fun searchSingleFile(
        path: String,
        rawPath: String,
        fileSize: Long,
        regex: Regex,
        context: ToolContext,
    ): ToolResult {
        if (isSymbolicLink(path)) return ToolResult("Refusing to search a symlink: $path", true)
        context.fileExclusions.matchingRuleForTarget(context.cwd, path)?.let {
            return ToolResult(exclusionError(path, it), true)
        }
        val unsearchable = !Files.isReadable(java.nio.file.Path.of(path)) || fileSize > MAX_GREP_FILE_BYTES
        val content = if (unsearchable) null else readMatchingLines(Path(path))
        if (content == null || content.contains('\u0000')) {
            // Binary content (NUL sniff) would surface mojibake matches; a
            // named-but-unsearchable file must not read as a bare "No matches".
            return ToolResult("No matches" + skipSuffix(1))
        }
        val rendered = mutableListOf<String>()
        content.split('\n').forEachIndexed { idx, line ->
            if (regex.containsMatchIn(line) && rendered.size < MAX_MATCHES) {
                rendered += "$rawPath:${idx + 1}:${truncateMatchLine(line)}"
            }
        }
        return ToolResult(rendered.joinToString("\n").ifEmpty { "No matches" })
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

private fun skipSuffix(count: Int): String =
    if (count > 0) "\n...($count binary, oversized or excluded files skipped)" else ""
