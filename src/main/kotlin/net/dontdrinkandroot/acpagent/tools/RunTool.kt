package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

internal const val ARGS_PLACEHOLDER = "[args]"

/**
 * Runs a run configuration defined in `.ai/run.json` in the session working
 * directory. The configuration command is executed via the shell; the model's
 * `args` array is delivered to the command as positional parameters (one
 * element = one argument, never shell-interpreted) at the command's `[args]`
 * slot - issue #4's injection channel is closed structurally. The run-config
 * storage layer lives in `RunConfigStore.kt`.
 */
public class RunTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "run"
    override val description = "Run a named run configuration in the project working directory. " +
            "Pass optional arguments via 'args' (an array of strings); they are passed to the " +
            "configuration command as positional parameters at its $ARGS_PLACEHOLDER slot."

    override val kind = ToolKind.EXECUTE
    override val mutating = false
    override val modes = emptyList<SessionModeId>()
    override fun title(arguments: JsonObject): String? =
        formatRunToolTitle(arguments.stringArg("config"), arguments.stringListArg("args"))

    override val parameters: JsonObject = jsonSchema(
        required("config", PropType.STRING, "Name of the run configuration to execute"),
        optional(
            "args",
            PropType.ARRAY,
            "Optional arguments passed to the configuration command as positional parameters " +
                    "(one element = one argument, never shell-interpreted); the command's $ARGS_PLACEHOLDER slot receives them.",
            items = jsonSchemaProperty(PropType.STRING),
        ),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val configName = arguments.stringArg("config") ?: return ToolResult(arguments.argError("config"), true)
        if (arguments.isNullArg("args")) return ToolResult(arguments.argError("args", "an array of strings"), true)
        val args = when (arguments["args"]) {
            null -> emptyList()
            else -> arguments.stringListArg("args")
                ?: return ToolResult(arguments.argError("args", "an array of strings"), true)
        }
        val configs = loadRunConfigs(cwd)
        val config = configs.firstOrNull { it.name == configName }
            ?: return ToolResult(
                "Unknown run configuration \"$configName\". Available configurations: " +
                        configs.joinToString(", ") { it.name },
                true,
            )
        if (args.isNotEmpty() && !config.command.contains(ARGS_PLACEHOLDER)) {
            return ToolResult("Run configuration \"$configName\" does not accept arguments", true)
        }
        // Every occurrence is substituted so multi-slot commands do not leak a
        // literal "[args]" into the shell; the slot becomes a quoted "$@" so the
        // shell expands it to the positional parameters without word-splitting
        // them (unquoted "$@" would split elements containing whitespace).
        val resolvedCommand = config.command.replace(ARGS_PLACEHOLDER, "\"\$@\"")
        return runShellCommand(resolvedCommand, context, "Run configuration failed", args)
    }
}