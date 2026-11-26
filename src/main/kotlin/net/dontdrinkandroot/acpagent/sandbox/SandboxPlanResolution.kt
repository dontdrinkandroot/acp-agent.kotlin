package net.dontdrinkandroot.acpagent.sandbox

import net.dontdrinkandroot.acpagent.config.APP_STATE_DIR
import java.nio.file.Files
import java.nio.file.Path

/**
 * The host queries the plan resolution needs. Production uses the real filesystem
 * ([RealHostProbes]); tests answer from a fixture tree, so the pinned `docker run`
 * composition never depends on the machine running the tests.
 */
interface HostProbes {
    fun isDirectory(path: String): Boolean

    fun exists(path: String): Boolean

    fun isRegularFile(path: String): Boolean

    /** `mkdir -p`; false when the directory cannot be created. */
    fun createDirectories(path: String): Boolean

    /** Child directory names of [parent], lexically sorted (the shell's glob order). */
    fun childDirectoryNames(parent: String): List<String>
}

class RealHostProbes : HostProbes {
    override fun isDirectory(path: String): Boolean = Files.isDirectory(Path.of(path))

    override fun exists(path: String): Boolean = Files.exists(Path.of(path))

    override fun isRegularFile(path: String): Boolean = Files.isRegularFile(Path.of(path))

    override fun createDirectories(path: String): Boolean =
        runCatching { Files.createDirectories(Path.of(path)) }.isSuccess

