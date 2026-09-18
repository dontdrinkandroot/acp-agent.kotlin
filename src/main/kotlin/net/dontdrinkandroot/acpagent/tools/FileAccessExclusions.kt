package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Path

/**
 * The file-access exclusion policy applied by the file tools: glob rules
 * (relative to the session cwd) whose matching files are refused to
 * direct-target tools (read_file/edit_file/write_file/delete_file) and hidden
 * from listings and searches (list_dir/glob/grep). Direct-target refusals are
 * hard tool errors; listings simply omit matching entries.
 *
 * Rule semantics follow gitignore conventions: a bare name (no '/') matches at
 * any depth, a rooted pattern matches against the cwd-relative path. The
 * default rule set is the fixed `.env*.local` secret-file exclusion. The
 * designed future source is a gitignore-style `.aiignore` in the project root:
 * its parsed patterns would feed [Companion.of], and no tool code changes.
 */
internal class FileAccessExclusions private constructor(private val rules: List<Rule>) {

    internal data class Rule(val glob: String, private val regex: Regex, private val bareName: Boolean) {

        fun matches(relativePath: String): Boolean =
            if (bareName) regex.matches(relativePath.substringAfterLast('/')) else regex.matches(relativePath)
    }

    /**
     * The first rule matching the given cwd-relative path (its glob, for error
     * messages and the system-prompt section), or null when the path is not
     * excluded.
     */
    fun matchingRule(relativePath: String): String? =
        rules.firstOrNull { it.matches(relativePath) }?.glob

    /**
     * The first rule matching an absolute [path]: checked as a cwd-relative
     * path and, when it lies outside the cwd (e.g. a trusted read path), as
     * its basename. The variant the walk-based tools (glob/grep/list_dir)
     * call for each visited entry.
     */
    fun matchingRuleForPath(cwd: String, path: String): String? {
        matchingRule(relativeTo(cwd, path))?.let { return it }
        val name = Path.of(path).fileName?.toString() ?: return null
        return matchingRule(name)
    }

    /**
     * The first rule matching an absolute direct-target [path]: [matchingRuleForPath]
     * plus the symlink-resolved name, so an alias link to an excluded file
     * cannot smuggle the read past the guard. Unresolvable paths only skip
     * that extra check: this is a block, not a gate, so failing open here
     * keeps dangling links harmless.
     */
    fun matchingRuleForTarget(cwd: String, path: String): String? {
        matchingRuleForPath(cwd, path)?.let { return it }
        val resolved = resolveAgainstSessionCwd(cwd, path) ?: return null
        return matchingRule(resolved.fileName.toString())
    }

    /** The globs of all rules, for the system-prompt "excluded files" line. */
    fun globs(): List<String> = rules.map { it.glob }

    companion object {

        val EMPTY: FileAccessExclusions = of(emptyList())

        val DEFAULT: FileAccessExclusions = of(listOf(".env*.local"))

        fun of(globs: List<String>): FileAccessExclusions =
            FileAccessExclusions(
                globs.map { glob ->
                    Rule(glob, globToRegex(glob), bareName = '/' !in glob)
                }
            )
    }
}

/**
 * Renders [path] relative to [cwd] when it lies inside it, else returns its
 * basename (the form bare-name rules match against).
 */
private fun relativeTo(cwd: String, path: String): String {
    val cwdPath = Path.of(cwd).toAbsolutePath().normalize()
    val abs = Path.of(path).toAbsolutePath().normalize()
    return if (abs.startsWith(cwdPath)) {
        cwdPath.relativize(abs).toString()
    } else {
        abs.fileName.toString()
    }
}

/**
 * The user-facing refusal for a direct-target tool call on an excluded file;
 * names the matched rule so the model can correlate the refusal with the
 * stated exclusion.
 */
internal fun exclusionError(path: String, rule: String): String =
    "'$path' is excluded from tool access (matches exclusion rule '$rule')"
