package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

public class BashTool : AgentTool {
    override val name = "bash"
    override val description = "Run a shell command in the project working directory. For build/test/git/diagnostics."
    override val kind = ToolKind.EXECUTE
    override val mutating = true
    override val modes = listOf(MODE_BASH)
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override val parameters: JsonObject = jsonSchema(
        required("command", PropType.STRING, "Shell command to run (via /bin/sh) in the project working directory.")
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val command = arguments.stringArg("command") ?: return ToolResult(arguments.argError("command"), true)
        return runShellCommand(command, context, "Command failed")
    }
}