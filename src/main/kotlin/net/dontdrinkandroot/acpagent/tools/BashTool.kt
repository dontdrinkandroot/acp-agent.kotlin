package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.*
import java.util.concurrent.TimeUnit

internal class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

internal object ProcessRunner {

    /**
     * Grace period after which a killed process is destroyed forcibly, so
     * stream readers cannot hang on a process that ignores SIGTERM.
     */
    private const val KILL_GRACE_MILLIS = 5000L

    /**
     * Grace period during which the stdout/stderr readers are allowed to drain
     * after the process has ended (or been killed). A backgrounded child that
     * inherited the pipe file descriptors can keep them open beyond the shell's
     * death; the readers are cancelled after this period so the tool cannot
     * hang on a dead process.
     */
    private const val DRAIN_GRACE_MILLIS = 2000L

    /**
     * Maximum number of characters kept from a process stream. Keeps the
     * context bounded no matter how much the process writes; the tail is kept
     * because errors and final state tend to arrive last.
     */
    private const val MAX_STREAM_CHARS = 30_000

    private const val TRUNCATION_MARKER = "...(truncated: %d chars omitted from the beginning)...\n"

    /**
     * Progressively captured, bounded tail of one process stream. Readers
     * append decoded chunks as they arrive; [snapshot] returns everything
     * captured so far, so output survives even when the reader never reaches
     * EOF (a surviving child holding the pipe descriptors). UTF-8, chunk-boundary
     * safe; when characters were dropped a marker line is prepended. The memory
     * footprint stays bounded regardless of how much the process writes.
     */
    private class StreamTail {
        private val lock = Any()
        private val tail = StringBuilder(MAX_STREAM_CHARS)
        private var dropped = 0

        fun append(chunk: String) {
            if (chunk.isEmpty()) return
            synchronized(lock) {
                tail.append(chunk)
                if (tail.length > MAX_STREAM_CHARS) {
                    dropped += tail.length - MAX_STREAM_CHARS
                    tail.delete(0, tail.length - MAX_STREAM_CHARS)
                }
            }
        }

        fun snapshot(): String = synchronized(lock) {
            val result = tail.toString()
            if (dropped > 0) String.format(Locale.ROOT, TRUNCATION_MARKER, dropped) + result else result
        }

        /**
         * Records a reader failure (e.g. an I/O error on the pipe) as part of
         * the captured output, so it stays visible instead of failing the whole
         * tool call.
         */
        fun recordError(error: Exception) {
            synchronized(lock) { tail.append("\n(output stream error: ${error.message})\n") }
        }
    }

    /**
     * Reads a process stream into the shared [tail] until EOF. Blocking on a
     * pipe whose far end is held open by a surviving child never returns; the
     * caller bounds this by detaching the reader and using [StreamTail.snapshot].
     */
    private fun readStreamTail(stream: InputStream, tail: StreamTail) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n == -1) break
            tail.append(decoder.decode(ByteBuffer.wrap(buffer, 0, n)).toString())
        }
        tail.append(decoder.decode(ByteBuffer.allocate(0)).toString())
    }

    public suspend fun run(
        command: String,
        cwd: String? = null,
        timeoutSeconds: Long = 600,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .apply {
                cwd?.let { directory(java.io.File(it)) }
            }
            .start()
        // The reader coroutines live in their own scope so a stuck read cannot
        // hold up the enclosing withContext: runInterruptible makes the
        // blocking reads responsive to cancellation, but a read blocked on a
        // pipe whose far end is held open by a surviving child cannot be
        // unblocked portably via Thread.interrupt — see the bounded drain.

        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stdoutTail = StreamTail()
        val stderrTail = StreamTail()
        val stdout = readerScope.async {
            runInterruptible {
                try {
                    readStreamTail(process.inputStream, stdoutTail)
                } catch (e: IOException) {
                    stdoutTail.recordError(e)
                }
            }
        }
        val stderr = readerScope.async {
            runInterruptible {
                try {
                    readStreamTail(process.errorStream, stderrTail)
                } catch (e: IOException) {
                    stderrTail.recordError(e)
                }
            }
        }
        val finished = try {
            runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
        } catch (e: CancellationException) {
            killProcessTree(process)
            readerScope.cancel()
            throw e
        }
        if (!finished) {
            killProcessTree(process)
        }

        // Bounded drain: normally the readers finish as soon as the process
        // (and its subtree) exited and the OS closed the pipes. When a child
        // inherited the pipe descriptors and outlived its parent (e.g. a
        // backgrounded `(sleep 100) &`), the pipes stay open and the reads
        // block forever. First the whole tree is force-killed so the surviving
        // child's pipe ends close; a short second drain usually completes. A
        // reader that still does not finish is detached and the output captured
        // so far is snapshotted - never discarded.
        if (!drainReaders(readerScope, stdout, stderr, DRAIN_GRACE_MILLIS)) {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            drainReaders(readerScope, stdout, stderr, DRAIN_GRACE_MILLIS)
        }
        val result = ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdoutTail.snapshot(),
            stderr = stderrTail.snapshot(),
            timedOut = !finished,
        )
        readerScope.cancel()
        result
    }

    /**
     * Kills the whole process tree so backgrounded children cannot outlive
     * the shell and keep the streams open.
     */
    private fun killProcessTree(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    /**
     * Awaits both stream readers with a deadline. Returns whether both
     * finished; on timeout the readers stay running (they are detached by the
     * caller) and their captured output remains available via the tails.
     */
    private suspend fun drainReaders(
        readerScope: CoroutineScope,
        stdout: Deferred<*>,
        stderr: Deferred<*>,
        graceMillis: Long,
    ): Boolean = withTimeoutOrNull(graceMillis) {
        stdout.join()
        stderr.join()
        true
    } ?: false
}

public class BashTool : AgentTool {
    override val name = "bash"
    override val description = "Run a shell command in the project working directory. For build/test/git/diagnostics."
    override val kind = ToolKind.EXECUTE
    override val mutating = true
    override val modes = listOf(SessionModeId("bash"))
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            putJsonObject("command") {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive("Shell command to run (via /bin/sh) in the project working directory.")
                )
            }
        })
        putJsonArray("required") { add(JsonPrimitive("command")) }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val command = arguments.stringArg("command") ?: return ToolResult(arguments.argError("command"), true)
        return runCatching {
            val result = ProcessRunner.run(command, context.cwd, context.bashTimeoutSeconds.toLong())
            val output = buildString {
                if (result.timedOut) {
                    append("(command timed out after ${context.bashTimeoutSeconds}s and was terminated)\n")
                }
                if (result.stdout.isNotBlank()) append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("STDERR:\n").append(result.stderr)
                }
            }
            ToolResult(
                text = if (output.isBlank()) "(no output, exit ${result.exitCode})" else output,
                isError = result.exitCode != 0 || result.timedOut,
            )
        }.getOrElse { ToolResult("Command failed: ${it.message}", true) }
    }
}
