package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunToolTest {

    private fun tempDir(): String = Files.createTempDirectory("acp-run-tool").toString()

    private fun context(cwd: String) = ToolContext(
        cwd = cwd,
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
    )

    @Test
    fun `loads run configurations from ai run json`() {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"test":{"command":"npm test {args}","description":"Run unit tests"}}""")
        val configs = loadRunConfigs(dir)
        assertEquals(1, configs.size)
        assertEquals("test", configs.single().name)
        assertEquals("npm test {args}", configs.single().command)
        assertEquals("Run unit tests", configs.single().description)
    }

    @Test
    fun `missing file returns empty list`() {
        assertTrue(loadRunConfigs(tempDir()).isEmpty())
    }

    @Test
    fun `title shows config and args without resolving the command`() {
        assertEquals(
            "run(config: test, args: --watch)",
            RunTool(tempDir()).title(
                buildJsonObject {
                    put("config", "test")
                    put("args", "--watch")
                },
            ),
        )
    }

    @Test
    fun `tool is execute kind, non-mutating and thus prompt-free in every mode`() {
        val tool = RunTool(tempDir())
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertFalse(tool.mutating)
    }

    @Test
    fun `malformed json returns empty list`() {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), "{not json")
        assertTrue(loadRunConfigs(dir).isEmpty())
    }

    @Test
    fun `unknown fields are ignored and commandless entries skipped`() {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(
            aiDir.resolve("run.json"),
            """{"good":{"command":"echo hi","extra":"ignored"},"bad":{"description":"no command"}}""",
        )
        val configs = loadRunConfigs(dir)
        assertEquals(listOf("good"), configs.map { it.name })
    }

    @Test
    fun `runs a configuration command`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"greet":{"command":"printf 'hello'"}}""")
        val result = RunTool(dir).execute(buildJsonObject { put("config", "greet") }, context(dir))
        assertFalse(result.isError, result.text)
        assertEquals("hello", result.text)
    }

    @Test
    fun `substitutes args for the placeholder`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '%s' {args}"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                put("args", "world")
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("world", result.text)
    }

    @Test
    fun `substitutes args for every placeholder occurrence`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(
            aiDir.resolve("run.json"),
            """{"twice":{"command":"printf '%s-%s' {args} {args}"}}""",
        )
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "twice")
                put("args", "ab")
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("ab-ab", result.text, "every {args} occurrence must be substituted")
    }

    @Test
    fun `missing placeholder substitutes empty string`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf 'a{args}b'"}}""")
        val result = RunTool(dir).execute(buildJsonObject { put("config", "echo") }, context(dir))
        assertFalse(result.isError, result.text)
        assertEquals("ab", result.text)
    }

    @Test
    fun `unknown configuration errors listing available ones`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"test":{"command":"npm test"}}""")
        val result = RunTool(dir).execute(buildJsonObject { put("config", "nope") }, context(dir))
        assertTrue(result.isError)
        assertTrue(result.text.contains("nope"), result.text)
        assertTrue(result.text.contains("test"), result.text)
    }

    @Test
    fun `args on a configuration without placeholder errors`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"test":{"command":"npm test"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "test")
                put("args", "-- --watch")
            },
            context(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("does not accept arguments"), result.text)
    }

    @Test
    fun `description is static and does not list configurations`() {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(
            aiDir.resolve("run.json"),
            """{"test":{"command":"npm test","description":"Run unit tests"},"lint":{"command":"npm run lint"}}""",
        )
        val description = RunTool(dir).description
        assertFalse(description.contains("npm test"), description)
        assertFalse(description.contains("Run unit tests"), description)
        assertFalse(description.contains("lint"), description)
        assertEquals(description, RunTool(tempDir()).description)
    }

    @Test
    fun `non-zero exit is an error with output`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"fail":{"command":"echo 'oops' >&2; exit 3"}}""")
        val result = RunTool(dir).execute(buildJsonObject { put("config", "fail") }, context(dir))
        assertTrue(result.isError)
        assertTrue(result.text.contains("oops"), result.text)
    }
}