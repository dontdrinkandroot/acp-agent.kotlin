package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CancellationException
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
 * the underlying error. The rename is reported as a delete+add diff pair so
 * both sides keep a rollback baseline (issue #56).
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
        executeMove(arguments, context, requireDirectory = false, successPrefix = "Moved") { source, destination ->
            moveFileEvidence(source, destination, context)
        }
}

/**
 * The change evidence a move reports before the rename runs (issue #56): the
 * diffs (per affected file) plus the fact phrase the result text states in
 * parentheses. "content unchanged" is the rename fact that disambiguates the
 * v1 delete+add diff pair - the bytes did not change, only their location.
 */
internal data class MoveEvidence(
    val diffs: List<ToolResultDiff>,
    val facts: String,
)

/**
 * move_file's change evidence: the delete+add pair for the rename (issue #56),
 * or the omitted-diff note with its reason (issue #57). The content is read
 * through the store - the user's view, like the other diff pre-reads - before
 * the move takes the file away.
 */
private suspend fun moveFileEvidence(source: String, destination: String, context: ToolContext): MoveEvidence {
    val content = try {
        context.fileStore.readRaw(source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return MoveEvidence(emptyList(), "content unchanged; diff omitted: cannot read original: ${e.message ?: e}")
    }
    return when (val removal = toolResultDiff(source, "", content)) {
        is DiffOutcome.Emit -> MoveEvidence(
            diffs = listOf(
                ToolResultDiff(source, "", content),
                ToolResultDiff(destination, content, null),
            ),
            facts = "content unchanged",
        )

        is DiffOutcome.Omit -> MoveEvidence(emptyList(), "content unchanged; diff omitted: ${removal.reason}")
        DiffOutcome.Suppressed -> MoveEvidence(emptyList(), "content unchanged")
    }
}

/**
 * The shared move flow of move_file/move_directory: argument decoding,
 * source type gate, exclusion gate (an excluded path may be neither the
 * source - moving it away removes it just like a delete - nor the destination,
 * which would create or replace it), destination-exists refusal, missing-parent
 * creation, the change evidence ([collectEvidence], before the move takes the
 * file away - issue #56) and the move itself. [requireDirectory] selects which
 * source type is expected and names the sibling tool in the refusal.
 */
internal suspend fun executeMove(
    arguments: JsonObject,
    context: ToolContext,
    requireDirectory: Boolean,
    successPrefix: String,
    collectEvidence: suspend (source: String, destination: String) -> MoveEvidence,
): ToolResult {
    val rawSource = arguments.stringArg("source") ?: return ToolResult(arguments.argError("source"), true)
    val rawDestination = arguments.stringArg("destination")
        ?: return ToolResult(arguments.argError("destination"), true)
    val source = absoluteToolPath(context.cwd, rawSource)
    val destination = absoluteToolPath(context.cwd, rawDestination)
    context.fileExclusions.matchingRuleForTarget(context.cwd, source)?.let {
        return ToolResult(exclusionError(source, it), true)
    }
    context.fileExclusions.matchingRuleForTarget(context.cwd, destination)?.let {
        return ToolResult(exclusionError(destination, it), true)
    }
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
        val evidence = collectEvidence(source, destination)
        movePath(sourcePath, destinationPath)
        ToolResult("$successPrefix $source to $destination (${evidence.facts})", diffs = evidence.diffs)
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
