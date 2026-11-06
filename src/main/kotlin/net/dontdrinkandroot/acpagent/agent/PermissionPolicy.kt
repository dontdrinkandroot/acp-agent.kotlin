package net.dontdrinkandroot.acpagent.agent

import kotlinx.serialization.json.JsonObject
import net.dontdrinkandroot.acpagent.config.UnlistedPaths
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.isWithin
import net.dontdrinkandroot.acpagent.tools.isWithinAnyRoot

/**
 * The path-access tiers of a session (issue #50, `permissions.*`):
 *
 * - the session cwd is implicitly read **and** write,
 * - [readableDirectories] hold paths the read-only path tools may touch
 *   prompt-free (the agent's scratch directory is merged in here as well),
 * - [writableDirectories] hold paths the mutating path tools may touch
 *   prompt-free; writable implies readable,
 * - every other path outside the cwd is governed by [unlistedPaths].
 *
 * Only path-scoped file tools are governed here; `bash`, `run` and MCP tools
 * keep their own permission semantics.
 */
internal data class PermissionPolicy(
    val readableDirectories: List<String> = emptyList(),
    val writableDirectories: List<String> = emptyList(),
    val unlistedPaths: UnlistedPaths = UnlistedPaths.PROMPT,
)

/** The outcome of the path-tier check, before any user interaction. */
internal enum class PermissionDecision {
    /** Inside the cwd or inside a matching tier: run without asking. */
    ALLOW,

    /** Outside every tier, with `unlistedPaths = prompt`: ask the user. */
    PROMPT,

    /** Outside every tier, with `unlistedPaths = deny`: refuse without asking. */
    DENY,
}

/**
 * Decides a tool call's path permission from its targets:
 *
 * 1. no targets (non-path tool such as `bash`): mutating tools prompt, read-only
 *    tools run - exactly as before the tiers existed;
 * 2. all targets inside the session cwd: allowed;
 * 3. all targets inside the tier matching the tool's kind (read tools: readable
 *    ∪ writable, mutating tools: writable): allowed;
 * 4. otherwise: the [PermissionPolicy.unlistedPaths] policy decides between
 *    prompting and a hard refusal.
 *
 * A partially covered call (one target inside a tier, one outside) is an
 * unlisted call - every target must be covered.
 */
internal fun permissionDecision(
    cwd: String,
    tool: AgentTool,
    arguments: JsonObject,
    policy: PermissionPolicy = PermissionPolicy(),
): PermissionDecision {
    val targets = tool.targetPaths(arguments)
    if (targets.isEmpty()) return if (tool.mutating) PermissionDecision.PROMPT else PermissionDecision.ALLOW
    if (targets.all { isWithin(cwd, it) }) return PermissionDecision.ALLOW
    val roots = if (tool.mutating) {
        policy.writableDirectories
    } else {
        policy.readableDirectories + policy.writableDirectories
    }
    if (roots.isNotEmpty() && targets.all { isWithinAnyRoot(cwd, roots, it) }) return PermissionDecision.ALLOW
    return when (policy.unlistedPaths) {
        UnlistedPaths.PROMPT -> PermissionDecision.PROMPT
        UnlistedPaths.DENY -> PermissionDecision.DENY
    }
}

/**
 * The model-facing refusal for a [PermissionDecision.DENY]: it names the tool,
 * the offending targets and the configuration entry that would have covered
 * them, because there is no prompt to latch onto - the fix is editing
 * `permissions.readableDirectories` / `permissions.writableDirectories`.
 */
internal fun unlistedPathRefusal(tool: AgentTool, arguments: JsonObject): String {
    val tier = if (tool.mutating) "permissions.writableDirectories" else "permissions.readableDirectories"
    val targets = tool.targetPaths(arguments).joinToString(", ") { "'$it'" }
    return "Error: tool \"${tool.name}\" was denied without a permission prompt: $targets " +
            "is outside the session directory and outside every configured directory tier, and " +
            "permissions.unlistedPaths is set to \"deny\". Ask the user to add the path to $tier " +
            "(or to switch permissions.unlistedPaths back to \"prompt\") and do not retry this call."
}