    override fun childDirectoryNames(parent: String): List<String> =
        runCatching {
            Files.list(Path.of(parent)).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
}

/**
 * Resolves the sandbox plan from the spec: the one place where host facts are *checked*
 * (directories created, existence probed, covered paths skipped). Everything that cannot be
 * honored fails with a [SandboxException] **before any `docker run`**, so a misconfiguration
 * can never silently degrade into a container with a different trust domain.
 */
fun resolveSandboxPlan(spec: SandboxSpec, probes: HostProbes = RealHostProbes()): SandboxPlan {
    val facts = spec.hostFacts
    val settings = spec.settings
    val cwd = trimTrailingSlashes(facts.cwd)
    // A root launch directory would compose an empty project mount and workdir (and turn every
    // permission path into "inside the project"), so fail loudly instead of handing docker an
    // empty bind source.
    if (cwd.isEmpty()) {
        throw SandboxException("the launch directory must not be the filesystem root, got '${facts.cwd}'")
    }
    val warnings = mutableListOf<SandboxWarning>()
    val mountedDestinations = mutableSetOf<String>()

    fun isInsideProject(path: String) = path == cwd || path.startsWith("$cwd/")

    val permissionMounts = resolvePermissionMounts(spec, cwd, probes, warnings)
    val envLocalMask = if (spec.envLocalMaskFile != null && probes.isRegularFile("$cwd/.env.local")) {
        BindMount(spec.envLocalMaskFile, "$cwd/.env.local", readOnly = true)
    } else {
        null
    }
    val homeMount: SandboxMount = settings.homeVolume?.let { VolumeMount(it, CONTAINER_HOME) }
        ?: TmpfsMount(CONTAINER_HOME, uidOptions(facts, size = "1g", exec = true))

    val caches = if (settings.mountCaches) {
        resolveSharedCaches(spec, probes, warnings, mountedDestinations, ::isInsideProject)
    } else {
        SharedCaches()
    }
    val stateMount = resolveStateMount(spec, probes, warnings, mountedDestinations, ::isInsideProject)
    val (configTmpfs, configMount) =
        resolveConfigMount(facts, probes, warnings, mountedDestinations, ::isInsideProject)

    return SandboxPlan(
        uid = facts.uid,
        gid = facts.gid,
        network = settings.network,
        hostDockerInternalTarget = if (settings.network == "host") "127.0.0.1" else "host-gateway",
        permissionMounts = permissionMounts,
        cwd = cwd,
        projectMount = BindMount(cwd, cwd),
        envLocalMask = envLocalMask,
        homeMount = homeMount,
        gradleTmpfs = listOf(
            TmpfsMount(CONTAINER_GRADLE_HOME, uidOptions(facts, size = "1g", exec = true)),
            TmpfsMount("$CONTAINER_GRADLE_HOME/caches", uidOptions(facts, size = "1g", exec = true)),
            TmpfsMount("$CONTAINER_GRADLE_HOME/wrapper", uidOptions(facts, size = "1g", exec = true)),
        ),
        gradleVersionTmpfs = caches.gradleVersionTmpfs,
        cacheTmpfs = TmpfsMount("$CONTAINER_HOME/.cache", uidOptions(facts, size = "1g")),
        localTmpfs = listOf(
            TmpfsMount("$CONTAINER_HOME/.local", uidOptions(facts, size = "1g")),
            TmpfsMount("$CONTAINER_HOME/.local/share", uidOptions(facts, size = "1g")),
            TmpfsMount("$CONTAINER_HOME/.local/state", uidOptions(facts, size = "1g")),
        ),
        cacheMounts = caches.mounts,
        stateMount = stateMount,
        configTmpfs = configTmpfs,
        configMount = configMount,
        readOnlyRootfs = !settings.rwRootfs,
        capAdd = settings.capAdd,
        gitIdentityEnv = facts.gitIdentity?.let {
            listOf(
                EnvEntry("GIT_AUTHOR_NAME", it.name),
                EnvEntry("GIT_AUTHOR_EMAIL", it.email),
                EnvEntry("GIT_COMMITTER_NAME", it.name),
                EnvEntry("GIT_COMMITTER_EMAIL", it.email),
            )
        }.orEmpty(),
        forwardedEnv = buildList {
            facts.timeZone?.let { add(EnvEntry("TZ", it)) }
            // The host Gradle home must never leak in: the container resolves its daemon and
            // process state to the private tmpfs, independent of the image's default and of
            // mountCaches (which only gates the shared leaf binds).
            add(EnvEntry("GRADLE_USER_HOME", CONTAINER_GRADLE_HOME))
        },
        cacheEnv = caches.env,
        baseEnv = listOf(
            EnvEntry("HOME", CONTAINER_HOME),
            EnvEntry("TERM", facts.term),
            EnvEntry("XDG_CONFIG_HOME", "$CONTAINER_HOME/.config"),
            EnvEntry("XDG_DATA_HOME", "$CONTAINER_HOME/.local/share"),
            EnvEntry("XDG_STATE_HOME", "$CONTAINER_HOME/.local/state"),
            EnvEntry("XDG_CACHE_HOME", "$CONTAINER_HOME/.cache"),
        ),
        extraArgs = settings.extraArgs,
        image = SANDBOX_IMAGE,
        agentArgs = spec.agentArgs,
        warnings = warnings,
    )
}

/**
 * The permission lists **are** the mount list: readable mounts `:ro`, writable rw, both at
 * the identical in-container path. A path listed in both tiers is mounted rw once; mounts are
 * sorted shallowest-first so a deeper mount lands on top of a shallower one, like the built-in
 * mounts that follow.
 */
private fun resolvePermissionMounts(
    spec: SandboxSpec,
    cwd: String,
    probes: HostProbes,
    warnings: MutableList<SandboxWarning>,
): List<BindMount> {
    val entries = mutableListOf<Pair<String, Boolean>>() // path to readOnly

    fun collect(list: List<String>, label: String, readOnly: Boolean) {
        list.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            // Normalized here too: the config parser already collapses trailing slashes, but a
            // hand-built spec (tests, a future config source) must not produce a duplicate
            // mount. The root stays "/" so the explicit refusal below still names it.
            val path = trimmed.trimEnd('/').ifEmpty { "/" }
            if (!path.startsWith("/")) {
                throw SandboxException("$label: '$path' is not an absolute host path")
            }
            if (path == "/") throw SandboxException("$label: / cannot be mounted")
            if (path == cwd || path.startsWith("$cwd/")) {
                warnings += SandboxWarning(
                    "skipping permission path '$path' - inside the project directory, which is always mounted rw",
                )
                return@forEach
            }
            if (path == CONTAINER_HOME || path.startsWith("$CONTAINER_HOME/")) {
                warnings += SandboxWarning("skipping permission path '$path' - covered by the container home")
                return@forEach
            }
            if (path == "/tmp" || path.startsWith("/tmp/")) {
                warnings += SandboxWarning("skipping permission path '$path' - covered by the container tmpfs")
                return@forEach
            }
            if (!probes.exists(path)) throw SandboxException("$label: '$path' does not exist on the host")
            if (readOnly) {
                if (entries.none { it.first == path }) entries += path to true
            } else {
                entries.removeAll { it.first == path }
                entries += path to false
            }
        }
    }

