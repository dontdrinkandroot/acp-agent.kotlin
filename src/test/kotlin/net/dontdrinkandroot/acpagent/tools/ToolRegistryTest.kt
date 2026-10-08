package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRegistryTest {
    @Test
    fun `register get all and override`() = runBlocking {
        val registry = ToolRegistry()
        val read = ReadFileTool()
        registry.register(read)
        registry.register(WriteFileTool())
        assertEquals(2, registry.all().size)
        assertEquals(read, registry.get("read_file"))
        assertEquals(null, registry.get("missing"))

        val override = object : AgentTool {
            override val name = "read_file"
            override val description = "override"
            override val parameters = buildJsonObject { put("type", JsonPrimitive("object")) }
            override val kind = com.agentclientprotocol.model.ToolKind.READ
            override val mutating = false
            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("x")
        }
        registry.register(override)
        assertEquals(2, registry.all().size)
        assertEquals("override", registry.get("read_file")?.description)
    }

    @Test
    fun `mode filtering withholds write and bash tools per mode`() = runBlocking {
        val registry = ToolRegistry()
        registry.registerAll(
            listOf(
                ReadFileTool(),
                WriteFileTool(),
                EditFileTool(),
                MoveFileTool(),
                MoveDirectoryTool(),
                DeleteFileTool(),
                DeleteDirectoryTool(),
                ListDirTool(),
                GlobTool(),
                GrepTool(),
                BashTool(),
            )
        )
        val plan = registry.availableForMode(SessionModeId("plan"))
        assertEquals(
            listOf("read_file", "list_dir", "glob", "grep").sorted(),
            plan.map { it.name }.sorted(),
        )
        assertTrue(registry.disabledInMode("write_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("edit_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("move_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("delete_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("bash", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("read_file", SessionModeId("plan")) == null)

        val build = registry.availableForMode(SessionModeId("build"))
        assertTrue(build.any { it.name == "write_file" })
        assertTrue(build.any { it.name == "edit_file" })
        assertTrue(build.any { it.name == "move_file" })
        assertTrue(build.any { it.name == "move_directory" })
        assertTrue(build.any { it.name == "delete_file" })
        assertTrue(build.any { it.name == "delete_directory" })
        assertTrue(build.none { it.name == "bash" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("build")) != null)

        val bash = registry.availableForMode(SessionModeId("bash"))
        assertTrue(bash.any { it.name == "bash" })
        assertTrue(bash.any { it.name == "write_file" })
        assertTrue(bash.any { it.name == "delete_directory" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("bash")) == null)
        assertTrue(registry.disabledInMode("unknown_tool", SessionModeId("bash")) == null)
    }
}
