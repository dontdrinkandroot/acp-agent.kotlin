package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

private val runConfigJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal const val ARGS_PLACEHOLDER = "{args}"

internal const val RUN_CONFIG_DIR = ".ai"
internal const val RUN_CONFIG_FILE = "run.json"

/**
 * A single run configuration from `.ai/run.json` in the session working
 * directory.
 */
internal data class RunConfig(
    val name: String,
    val command: String,
    val description: String?,
)

/**
 * Raised by the run-config mutation helpers when an operation cannot be
 * performed; the message is the user-facing tool result text.
 */
internal class RunConfigException(message: String) : Exception(message)

internal fun runConfigTarget(cwd: String): String = "$cwd/$RUN_CONFIG_DIR/$RUN_CONFIG_FILE"

/**
 * Loads the run configurations from `.ai/run.json` directly from the session
 * working directory (never via the client fs proxy). Returns an empty list
 * when the file does not exist or does not parse; read errors are logged and
 * never fail the turn. Concurrent writers (several sessions on one cwd) are
 * last-writer-wins; writes are atomic per file.
 */
internal fun loadRunConfigs(cwd: String): List<RunConfig> {
    val root = try {
        loadRunConfigRoot(cwd)
    } catch (e: RunConfigException) {
        logger.warn(e) { "Failed to load run configurations: ${runConfigTarget(cwd)}" }
        return emptyList()
    }
    return parseRunConfigsFromRoot(root)
}

/**
 * Reads the raw `.ai/run.json` root object. Returns null when the file does
 * not exist; throws [RunConfigException] when it does exist but does not
 * parse.
 */
internal fun loadRunConfigRoot(cwd: String): JsonObject? {
    val target = runConfigTarget(cwd)
    val fs = SystemFileSystem
    if (!fs.exists(Path(target))) return null
    return try {
        val text = fs.source(Path(target)).buffered().use { it.readString() }
        parseRunConfigRoot(text)
    } catch (e: Exception) {
        throw RunConfigException("Could not parse $target; refusing to modify an unparseable file")
    }
}

internal fun parseRunConfigRoot(text: String): JsonObject = runConfigJson.parseToJsonElement(text).jsonObject

internal fun parseRunConfigsFromRoot(root: JsonObject?): List<RunConfig> =
    root?.mapNotNull { (name, value) ->
        if (value !is JsonObject) return@mapNotNull null
        val command = value.stringArg("command")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val description = value.stringArg("description")?.takeIf { it.isNotBlank() }
        RunConfig(name, command, description)
    } ?: emptyList()

internal fun createRunConfig(cwd: String, name: String, command: String?, description: String?): RunConfig {
    val trimmedName = name.trim()
    if (trimmedName.isBlank()) throw RunConfigException("Run configuration name must not be blank")
    val trimmedCommand = command?.trim().orEmpty()
    if (trimmedCommand.isBlank()) throw RunConfigException("Run configuration \"$trimmedName\" requires a command")
    val root = loadRunConfigRoot(cwd)
    if (root?.containsKey(trimmedName) == true) {
        throw RunConfigException("Run configuration \"$trimmedName\" already exists")
    }
    val newRoot = buildJsonObject {
        root?.forEach { (key, value) -> put(key, value) }
        put(trimmedName, runConfigEntryJson(trimmedCommand, description))
    }
    writeRunConfigRoot(cwd, newRoot)
    val normalizedDescription = description?.takeIf { it.isNotBlank() }
    return RunConfig(trimmedName, trimmedCommand, normalizedDescription)
}

