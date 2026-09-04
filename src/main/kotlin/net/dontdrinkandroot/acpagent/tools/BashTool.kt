package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
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
        coroutineScope {
            // runInterruptible makes the blocking reads and the wait
            // responsive to coroutine cancellation (session/cancel).
            val stdout = async { runInterruptible { process.inputStream.readBytes().decodeToString() } }
            val stderr = async { runInterruptible { process.errorStream.readBytes().decodeToString() } }
            val finished = try {
                runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
            } catch (e: CancellationException) {
                killProcessTree(process)
                throw e
            }
            if (!finished) {
                killProcessTree(process)
            }
            ProcessResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.await(),
                stderr = stderr.await(),
                timedOut = !finished,
            )
        }
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
