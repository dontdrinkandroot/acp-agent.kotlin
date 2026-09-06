package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject

public class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description = "List the contents of a directory."
    override val kind = ToolKind.READ
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "Directory path, absolute or relative to the working directory."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments.stringArg("path") ?: return ToolResult(arguments.argError("path"), true)
        val path = absoluteToolPath(context.cwd, rawPath)
        return runCatching {
            val fs = SystemFileSystem
            val dir = Path(path)
            val meta = fs.metadataOrNull(dir)
            if (meta == null || !meta.isDirectory) return@runCatching ToolResult("Not a directory: $path", true)
            val sorted = fs.list(dir).map { it.name }.sorted()
            val listing = sorted.take(MAX_LISTING_ENTRIES).joinToString("\n")
            if (sorted.size > MAX_LISTING_ENTRIES) {
                ToolResult("$listing\n...(${sorted.size - MAX_LISTING_ENTRIES} more entries omitted)")
            } else {
                ToolResult(listing)
            }
        }.getOrElse { ToolResult("List failed: ${it.message}", true) }
    }
}

private const val MAX_LISTING_ENTRIES = 500
