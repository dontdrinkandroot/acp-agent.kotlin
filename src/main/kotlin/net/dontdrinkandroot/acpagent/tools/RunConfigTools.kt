package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.*

private val RUN_CONFIG_WRITE_MODES = listOf(
    SessionModeId("build"),
    SessionModeId("bash"),
)

private val RUN_CONFIG_WRITE_PARAMETERS: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildJsonObject {
        putJsonObject("name") {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Name of the run configuration"))
        }
        putJsonObject("command") {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Shell command to run once the configuration is executed"))
        }
        putJsonObject("description") {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Optional human-readable description of the configuration"))
        }
    })
    putJsonArray("required") { add(JsonPrimitive("name")) }
}

/**
 * The create tool's schema: `command` is required.
 */
private fun configRunWriteParameters(mutable: Boolean): JsonObject {
    val required = buildJsonArray {
        add(JsonPrimitive("name"))
        if (mutable) add(JsonPrimitive("command"))
    }
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("name") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Name of the run configuration"))
            }
            putJsonObject("command") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Shell command to run once the configuration is executed"))
            }
            putJsonObject("description") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional human-readable description of the configuration"))
            }
        })
        put("required", required)
    }
}

private val NO_ARGUMENT_PARAMETERS: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildJsonObject {})
    putJsonArray("required") {}
}

/**
 * Lists the run configurations defined in `.ai/run.json`. Read-only and
 * available in every mode.
 */
internal class ListRunConfigsTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "list_run_configs"
    override val description = "List the run configurations defined in .ai/run.json in the project working directory."
    override val kind = ToolKind.EXECUTE
    override val mutating = false
    override val modes = emptyList<SessionModeId>()
    override val parameters: JsonObject = NO_ARGUMENT_PARAMETERS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val configs = loadRunConfigs(cwd)
        if (configs.isEmpty()) return ToolResult("No run configurations defined in $RUN_CONFIG_FILE")
        val listing = configs.joinToString("\n") { config ->
            buildString {
                append(config.name)
                if (config.description != null) append(" - ${config.description}")
                append("\n")
                append("  command: ").append(config.command)
            }
        }
        return ToolResult(listing)
    }
}

/**
 * Creates a run configuration in `.ai/run.json`. Mutating, build/bash only.
 */
internal class CreateRunConfigTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "create_run_config"
    override val description =
        "Create a run configuration in .ai/run.json in the project working directory. The name must not exist yet."
    override val kind = ToolKind.EXECUTE
    override val mutating = true
    override val modes = RUN_CONFIG_WRITE_MODES
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)
    override val parameters: JsonObject = configRunWriteParameters(mutable = true)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.stringArg("name") ?: return ToolResult(arguments.argError("name"), true)
        val command = arguments.stringArg("command")
        val description = arguments.stringArg("description")?.takeIf { it.isNotBlank() }
        if (arguments.isNullArg("description")) return ToolResult(arguments.argError("description"), true)
        return try {
            val config = createRunConfig(cwd, name, command, description)
            ToolResult("Created run configuration \"${config.name}\": ${config.command}")
        } catch (e: RunConfigException) {
            ToolResult(e.message ?: "Could not create run configuration", isError = true)
        }
    }
}

/**
 * Field-level updates a run configuration in `.ai/run.json`. `command` is
 * never cleared; an omitted field stays unchanged, an empty-string
 * `description` clears it. Build/bash only.
 */
internal class UpdateRunConfigTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "update_run_config"
    override val description =
        "Update a run configuration in .ai/run.json in the project working directory. Provide only the fields to change; " +
                "the command can never be cleared."
    override val kind = ToolKind.EXECUTE
    override val mutating = true
    override val modes = RUN_CONFIG_WRITE_MODES
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)
    override val parameters: JsonObject = configRunWriteParameters(mutable = false)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.stringArg("name") ?: return ToolResult(arguments.argError("name"), true)
        val command = arguments.stringArg("command")
        val description = arguments.stringArg("description")
        if (arguments.isNullArg("command")) return ToolResult(arguments.argError("command"), true)
        if (arguments.isNullArg("description")) return ToolResult(arguments.argError("description"), true)
        return try {
            val config = updateRunConfig(cwd, name, command, description)
            val output = buildString {
                append("Updated run configuration \"").append(config.name).append("\": ")
                append(config.command)
                if (config.description != null) append(" (").append(config.description).append(")")
            }
            ToolResult(output)
        } catch (e: RunConfigException) {
            ToolResult(e.message ?: "Could not update run configuration", isError = true)
        }
    }
}

/**
 * Deletes a run configuration from `.ai/run.json`. Build/bash only.
 */
internal class DeleteRunConfigTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "delete_run_config"
    override val description =
        "Delete a run configuration from .ai/run.json in the project working directory."
    override val kind = ToolKind.EXECUTE
    override val mutating = true
    override val modes = RUN_CONFIG_WRITE_MODES
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("name") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Name of the run configuration to delete"))
            }
        })
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.stringArg("name") ?: return ToolResult(arguments.argError("name"), true)
        return try {
            val config = deleteRunConfig(cwd, name)
            val output = buildString {
                append("Deleted run configuration \"").append(config.name).append("\"")
                if (config.command.isNotBlank()) append(": ").append(config.command)
            }
            ToolResult(output)
        } catch (e: RunConfigException) {
            ToolResult(e.message ?: "Could not delete run configuration", isError = true)
        }
    }
}