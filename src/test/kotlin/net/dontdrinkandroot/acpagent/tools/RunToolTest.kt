package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.nio.file.Files
import kotlin.test.*

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
    fun `title leads with the config name and truncates only args`() {
        // Behavioral change: run(config: ..., args: ...) hid the config when
        // args were long; the config name now always leads, untruncated.
        assertEquals(
            "run(test: --watch)",
            RunTool(tempDir()).title(
                buildJsonObject {
                    put("config", "test")
                    putJsonArray("args") { add("--watch") }
                },
            ),
        )
        val longArgs = "arg ".repeat(40)
        val title = RunTool(tempDir()).title(
            buildJsonObject {
                put("config", "test")
                putJsonArray("args") { add(longArgs.trim()) }
            },
        ) ?: ""
        assertTrue(title.startsWith("run(test: "), title)
        assertTrue(title.contains("..."), title)
    }

    @Test
    fun `tool is execute kind, non-mutating and thus prompt-free in every mode`() {
        val tool = RunTool(tempDir())
        assertEquals(ToolKind.EXECUTE, tool.kind)
        assertFalse(tool.mutating)
    }

    @Test
    fun `schema pins config with optional args`() {
        val tool = RunTool(tempDir())
        assertEquals(
            """{"type":"object","properties":{"config":{"type":"string","description":"Name of the run configuration to execute"},"args":{"type":"array","description":"Optional arguments passed to the configuration command as positional parameters (one element = one argument, never shell-interpreted); the command's [args] slot receives them.","items":{"type":"string"}}},"required":["config"]}""",
            llmWireJson.encodeToString(tool.parameters),
        )
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
    fun `cancelling the tool during a running command propagates instead of a failed result`() = runBlocking {
        val dir = tempDir()
        val pidFile = "$dir/pid"
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), "{\"sleep\":{\"command\":\"echo \$\$ > $pidFile; sleep 30\"}}")
        // Regression (issue #1): the tool used to swallow the CancellationException
        // into a bogus "Run configuration failed" ToolResult; it must propagate instead.
        // The captured outcome distinguishes the two: a swallowing body returns
        // a ToolResult (no suspension point after the catch), a propagating body
        // never completes normally, so it stays null.
        var outcome: ToolResult? = null
        val job = launch(Dispatchers.IO) {
            outcome = RunTool(dir).execute(buildJsonObject { put("config", "sleep") }, context(dir))
        }
        awaitPid(pidFile)
        job.cancelAndJoin()
        assertNull(outcome, "cancellation must propagate; the tool must not return a failed result, got: $outcome")
    }

    private suspend fun awaitPid(file: String, timeoutMillis: Long = 5000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val content = runCatching {
                SystemFileSystem.source(Path(file)).buffered().use { it.readString() }
            }.getOrNull()
            if (!content.isNullOrBlank()) return content.trim()
            delay(50)
        }
        error("pid file $file was never written")
    }

    @Test
    fun `substitutes args for the placeholder`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '%s' [args]"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                putJsonArray("args") { add("world") }
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("world", result.text)
    }

    @Test
    fun `multi-word element arrives as one argument`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '[%s]' [args]"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                putJsonArray("args") { add("a b") }
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("[a b]", result.text, "one element is exactly one argument, spaces included")
    }

    @Test
    fun `injection-shaped element is passed through literally`() = runBlocking {
        // Regression (issue #4): model-controlled args were spliced into the
        // command string, so `x"; rm -rf ~ #` on the quoted test_class config
        // executed the rm. With argv delivery the same element is one literal
        // token that the command sees as data.
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '[%s]' [args]"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                putJsonArray("args") { add("""x"; rm -rf ~ #""") }
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("""[x"; rm -rf ~ #]""", result.text)
    }

    @Test
    fun `substitutes args for every placeholder occurrence`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(
            aiDir.resolve("run.json"),
            """{"twice":{"command":"printf '%s-%s' [args] [args]"}}""",
        )
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "twice")
                putJsonArray("args") { add("ab") }
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("ab-ab", result.text, "every [args] occurrence must be substituted")
    }

    @Test
    fun `args reaching the shell as separate tokens still re-orders as argv`() = runBlocking {
        // Deliberate semantics pin: the command text keeps the shell (the
        // config author writes pipelines/redirects there), so positional
        // parameters are delivered where the command's argv conventions put
        // them - the config decides the slot, the model only fills it.
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"twice":{"command":"printf '[%s][%s]' [args] [args]"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "twice")
                putJsonArray("args") {
                    add("a")
                    add("b")
                }
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("[a][b][a][b]", result.text, "both placeholders expand to the full args list")
    }

    @Test
    fun `empty args array is allowed on a configuration without placeholder`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"test":{"command":"true"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "test")
                putJsonArray("args") {}
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
    }

    @Test
    fun `non-string or null args element errors`() = runBlocking {
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '%s' [args]"}}""")
        for (element in listOf<JsonElement>(JsonNull, JsonPrimitive(42))) {
            val result = RunTool(dir).execute(
                buildJsonObject {
                    put("config", "echo")
                    putJsonArray("args") { add(element) }
                },
                context(dir),
            )
            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("'args'"), result.text)
        }
    }

    @Test
    fun `placeholder with empty args vanishes`() = runBlocking {
        // Quoted "$@" with zero positional parameters expands to nothing, so a
        // command like `printf 'a[args]b'` renders "ab" with no args.
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '[%s]' [args]"}}""")
        val result = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                putJsonArray("args") {}
            },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("[]", result.text)
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
                putJsonArray("args") { add("--watch") }
            },
            context(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("does not accept arguments"), result.text)
    }

    @Test
    fun `legacy braces placeholder is a literal word and rejects args`() = runBlocking {
        // Hard switch (issue #4): `{args}` is no longer substituted; a config
        // still carrying it must fail loudly with args instead of running the
        // injection-prone splice.
        val dir = tempDir()
        val aiDir = Files.createDirectories(java.nio.file.Path.of(dir, ".ai"))
        Files.writeString(aiDir.resolve("run.json"), """{"echo":{"command":"printf '%s' {args}"}}""")
        val withArgs = RunTool(dir).execute(
            buildJsonObject {
                put("config", "echo")
                putJsonArray("args") { add("hi") }
            },
            context(dir),
        )
        assertTrue(withArgs.isError, withArgs.text)
        assertTrue(withArgs.text.contains("does not accept arguments"), withArgs.text)
        val withoutArgs = RunTool(dir).execute(buildJsonObject { put("config", "echo") }, context(dir))
        assertFalse(withoutArgs.isError, withoutArgs.text)
        assertEquals("{args}", withoutArgs.text, "without args the literal word passes through unchanged")
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
