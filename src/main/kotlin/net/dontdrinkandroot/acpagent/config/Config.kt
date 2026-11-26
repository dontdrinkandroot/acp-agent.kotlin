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
     * outside the session cwd (`permissions.readableDirectories`, unioned with
     * the project overlay's entries - see [mergePermissionTiers]).
     */
    val readableDirectories: List<String> = emptyList(),
    /**
     * Absolute paths the mutating path tools (`write_file`, `edit_file`,
     * `move_*`, `delete_*`) may access without a permission prompt
     * (`permissions.writableDirectories`, unioned with the project overlay's
     * entries). Writable implies readable.
     */
    val writableDirectories: List<String> = emptyList(),
    /**
     * Treatment of paths in neither list nor the cwd (`permissions.unlistedPaths`):
     * the project overlay replaces the base value outright, in either direction.
     */
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
    /**
     * The project-local overlay that was applied - the *path*, or null when the
     * launch directory has none (issue #85). `/info` names it so a project grant
     * can be told apart from a global one without annotating the tiers the model
     * sees.
     */
    val projectConfigFile: String? = null,
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
         * Resolves the configuration: built-in defaults, overridden by the selected
         * `config.json` and then by the project overlay
         * ([PROJECT_CONFIG_RELATIVE_PATH] in [cwd]).
         *
         * The config file is `$XDG_CONFIG_HOME/ddr-acp-agent/config.json`
         * (fallback `~/.config`). [profile] selects `<profile>.json` *inside that
         * directory* (see [profileFileName]), so the name resolves the same way on
         * the host and inside the docker container. An absent default file means
         * "all defaults"; a selected profile must exist.
         *
         * [cwd] is the launch directory, which is also the session cwd by
         * assumption (the sandbox binds the project at the identical path and pins
         * it as `--workdir`). It is read exactly once, here: a session opened on a
         * different directory runs under the launch directory's overlay. Deliberately
         * required - a default bound to the process directory would make the overlay
         * read implicit at every call site, tests included.
         *
         * Only `XDG_CONFIG_HOME` is read from [env] - the agent's settings are
         * file-only, and a `DDR_ACP_AGENT_*` variable is deliberately inert
         * (issue #84).
         *
         * [warnings] receives non-fatal findings (a configured directory that
         * does not exist); they never fail the startup. A broken *project* file is
         * fatal instead: it belongs to the checkout, and silently running with
         * fewer permissions is exactly the failure it must not have.
         */
        public fun load(
            env: Map<String, String> = platformEnv(),
            home: Path = Path.of(System.getProperty("user.home")),
            profile: String? = null,
            cwd: Path,
            warnings: (String) -> Unit = {},
        ): Config {
            // The sources are read before the key: a broken project file must be reported
            // even when no key is configured, or the user would fix the key first and hit
            // the same wall again.
            val sources = readConfigSources(env, home, profile?.let { profileFileName(it) }, cwd)
            val tiers = mergePermissionTiers(sources.base.permissions, sources.project?.permissions)
            val apiKey = resolveApiKey(sources.base.openRouter?.apiKeyFile, sources.configDir)

            return Config(
                openRouterApiKey = apiKey.secret,
                openRouterModel = sources.base.openRouter?.defaultModel ?: DEFAULT_MODEL,
                openRouterBaseUrl = sources.base.openRouter?.baseUrl ?: DEFAULT_BASE_URL,
                autoThroughputSortingEnabled = sources.base.openRouter?.autoThroughputSorting ?: true,
                fsProxyEnabled = sources.base.tools?.fsProxy ?: true,
                mcpTrustAnnotations = sources.base.mcp?.trustAnnotations ?: true,
                bashTimeoutSeconds = sources.base.tools?.bash?.timeoutSeconds ?: DEFAULT_BASH_TIMEOUT_SECONDS,
                maxTurnRequests = sources.base.tools?.maxTurnRequests ?: DEFAULT_MAX_TURN_REQUESTS,
                webFetchAllowPrivate = sources.base.tools?.webFetch?.allowPrivate ?: false,
                readableDirectories = tiers.readableDirectories,
                writableDirectories = tiers.writableDirectories,
                unlistedPaths = tiers.unlistedPaths,
                scratchDir = sources.base.scratchDir ?: DEFAULT_SCRATCH_DIR,
                openRouterApiKeyFile = apiKey.path.toString(),
                projectConfigFile = sources.project?.file?.toString(),
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

/**
 * The configuration sources of one process, read once (issue #85): the selected base
 * file plus the project overlay. This is the **single** place that decides which files
 * are read and in which order - both entry points project from it, the agent
 * ([Config.load]) and the sandbox supervisor (`loadSandboxSpec`), so the trust domain
 * the agent applies and the mount list the supervisor composes cannot diverge.
 *
 * Deliberately key-free: the supervisor must be able to read the same sources without
 * an API key, which is why this step does not resolve one.
 */
internal data class ConfigSources(
    val configDir: Path,
    val base: ConfigFile,
    /** The project overlay that was found, or null when the launch directory has none. */
    val project: ProjectConfigSource?,
)

/**
 * Reads the base config file (which may be absent) and then the project overlay of
 * [cwd]. The order is load-bearing: a broken base file is reported before a broken
 * overlay, and a profile selected with `--profile` must exist on both sides anyway.
 */
internal fun readConfigSources(
    env: Map<String, String>,
    home: Path,
    configFileName: String?,
    cwd: Path,
): ConfigSources {
    val directory = Config.configDir(env, home)
    return ConfigSources(
        configDir = directory,
        base = Config.readConfigFile(configFileName, directory),
        project = readProjectConfig(cwd),
    )
}

/** The resolved `permissions` tiers after the layering: defaults < base file < project overlay. */
internal data class PermissionTiers(
    val readableDirectories: List<String> = emptyList(),
    val writableDirectories: List<String> = emptyList(),
    val unlistedPaths: UnlistedPaths = UnlistedPaths.PROMPT,
)

/**
 * Layers the project overlay over the base `permissions` group:
 *
 * - the directory lists are **unioned** (base order first), so a project can add entries
 *   but never remove a base entry - a checkout must not be able to narrow the user's own
 *   global configuration;
 * - [PermissionTiers.unlistedPaths] is replaced outright in either direction, because it
 *   is a single mode and not an accumulation.
 *
 * Pure, so the layering rule is unit-pinned once and both readers share it.
 */
internal fun mergePermissionTiers(
    base: ConfigFile.Permissions?,
    project: ConfigFile.Permissions?,
): PermissionTiers = PermissionTiers(
    readableDirectories = unionDirectories(base?.readableDirectories, project?.readableDirectories),
    writableDirectories = unionDirectories(base?.writableDirectories, project?.writableDirectories),
    unlistedPaths = project?.unlistedPaths ?: base?.unlistedPaths ?: UnlistedPaths.PROMPT,
)

/** Base entries first, project additions appended; `distinct()` keeps the first occurrence. */
private fun unionDirectories(base: List<String>?, project: List<String>?): List<String> =
    (base.orEmpty() + project.orEmpty()).distinct()
