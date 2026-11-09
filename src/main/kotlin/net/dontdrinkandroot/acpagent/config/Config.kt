package net.dontdrinkandroot.acpagent.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The agent's resolved configuration (issue #50): built-in defaults, overridden
 * by `config.json`, overridden by the `DDR_ACP_AGENT_*` environment variables.
 * The value is immutable and resolved once at startup ([load]); there is no
 * live reload.
 *
 * The instance is safe to log: [openRouterApiKey] is a [Secret], so the
 * generated `toString()` (and every other implicit rendering) prints `***`
 * instead of the key (issue #78).
 */
public data class Config(
    val openRouterApiKey: Secret,
    val openRouterModel: String,
    val openRouterBaseUrl: String,
    val autoThroughputSortingEnabled: Boolean = true,
    val fsProxyEnabled: Boolean = true,
    val mcpTrustAnnotations: Boolean = true,
    val bashTimeoutSeconds: Int = 600,
    val maxTurnRequests: Int = 100,
    val webFetchAllowPrivate: Boolean = false,
    /**
     * Absolute paths the read-only path tools (`read_file`, `list_dir`, `glob`,
     * `grep`) may access without a permission prompt even though they lie
     * outside the session cwd (`permissions.readableDirectories`).
     */
    val readableDirectories: List<String> = emptyList(),
    /**
     * Absolute paths the mutating path tools (`write_file`, `edit_file`,
     * `move_*`, `delete_*`) may access without a permission prompt
     * (`permissions.writableDirectories`). Writable implies readable.
     */
    val writableDirectories: List<String> = emptyList(),
    /** Treatment of paths in neither list nor the cwd (`permissions.unlistedPaths`). */
    val unlistedPaths: UnlistedPaths = UnlistedPaths.PROMPT,
    /**
     * The agent's own scratch directory (default `/tmp/ddr-acp-agent`):
     * read-trusted like [readableDirectories], so the non-mutating path tools
     * run there without a permission prompt in every mode. Deliberately *not*
     * part of [readableDirectories] - that list is the user-configured one; the
     * two are merged only where the trust roots are consumed (see the
     * `PermissionPolicy` assembly in `AgentSessionImpl`).
     *
     * This is the *configured* value. Session assembly prepares it (creates +
     * verifies it) and passes the resulting real path separately, so this field
     * never becomes a trust root unverified.
     */
    val scratchDir: String = DEFAULT_SCRATCH_DIR,
) {
    public companion object {
        private const val DEFAULT_MODEL = "openrouter/auto"
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_BASH_TIMEOUT_SECONDS = 600
        private const val DEFAULT_MAX_TURN_REQUESTS = 100

        /** Default scratch directory: a literal, so the documented path is stable. */
        internal const val DEFAULT_SCRATCH_DIR = "/tmp/ddr-acp-agent"

        /** Directory name below `$XDG_CONFIG_HOME` holding `config.json` and the default key file. */
        public const val APP_CONFIG_DIR: String = "ddr-acp-agent"

        public const val CONFIG_FILE_NAME: String = "config.json"

        public const val DEFAULT_API_KEY_FILE_NAME: String = "openrouter.key"

        /** Env variable that moves `config.json` itself (it cannot live inside the file). */
        public const val CONFIG_FILE_ENV: String = "DDR_ACP_AGENT_CONFIG_FILE"

        private const val ENV_PREFIX = "DDR_ACP_AGENT_"

        /**
         * Resolves the configuration: built-in defaults, overridden by
         * `config.json` (when present), overridden by the environment.
         *
         * The config file is `$XDG_CONFIG_HOME/ddr-acp-agent/config.json`
         * (fallback `~/.config`). [CONFIG_FILE_ENV] selects a different *file
         * name inside that directory* (a "profile"), so the name resolves the
         * same way on the host and inside the docker container. A missing file
         * with no explicit selection means "all defaults"; a missing explicitly
         * selected file is a configuration error.
         *
         * [warnings] receives non-fatal findings (a configured directory that
         * does not exist); they never fail the startup.
         */
        public fun load(
            env: Map<String, String> = platformEnv(),
            home: Path = Path.of(System.getProperty("user.home")),
            warnings: (String) -> Unit = {},
        ): Config {
            val configDir = configDir(env, home)
            val file = readConfigFile(env[CONFIG_FILE_ENV], configDir)
            val envValues = EnvValues(env)

            return Config(
                openRouterApiKey = Secret(resolveApiKey(envValues, file.openRouter?.apiKeyFile, configDir)),
                openRouterModel = envValues.string("openRouter.defaultModel")
                    ?: file.openRouter?.defaultModel ?: DEFAULT_MODEL,
                openRouterBaseUrl = envValues.string("openRouter.baseUrl")
                    ?: file.openRouter?.baseUrl ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = envValues.boolean("openRouter.autoThroughputSorting")
                    ?: file.openRouter?.autoThroughputSorting ?: true,
                fsProxyEnabled = envValues.boolean("tools.fsProxy") ?: file.tools?.fsProxy ?: true,
                mcpTrustAnnotations = envValues.boolean("mcp.trustAnnotations")
                    ?: file.mcp?.trustAnnotations ?: true,
                bashTimeoutSeconds = envValues.int("tools.bash.timeoutSeconds", ConfigFile.BASH_TIMEOUT_RANGE)
                    ?: file.tools?.bash?.timeoutSeconds ?: DEFAULT_BASH_TIMEOUT_SECONDS,
                maxTurnRequests = envValues.int("tools.maxTurnRequests", ConfigFile.MAX_TURN_REQUESTS_RANGE)
                    ?: file.tools?.maxTurnRequests ?: DEFAULT_MAX_TURN_REQUESTS,
                webFetchAllowPrivate = envValues.boolean("tools.webFetch.allowPrivate")
                    ?: file.tools?.webFetch?.allowPrivate ?: false,
                readableDirectories = envValues.pathList("permissions.readableDirectories")
                    ?: file.permissions?.readableDirectories.orEmpty(),
                writableDirectories = envValues.pathList("permissions.writableDirectories")
                    ?: file.permissions?.writableDirectories.orEmpty(),
                unlistedPaths = envValues.string("permissions.unlistedPaths")
                    ?.let { parseUnlistedPaths(EnvValues.name("permissions.unlistedPaths"), it) }
                    ?: file.permissions?.unlistedPaths
                    ?: UnlistedPaths.PROMPT,
                scratchDir = envValues.string("scratchDir")?.let { requireScratchDir(it, EnvValues.name("scratchDir")) }
                    ?: file.scratchDir
                    ?: DEFAULT_SCRATCH_DIR,
            ).also { config -> config.directoryWarnings().forEach(warnings) }
        }

        /** Warnings for configured directories that do not exist yet (not fatal). */
        internal fun Config.directoryWarnings(): List<String> =
            (readableDirectories + writableDirectories)
                .distinct()
                .filterNot { Files.isDirectory(Path.of(it)) }
                .map { "configured directory '$it' does not exist" }

        /**
         * The config directory: `$XDG_CONFIG_HOME/ddr-acp-agent`, or
         * `~/.config/ddr-acp-agent` when `XDG_CONFIG_HOME` is unset or blank.
         */
        internal fun configDir(env: Map<String, String>, home: Path): Path {
            val xdgConfigHome = env["XDG_CONFIG_HOME"]
            val root = if (xdgConfigHome.isNullOrBlank()) home.resolve(".config") else Path.of(xdgConfigHome)
            return root.resolve(APP_CONFIG_DIR)
        }

        /**
         * Reads and parses the config file. [configuredName] (from
         * [CONFIG_FILE_ENV]) selects a *file name inside the config directory*,
         * so the same name resolves to the same file wherever that directory
         * lives (a host path would be meaningless inside the docker container).
         * An explicitly selected file must exist; the default one may be absent.
         * Parse and validation errors name the file and the JSON path.
         */
        internal fun readConfigFile(configuredName: String?, configDir: Path): ConfigFile {
            val name = configuredName?.trim()?.takeUnless { it.isEmpty() }
            val path = if (name != null) {
                requireConfigFileName(name)
                configDir.resolve(name)
            } else {
                configDir.resolve(CONFIG_FILE_NAME)
            }
            if (name == null && !Files.exists(path)) return ConfigFile()
            require(Files.isRegularFile(path)) { "config file '$path' is not a regular file" }
            val text = runCatching { File(path.toString()).readText() }
                .getOrElse { failure ->
                    throw IllegalArgumentException("config file '$path' cannot be read: ${failure.message}", failure)
                }
            return try {
                ConfigFile.parse(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("config file '$path': ${e.message}", e)
            }
        }

        /**
         * A config file name must be a bare file name: no directory part, no
         * traversal. The file always lives in the config directory, so the name
         * means the same thing on the host and inside the docker container.
         */
        private fun requireConfigFileName(name: String) {
            require(!name.contains('/')) {
                "$CONFIG_FILE_ENV must be a file name inside the config directory, " +
                        "not a path, got '$name'"
            }
            require(name != "." && name != "..") {
                "$CONFIG_FILE_ENV must be a file name inside the config directory, got '$name'"
            }
        }

        /**
         * Resolves the OpenRouter API key. A key is only ever read from a file -
         * never from an environment variable holding the key itself, which every
         * child process and `ps` output would inherit. Order:
         *
         * 1. `DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE`,
         * 2. `openRouter.apiKeyFile` from the config file,
         * 3. `<config dir>/openrouter.key`, when it exists.
         *
         * Every file is validated (regular file, owner-only `0600`/`0400`,
         * non-empty); a failure names the path. When nothing resolves, the error
         * lists the tried sources.
         */
        internal fun resolveApiKey(envValues: EnvValues, fileKeyFile: String?, configDir: Path): String {
            val keyFileEnv = EnvValues.name("openRouter.apiKeyFile")
            val defaultKeyFile = configDir.resolve(DEFAULT_API_KEY_FILE_NAME)
            val candidates: List<Pair<String, Path>> = buildList {
                envValues.string("openRouter.apiKeyFile")?.let {
                    val path = Path.of(it)
                    require(path.isAbsolute) { "$keyFileEnv must be an absolute path, got '$it'" }
                    add("$keyFileEnv '$it'" to path)
                }
                fileKeyFile?.let {
                    val path = Path.of(it)
                    require(path.isAbsolute) {
                        "config file openRouter.apiKeyFile must be an absolute path, got '$it'"
                    }
                    add("config file openRouter.apiKeyFile '$it'" to path)
                }
                if (Files.exists(defaultKeyFile)) add("default location '$defaultKeyFile'" to defaultKeyFile)
            }
            if (candidates.isEmpty()) {
                throw IllegalArgumentException(
                    "No OpenRouter API key configured. Set $keyFileEnv, openRouter.apiKeyFile in " +
                            "$CONFIG_FILE_NAME, or create '$defaultKeyFile'."
                )
            }
            val (source, path) = candidates.first()
            require(Files.isRegularFile(path)) { "OpenRouter API key file from $source is not a regular file" }
            requireOwnerOnly(path, source)
            val content = runCatching { Files.readString(path) }
                .getOrElse { failure ->
                    throw IllegalArgumentException("API key file '$path' cannot be read: ${failure.message}", failure)
                }
                .trim()
            require(content.isNotEmpty()) { "OpenRouter API key file from $source is empty" }
            return content
        }

        /**
         * A key file must not be accessible by group or others: a leaked mode is
         * indistinguishable from a leaked key. Fails loudly with the path and the
         * offending mode instead of quietly using the file.
         */
        private fun requireOwnerOnly(path: Path, source: String) {
            val permissions = runCatching { Files.getPosixFilePermissions(path) }
                .getOrElse { failure ->
                    throw IllegalArgumentException(
                        "cannot verify the permissions of API key file '$path' ($source): ${failure.message}",
                        failure,
                    )
                }
            require(permissions.none { it in GROUP_OR_OTHER_PERMISSIONS }) {
                "API key file '$path' ($source) must be owner-only (0600 or 0400), " +
                        "but is group/other accessible: $permissions"
            }
        }

        private val GROUP_OR_OTHER_PERMISSIONS = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )

        /** `tools.bash.timeoutSeconds` -> `DDR_ACP_AGENT_TOOLS_BASH_TIMEOUT_SECONDS`. */
        internal fun envName(configPath: String): String =
            ENV_PREFIX + configPath.replace('.', '_')
                .split('_')
                .joinToString("_") { camelToUpperSnake(it) }

        /** `timeoutSeconds` -> `TIMEOUT_SECONDS`, `webFetch` -> `WEB_FETCH`, `fs` -> `FS`. */
        internal fun camelToUpperSnake(name: String): String = buildString {
            name.forEachIndexed { index, c ->
                if (c.isUpperCase() && index > 0 && (name[index - 1].isLowerCase() || name[index - 1].isDigit())) {
                    append('_')
                }
                append(c.uppercaseChar())
            }
        }
    }
}

/**
 * The environment layer: typed, strict accessors over the `DDR_ACP_AGENT_*`
 * variables derived from the config paths. An unset (or blank) variable means
 * "no override"; a set but invalid value fails loudly.
 */
internal class EnvValues(private val env: Map<String, String>) {

    /** The trimmed value of the variable for [configPath], or null when unset/blank. */
    fun string(configPath: String): String? =
        env[name(configPath)]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Strict booleans: `1/true/yes/on` and `0/false/no/off` (case-insensitive).
     * Anything else is an error - a typo must not silently disable a safety
     * feature.
     */
    fun boolean(configPath: String): Boolean? {
        val name = name(configPath)
        val raw = env[name] ?: return null
        return when (val value = raw.trim().lowercase()) {
            in TRUE_VALUES -> true
            in FALSE_VALUES -> false
            else -> throw IllegalArgumentException("$name must be one of 1/true/yes/on or 0/false/no/off, got '$value'")
        }
    }

    /** A strict integer within [range]: no clamping, an out-of-range value is an error. */
    fun int(configPath: String, range: IntRange): Int? {
        val name = name(configPath)
        val raw = env[name] ?: return null
        val value = raw.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer, got '$raw'")
        if (value !in range) {
            throw IllegalArgumentException("$name must be ${rangeText(range)}, got $value")
        }
        return value
    }

    /** A comma-separated list of absolute paths: trimmed, blanks dropped, deduped. */
    fun pathList(configPath: String): List<String>? {
        val value = string(configPath) ?: return null
        val name = name(configPath)
        return value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { requireAbsolutePath(name, it) }
            .distinct()
    }

    companion object {
        fun name(configPath: String): String = Config.envName(configPath)
        private val TRUE_VALUES = setOf("1", "true", "yes", "on")
        private val FALSE_VALUES = setOf("0", "false", "no", "off")
    }
}
