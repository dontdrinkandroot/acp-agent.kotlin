package net.dontdrinkandroot.acpagent.sandbox

import net.dontdrinkandroot.acpagent.config.APP_STATE_DIR
import net.dontdrinkandroot.acpagent.config.Config

/**
 * The fixed image tag. Deliberately a Kotlin constant rather than a `sandbox.image` config
 * key: the wrapper must know the tag without parsing JSON. The tag also appears in
 * `build-docker` and `bin/acp-agent-sandbox`.
 */
const val SANDBOX_IMAGE = "ghcr.io/dontdrinkandroot/acp-agent:latest"

/** The container's home directory (the image's user), the anchor of every in-container path. */
const val CONTAINER_HOME = "/home/dev"
const val CONTAINER_STATE_DIR = "$CONTAINER_HOME/.local/state/$APP_STATE_DIR"
const val CONTAINER_CONFIG_DIR = "$CONTAINER_HOME/.config/${Config.APP_CONFIG_DIR}"
const val CONTAINER_GRADLE_HOME = "$CONTAINER_HOME/.gradle"

/** The host git identity forwarded into the container, so commits made there belong to the host user. */
data class GitIdentity(val name: String, val email: String)

/**
 * Host-side facts the sandbox composition reads. Every value is a plain input so the
 * composition can be pinned with fixture facts (`uid=1001`, fixture paths) instead of the
 * ambient host - the supervisor fills this from the running system.
 */
data class HostFacts(
    /**
     * The host user's *real* ids: the container runs as `--user uid:gid` and its files belong
     * to them (`UnixSystem`). A normally launched JVM coincides with the effective ones the
     * old shell launcher used (`id -u`/`id -g`).
     */
    val uid: Int,
    val gid: Int,
    /** The session cwd and the launch cwd - identical by assumption (see the docs). */
    val cwd: String,
    val home: String,
    val xdgCacheHome: String = "$home/.cache",
    val xdgDataHome: String = "$home/.local/share",
    val xdgStateHome: String = "$home/.local/state",
    /**
     * The agent's config directory. Filled from the agent's own `Config.configDir` resolver,
     * so the directory the supervisor mounts and the one the containerized agent reads can
     * never diverge.
     */
    val configDir: String = "$home/.config/${Config.APP_CONFIG_DIR}",
    /** `TERM` forwarded verbatim (may be empty, as the shell launcher did). */
    val term: String = "",
    /** `TZ` forwarded verbatim when set - the container wall clock must follow the host zone. */
    val timeZone: String? = null,
    val gitIdentity: GitIdentity? = null,
    /**
     * Tool-owned environment (`UV_CACHE_DIR`, `GRADLE_USER_HOME`, `ANDROID_HOME`, ...) - the
     * tools' own configuration and host facts, never agent config. `DOCKER_BIN` is not among
     * them: docker comes from `PATH`.
     */
    val toolEnv: Map<String, String> = emptyMap(),
)

/**
 * The `sandbox` group of `config.json` (parsed as [net.dontdrinkandroot.acpagent.config.ConfigFile.Sandbox],
 * validated for shape only: types, absolute-path form, list values) with the defaults applied.
 * Every check that needs a host fact (directory creation, existence probes) belongs to the
 * sandbox plan resolution - the containerized agent parses the same file and must never fault
 * on a host path.
 */
data class SandboxSettings(
    val network: String = "host",
    /** Host session-state dir mounted at `/home/dev/.local/state/ddr-acp-agent`; null = `$XDG_STATE_HOME/ddr-acp-agent`. */
    val stateDir: String? = null,
    val mountCaches: Boolean = true,
    /** Named volume for the container home; null = the uid-mapped home tmpfs. */
    val homeVolume: String? = null,
    val extraArgs: List<String> = emptyList(),
    val capAdd: List<String> = emptyList(),
    val rwRootfs: Boolean = false,
)

/**
 * Everything the sandbox composition reads: the config values the supervisor parses (the
 * `sandbox` group plus the permission tiers it must mount) and the host facts. `agentArgs`
 * are the arguments forwarded verbatim into the container (e.g. the shared `--profile <name>`
 * flag). The agent's `scratchDir` is deliberately absent: it is a container path that the
 * agent reads from the mounted `config.json` itself, so the supervisor never mounts it.
 */
data class SandboxSpec(
    val hostFacts: HostFacts,
    val settings: SandboxSettings = SandboxSettings(),
    val readableDirectories: List<String> = emptyList(),
    val writableDirectories: List<String> = emptyList(),
    val agentArgs: List<String> = emptyList(),
    /**
     * The empty file mounted over `<cwd>/.env.local` when the project has one. A per-run
     * artifact (the supervisor creates the temp file and deletes it on exit), deliberately
     * *not* a host fact: resolution only reads the path, so the plan stays free of file
     * creation.
     */
    val envLocalMaskFile: String? = null,
)

/** A mount in the `docker run` vocabulary - typed parts, rendered once by the composer. */
sealed interface SandboxMount {
    fun render(): String
}

/** `type=bind,src=...,dst=...[,ro]` - the only mount kind the permission tiers use. */
data class BindMount(val source: String, val destination: String, val readOnly: Boolean = false) : SandboxMount {
    override fun render(): String =
        "type=bind,src=$source,dst=$destination" + if (readOnly) ",ro" else ""
}

/** `<path>:<options>`, used via `--tmpfs`. */
data class TmpfsMount(val path: String, val options: String) : SandboxMount {
    override fun render(): String = "$path:$options"
}

/** `type=volume,src=<name>,dst=<path>` - the optional named home volume. */
data class VolumeMount(val name: String, val destination: String) : SandboxMount {
    override fun render(): String = "type=volume,src=$name,dst=$destination"
}

/** One `--env K=V` pair. */
data class EnvEntry(val key: String, val value: String)

/** A non-fatal finding: something composed out of the plan that the user should know about. */
data class SandboxWarning(val message: String)

/**
 * The resolved `docker run` input: typed, side-effect-free data in exactly the order the
 * composition emits it. [composeDockerInvocation] is the single renderer, so no `<flag>
 * <value>` pair is ever assembled twice.
 */
data class SandboxPlan(
    val uid: Int,
    val gid: Int,
    val network: String,
    /** `127.0.0.1` on the host network, `host-gateway` on an explicit one. */
    val hostDockerInternalTarget: String,
    val permissionMounts: List<BindMount>,
    val cwd: String,
    val projectMount: BindMount,
    val envLocalMask: BindMount?,
    val homeMount: SandboxMount,
    val gradleTmpfs: List<TmpfsMount>,
    val gradleVersionTmpfs: List<TmpfsMount>,
    val cacheTmpfs: TmpfsMount,
    val localTmpfs: List<TmpfsMount>,
    val cacheMounts: List<BindMount>,
    val stateMount: BindMount?,
    val configTmpfs: TmpfsMount?,
    val configMount: BindMount?,
    val readOnlyRootfs: Boolean,
    val capAdd: List<String>,
    val gitIdentityEnv: List<EnvEntry>,
    val forwardedEnv: List<EnvEntry>,
    val cacheEnv: List<EnvEntry>,
    val baseEnv: List<EnvEntry>,
    val extraArgs: List<String>,
    val image: String,
    val agentArgs: List<String>,
    val warnings: List<SandboxWarning> = emptyList(),
)

/** A sandbox input the host cannot honor: fail before any `docker run` instead of degrading. */
class SandboxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
