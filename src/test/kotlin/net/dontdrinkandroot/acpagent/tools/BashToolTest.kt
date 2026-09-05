package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BashToolTest {

    private val context = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
    )

    @Test
    fun `runs a command and captures stdout`() = runBlocking {
        val result = BashTool().execute(buildJsonObject { put("command", "printf 'hello'") }, context)
        assertFalse(result.isError, result.text)
        assertEquals("hello", result.text)
    }

    @Test
    fun `non-zero exit is an error with output`() = runBlocking {
        val result = BashTool().execute(buildJsonObject { put("command", "echo 'oops' >&2; exit 3") }, context)
        assertTrue(result.isError)
        assertTrue(result.text.contains("oops"), result.text)
    }

    @Test
    fun `missing command errors`() = runBlocking {
        val result = BashTool().execute(buildJsonObject {}, context)
        assertTrue(result.isError)
    }

    @Test
    fun `title shows the command argument`() {
        assertEquals(
            "bash(command: ./gradlew test)",
            BashTool().title(buildJsonObject { put("command", "./gradlew test") }),
        )
    }

    @Test
    fun `command timing out is terminated and reported`() = runBlocking {
        val ctx = ToolContext(
            cwd = "/tmp",
            client = null,
            clientCapabilities = ClientCapabilities(),
            sessionId = SessionId("sess_test"),
            bashTimeoutSeconds = 2,
        )
        val start = System.currentTimeMillis()
        val result = BashTool().execute(buildJsonObject { put("command", "sleep 30") }, ctx)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(result.isError, result.text)
        assertTrue(result.text.contains("timed out"), result.text)
        assertTrue(elapsed < 15000, "timeout must terminate the process quickly, took ${elapsed}ms")
    }

    @Test
    fun `cancelling a running command terminates it promptly`() = runBlocking {
        val dir = Files.createTempDirectory("acp-bash-cancel").toString()
        val pidFile = "$dir/pid"
        val job = launch(Dispatchers.IO) {
            ProcessRunner.run("echo \$\$ > $pidFile; sleep 30", null, 600)
        }
        val pid = awaitPid(pidFile)
        val start = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue(
            System.currentTimeMillis() - start < 10000,
            "cancellation must interrupt the running command, took ${System.currentTimeMillis() - start}ms",
        )
        delay(200)
        val alive = ProcessBuilder("kill", "-0", pid).start().waitFor() == 0
        assertFalse(alive, "process $pid must be dead after cancellation")
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
}
