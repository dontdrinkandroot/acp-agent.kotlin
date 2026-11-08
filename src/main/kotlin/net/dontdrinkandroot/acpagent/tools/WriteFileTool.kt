package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

public class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Write text content to a file (creates or overwrites). " +
            "Files matching an exclusion rule (the .env*.local secret files and .ai/run.json) are refused."
    override val kind = ToolKind.EDIT
    override val mutating = true
    override val modes = BUILD_AND_BASH_MODES
    override val parameters: JsonObject = jsonSchema(
        required("path", PropType.STRING, "File path, absolute or relative to the working directory."),
        required("content", PropType.STRING, "Full file content; replaces existing content."),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    /**
     * A human-readable title so the model (and the permission prompt) shows what
     * is being replaced: a whole-file write with the content size, not a bare
     * "write_file". The size signals the overwrite scope (e.g. a rewrite of a
     * large file) without needing to I/O in the title.
     */
    override fun title(arguments: JsonObject): String? {
        val path = arguments.stringArg("path") ?: return null
        val content = arguments.stringArg("content") ?: return null
        return formatToolTitle(name, buildJsonObject {
            put("path", JsonPrimitive(path))
            put("size", JsonPrimitive("${content.length} chars"))
        })
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawPath = arguments.stringArg("path") ?: return ToolResult(arguments.argError("path"), true)
        val path = absoluteToolPath(context.cwd, rawPath)
        val excludedRule = context.fileExclusions.matchingRuleForTarget(context.cwd, path)
        if (excludedRule != null) return ToolResult(exclusionError(path, excludedRule), true)
        val content = arguments.stringArg("content") ?: return ToolResult(arguments.argError("content"), true)
        return executeSafely("Write failed") {
            val outcome = writeDiffOutcome(path, content, context)
            context.fileStore.writeFile(path, content)
            mutationResult("Written $path", path, outcome)
        }
    }
}

/**
 * Best-effort pre-read of the target so the result can carry a `Diff` content
 * block for clients that render tool-call diffs without the client fs proxy.
 * Only a genuinely absent file yields a diff with `oldText = null` (ACP's "this
 * file is new"); a file that exists but cannot be read omits the diff instead
 * of claiming newness (issue #55) and reports the omission (issue #57). A
 * change the client fs proxy renders itself carries no diff and costs no
 * pre-read round-trip (issue #58, the shared rule in `DiffPolicy.kt`);
 * oversized content is dropped with a visible note to keep the wire payload
 * sane.
 */
private suspend fun writeDiffOutcome(path: String, newText: String, context: ToolContext): DiffOutcome {
    if (!emitsToolDiff(context.fileStore.rendersChange)) return DiffOutcome.Suppressed
    val oldText = try {
        context.fileStore.readRaw(path)
    } catch (e: CancellationException) {
        // A cancelled turn must abort; the pre-read is best-effort but the
        // cancellation is not: swallowing it here would defeat the tool's
        // own rethrow.
        throw e
    } catch (e: FileAbsentException) {
        // A missing file is a new-file write: the diff shows oldText = null.
        null
    } catch (e: Exception) {
        // Exists but is unreadable (or oversized): no baseline, so no diff -
        // never a false "new file". The drop is reported (issue #57).
        return DiffOutcome.Omit("cannot read original: ${e.message ?: e}")
    }
    return toolResultDiff(path, newText, oldText)
}