    collect(spec.readableDirectories, "permissions.readableDirectories", readOnly = true)
    collect(spec.writableDirectories, "permissions.writableDirectories", readOnly = false)

    return entries
        .sortedBy { (path, _) -> path.count { it == '/' } } // stable, like `sort -k1,1n -s`
        .map { (path, readOnly) -> BindMount(path, path, readOnly) }
}

private class SharedCaches(
    val mounts: List<BindMount> = emptyList(),
    val env: List<EnvEntry> = emptyList(),
    val gradleVersionTmpfs: List<TmpfsMount> = emptyList(),
)

/**
 * Every host tool cache and the Gradle leaves ([mountCaches] enabled). Two cases per tool:
 * an explicitly configured dir (the tool's env var is set) is identity-bound and its value
 * forwarded verbatim; without config the first existing default candidate is mounted at the
 * tool's in-container default and the env var pinned to that destination.
 */
private fun resolveSharedCaches(
    spec: SandboxSpec,
    probes: HostProbes,
    warnings: MutableList<SandboxWarning>,
    mountedDestinations: MutableSet<String>,
    isInsideProject: (String) -> Boolean,
): SharedCaches {
    val facts = spec.hostFacts
    val mounts = mutableListOf<BindMount>()
    val env = mutableListOf<EnvEntry>()
    val containerDataDir = "$CONTAINER_HOME/.local/share"

    fun addToolCache(destination: String, envName: String?, candidates: List<String>) {
        val configured = envName?.let { facts.toolEnv[it]?.takeIf(String::isNotEmpty) }
        val hostDir = when {
            configured != null -> {
                if (!configured.startsWith("/")) {
                    throw SandboxException("$envName: $configured is not an absolute host path")
                }
                val normalized = trimTrailingSlashes(configured)
                if (normalized.isEmpty()) throw SandboxException("$envName: / cannot be used as a cache directory")
                if (!probes.isDirectory(normalized) && !probes.createDirectories(normalized)) {
                    throw SandboxException("$envName: $configured could not be created on the host")
                }
                normalized
            }

            else -> candidates.firstOrNull { probes.isDirectory(it) }
        }
        if (hostDir == null || hostDir == "/") return

        // An explicit dir is identity-bound; a default candidate is relocated to the tool's
        // in-container default (the container home differs from the host home).
        val mountDestination = if (configured != null) hostDir else destination
        val doMount = !isInsideProject(hostDir) && mountDestination !in mountedDestinations
        if (doMount) {
            mounts += BindMount(hostDir, mountDestination)
            mountedDestinations += mountDestination
        }
        if (envName == null) return
        when {
            // The configured value travels verbatim even when the mount is skipped: an
            // in-project dir is covered by the project mount at the identical path, a deduped
            // destination is already mounted - the tool must still honor its own config.
            configured != null -> env += EnvEntry(envName, configured)
            doMount -> env += EnvEntry(envName, mountDestination)
        }
    }

    val cacheHome = facts.xdgCacheHome
    val dataHome = facts.xdgDataHome
    addToolCache("$CONTAINER_HOME/.cache/uv", "UV_CACHE_DIR", listOf("$cacheHome/uv"))
    addToolCache("$CONTAINER_HOME/.cache/pip", "PIP_CACHE_DIR", listOf("$cacheHome/pip"))
    addToolCache("$CONTAINER_HOME/.cache/composer", "COMPOSER_CACHE_DIR", listOf("$cacheHome/composer"))
    addToolCache("$CONTAINER_HOME/.cache/yarn", "YARN_CACHE_FOLDER", listOf("$cacheHome/yarn"))
    addToolCache("$CONTAINER_HOME/.cache/huggingface", "HF_HOME", listOf("$cacheHome/huggingface"))
    addToolCache("$CONTAINER_HOME/.npm", "NPM_CONFIG_CACHE", listOf("${facts.home}/.npm"))
    addToolCache("$CONTAINER_HOME/.m2", null, listOf("${facts.home}/.m2"))
    addToolCache("$CONTAINER_HOME/.cargo", "CARGO_HOME", listOf("${facts.home}/.cargo"))
    addToolCache("$CONTAINER_HOME/go/pkg/mod", "GOMODCACHE", listOf("${facts.home}/go/pkg/mod"))
    addToolCache("$CONTAINER_HOME/.nuget/packages", "NUGET_PACKAGES", listOf("${facts.home}/.nuget/packages"))
    addToolCache("$CONTAINER_HOME/.pub-cache", "PUB_CACHE", listOf("${facts.home}/.pub-cache"))
    // pnpm reads its config from pnpm_config_*/PNPM_CONFIG_* env vars (pnpm.io/configuring),
    // so its store/cache go through the same explicit/fallback policy.
    addToolCache("$containerDataDir/pnpm/store", "PNPM_CONFIG_STORE_DIR", listOf("$dataHome/pnpm/store"))
    addToolCache("$CONTAINER_HOME/.cache/pnpm", "PNPM_CONFIG_CACHE_DIR", listOf("$cacheHome/pnpm"))
    addToolCache("$CONTAINER_HOME/.konan", "KONAN_DATA_DIR", listOf("${facts.home}/.konan"))

    val gradle = resolveGradleCaches(spec, probes, mounts, mountedDestinations, isInsideProject)
    resolveAndroidSdk(spec, probes, warnings, mounts, env, mountedDestinations, isInsideProject)

    facts.toolEnv["UV_LINK_MODE"]?.takeIf { it.isNotEmpty() }?.let { env += EnvEntry("UV_LINK_MODE", it) }
    facts.toolEnv["PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE"]?.takeIf { it.isNotEmpty() }
        ?.let { env += EnvEntry("PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE", it) }

    return SharedCaches(mounts = mounts, env = env, gradleVersionTmpfs = gradle)
}

