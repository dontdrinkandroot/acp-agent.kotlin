package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.*

class RunConfigToolsTest {

    private fun tempDir(): String = Files.createTempDirectory("acp-run-cfg").toString()

    private fun runJsonPath(dir: String): java.nio.file.Path =
        java.nio.file.Path.of(dir, ".ai", "run.json")

    private fun writeRunJson(dir: String, text: String) {
        val path = runJsonPath(dir)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun configs(dir: String): List<RunConfig> = loadRunConfigs(dir)

    private fun entryExtra(dir: String, name: String): Int {
        val root = parseRunConfigRoot(Files.readString(runJsonPath(dir)))
        val entry = root[name] as kotlinx.serialization.json.JsonObject
        return (entry["extra"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
    }

    private fun context(cwd: String) = ToolContext(
        cwd = cwd,
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
    )

    @Test
    fun `create adds an entry`() {
        val dir = tempDir()
        val config = createRunConfig(dir, "test", "npm test", "Run unit tests")
        assertEquals(RunConfig("test", "npm test", "Run unit tests"), config)
        assertEquals(config, configs(dir).single())
    }

    @Test
    fun `writes to the run json are pretty printed`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "Run unit tests")
        val text = Files.readString(runJsonPath(dir))
        assertTrue(text.startsWith("{\n"), text)
        assertTrue(text.contains("\n    \"test\": {\n"), text)
        assertEquals(RunConfig("test", "npm test", "Run unit tests"), configs(dir).single())
    }

    @Test
    fun `create is rejected for an existing name`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", null)
        val e = assertFailsWith<RunConfigException> { createRunConfig(dir, "test", "other", null) }
        assertTrue(e.message!!.contains("already exists"), e.message)
    }

    @Test
    fun `create is rejected for a blank command and writes nothing`() {
        val dir = tempDir()
        val e = assertFailsWith<RunConfigException> { createRunConfig(dir, "test", "   ", null) }
        assertTrue(e.message!!.contains("requires a command"), e.message)
        assertTrue(!Files.exists(runJsonPath(dir)), "no file must be written on failure")
    }

    @Test
    fun `create on a missing file creates it`() {
        val dir = tempDir()
        createRunConfig(dir, "a", "echo a", null)
        assertTrue(Files.exists(runJsonPath(dir)))
        assertEquals("echo a", configs(dir).single().command)
    }

    @Test
    fun `update changes only the command`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "desc")
        val updated = updateRunConfig(dir, "test", "npm test -- --watch", null)
        assertEquals("npm test -- --watch", updated.command)
        assertEquals("desc", updated.description)
        assertEquals(RunConfig("test", "npm test -- --watch", "desc"), configs(dir).single())
    }

    @Test
    fun `update changes only the description`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "old")
        val updated = updateRunConfig(dir, "test", null, "new")
        assertEquals("npm test", updated.command)
        assertEquals("new", updated.description)
        assertEquals(RunConfig("test", "npm test", "new"), configs(dir).single())
    }

    @Test
    fun `update clears the description with an empty string`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "old")
        val updated = updateRunConfig(dir, "test", null, "  ")
        assertNull(updated.description)
        assertEquals(RunConfig("test", "npm test", null), configs(dir).single())
    }

    @Test
    fun `update rejects a blank command`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", null)
        val e = assertFailsWith<RunConfigException> { updateRunConfig(dir, "test", "  ", null) }
        assertTrue(e.message!!.contains("blank command"), e.message)
    }

    @Test
    fun `update rejects when nothing is provided`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", null)
        val e = assertFailsWith<RunConfigException> { updateRunConfig(dir, "test", null, null) }
        assertTrue(e.message!!.contains("Nothing to update"), e.message)
    }

    @Test
    fun `update rejects an unknown name`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", null)
        val e = assertFailsWith<RunConfigException> { updateRunConfig(dir, "nope", null, "x") }
        assertTrue(e.message!!.contains("Unknown run configuration \"nope\""), e.message)
    }

    @Test
    fun `delete removes the entry`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "d")
        val deleted = deleteRunConfig(dir, "test")
        assertEquals("test", deleted.name)
        assertTrue(configs(dir).isEmpty())
    }

    @Test
    fun `delete rejects an unknown name`() {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", null)
        val e = assertFailsWith<RunConfigException> { deleteRunConfig(dir, "nope") }
        assertTrue(e.message!!.contains("Unknown run configuration \"nope\""), e.message)
    }

    @Test
    fun `corrupt file refuses create and leaves it unchanged`() {
        val dir = tempDir()
        writeRunJson(dir, "{not json")
        val e = assertFailsWith<RunConfigException> { createRunConfig(dir, "test", "npm test", null) }
        assertTrue(e.message!!.contains("refusing to modify"), e.message)
        assertEquals("{not json", Files.readString(runJsonPath(dir)))
    }

    @Test
    fun `unknown entry fields survive a create round trip`() {
        val dir = tempDir()
        writeRunJson(dir, """{"existing":{"command":"x","extra":42}}""")
        createRunConfig(dir, "new", "y", null)
        assertEquals(42, entryExtra(dir, "existing"))
    }

    @Test
    fun `tool modes and mutation flags are correct`() {
        val expect = listOf(SessionModeId("build"), SessionModeId("bash"))
        assertTrue(ListRunConfigsTool("/tmp").modes.isEmpty())
        assertTrue(!ListRunConfigsTool("/tmp").mutating)
        assertTrue(CreateRunConfigTool("/tmp").mutating)
        assertTrue(UpdateRunConfigTool("/tmp").mutating)
        assertTrue(DeleteRunConfigTool("/tmp").mutating)
        assertEquals(expect, CreateRunConfigTool("/tmp").modes)
        assertEquals(expect, UpdateRunConfigTool("/tmp").modes)
        assertEquals(expect, DeleteRunConfigTool("/tmp").modes)
    }

    @Test
    fun `titles show the write arguments`() {
        assertEquals(
            "create_run_config(name: test, command: echo hello, description: unit)",
            CreateRunConfigTool("/tmp").title(
                buildJsonObject {
                    put("name", "test")
                    put("command", "echo hello")
                    put("description", "unit")
                },
            ),
        )
        assertEquals(
            "update_run_config(name: test, command: echo new)",
            UpdateRunConfigTool("/tmp").title(
                buildJsonObject {
                    put("name", "test")
                    put("command", "echo new")
                },
            ),
        )
        assertEquals(
            "delete_run_config(name: test)",
            DeleteRunConfigTool("/tmp").title(buildJsonObject { put("name", "test") }),
        )
    }

    @Test
    fun `list tool output names configs`() = runBlocking {
        val dir = tempDir()
        createRunConfig(dir, "test", "npm test", "unit")
        val result = ListRunConfigsTool(dir).execute(buildJsonObject {}, context(dir))
        assertTrue(!result.isError, result.text)
        assertTrue(result.text.contains("test - unit"), result.text)
        assertTrue(result.text.contains("npm test"), result.text)
    }

    @Test
    fun `list tool notes when empty`() = runBlocking {
        val dir = tempDir()
        val result = ListRunConfigsTool(dir).execute(buildJsonObject {}, context(dir))
        assertTrue(result.text.contains("No run configurations"), result.text)
    }
}
