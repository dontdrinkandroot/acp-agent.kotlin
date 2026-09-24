package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject

public class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description =
        "List the contents of a directory. Entries matching an exclusion rule " +
                "(currently .env*.local) are omitted."
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
        return executeSafely("List failed") {
            val dir = Path(path)
            val meta = SystemFileSystem.metadataOrNull(dir)
            if (meta == null || !meta.isDirectory) return@executeSafely ToolResult("Not a directory: $path", true)
            val sorted = SystemFileSystem.list(dir)
                .filter { context.fileExclusions.matchingRuleForPath(context.cwd, it.toString()) == null }
                .map { it.name }
                .sorted()
            ToolResult(formatCappedListing(sorted))
        }
    }
}