internal fun updateRunConfig(
    cwd: String,
    name: String,
    command: String?,
    description: String?,
): RunConfig {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw IllegalArgumentException("Run configuration name must not be blank")
    if (command == null && description == null) {
        throw RunConfigException("Nothing to update for run configuration \"$trimmedName\"")
    }
    val root = loadRunConfigRoot(cwd)
        ?: throw RunConfigException("Could not update \"$trimmedName\": no run configurations are defined")
    val existing = root[trimmedName] as? JsonObject
        ?: throw RunConfigException("Unknown run configuration \"$trimmedName\"")
    val existingCommand = existing.stringArg("command").orEmpty()
    if (command != null && command.isBlank()) {
        throw RunConfigException("Run configuration \"$trimmedName\" cannot have a blank command")
    }
    if (command == null && existingCommand.isBlank()) {
        throw RunConfigException("Run configuration \"$trimmedName\" has no command to keep")
    }
    val effectiveCommand = command?.trim() ?: existingCommand
    val entry = buildJsonObject {
        existing.forEach { (key, value) ->
            if (key != "command" && key != "description") put(key, value)
        }
        put("command", JsonPrimitive(effectiveCommand))
        when {
            description == null -> existing["description"]?.let { put("description", it) }
            description.isBlank() -> Unit
            else -> put("description", JsonPrimitive(description.trim()))
        }
    }
    val newRoot = buildJsonObject {
        root.forEach { (key, value) -> if (key == trimmedName) put(key, entry) else put(key, value) }
    }
    writeRunConfigRoot(cwd, newRoot)
    return RunConfig(
        name = trimmedName,
        command = effectiveCommand,
        description = entry.stringArg("description")?.takeIf { it.isNotBlank() },
    )
}

internal fun deleteRunConfig(cwd: String, name: String): RunConfig {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw IllegalArgumentException("Run configuration name must not be blank")
    val root = loadRunConfigRoot(cwd)
        ?: throw RunConfigException("Could not delete \"$trimmedName\": no run configs are defined")
    val removed = root[trimmedName] as? JsonObject
        ?: throw RunConfigException("Unknown run configuration \"$trimmedName\"")
    val command = removed.stringArg("command").orEmpty()
    val description = removed.stringArg("description")?.takeIf { it.isNotBlank() }
    val newRoot = buildJsonObject {
        root.forEach { (key, value) -> if (key != trimmedName) put(key, value) }
    }
    writeRunConfigRoot(cwd, newRoot)
    return RunConfig(trimmedName, command, description)
}

private fun runConfigEntryJson(command: String, description: String?): JsonObject = buildJsonObject {
    put("command", JsonPrimitive(command))
    if (!description.isNullOrBlank()) put("description", JsonPrimitive(description.trim()))
}

private fun writeRunConfigRoot(cwd: String, root: JsonObject) {
    val target = java.nio.file.Path.of(runConfigTarget(cwd))
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, "run.json", ".tmp")
    try {
        Files.writeString(temp, runConfigJson.encodeToString(JsonObject.serializer(), root))
        moveAtomically(temp, target)
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun moveAtomically(source: java.nio.file.Path, target: java.nio.file.Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Runs a run configuration defined in `.ai/run.json` in the session working
 * directory. The configuration command is executed via the shell; arguments
 * passed by the model are substituted for the `{args}` placeholder.
 */
public class RunTool internal constructor(private val cwd: String) : AgentTool {
    override val name = "run"
    override val description = "Run a named run configuration in the project working directory. " +
            "Pass optional arguments via 'args'; they are substituted for the $ARGS_PLACEHOLDER " +
            "placeholder in the configuration command."

    override val kind = ToolKind.EXECUTE
    override val mutating = false
    override val modes = emptyList<SessionModeId>()
    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override val parameters: JsonObject = jsonSchema(
        required("config", PropType.STRING, "Name of the run configuration to execute"),
        optional(
            "args",
            PropType.STRING,
            "Optional arguments substituted for the $ARGS_PLACEHOLDER placeholder in the command"
        ),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val configName = arguments.stringArg("config") ?: return ToolResult(arguments.argError("config"), true)
        if (arguments.isNullArg("args")) return ToolResult(arguments.argError("args"), true)
        val args = arguments.stringArg("args")?.takeIf { it.isNotBlank() }
        val configs = loadRunConfigs(cwd)
        val config = configs.firstOrNull { it.name == configName }
            ?: return ToolResult(
                "Unknown run configuration \"$configName\". Available configurations: " +
                        configs.joinToString(", ") { it.name },
                true,
            )
        if (args != null && !config.command.contains(ARGS_PLACEHOLDER)) {
            return ToolResult("Run configuration \"$configName\" does not accept arguments", true)
        }
        // Every occurrence is substituted so multi-placeholder commands do not
        // leak a literal "{args}" into the shell.
        val resolvedCommand = config.command.replace(ARGS_PLACEHOLDER, args.orEmpty())
        return runCatching {
            val result = ProcessRunner.run(resolvedCommand, context.cwd, context.bashTimeoutSeconds.toLong())
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
        }.getOrElse { ToolResult("Run configuration failed: ${it.message}", true) }
    }
}