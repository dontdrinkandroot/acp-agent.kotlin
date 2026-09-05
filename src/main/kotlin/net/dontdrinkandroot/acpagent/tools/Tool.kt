package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_TITLE_ARGUMENTS_LENGTH = 50
private const val MAX_TITLE_ARGUMENTS_KEEP = 47

/**
 * Formats a human-readable tool-call title as `name(key: value, ...)` for
 * permission prompts and tool-call progress. Values are shown unquoted, blank
 * values are skipped and the argument part is trimmed when it exceeds 50
 * characters.
 *
 * The JetBrains ACP client renders only the `title` of a tool call in its
 * permission prompts (the `rawInput` wire field is not displayed), so tools
 * with meaningful arguments should use this via [AgentTool.title]. Clients
 * that do render `rawInput` (e.g. Zed) are unaffected.
 */
public fun formatToolTitle(name: String, arguments: JsonObject): String {
    val rendered = arguments.mapNotNull { (key, value) ->
        val content = value.primitiveContentOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        "$key: $content"
    }
    val joined = rendered.joinToString(", ")
    val args = if (joined.length > MAX_TITLE_ARGUMENTS_LENGTH) {
        joined.take(MAX_TITLE_ARGUMENTS_KEEP) + "..."
    } else {
        joined
    }
    return if (args.isEmpty()) name else "$name($args)"
}

private fun JsonElement.primitiveContentOrNull(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else content
    else -> null
}

public data class ToolResult(
    val text: String,
    val isError: Boolean = false
)

public class ToolContext internal constructor(
    val cwd: String,
    val client: ClientSessionOperations?,
    val clientCapabilities: ClientCapabilities,
    val sessionId: com.agentclientprotocol.model.SessionId,
    val updatePlan: suspend (List<PlanEntry>) -> Unit = {},
    internal val fileStore: FileStore = LocalFileStore(),
    internal val bashTimeoutSeconds: Int = 600,
) {
    public val hasClient: Boolean get() = client != null
}

public interface AgentTool {
    public val name: String
    public val description: String
    public val parameters: JsonObject
    public val kind: ToolKind
    public val mutating: Boolean

    public val modes: List<SessionModeId>
        get() = emptyList()

    /**
     * The filesystem target of the tool, derived from its arguments, or null
     * when the tool is not path-scoped. Drives the in-project / out-of-project
     * permission decision.
     */
    public fun targetPath(arguments: JsonObject): String? = null

    /**
     * A human-readable title for a tool call, defaulting to the tool name.
     * The JetBrains ACP client renders only this field in permission prompts
     * (`rawInput` is not displayed), so tools with meaningful arguments should
     * return a short `name(key: value, ...)` summary via [formatToolTitle].
     * Returns null to fall back to the bare tool name; when JetBrains starts
     * rendering `rawInput` the overrides can be dropped and the call sites
     * revert to `tool.name` unchanged.
     */
    public fun title(arguments: JsonObject): String? = null

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}