/**
 * The Gradle user home is never shared as a whole (one daemon registry + logs across the host
 * and container PID namespaces would let probing clients prune each other's daemons). Only
 * what Gradle documents as a cache is shared rw; everything else stays on the uid-mapped
 * `.gradle` tmpfs. Returns the per-version tmpfs entries.
 */
private fun resolveGradleCaches(
    spec: SandboxSpec,
    probes: HostProbes,
    mounts: MutableList<BindMount>,
    mountedDestinations: MutableSet<String>,
    isInsideProject: (String) -> Boolean,
): List<TmpfsMount> {
    val facts = spec.hostFacts
    val explicit = facts.toolEnv["GRADLE_USER_HOME"]?.takeIf { it.startsWith("/") }
    val gradleHome = explicit
        ?: "${facts.home}/.gradle".takeIf { probes.isDirectory(it) }
        ?: return emptyList()
    if (gradleHome == "/") return emptyList()
    if (isInsideProject(gradleHome)) return emptyList()

    // Best-effort creation: the leaf binds need a source, and the current-format cache names
    // (jars-9, build-cache-1) only exist after a first build.
    listOf(
        "$gradleHome/caches/modules-2",
        "$gradleHome/caches/jars-9",
        "$gradleHome/caches/build-cache-1",
        "$gradleHome/wrapper/dists",
        "$gradleHome/jdks",
    ).forEach { probes.createDirectories(it) }

    fun shareLeaf(source: String, relative: String) {
        if (!probes.isDirectory(source)) return
        val destination = "$CONTAINER_GRADLE_HOME/$relative"
        if (destination in mountedDestinations) return
        mounts += BindMount(source, destination)
        mountedDestinations += destination
    }

    shareLeaf("$gradleHome/caches/modules-2", "caches/modules-2")
    val cachesDir = "$gradleHome/caches"
    val children = probes.childDirectoryNames(cachesDir)
    // One glob group per cache family, like the shell's three loops (jars-*, build-cache-*,
    // transforms-*), so the argv keeps the old ordering.
    listOf("jars-", "build-cache-", "transforms-").forEach { prefix ->
        children.filter { it.startsWith(prefix) }.forEach { name -> shareLeaf("$cachesDir/$name", "caches/$name") }
    }
    shareLeaf("$gradleHome/wrapper/dists", "wrapper/dists")
    shareLeaf("$gradleHome/jdks", "jdks")

    // Version-bound caches: the version dir gets its own uid tmpfs (its private siblings -
    // fileHashes, kotlin-dsl, the configuration-cache keystore - must stay container-private),
    // and the two shared children are bound on top. Versions come from the digit-named cache
    // dirs plus the shared wrapper dists, so a wrapper upgrade is shared from the first
    // session instead of re-deriving generated-gradle-jars + transforms into the tmpfs.
    val versions = mutableListOf<String>()
    children.forEach { name ->
        if (name.firstOrNull()?.isDigit() == true) versions += name
    }
    probes.childDirectoryNames("$gradleHome/wrapper/dists").forEach { name ->
        if (!name.startsWith("gradle-")) return@forEach
        val version = name.removePrefix("gradle-").removeSuffix("-bin").removeSuffix("-all")
        if (version.isNotEmpty() && version.first().isDigit() && version !in versions) versions += version
    }
    return versions.map { version ->
        val versionDir = "$cachesDir/$version"
        listOf("transforms", "generated-gradle-jars").forEach { leaf ->
            probes.createDirectories("$versionDir/$leaf")
            shareLeaf("$versionDir/$leaf", "caches/$version/$leaf")
        }
        TmpfsMount(
            "$CONTAINER_GRADLE_HOME/caches/$version",
            uidOptions(facts, size = "1g", exec = true),
        )
    }
}

