package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
        val stdout = readerScope.async { runInterruptible { process.inputStream.readBytes().decodeToString() } }
        val stderr = readerScope.async { runInterruptible { process.errorStream.readBytes().decodeToString() } }
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
        // block forever; after the grace period we detach the readers rather
        // than hang the tool, relying on the best-effort force-kill to close
        // the surviving child's pipe ends eventually.

        val drained = withTimeoutOrNull(DRAIN_GRACE_MILLIS) {
            stdout.await() to stderr.await()
        }
        if (drained == null) {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            readerScope.cancel()
        }
        val (stdoutText, stderrText) = drained ?: ("" to "")
        ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdoutText,
            stderr = stderrText,
            timedOut = !finished,
        )
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
        val command = arguments["command"]?.jsonPrimitive?.content ?: return ToolResult("Missing 'command'", true)
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
