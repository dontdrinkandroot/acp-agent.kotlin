package net.dontdrinkandroot.acpagent.agent

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import net.dontdrinkandroot.acpagent.isoDateToday
import net.dontdrinkandroot.acpagent.tools.RunConfig

/** Example working directory of the dump - deliberately not the real one. */
private const val EXAMPLE_CWD = "/home/dev/projects/example-project"

private val examplePermissionPolicy = PermissionPolicy(
    readableDirectories = listOf("/srv/data", "/mnt/scratch", "/tmp/ddr-acp-agent"),
    writableDirectories = listOf("/srv/scratch"),
)

private val exampleExcludedFileGlobs = listOf(".env*.local", ".ai/run.json", "secrets/**")

private val exampleRunConfigs = listOf(
    RunConfig("build", "./gradlew build", "Compile + run all unit and e2e tests"),
    RunConfig("test_class", "./gradlew test --tests [args]", "Run a single test class via [args]"),
)

/**
 * Prints the assembled system prompt for review (run config `system_prompt`,
 * Gradle task `printSystemPrompt`).
 */
public fun main() {
    // Keep stdout clean: the kotlin-logging startup banner would otherwise
    // become the prompt's first line.
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    KotlinLoggingConfiguration.logStartupMessage = false
    print(reviewSystemPrompt())
}

/**
 * Assembles the system prompt like [AgentSessionImpl] does it, but without the
 * project instructions (AGENTS.md) and from invented example values instead of
 * the actual environment: the working directory, the trusted read paths, the
 * exclusion rules and the run configurations are all made up, so the review is
 * reproducible and always renders every section - a real cwd would silently
 * drop the sections it has no data for. Only the build hash and today's date
 * come from the running build.
 */
internal fun reviewSystemPrompt(): String =
    SystemPromptBuilder(
        EXAMPLE_CWD,
        ::isoDateToday,
        runConfigsProvider = { exampleRunConfigs },
        permissionPolicy = examplePermissionPolicy,
        excludedFileGlobs = exampleExcludedFileGlobs,
    ).build(null)
