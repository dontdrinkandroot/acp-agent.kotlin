package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*

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
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("source") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Directory to move, absolute or relative to the working directory."))
            }
            putJsonObject("destination") {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "New directory location, absolute or relative to the working directory; " +
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
            if (!meta.isDirectory) return@runCatching ToolResult(
                "Source is not a directory: $source (use move_file)",
                true
            )
            val destinationPath = Path(destination)
            if (fs.exists(destinationPath)) return@runCatching ToolResult("Destination exists: $destination", true)
            fs.createDirectories(destinationPath.parent ?: Path("."))
            movePath(sourcePath, destinationPath)
            ToolResult("Moved directory $source to $destination")
        }.getOrElse { ToolResult("Move failed: ${it.message}", true) }
    }
}
