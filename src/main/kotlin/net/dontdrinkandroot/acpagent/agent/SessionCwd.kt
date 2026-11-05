package net.dontdrinkandroot.acpagent.agent

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Checks a session's `cwd` before a session is created, loaded or resumed and
 * returns the refusal text, or null when the directory is usable.
 *
 * The working directory *is* the session's file context (every path-scoped tool
 * resolves against it, and `bash`/`run` and stdio MCP servers start there), so a
 * `cwd` that does not name an existing directory cannot be served: without this
 * gate the session came into existence and then failed per tool call - or worse,
 * a stdio MCP server silently inherited the agent's own working directory
 * (issue #74).
 *
 * Only a gate, no canonicalization: the value stays the session's identity (it
 * is persisted and compared verbatim on load/resume), so symlinked or
 * differently-spelled paths are deliberately not folded here.
 */
internal fun sessionCwdProblem(cwd: String): String? {
    val path = try {
        Path.of(cwd)
    } catch (e: InvalidPathException) {
        return "invalid cwd \"$cwd\": ${e.reason ?: e.message}"
    }
    if (!path.isAbsolute) return "cwd \"$cwd\" must be an absolute path"
    if (!Files.exists(path)) return "cwd \"$cwd\" does not exist"
    if (!Files.isDirectory(path)) return "cwd \"$cwd\" is not a directory"
    return null
}
