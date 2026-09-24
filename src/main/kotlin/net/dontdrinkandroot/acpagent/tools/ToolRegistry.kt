package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId

public class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    public fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    public fun registerAll(tools: Iterable<AgentTool>) {
        tools.forEach { register(it) }
    }

    public fun get(name: String): AgentTool? = tools[name]

    public fun all(): List<AgentTool> = tools.values.toList()

    public fun availableForMode(mode: SessionModeId): List<AgentTool> =
        tools.values.filter { it.modes.isEmpty() || mode in it.modes }

    public fun disabledInMode(name: String, mode: SessionModeId): AgentTool? =
        tools[name]?.takeIf { it.modes.isNotEmpty() && mode !in it.modes }
}
