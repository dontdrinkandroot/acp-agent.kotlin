package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject

public class GlobTool : AgentTool {
    override val name = "glob"
    override val description =
        "Find files matching a glob pattern (e.g. src/**/*.kt) under a directory. " +
                "Files matching an exclusion rule (the .env*.local secret files and .ai/run.json) are omitted."
    override val kind = ToolKind.SEARCH
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required("pattern", PropType.STRING, "Glob pattern (e.g. src/**/*.kt); ** crosses directory boundaries."),
        optional(
            "path",
            PropType.STRING,
            "Directory to search, absolute or relative to the working directory; defaults to the working directory."
        ),
    )

    override fun targetPath(arguments: JsonObject): String? =
        arguments.stringArg("path")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("path")) return ToolResult(arguments.argError("path"), true)
        val rawRoot = arguments.stringArg("path") ?: context.cwd
        val root = absoluteToolPath(context.cwd, rawRoot)
        val pattern = arguments.stringArg("pattern") ?: return ToolResult(arguments.argError("pattern"), true)
        return executeSafely("Glob failed") {
            val regex = globToRegex(pattern)
            val base = Path(root)
            searchRootError(root)?.let { return@executeSafely ToolResult(it, true) }
            val results = mutableListOf<String>()
            walk(base, 0) { f ->
                if (context.fileExclusions.matchingRuleForPath(context.cwd, f.toString()) != null) return@walk
                val rel = relativeToRoot(root, f.toString())
                if (regex.matches(rel)) results += rel
            }
            ToolResult(formatCappedListing(results))
        }
    }
}
