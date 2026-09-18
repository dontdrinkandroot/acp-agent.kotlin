package net.dontdrinkandroot.acpagent.agent

import net.dontdrinkandroot.acpagent.BuildInfo
import net.dontdrinkandroot.acpagent.tools.FileAccessExclusions
import net.dontdrinkandroot.acpagent.tools.RunConfig

/**
 * Builds the system prompt for a prompt turn. The prompt is static with
 * respect to the session's mode: it describes the available modes and holds
 * no "current mode" statement (that lives in the mode status messages inside
 * the conversation history). It is re-read from disk per turn only for the
 * run configurations and the AGENTS.md instructions, so mid-session edits to
 * either apply.
 */
internal class SystemPromptBuilder(
    private val cwd: String,
    private val todayProvider: () -> String,
    private val runConfigsProvider: () -> List<RunConfig> = { emptyList() },
    /**
     * Absolute read-trusted paths (`ACP_EXTRA_MOUNTS`, e.g. the extra docker
     * mounts): reads under them never prompt. Static for the process lifetime,
     * so the prompt can state them once.
     */
    private val trustedReadPaths: List<String> = emptyList(),
    /**
     * Glob rules of the file-access exclusion policy (see
     * [net.dontdrinkandroot.acpagent.tools.FileAccessExclusions]): rendered
     * into a static prompt section so the model knows which files the file
     * tools refuse or hide. Static for the process lifetime.
     */
    private val excludedFileGlobs: List<String> = FileAccessExclusions.DEFAULT.globs(),
) {

    fun build(instructions: AgentsInstructions?): String = buildString {
        appendLine("You are acp-agent, a fast and compact coding agent embedded in the user's IDE via the Agent Client Protocol.")
        appendLine("Agent build: ${BuildInfo.commit}")
        appendLine()
        appendLine("Session working directory: $cwd")
        appendLine("Today's date: ${todayProvider()}")
        appendLine()
        appendLine("## Modes")
        appendLine()
        appendLine(
            "The session runs in one of the following modes. The current mode is stated in a status " +
                    "message in the conversation, together with the tools available in that mode; the tool schemas " +
                    "in the request are the tools you may call right now. Yours must respect the current mode."
        )
        appendLine()
        appendLine(
            "- plan: read-only. You may research and analyze, listing files, reading files, searching and " +
                    "updating the plan, but you must not modify files."
        )
        appendLine("- build: read-write. Adds the file write/edit/move/delete tools and the run-config management tools.")
        appendLine(
            "- bash: build plus the permission-gated bash tool. Commands run in the session working directory " +
                    "and every command is confirmed by the user first."
        )
        appendLine()
        appendLine("Operating rules:")
        appendLine("- Use the provided tools; do not claim to have run tools you have not called.")
        appendLine("- When several tool calls are independent, request them together in one block.")
        appendLine("- Create the execution plan with update_plan before starting work and keep its statuses current.")
        appendLine("- Implement exactly what was asked; do not add features, abstractions, or refactors beyond the task.")
        appendLine("- Cite code as file_path:line_number where it helps navigation.")
        appendLine("- Keep responses terse; skip preamble and filler.")
        appendLine("- After finishing, summarize the result concisely in Markdown.")
        appendLine("- Modify existing code with `edit_file` deltas; use `write_file` only for new files or an intentional whole-file rewrite (read the full file first - a hasty rewrite can drop the tail).")
        appendLine("- When asserting behavior in a test, derive the expectation from the code being tested or its existing tests, not from assumptions.")
        appendLine("- Prefer the `run` tool's named configurations for the standard build/test/compile loop over `bash` shells.")
        appendLine(
            "- On a mode switch, continue with the mode stated in the latest status message; tool schemas " +
                    "in the request are already filtered to that mode. If a tool you wanted is not available, ask the " +
                    "user to switch mode rather than attempting a workaround."
        )
        append(trustedReadPathsSection(trustedReadPaths))
        append(excludedFilesSection(excludedFileGlobs))
        append(runConfigsSection(runConfigsProvider()))
        append(instructionsSection(instructions))
    }

    private fun trustedReadPathsSection(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        return buildString {
            appendLine()
            append("Trusted read paths (no permission prompt for `read_file`/`list_dir`/`glob`/`grep`; ")
            append("writes and mutations still require permission outside the working directory): ")
            appendLine(paths.joinToString(", "))
        }
    }

    /**
     * Static section stating the file-access exclusion policy: the direct
     * targets matching a rule are refused and listings/searches hide matches,
     * so the model does not have to discover the refusal per call.
     */
    private fun excludedFilesSection(globs: List<String>): String {
        if (globs.isEmpty()) return ""
        return buildString {
            appendLine()
            append(
                "Excluded files: file tools refuse or hide files matching the exclusion rules " +
                        "(${globs.joinToString(", ")}) - secrets stay out of the model context; " +
                        "the user can paste contents manually if needed."
            )
            appendLine()
        }
    }

    private fun runConfigsSection(configs: List<RunConfig>): String {
        if (configs.isEmpty()) return ""
        return buildString {
            append("\nAvailable run configurations:\n")
            configs.forEach { config ->
                append("- ").append(config.name).append(": ").append(config.command)
                config.description?.let { append(" — ").append(it) }
                appendLine()
            }
            appendLine(
                "Execute one via the `run` tool with `config: <name>`; pass `args` only for " +
                        "configurations whose command contains the {args} placeholder."
            )
        }
    }
}
