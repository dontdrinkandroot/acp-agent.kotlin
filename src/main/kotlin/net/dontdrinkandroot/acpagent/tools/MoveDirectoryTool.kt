package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject

/**
 * Moves or renames a directory and everything below it. The destination must
 * not exist; missing parent directories of the destination are created. Every
 * moved file is reported as a delete+add diff pair so both sides keep a
 * rollback baseline (issue #56), bounded and in sorted path order like the
 * tree deletion evidence.
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
        executeMove(
            arguments,
            context,
            requireDirectory = true,
            successPrefix = "Moved directory"
        ) { source, destination ->
            moveTreeEvidence(source, destination, context)
        }
}

/**
 * move_directory's change evidence (issue #56): a delete+add pair per moved
 * file, in sorted path order within the bounded tree-diff budget; files whose
 * content cannot be reported surface as the "original content reported for M
 * of N files" fact (issue #57). VCS metadata is not evidenced (issue #79) but
 * still moves with the tree.
 */
private suspend fun moveTreeEvidence(source: String, destination: String, context: ToolContext): MoveEvidence {
    val collector = TreeDiffCollector(context.fileStore, context.fileExclusions, context.cwd)
    for (file in treeFilesSorted(SystemFileSystem, Path(source))) {
        val relative = file.removePrefix(source).trimStart('/')
        val movedTo = if (relative.isEmpty()) destination else "$destination/$relative"
        collector.collect(file, movedTo = movedTo)
    }
    val note = collector.partialNote()
    val facts = if (note == null) "content unchanged" else "content unchanged; ${note.removeSurrounding("(", ")")}"
    return MoveEvidence(collector.diffs, facts)
}
