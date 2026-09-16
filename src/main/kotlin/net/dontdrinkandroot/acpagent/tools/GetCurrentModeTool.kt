package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

/**
 * Returns the current mode and the tools available in it, as the modal status
 * messages in the conversation history state them. A safety valve for the case
 * where the model is unsure which mode governs the session (e.g. in a restored
 * session whose history trail predates the status messages, or after a long
 * history with many mode switches); the answer reflects the mode captured
 * when the turn started, which is also what gates tool calls and permissions.
 */
public class GetCurrentModeTool : AgentTool {
    override val name = "get_current_mode"
    override val description =
        "Returns the current session mode (plan, build or bash) and the tools available in it. " +
                "Use it when unsure which mode is active and what you are allowed to do."
    override val kind = ToolKind.OTHER
    override val mutating = false
    override val parameters: JsonObject = jsonSchema()

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val text = context.modeStatusText().takeIf { it.isNotBlank() }
            ?: return ToolResult("Current mode is unavailable in this context.", true)
        return ToolResult(text)
    }
}
