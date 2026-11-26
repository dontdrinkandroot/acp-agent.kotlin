package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.AvailableCommand
import com.agentclientprotocol.model.ContentBlock
import net.dontdrinkandroot.acpagent.config.Config

/** The advertised command name; the spec carries names without the leading slash. */
internal const val INFO_COMMAND_NAME = "info"

/**
 * The command's advertised surface. Lives next to [infoCommandRemainder] so the
 * name the client shows and the name the turn recognizes cannot drift apart.
 */
internal fun infoAvailableCommand(): AvailableCommand = AvailableCommand(
    name = INFO_COMMAND_NAME,
    description = "Print the resolved agent configuration and the session's current model",
)

/**
 * Longest discarded remainder rendered in the note. A user who pastes a wall of
 * text after the command must not let that text dominate the diagnostic output.
 */
private const val MAX_DISCARDED_TEXT_LENGTH = 200

private val WHITESPACE = Regex("\\s+")

/**
 * The extra text of a `/info` invocation, or null when [content] is no
 * invocation at all:
 *
 * - exactly one text block (any further block could be a real request the
 *   command must not swallow),
 * - whose trimmed text is `/info` or starts with `/info` and whitespace - the
 *   word boundary keeps `/infofoo` an ordinary prompt, matching the spec's rule
 *   for clients that offer completion,
 * - case-sensitive, so `/INFO` reads as prose rather than as a silent no-op.
 *
 * The remainder is what the turn discards; it is reported back to the user by
 * [renderInfo] instead of being dropped silently.
 */
internal fun infoCommandRemainder(content: List<ContentBlock>): String? {
    val commandText = (content.singleOrNull() as? ContentBlock.Text)?.text?.trim() ?: return null
    val invocation = "/$INFO_COMMAND_NAME"
    if (!commandText.startsWith(invocation)) return null
    val remainder = commandText.removePrefix(invocation)
    if (remainder.isNotEmpty() && !remainder.first().isWhitespace()) return null
    return remainder.trim()
}

/**
 * Everything [renderInfo] shows, captured at invocation time. Deliberately
 * holds the [Config] (safe to render - the key is a `Secret`) but no API key
 * value: only the key *file* is named (issue #78).
 *
 * The session-scoped values are the live ones (mode, model, reasoning and
 * provider as currently selected), not the startup defaults.
 */
internal data class InfoSnapshot(
    val config: Config,
    val agentVersion: String,
    val buildCommit: String,
    val sessionId: String,
    val cwd: String,
    val mode: String,
    /** The mode's advertised description; blank when the mode has none. */
    val modeDescription: String,
    val sessionModel: String,
    /** The reasoning picker's value, or null when the model exposes no reasoning option. */
    val reasoning: String?,
    val provider: String,
    /**
     * The scratch directory the agent actually trusts: the prepared (verified,
     * symlink-resolved) path of this session, falling back to the configured one
     * when the session has none. The two differ only when a path component is a
     * symlink, in which case the dump must show the path the trust checks use.
     */
    val scratchDir: String,
    /** Text the invocation discarded (everything after the command), empty when none. */
    val discardedText: String = "",
)

/**
 * Renders the `/info` reply as one Markdown block: the effective values only,
 * no provenance and no staleness note - the config is resolved once at startup
 * (documented in the README instead).
 */
internal fun renderInfo(snapshot: InfoSnapshot): String = buildString {
    val config = snapshot.config
    if (snapshot.discardedText.isNotEmpty()) {
        appendLine("**Note:** `/info` ignores additional text; discarded: \"${noteText(snapshot.discardedText)}\"")
        appendLine()
    }
    appendLine("## Agent")
    appendLine("- Version: ${snapshot.agentVersion}")
    appendLine("- Build: ${snapshot.buildCommit}")
    appendLine()
    appendLine("## Session")
    appendLine("- Id: ${snapshot.sessionId}")
    appendLine("- Cwd: ${code(snapshot.cwd)}")
    appendLine("- Mode: ${oneLine(snapshot.mode)}${modeSuffix(snapshot.modeDescription)}")
    appendLine()
    appendLine("## Model")
    appendLine("- Base URL: ${oneLine(config.openRouterBaseUrl)}")
    appendLine("- Default model: ${oneLine(config.openRouterModel)}")
    appendLine("- Session model: ${oneLine(snapshot.sessionModel)}")
    appendLine("- Reasoning: ${snapshot.reasoning?.let(::oneLine) ?: "not supported by this model"}")
    appendLine("- Provider: ${oneLine(snapshot.provider)}")
    appendLine()
    appendLine("## Behavior")
    appendLine("- Auto throughput sorting: ${onOff(config.autoThroughputSortingEnabled)}")
    appendLine("- FS proxy: ${onOff(config.fsProxyEnabled)}")
    appendLine("- MCP trust annotations: ${onOff(config.mcpTrustAnnotations)}")
    appendLine("- Bash timeout: ${config.bashTimeoutSeconds}s")
    appendLine("- Max turn requests: ${config.maxTurnRequests}")
    appendLine("- Web fetch private: ${onOff(config.webFetchAllowPrivate)}")
    appendLine()
    appendLine("## Paths")
    appendLine("- API key: ${keyFile(config.openRouterApiKeyFile)}")
    appendLine("- Project file: ${projectFile(config.projectConfigFile)}")
    appendLine("- Readable: ${paths(config.readableDirectories)}")
    appendLine("- Writable: ${paths(config.writableDirectories)}")
    appendLine("- Unlisted paths: ${config.unlistedPaths.name.lowercase()}")
    appendLine("- Scratch: ${code(snapshot.scratchDir)}")
}.trimEnd()

private fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

/**
 * Every value is rendered inside a list item, so a line break in a config value
 * or a path would break the block apart. Flattening keeps the dump's structure
 * independent of what a config file contains.
 */
private fun oneLine(value: String): String = value.replace(WHITESPACE, " ")

/** A value rendered as inline code (paths the user may copy). */
private fun code(value: String): String = "`${oneLine(value)}`"

private fun paths(directories: List<String>): String =
    if (directories.isEmpty()) "(none)" else directories.joinToString(", ") { code(it) }

private fun keyFile(path: String?): String = if (path == null) "(unknown)" else "${code(path)} (owner-only)"

/**
 * The project overlay that was applied, or `(none)`. The merged tiers above cannot
 * show provenance by design (the model needs the path, not its origin), so the dump
 * names the file once - "why can this session read `/srv/data`?" stays answerable.
 */
private fun projectFile(path: String?): String = if (path == null) "(none)" else code(path)

private fun modeSuffix(description: String): String = if (description.isBlank()) "" else " (${oneLine(description)})"

/** One line, capped, so the note can neither break the block nor grow without bound. */
private fun noteText(discardedText: String): String {
    val flattened = discardedText.replace(WHITESPACE, " ").trim()
    return if (flattened.length > MAX_DISCARDED_TEXT_LENGTH) {
        flattened.take(MAX_DISCARDED_TEXT_LENGTH) + "…"
    } else {
        flattened
    }
}
