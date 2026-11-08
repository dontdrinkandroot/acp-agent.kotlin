package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
                searchSingleFile(path, rawPath, regex, context)
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
        var skippedBinaryOrExcluded = 0
        // Bounded as the walk goes: only the first MAX_MATCHES matches by
        // path/line are kept, so a broad pattern over a large tree cannot grow
        // the tool's memory with the repository instead of with its output.
        val matches = BoundedMatches(MAX_MATCHES)
        walk(base, 0) { f ->
            if (context.fileExclusions.matchingRuleForPath(context.cwd, f.toString()) != null) {
                skippedBinaryOrExcluded++
                return@walk
            }
            val rel = relativeToRoot(root, f.toString())
            if (fileFilter != null && !fileFilter.matches(rel)) return@walk
            // Early exit: once the cap is full, a file sorting after the
            // largest kept match cannot contribute, so its read and regex scan
            // are skipped. How much this saves depends on the walk order (which
            // is filesystem-dependent); the kept set does not. Because this
            // check runs before the read/binary checks, a late-sorting binary
            // file is cap-pruned instead of counted in the skip note.
            if (!matches.admits(rel)) return@walk
            // The scan stages its matches and discards them when the file
            // turns out binary (NUL sniff over the whole file, issue #24).
            val scan = scanStagedMatches(f, rel, regex) ?: return@walk
            if (scan.binary) {
                skippedBinaryOrExcluded++
                return@walk
            }
            scan.matches.toSortedList().forEach { matches.add(it) }
        }
        val rendered = matches.toSortedList().map { "${it.path}:${it.line}:${it.text}" }
        return ToolResult(rendered.joinToString("\n").ifEmpty { "No matches" } + skipSuffix(skippedBinaryOrExcluded))
    }

    /**
     * Searches a single explicitly named file (ripgrep model): the glob filter
     * does not apply to an explicit file path; exclusion rules and symlinks
     * are refused; an unreadable or binary file is reported through
     * the shared skip note instead of a bare "No matches". Match lines echo
     * the path argument as given.
     */
    private fun searchSingleFile(
        path: String,
        rawPath: String,
        regex: Regex,
        context: ToolContext,
    ): ToolResult {
        if (isSymbolicLink(path)) return ToolResult("Refusing to search a symlink: $path", true)
        // The exclusion gate only: the search root itself is deliberately not
        // checked in execute() (directory roots follow the walk-side rules).
        val target = resolveToolTarget(context, path)
        if (target is ToolTarget.Rejected) return target.error
        val scan =
            if (Files.isReadable(java.nio.file.Path.of(path))) scanStagedMatches(Path(path), rawPath, regex) else null
        if (scan == null || scan.binary) {
            // Binary content (NUL sniff) would surface mojibake matches; a
            // named-but-unsearchable file must not read as a bare "No matches".
            return ToolResult("No matches" + skipSuffix(1))
        }
        val rendered = scan.matches.toSortedList().map { "${it.path}:${it.line}:${it.text}" }
        return ToolResult(rendered.joinToString("\n").ifEmpty { "No matches" })
    }

    /**
     * Streams [file] line by line and stages at most [MAX_MATCHES] matches of
     * [regex] labeled with [pathLabel] (issue #24: no whole-file
     * materialization). Matching runs on each line's kept prefix
     * ([MAX_LINE_ACCUMULATION_BYTES], see [StreamingLineReader]) - a match
     * past the cap inside one line is the documented trade-off. A NUL byte
     * anywhere in the file (including clamped tails) marks it binary and the
     * staged matches are discarded by the caller. Null when the file cannot
     * be read (skipped silently like before).
     */
    private fun scanStagedMatches(file: Path, pathLabel: String, regex: Regex): StagedScan? = runCatching {
        val staged = BoundedMatches(MAX_MATCHES)
        val reader = StreamingLineReader(file)
        try {
            while (!reader.sawNul) {
                // readLine() advances lineNumber past the line it returns, so
                // the number of the line being read must be captured first.
                val lineNumber = reader.lineNumber
                val line = reader.readLine() ?: break
                if (regex.containsMatchIn(line.text)) {
                    staged.add(GrepMatch(pathLabel, lineNumber, truncateMatchLine(line.text)))
                }
            }
            StagedScan(binary = reader.sawNul, matches = staged)
        } finally {
            reader.close()
        }
    }.getOrNull()
}

/** Result of [GrepTool] staging: the per-file matches plus the binary verdict. */
private class StagedScan(val binary: Boolean, val matches: BoundedMatches)

internal data class GrepMatch(val path: String, val line: Int, val text: String)

/**
 * The match accumulator of the directory search: keeps only the [limit]
 * smallest matches by path and line, so the memory footprint follows the
 * output cap instead of the size of the searched tree (a broad pattern in a
 * large repository would otherwise collect every match before the cap is
 * applied). The kept set is exactly the sorted prefix the cap renders, so
 * bounding the collection does not change the output. [admits] is the walk's
 * early exit over the same invariant.
 */
internal class BoundedMatches(private val limit: Int) {
    private val kept = sortedSetOf(MATCH_ORDER)

    fun add(match: GrepMatch) {
        kept.add(match)
        if (kept.size > limit) kept.remove(kept.last())
    }

    /**
     * Whether a file at [relativePath] can still contribute to the kept
     * prefix: every path does while the cap has room, and once it is full only
     * paths at or before the largest kept match (whose earlier lines may still
     * sort into the prefix).
     */
    fun admits(relativePath: String): Boolean = kept.size < limit || relativePath <= kept.last().path

    fun toSortedList(): List<GrepMatch> = kept.toList()

    private companion object {
        val MATCH_ORDER: Comparator<GrepMatch> = compareBy({ it.path }, { it.line })
    }
}

private const val MAX_MATCHES = 500
private const val MAX_MATCH_LINE_CHARS = 500

private fun truncateMatchLine(line: String): String =
    if (line.length > MAX_MATCH_LINE_CHARS) line.take(MAX_MATCH_LINE_CHARS) + "..." else line

private fun skipSuffix(count: Int): String =
    if (count > 0) "\n...($count binary or excluded files skipped)" else ""
