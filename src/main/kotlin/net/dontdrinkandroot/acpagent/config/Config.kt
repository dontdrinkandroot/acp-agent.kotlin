package net.dontdrinkandroot.acpagent.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The agent's resolved configuration (issue #50): built-in defaults overridden
 * by the selected `config.json`. The configuration is **file-only** - no
 * environment variable selects a value or the file (issue #84); `--profile
 * <name>` is the only startup input. The value is immutable and resolved once at
 * startup ([load]); there is no live reload.
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
    /**
     * The file the API key was resolved from - the *path*, never the key
     * (issue #78). `/info` names it so "which key file is this agent using?"
     * is answerable without inspecting the process environment.
     *
     * Declared last so the existing positional construction order stays put;
     * [load] always sets it, the default serves direct construction in tests.
     */
    val openRouterApiKeyFile: String? = null,
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

        /**
         * Resolves the configuration: built-in defaults overridden by the selected
         * `config.json`.
         *
         * The config file is `$XDG_CONFIG_HOME/ddr-acp-agent/config.json`
         * (fallback `~/.config`). [profile] selects `<profile>.json` *inside that
         * directory* (see [profileFileName]), so the name resolves the same way on
         * the host and inside the docker container. An absent default file means
         * "all defaults"; a selected profile must exist.
         *
         * Only `XDG_CONFIG_HOME` is read from [env] - the agent's settings are
         * file-only, and a `DDR_ACP_AGENT_*` variable is deliberately inert
         * (issue #84).
         *
         * [warnings] receives non-fatal findings (a configured directory that
         * does not exist); they never fail the startup.
         */
        public fun load(
            env: Map<String, String> = platformEnv(),
            home: Path = Path.of(System.getProperty("user.home")),
            profile: String? = null,
            warnings: (String) -> Unit = {},
        ): Config {
            val configDir = configDir(env, home)
            val file = readConfigFile(profile?.let { profileFileName(it) }, configDir)
            val apiKey = resolveApiKey(file.openRouter?.apiKeyFile, configDir)

            return Config(
                openRouterApiKey = apiKey.secret,
                openRouterModel = file.openRouter?.defaultModel ?: DEFAULT_MODEL,
                openRouterBaseUrl = file.openRouter?.baseUrl ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = file.openRouter?.autoThroughputSorting ?: true,
                fsProxyEnabled = file.tools?.fsProxy ?: true,
                mcpTrustAnnotations = file.mcp?.trustAnnotations ?: true,
                bashTimeoutSeconds = file.tools?.bash?.timeoutSeconds ?: DEFAULT_BASH_TIMEOUT_SECONDS,
                maxTurnRequests = file.tools?.maxTurnRequests ?: DEFAULT_MAX_TURN_REQUESTS,
                webFetchAllowPrivate = file.tools?.webFetch?.allowPrivate ?: false,
                readableDirectories = file.permissions?.readableDirectories.orEmpty(),
                writableDirectories = file.permissions?.writableDirectories.orEmpty(),
                unlistedPaths = file.permissions?.unlistedPaths ?: UnlistedPaths.PROMPT,
                scratchDir = file.scratchDir ?: DEFAULT_SCRATCH_DIR,
                openRouterApiKeyFile = apiKey.path.toString(),
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
         * Reads and parses the config file. [configuredName] is a *file name inside
         * the config directory* (already resolved from a profile), so the same name
         * resolves to the same file wherever that directory lives (a host path would
         * be meaningless inside the docker container). A selected file must exist;
         * the default one may be absent. Parse and validation errors name the file
         * and the JSON path.
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
                "a config file name must be a bare name inside the config directory, not a path, got '$name'"
            }
            require(name != "." && name != "..") {
                "a config file name must be a bare name inside the config directory, got '$name'"
            }
        }

        /**
         * Resolves the OpenRouter API key. A key is only ever read from a file -
         * never from an environment variable holding the key itself, which every
         * child process and `ps` output would inherit. Order:
         *
         * 1. `openRouter.apiKeyFile` from the config file,
         * 2. `<config dir>/openrouter.key`, when it exists.
         *
         * Every file is validated (regular file, owner-only `0600`/`0400`,
         * non-empty); a failure names the path. When nothing resolves, the error
         * lists the tried sources.
         */
        internal fun resolveApiKey(fileKeyFile: String?, configDir: Path): ResolvedApiKey {
            val defaultKeyFile = configDir.resolve(DEFAULT_API_KEY_FILE_NAME)
            val candidates: List<Pair<String, Path>> = buildList {
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
                    "No OpenRouter API key configured. Set openRouter.apiKeyFile in " +
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
            return ResolvedApiKey(secret = Secret(content), path = path)
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

    }
}

/**
 * The resolved API key together with the file it came from. The key is typed as
 * a [Secret] from the moment it is read, so the carrier stays loggable by
 * construction (issue #78); the path is recorded in
 * [Config.openRouterApiKeyFile] so `/info` can name the source without
 * re-running the resolution order.
 */
internal data class ResolvedApiKey(val secret: Secret, val path: Path)
