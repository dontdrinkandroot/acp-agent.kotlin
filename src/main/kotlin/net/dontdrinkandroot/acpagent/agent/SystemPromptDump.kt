package net.dontdrinkandroot.acpagent.agent

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.config.platformEnv
import net.dontdrinkandroot.acpagent.isoDateToday
import net.dontdrinkandroot.acpagent.tools.FileAccessExclusions
import net.dontdrinkandroot.acpagent.tools.loadRunConfigs
import java.io.File

/**
 * Prints the assembled system prompt for review (run config `system_prompt`,
 * Gradle task `printSystemPrompt`). The optional first argument is the working
 * directory to render for, defaulting to the current one.
 */
public fun main(args: Array<String>) {
    // Keep stdout clean: the kotlin-logging startup banner would otherwise
    // become the prompt's first line.
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    KotlinLoggingConfiguration.logStartupMessage = false
    val cwd = args.firstOrNull()?.let { File(it).absolutePath } ?: System.getProperty("user.dir")
    print(reviewSystemPrompt(cwd))
}

/**
 * Assembles the system prompt exactly like [AgentSessionImpl] does it, but
 * without the project instructions (AGENTS.md), so the static prompt sections
 * can be reviewed on their own. The environment only contributes the trusted
 * read paths (`ACP_EXTRA_MOUNTS`); no API key is required.
 */
internal fun reviewSystemPrompt(cwd: String, env: Map<String, String> = platformEnv()): String =
    SystemPromptBuilder(
        cwd,
        ::isoDateToday,
        runConfigsProvider = { loadRunConfigs(cwd) },
        trustedReadPaths = Config.parseExtraMounts(env["ACP_EXTRA_MOUNTS"]),
        excludedFileGlobs = FileAccessExclusions.DEFAULT.globs(),
    ).build(null)
