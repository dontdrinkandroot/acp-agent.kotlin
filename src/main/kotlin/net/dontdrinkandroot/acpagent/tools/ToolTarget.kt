package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.JsonObject

/**
 * The outcome of resolving one tool-supplied path against the session cwd:
 * either the absolute, exclusion-checked path the tool may use for I/O, or
 * the error result to return instead (issue #80 - the single choke point for
 * the path-tool prologue).
 */
internal sealed interface ToolTarget {

    /** The absolute path (relative arguments resolved against the session cwd). */
    data class Resolved(val path: String) : ToolTarget

    /** The error result the tool must return instead of touching the path. */
    data class Rejected(val error: ToolResult) : ToolTarget
}

/**
 * Resolves a raw tool-supplied path: absolutized against the session working
 * directory ([absoluteToolPath]) and checked against the file-access exclusion
 * policy ([FileAccessExclusions.matchingRuleForTarget] + [exclusionError]), in
 * exactly the order every path tool used before this seam existed. This is
 * the one place where a direct tool target may be refused as excluded.
 */
internal fun resolveToolTarget(context: ToolContext, rawPath: String): ToolTarget {
    val path = absoluteToolPath(context.cwd, rawPath)
    val excludedRule = context.fileExclusions.matchingRuleForTarget(context.cwd, path)
    if (excludedRule != null) return ToolTarget.Rejected(ToolResult(exclusionError(path, excludedRule), true))
    return ToolTarget.Resolved(path)
}

/**
 * The required-argument form: decodes [argName] (with the shared [argError]
 * texts for missing, explicit-null and non-string values) and then resolves it
 * via [resolveToolTarget]. Tools with optional path arguments or interleaved
 * argument validation decode themselves and call the raw form.
 */
internal fun resolveToolTarget(
    context: ToolContext,
    arguments: JsonObject,
    argName: String = "path",
): ToolTarget {
    val rawPath = arguments.stringArg(argName)
        ?: return ToolTarget.Rejected(ToolResult(arguments.argError(argName), true))
    return resolveToolTarget(context, rawPath)
}