/**
 * The host Android SDK: an explicit `ANDROID_HOME`/`ANDROID_SDK_ROOT` (first one set wins)
 * is identity-bound and both variables carry the configured value; the default candidate
 * (`$HOME/Android/Sdk`, never created) keeps the relocation to the in-container default.
 */
private fun resolveAndroidSdk(
    spec: SandboxSpec,
    probes: HostProbes,
    warnings: MutableList<SandboxWarning>,
    mounts: MutableList<BindMount>,
    env: MutableList<EnvEntry>,
    mountedDestinations: MutableSet<String>,
    isInsideProject: (String) -> Boolean,
) {
    val facts = spec.hostFacts
    val explicitName = when {
        facts.toolEnv["ANDROID_HOME"]?.isNotEmpty() == true -> "ANDROID_HOME"
        facts.toolEnv["ANDROID_SDK_ROOT"]?.isNotEmpty() == true -> "ANDROID_SDK_ROOT"
        else -> null
    }
    if (explicitName != null) {
        val configured = facts.toolEnv.getValue(explicitName)
        if (!configured.startsWith("/")) {
            throw SandboxException("$explicitName: $configured is not an absolute host path")
        }
        if (configured == "/") throw SandboxException("$explicitName: / cannot be the Android SDK directory")
        if (!probes.isDirectory(configured)) {
            throw SandboxException("$explicitName: $configured is not a directory on the host")
        }
        val sdkDir = trimTrailingSlashes(configured)
        if (!isInsideProject(sdkDir) && sdkDir !in mountedDestinations) {
            mounts += BindMount(sdkDir, sdkDir)
            mountedDestinations += sdkDir
        }
        // The configured path is pinned even when the mount is skipped: an in-project SDK is
        // covered by the project mount, and pointing at the skipped container default would
        // reference a nonexistent directory.
        env += EnvEntry("ANDROID_HOME", configured)
        env += EnvEntry("ANDROID_SDK_ROOT", configured)
        return
    }

    val defaultCandidate = "${facts.home}/Android/Sdk"
    if (!probes.isDirectory(defaultCandidate)) return
    val destination = "$CONTAINER_HOME/Android/Sdk"
    if (isInsideProject(defaultCandidate)) {
        warnings += SandboxWarning(
            "skipping the default Android SDK at $defaultCandidate - inside the project directory, " +
                    "which is always mounted rw",
        )
        return
    }
    if (destination in mountedDestinations) {
        warnings += SandboxWarning("skipping the default Android SDK at $defaultCandidate - $destination is already mounted")
        return
    }
    mounts += BindMount(defaultCandidate, destination)
    mountedDestinations += destination
    env += EnvEntry("ANDROID_HOME", destination)
    env += EnvEntry("ANDROID_SDK_ROOT", destination)
}

