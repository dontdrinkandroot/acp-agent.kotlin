package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.*

private fun tmpDir(): String {
    val dir = "/tmp/acp-agent-test-${Random.nextBytes(6).toHex()}"
    SystemFileSystem.createDirectories(Path(dir))
    return dir
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun context(cwd: String) = ToolContext(
    cwd = cwd,
    client = null,
    clientCapabilities = ClientCapabilities(),
    sessionId = SessionId("sess_test"),
)

class FileToolsTest {

    @Test
    fun `write then read round trip`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/a/b/c.txt"
        val write = WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "line1\nline2\nline3") }, context(dir))
        assertFalse(write.isError, write.text)
        assertTrue(write.text.contains("Written"))

        val read = ReadFileTool().execute(buildJsonObject { put("path", path) }, context(dir))
        assertFalse(read.isError, read.text)
        assertEquals("line1\nline2\nline3", read.text)
    }

    @Test
    fun `read with line and limit slices`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc\nd\ne") }, context(dir))
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("line", 2); put("limit", 2) }, context(dir))
        assertFalse(read.isError, read.text)
        assertEquals("b\nc", read.text)
    }

    @Test
    fun `read missing file errors`() = runBlocking {
        val result = ReadFileTool().execute(buildJsonObject { put("path", "/nonexistent/nope.txt") }, context("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `write missing path errors`() = runBlocking {
        val result = WriteFileTool().execute(buildJsonObject { put("content", "x") }, context("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `edit replaces unique substring`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world hello") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", "kotlin") },
            context(dir),
        )
        assertFalse(edit.isError, edit.text)
        val content = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals("hello kotlin hello", content)
    }

    @Test
    fun `edit missing old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "abc") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "zzz"); put("new_string", "x") },
            context(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("not found"))
    }

    @Test
    fun `edit ambiguous old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a a a") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "a"); put("new_string", "b") },
            context(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("matches"))
    }

    @Test
    fun `list dir and glob and grep`() = runBlocking {
        val dir = tmpDir()
        val fs = SystemFileSystem
        fs.createDirectories(Path("$dir/nested"))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "alpha") }, context(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/nested/b.kt"); put("content", "val alpha = 1") }, context(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/nested/c.txt"); put("content", "beta") }, context(dir))

        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, context(dir))
        assertFalse(list.isError, list.text)
        assertEquals(listOf("a.txt", "nested"), list.text.split("\n"))

        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "**/*.kt") }, context(dir))
        assertFalse(glob.isError, glob.text)
        assertEquals(listOf("nested/b.kt"), glob.text.split("\n"))

        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "alpha") }, context(dir))
        assertFalse(grep.isError, grep.text)
        assertEquals(listOf("a.txt:1:alpha", "nested/b.kt:1:val alpha = 1"), grep.text.split("\n"))
    }

    @Test
    fun `glob root not found errors`() = runBlocking {
        val result = GlobTool().execute(buildJsonObject { put("root", "/nonexistent-root"); put("pattern", "*") }, context("/tmp"))
        assertTrue(result.isError)
    }
}

class GlobRegexTest {

    @Test
    fun `simple star does not cross directories`() {
        val regex = globToRegex("src/*.kt")
        assertTrue(regex.matches("src/Main.kt"))
        assertFalse(regex.matches("src/sub/Main.kt"))
    }

    @Test
    fun `double star crosses directories`() {
        val regex = globToRegex("src/**/*.kt")
        assertTrue(regex.matches("src/Main.kt"))
        assertTrue(regex.matches("src/a/b/Main.kt"))
    }

    @Test
    fun `question mark matches single char`() {
        val regex = globToRegex("?.txt")
        assertTrue(regex.matches("a.txt"))
        assertFalse(regex.matches("ab.txt"))
    }

    @Test
    fun `regex metacharacters are escaped`() {
        val regex = globToRegex("a+b.kt")
        assertTrue(regex.matches("a+b.kt"))
        assertFalse(regex.matches("ab.kt"))
        assertFalse(regex.matches("aaa.kt"))
    }
}

class ToolSchemaTest {

    @Test
    fun `required is a json array in every tool schema`() {
        val tools = listOf(
            ReadFileTool(),
            WriteFileTool(),
            EditFileTool(),
            ListDirTool(),
            GlobTool(),
            GrepTool(),
            BashTool(),
        )
        tools.forEach { tool ->
            val required = tool.parameters["required"]
            assertIs<JsonArray>(required, "${tool.name} required must be an array")
            required.forEach { assertIs<JsonPrimitive>(it) }
        }
    }
}

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
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject, context: ToolContext): ToolResult = ToolResult("x")
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
        assertTrue(registry.disabledInMode("bash", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("read_file", SessionModeId("plan")) == null)

        val build = registry.availableForMode(SessionModeId("build"))
        assertTrue(build.any { it.name == "write_file" })
        assertTrue(build.any { it.name == "edit_file" })
        assertTrue(build.none { it.name == "bash" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("build")) != null)

        val bash = registry.availableForMode(SessionModeId("bash"))
        assertTrue(bash.any { it.name == "bash" })
        assertTrue(bash.any { it.name == "write_file" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("bash")) == null)
        assertTrue(registry.disabledInMode("unknown_tool", SessionModeId("bash")) == null)
    }
}
