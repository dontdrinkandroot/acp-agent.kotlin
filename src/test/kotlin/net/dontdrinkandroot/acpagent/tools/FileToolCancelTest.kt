package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * A [FileStore] double whose suspending calls park until the coroutine is
 * cancelled, so a cancellation during the store call is deterministic.
 * Mirrors the client fs proxy, where `readFile`/`writeFile`/`readRaw` are
 * suspending RPC round trips (issue #28).
 */
private class SuspendingStore(val entered: CompletableDeferred<Unit>) : FileStore {
    var suspendReadFile = true
    var suspendReadRaw = false
    var suspendWriteFile = false
    var rawContent: String? = null

    override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult {
        if (suspendReadFile) {
            entered.complete(Unit)
            awaitCancellation()
        }
        return ReadResult("")
    }

    override suspend fun writeFile(path: String, content: String) {
        if (suspendWriteFile) {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    override suspend fun readRaw(path: String): String {
        if (suspendReadRaw) {
            entered.complete(Unit)
            awaitCancellation()
        }
        return rawContent ?: throw FileStoreException("file not found: $path")
    }
}

/**
 * Cancellation must propagate out of the fs-proxy file tools: their store
 * calls are suspending RPCs with the client proxy, so a swallowed
 * CancellationException would surface as a bogus failed ToolResult instead
 * of ending the turn cancelled (issue #28). This covers the tool-level
 * catches and the inner one on the write path ([writeResultDiff]'s pre-read).
 *
 * The captured outcome distinguishes swallow from propagate: a swallowing
 * body returns a ToolResult (no suspension point after the catch), a
 * propagating body never completes normally, so it stays null. The store
 * handshake fails fast (5s) when the tool completes without entering a
 * suspending store call, so a test-design slip errors instead of hanging.
 */
class FileToolCancelTest {

    private fun tmpDir(): String {
        val dir = "/tmp/acp-agent-cancel-test-${Random.nextBytes(6).toHex()}"
        SystemFileSystem.createDirectories(Path(dir))
        return dir
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun context(cwd: String, store: FileStore) = ToolContext(
        cwd = cwd,
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
        fileStore = store,
    )

    private fun runCancelled(store: SuspendingStore, block: suspend () -> Unit) = runBlocking {
        var outcome: ToolResult? = null
        val job = launch(Dispatchers.IO) {
            block()
            outcome = ToolResult("(completed normally - the store call must not return)")
        }
        if (withTimeoutOrNull(5_000) { store.entered.await() } == null) {
            error("the tool completed without entering a suspending store call; nothing was cancelled")
        }
        job.cancelAndJoin()
        assertNull(outcome, "cancellation must propagate; the tool must not return a failed result, got: $outcome")
    }

    @Test
    fun `cancelling read_file during the store call propagates instead of a failed result`() {
        val dir = tmpDir()
        val store = SuspendingStore(CompletableDeferred())
        runCancelled(store) {
            ReadFileTool().execute(buildJsonObject { put("path", "$dir/r.txt"); put("limit", 10) }, context(dir, store))
        }
    }

    @Test
    fun `cancelling write_file during the diff pre-read propagates instead of a failed result`() {
        val dir = tmpDir()
        val store = SuspendingStore(CompletableDeferred()).apply { suspendReadRaw = true }
        runCancelled(store) {
            WriteFileTool().execute(buildJsonObject { put("path", "$dir/w.txt"); put("content", "x") }, context(dir, store))
        }
    }

    @Test
    fun `cancelling write_file during the store write propagates instead of a failed result`() {
        val dir = tmpDir()
        val store = SuspendingStore(CompletableDeferred()).apply {
            suspendReadFile = false
            suspendWriteFile = true
        }
        runCancelled(store) {
            WriteFileTool().execute(buildJsonObject { put("path", "$dir/w.txt"); put("content", "x") }, context(dir, store))
        }
    }

    @Test
    fun `cancelling edit_file during the raw read propagates instead of a failed result`() {
        val dir = tmpDir()
        val store = SuspendingStore(CompletableDeferred()).apply { suspendReadRaw = true }
        runCancelled(store) {
            EditFileTool().execute(
                buildJsonObject { put("path", "$dir/e.txt"); put("old_string", "hello"); put("new_string", "bye") },
                context(dir, store),
            )
        }
    }

    @Test
    fun `cancelling edit_file during the store write propagates instead of a failed result`() {
        val dir = tmpDir()
        val store = SuspendingStore(CompletableDeferred()).apply {
            suspendReadFile = false
            suspendWriteFile = true
            rawContent = "hello"
        }
        runCancelled(store) {
            EditFileTool().execute(
                buildJsonObject { put("path", "$dir/e.txt"); put("old_string", "hello"); put("new_string", "bye") },
                context(dir, store),
            )
        }
    }
}