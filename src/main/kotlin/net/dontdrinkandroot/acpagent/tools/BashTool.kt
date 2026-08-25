package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal object ProcessRunner {

    public suspend fun run(command: String, cwd: String? = null): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .apply {
                cwd?.let { directory(java.io.File(it)) }
            }
            .start()
        coroutineScope {
            val stdout = async { process.inputStream.readBytes().decodeToString() }
            val stderr = async { process.errorStream.readBytes().decodeToString() }
            val exitCode = process.waitFor()
            ProcessResult(exitCode, stdout.await(), stderr.await())
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
            val result = ProcessRunner.run(command, context.cwd)
            val output = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("STDERR:\n").append(result.stderr)
                }
            }
            ToolResult(
                text = if (output.isBlank()) "(no output, exit ${result.exitCode})" else output,
                isError = result.exitCode != 0,
            )
        }.getOrElse { ToolResult("Command failed: ${it.message}", true) }
    }
}
