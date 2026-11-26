package net.dontdrinkandroot.acpagent.sandbox

import com.sun.security.auth.module.UnixSystem
import net.dontdrinkandroot.acpagent.config.AgentArguments
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.config.ConfigFile
import net.dontdrinkandroot.acpagent.config.stateHome
import java.nio.file.Path

/**
 * The environment variables that are a *tool's* own configuration (or a host fact), not agent
 * config (`DDR_ACP_AGENT_*` is dead, issue #84). Caches and the Android SDK are shared from
 * these; nothing else is read.
 */
private val TOOL_ENV_NAMES: List<String> = listOf(
    "UV_CACHE_DIR", "PIP_CACHE_DIR", "COMPOSER_CACHE_DIR", "YARN_CACHE_FOLDER", "HF_HOME",
    "NPM_CONFIG_CACHE", "CARGO_HOME", "GOMODCACHE", "NUGET_PACKAGES", "PUB_CACHE",
    "PNPM_CONFIG_STORE_DIR", "PNPM_CONFIG_CACHE_DIR", "PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE",
    "KONAN_DATA_DIR", "GRADLE_USER_HOME", "UV_LINK_MODE", "ANDROID_HOME", "ANDROID_SDK_ROOT",
)

/** The git identity for commits made inside the container, from the host's git config. */
private fun gitIdentity(): GitIdentity? {
    fun configValue(key: String): String? = runCatching {
        val process = ProcessBuilder("git", "config", key).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) value.takeIf { it.isNotEmpty() } else null
    }.getOrNull()
    val name = configValue("user.name") ?: return null
    val email = configValue("user.email") ?: return null
    return GitIdentity(name, email)
}

/**
 * Collects the host facts the sandbox composition reads. `uid`/`gid` are the *real* ids
 * (`UnixSystem`); a normally launched JVM coincides with the effective ones (`id -u`/`id -g`)
 * the shell launcher used.
 *
 * `$HOME` is the JVM's `user.home` (on Linux that *is* `$HOME`, see the docs); the XDG roots
 * and the config directory come from the shared resolvers, so they agree with what the agent
 * resolves.
 */
fun resolveHostFacts(
    env: Map<String, String>,
    home: Path,
    cwd: Path,
): HostFacts {
    val unix = UnixSystem()
    val xdgCacheHome = env["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() } ?: home.resolve(".cache").toString()
    val xdgDataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: home.resolve(".local/share").toString()
    return HostFacts(
        uid = unix.uid.toInt(),
        gid = unix.gid.toInt(),
        cwd = cwd.toAbsolutePath().toString(),
        home = home.toString(),
        xdgCacheHome = xdgCacheHome,
        xdgDataHome = xdgDataHome,
        xdgStateHome = stateHome(env, home).toString(),
        // The agent's own resolver, not a mirrored precedence rule: the mount and the read
        // agree by construction.
        configDir = Config.configDir(env, home).toString(),
        term = env["TERM"].orEmpty(),
        timeZone = env["TZ"]?.takeIf { it.isNotBlank() },
        gitIdentity = gitIdentity(),
        toolEnv = TOOL_ENV_NAMES.mapNotNull { name -> env[name]?.let { name to it } }.toMap(),
    )
}

/**
 * The parsed `sandbox` group with the model's defaults applied. [SandboxSettings] owns the
 * defaults, so a changed default needs no second edit here.
 */
private fun sandboxSettings(sandbox: ConfigFile.Sandbox?): SandboxSettings {
    val defaults = SandboxSettings()
    if (sandbox == null) return defaults
    return SandboxSettings(
        network = sandbox.network ?: defaults.network,
        stateDir = sandbox.stateDir,
        mountCaches = sandbox.mountCaches ?: defaults.mountCaches,
        homeVolume = sandbox.homeVolume,
        extraArgs = sandbox.extraArgs.orEmpty(),
        capAdd = sandbox.capAdd.orEmpty(),
        rwRootfs = sandbox.rwRootfs ?: defaults.rwRootfs,
    )
}

/**
 * Reads the supervisor's view of the config: the **same file** the agent reads, but only what
 * the mount composition needs. Deliberately not `Config.load` - the supervisor must not
 * require the API key. Fail loudly: an unreadable or invalid file prevents the sandbox from
 * starting instead of silently falling back to defaults, and a selected profile must exist on
 * both sides.
 */
fun loadSandboxSpec(
    env: Map<String, String>,
    home: Path,
    cwd: Path,
    arguments: AgentArguments,
    envLocalMaskFile: String?,
    agentArgs: List<String>,
): SandboxSpec {
    val configDir = Config.configDir(env, home)
    val file = try {
        Config.readConfigFile(arguments.configFileName, configDir)
    } catch (e: IllegalArgumentException) {
        throw SandboxException(e.message ?: "config file cannot be read", e)
    }
    return SandboxSpec(
        hostFacts = resolveHostFacts(env, home, cwd),
        settings = sandboxSettings(file.sandbox),
        readableDirectories = file.permissions?.readableDirectories.orEmpty(),
        writableDirectories = file.permissions?.writableDirectories.orEmpty(),
        agentArgs = agentArgs,
        envLocalMaskFile = envLocalMaskFile,
    )
}
