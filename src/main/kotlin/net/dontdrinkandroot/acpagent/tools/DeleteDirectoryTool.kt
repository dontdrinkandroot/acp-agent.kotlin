package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*
import java.nio.file.Files

/**
 * Deletes a directory and everything below it. The tree is scanned for
 * symlinks first and the deletion is refused when any is found: kotlinx-io
 * follows links, so a link inside the tree could otherwise smuggle the
 * recursive delete outside the project the permission flow approved.
 */
public class DeleteDirectoryTool : AgentTool {
    override val name = "delete_directory"
    override val description = "Delete a directory and all its contents (refuses trees containing symlinks)."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = BUILD_AND_BASH_MODES
    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("path") {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Directory to delete, absolute or relative to the working directory."))
            }
        })
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override fun targetPath(arguments: JsonObject): String? =
        arguments["path"]?.jsonPrimitive?.content

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments["path"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'path'", true)
        val path = absoluteToolPath(context.cwd, rawPath)
        return runCatching {
            val fs = SystemFileSystem
            val target = Path(path)
            val meta = fs.metadataOrNull(target)
                ?: return@runCatching ToolResult("Path not found: $path", true)
            if (!meta.isDirectory) return@runCatching ToolResult("Not a directory: $path (use delete_file)", true)
            if (Files.isSymbolicLink(java.nio.file.Path.of(path))) {
                return@runCatching ToolResult("Refusing to delete a symlink: $path", true)
            }
            containsSymlink(fs, target)?.let { link ->
                return@runCatching ToolResult("Refusing to delete directory containing symlinks: $link", true)
            }
            deleteRecursively(fs, target)
            ToolResult("Deleted directory $path")
        }.getOrElse { ToolResult("Delete failed: ${it.message}", true) }
    }
}

/**
 * Reports the first symlink found in the tree below [dir], or null. Symlinks
 * are detected before any metadata-based descent so links to directories
 * cannot smuggle the scan outside the tree.
 */
private fun containsSymlink(fs: FileSystem, dir: Path): String? {
    for (entry in fs.list(dir)) {
        if (Files.isSymbolicLink(java.nio.file.Path.of(entry.toString()))) return entry.toString()
        val meta = fs.metadataOrNull(entry)
        if (meta?.isDirectory == true) {
            containsSymlink(fs, entry)?.let { return it }
        }
    }
    return null
}

private fun deleteRecursively(fs: FileSystem, dir: Path) {
    for (entry in fs.list(dir)) {
        val meta = fs.metadataOrNull(entry)
        if (meta?.isDirectory == true) {
            deleteRecursively(fs, entry)
        } else {
            fs.delete(entry, mustExist = true)
        }
    }
    fs.delete(dir, mustExist = true)
}