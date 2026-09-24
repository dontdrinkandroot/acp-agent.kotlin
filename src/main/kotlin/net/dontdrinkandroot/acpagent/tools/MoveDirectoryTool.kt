package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

/**
 * Moves or renames a directory and everything below it. The destination must
 * not exist; missing parent directories of the destination are created.
 */
public class MoveDirectoryTool : AgentTool {
    override val name = "move_directory"
    override val description = "Move or rename a directory to a new location (refuses when the destination exists)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = BUILD_AND_BASH_MODES
    override val parameters: JsonObject = jsonSchema(
        required("source", PropType.STRING, "Directory to move, absolute or relative to the working directory."),
        required(
            "destination",
            PropType.STRING,
            "New directory location, absolute or relative to the working directory; missing parent directories are created."
        ),
    )

    override fun targetPaths(arguments: JsonObject): List<String> =
        listOfNotNull(
            arguments.stringArg("source"),
            arguments.stringArg("destination"),
        )

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        executeMove(arguments, context, requireDirectory = true, successPrefix = "Moved directory")
}