/**
 * Session state is always persisted on the host so conversations survive the ephemeral
 * container home: the host directory mirrors the container's `XDG_STATE_HOME`, so agent-side
 * writes land in `~/.local/state/ddr-acp-agent`.
 *
 * A state dir that cannot be created fails here instead of degrading: docker cannot bind-mount
 * a missing source, so deferring (as the shell launcher did with `mkdir -p ... || true`) would
 * only move the failure into the daemon's less actionable message.
 */
private fun resolveStateMount(
    spec: SandboxSpec,
    probes: HostProbes,
    warnings: MutableList<SandboxWarning>,
    mountedDestinations: MutableSet<String>,
    isInsideProject: (String) -> Boolean,
): BindMount? {
    val configured = spec.settings.stateDir
    val stateDir = configured ?: "${spec.hostFacts.xdgStateHome}/$APP_STATE_DIR"
    if (!stateDir.startsWith("/")) throw SandboxException("sandbox.stateDir must be an absolute path, got '$stateDir'")
    val normalized = trimTrailingSlashes(stateDir)
    if (normalized.isEmpty()) return null
    if (isInsideProject(normalized)) {
        // The project mount covers it at the *host* path only; the agent writes to the
        // container's state dir, which would stay on the container tmpfs and lose the records.
        warnings += SandboxWarning(
            "skipping session state '$normalized' - inside the project directory; " +
                    "sessions written in the container are lost with it",
        )
        return null
    }
    if (CONTAINER_STATE_DIR in mountedDestinations) return null
    if (!probes.isDirectory(normalized) && !probes.createDirectories(normalized)) {
        throw SandboxException("sandbox.stateDir: '$normalized' could not be created on the host")
    }
    return BindMount(normalized, CONTAINER_STATE_DIR)
}

/**
 * The agent's config directory (holding `config.json` and the default key file) is mounted
 * read-only at the container's own config directory - that mount is the agent's only key
 * source inside the container. [HostFacts.configDir] comes from the agent's own resolver, so
 * the mounted and the read directory cannot diverge. `~/.config` gets a uid tmpfs underneath:
 * runc would otherwise create the missing mountpoint parent root-owned, leaving it unwritable.
 */
private fun resolveConfigMount(
    facts: HostFacts,
    probes: HostProbes,
    warnings: MutableList<SandboxWarning>,
    mountedDestinations: MutableSet<String>,
    isInsideProject: (String) -> Boolean,
): Pair<TmpfsMount?, BindMount?> {
    val configDir = trimTrailingSlashes(facts.configDir)
    if (!probes.isDirectory(configDir)) return null to null
    if (isInsideProject(configDir)) {
        // Same reason as the state dir: the project mount only exposes the host path, while
        // the containerized agent reads its config from $XDG_CONFIG_HOME.
        warnings += SandboxWarning(
            "skipping the agent config '$configDir' - inside the project directory; " +
                    "the container resolves its config from $CONTAINER_CONFIG_DIR instead",
        )
        return null to null
    }
    if (CONTAINER_CONFIG_DIR in mountedDestinations) return null to null
    return TmpfsMount("$CONTAINER_HOME/.config", uidOptions(facts, mode = "700", size = "64m")) to
            BindMount(configDir, CONTAINER_CONFIG_DIR, readOnly = true)
}

private fun uidOptions(facts: HostFacts, mode: String = "700", size: String = "1g", exec: Boolean = false): String =
    "uid=${facts.uid},gid=${facts.gid},mode=$mode,size=$size" + if (exec) ",exec" else ""

/** Strips trailing slashes; `/` itself becomes `""` (the caller decides what that means). */
private fun trimTrailingSlashes(path: String): String = path.trimEnd('/')
