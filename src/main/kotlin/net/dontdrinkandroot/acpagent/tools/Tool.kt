package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

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

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}
