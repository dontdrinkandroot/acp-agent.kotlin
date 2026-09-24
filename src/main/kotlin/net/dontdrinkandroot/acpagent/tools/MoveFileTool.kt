package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files

/**
 * Moves or renames a file. The destination must not exist; missing parent
 * directories of the destination are created. The move never follows symlinks:
 * a symlink source is moved as the link itself. kotlinx-io's `atomicMove` is a
 * JVM stub that always throws, so the move goes through java.nio directly
 * (same as the run-config writes in `RunTool`); cross-device moves fail with
 * the underlying error.
 */
public class MoveFileTool : AgentTool {
    override val name = "move_file"
    override val description = "Move or rename a file to a new location (refuses when the destination exists)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = BUILD_AND_BASH_MODES
    override val parameters: JsonObject = jsonSchema(
        required("source", PropType.STRING, "File to move, absolute or relative to the working directory."),
        required(
            "destination",
            PropType.STRING,
            "New file location, absolute or relative to the working directory; missing parent directories are created."
        ),
    )

    override fun targetPaths(arguments: JsonObject): List<String> =
        listOfNotNull(
            arguments.stringArg("source"),
            arguments.stringArg("destination"),
        )

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        executeMove(arguments, context, requireDirectory = false, successPrefix = "Moved")
}

/**
 * The shared move flow of move_file/move_directory: argument decoding,
 * source type gate, destination-exists refusal, missing-parent creation and
 * the move itself. [requireDirectory] selects which source type is expected
 * and names the sibling tool in the refusal.
 */
internal suspend fun executeMove(
    arguments: JsonObject,
    context: ToolContext,
    requireDirectory: Boolean,
    successPrefix: String,
): ToolResult {
    val rawSource = arguments.stringArg("source") ?: return ToolResult(arguments.argError("source"), true)
    val rawDestination = arguments.stringArg("destination")
        ?: return ToolResult(arguments.argError("destination"), true)
    val source = absoluteToolPath(context.cwd, rawSource)
    val destination = absoluteToolPath(context.cwd, rawDestination)
    return executeSafely("Move failed") {
        val fs = SystemFileSystem
        val sourcePath = Path(source)
        val meta = fs.metadataOrNull(sourcePath)
            ?: return@executeSafely ToolResult("Source not found: $source", true)
        if (meta.isDirectory != requireDirectory) {
            val shape = if (requireDirectory) "is not a directory" else "is a directory"
            val sibling = if (requireDirectory) "move_file" else "move_directory"
            return@executeSafely ToolResult("Source $shape: $source (use $sibling)", true)
        }
        val destinationPath = Path(destination)
        if (fs.exists(destinationPath)) return@executeSafely ToolResult("Destination exists: $destination", true)
        fs.createDirectories(destinationPath.parent ?: Path("."))
        movePath(sourcePath, destinationPath)
        ToolResult("$successPrefix $source to $destination")
    }
}

/**
 * Renames [source] to [destination]. kotlinx-io's atomicMove is a JVM stub
 * (always throws); java.nio move without REPLACE_EXISTING keeps the
 * destination-exists refusal and does not follow symlinks.
 */
internal fun movePath(source: Path, destination: Path) {
    Files.move(
        java.nio.file.Path.of(source.toString()),
        java.nio.file.Path.of(destination.toString()),
    )
}
