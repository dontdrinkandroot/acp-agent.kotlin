package net.dontdrinkandroot.acpagent.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

private val logger = KotlinLogging.logger {}

/**
 * The content of the AGENTS.md file in the session working directory.
 */
internal data class AgentsInstructions(
    val path: String,
    val content: String,
)

/**
 * Reads AGENTS.md directly from the session working directory (never via the
 * client fs proxy). Returns null when the file does not exist; other read
 * errors are logged and never fail the turn.
 */
internal fun loadAgentsInstructions(cwd: String): AgentsInstructions? {
    val path = "$cwd/AGENTS.md"
    val fs = SystemFileSystem
    if (!fs.exists(Path(path))) return null
    return runCatching {
        AgentsInstructions(path, fs.source(Path(path)).buffered().use { it.readString() })
    }.getOrElse {
        logger.warn(it) { "Failed to load AGENTS.md: $path" }
        null
    }
}

/**
 * Renders the project instructions section for the system prompt, or an empty
 * string when there are no instructions.
 */
internal fun instructionsSection(instructions: AgentsInstructions?): String {
    if (instructions == null || instructions.content.isBlank()) return ""
    return "\n\n## Project Instructions (from AGENTS.md)\n\n" +
            "Loaded from: ${instructions.path}\n" +
            instructions.content
}