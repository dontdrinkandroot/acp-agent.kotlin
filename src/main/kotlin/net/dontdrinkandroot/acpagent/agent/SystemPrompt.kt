package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.model.SessionModeId
import net.dontdrinkandroot.acpagent.BuildInfo
import net.dontdrinkandroot.acpagent.tools.RunConfig

/**
 * Builds the system prompt for a prompt turn. Pure text assembly: the prompt
 * is derived from the session mode, the working directory, today's date, the
 * run configurations and the AGENTS.md instructions, so it is independent of
 * the session's mutable state and trivially testable.
 */
internal class SystemPromptBuilder(
    private val cwd: String,
    private val todayProvider: () -> String,
    private val runConfigsProvider: () -> List<RunConfig> = { emptyList() },
) {

    fun build(mode: SessionModeId, instructions: AgentsInstructions?): String = buildString {
        appendLine("You are acp-agent, a fast and compact coding agent embedded in the user's IDE via the Agent Client Protocol.")
        appendLine("Agent build: ${BuildInfo.commit}")
        appendLine()
        appendLine("Session working directory: $cwd")
        appendLine("Today's date: ${todayProvider()}")
        appendLine("Current mode: ${mode.value}. ${modeDescription(mode)}")
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
        appendLine("- Prefer the `run` tool's named configurations for the standard build/test/compile loop over raw `bash` shells.")
        append(runConfigsSection(runConfigsProvider()))
        append(instructionsSection(instructions))
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

    private fun modeDescription(mode: SessionModeId): String = when (mode.value) {
        "build" -> "You may read, write, move and delete files to implement the user's task."
        "bash" ->
            "You may read, modify files, and run shell commands via the 'bash' tool. " +
                    "Every command is confirmed by the user first; do not retry a rejected command. " +
                    "Commands run in the session working directory."

        else ->
            "You are in PLAN mode. You must not modify files: research, evaluate, and analyze the " +
                    "codebase, presenting findings or a concise implementation plan in Markdown as the task demands. " +
                    "Do not call write tools even if offered."
    }
}
