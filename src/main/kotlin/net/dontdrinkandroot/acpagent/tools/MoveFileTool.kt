package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*
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
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("source") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("File to move, absolute or relative to the working directory."))
            }
            putJsonObject("destination") {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "New file location, absolute or relative to the working directory; " +
                                "missing parent directories are created."
                    ),
                )
            }
        })
        putJsonArray("required") {
            add(JsonPrimitive("source"))
            add(JsonPrimitive("destination"))
        }
    }

    override fun targetPaths(arguments: JsonObject): List<String> =
        listOfNotNull(
            arguments["source"]?.jsonPrimitive?.content,
            arguments["destination"]?.jsonPrimitive?.content,
        )

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawSource = arguments["source"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'source'", true)
        val rawDestination = arguments["destination"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'destination'", true)
        val source = absoluteToolPath(context.cwd, rawSource)
        val destination = absoluteToolPath(context.cwd, rawDestination)
        return runCatching {
            val fs = SystemFileSystem
            val sourcePath = Path(source)
            val meta = fs.metadataOrNull(sourcePath)
                ?: return@runCatching ToolResult("Source not found: $source", true)
            if (meta.isDirectory) return@runCatching ToolResult(
                "Source is a directory: $source (use move_directory)",
                true
            )
            val destinationPath = Path(destination)
            if (fs.exists(destinationPath)) return@runCatching ToolResult("Destination exists: $destination", true)
            fs.createDirectories(destinationPath.parent ?: Path("."))
            movePath(sourcePath, destinationPath)
            ToolResult("Moved $source to $destination")
        }.getOrElse { ToolResult("Move failed: ${it.message}", true) }
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
