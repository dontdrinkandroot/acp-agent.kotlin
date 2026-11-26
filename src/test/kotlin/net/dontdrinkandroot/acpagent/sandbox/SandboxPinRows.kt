package net.dontdrinkandroot.acpagent.sandbox

import kotlin.test.assertTrue

/**
 * The `docker run` composition pin table (see [PinCase] for the row contract).
 *
 * Rows were ported 1:1 from the shell suite that pinned the bash launcher; env-fed rows are
 * written in the config/flag input shape, and every row asserts the same argv composition as
 * before. The pull/`--skip-pull` cases and the env-channel cases (api-key, agent-env
 * forwarding, launcher-knob carve-out, legacy `ACP_DOCKER_*`) dissolved with their subjects -
 * surviving assertions were re-homed as dedicated rows, the rest is closed by
 * [ALLOWED_ENV_KEYS]'s row. The "permission lists forwarded unchanged" row dissolved the same
 * way (the agent reads the mounted `config.json`), its surviving "exactly one mount per tier"
 * half lives in the permission rows.
 *
 * ## Coverage invariant
 *
 * Every `sandbox.*` key, every shared launcher flag (`--profile`) and every forwarded host
 * fact (TZ, tool caches, git identity) has at least one named row here; the `--env`
 * allowlist row ([ALLOWED_ENV_KEYS]) is the negative closure, so a newly forwarded variable
 * fails a pin instead of slipping through. Rows written against [FIXTURE_FACTS] never read
 * the ambient host.
 */
object SandboxPins {

    val all: List<PinCase> = listOf(
        projectIsMountedAtTheIdenticalPath(),
        hardeningFlagsArePresent(),
        rwRootfsOptOutRelaxesReadOnly(),
        hostNetworkIsTheDefault(),
        explicitNetworkKeepsTheHostGatewayMapping(),
        explicitNetworkIsHonored(),
        extraArgsAreAppended(),
        capAddTokensAreEmitted(),
        homeVolumeReplacesTheHomeTmpfs(),
        timeZoneIsForwarded(),
        stateBindLandsAfterTheLocalStateTmpfs(),
        homeAndLocalDirectoriesGetUidMappedTmpfsMounts(),
        mountCachesZeroSkipsTheStoreAndCacheMounts(),
        explicitCacheDirIsIdentityMountedAndForwardedUnchanged(),
        explicitCacheDirInsideTheProjectIsForwardedWithoutAMount(),
        uvLinkModeIsForwardedWhenSet(),
        pnpmStoreAndCacheDirsAreIdentityMountedAndForwarded(),
        pnpmGlobalVirtualStoreIsForwardedWhenSet(),
        pnpmDefaultCandidatesAreMountedAtTheContainerDefaults(),
        mountCachesZeroIgnoresExplicitCacheConfigEntirely(),
        envLocalIsMaskedWithAnEmptyReadOnlyFile(),
        envLocalIsNotMountedWhenAbsent(),
        gitIdentityIsForwardedWhenConfigured(),
        gitIdentityIsOmittedWhenNotConfigured(),
        explicitAndroidHomeIsIdentityMountedAndForwarded(),
        explicitAndroidSdkRootIsForwardedWithASynthesizedHome(),
        androidHomeWinsOverAndroidSdkRoot(),
        defaultAndroidSdkDirIsPickedUpWithoutEnv(),
        defaultAndroidSdkDirInsideTheProjectIsSkipped(),
        noAndroidMountWithoutAnSdk(),
        androidMountHonorsMountCachesZero(),
        androidSdkInsideTheProjectDirPinsTheHostPath(),
        gradleContentAddressedLeavesAreSharedRw(),
        gradleLeavesAreNotSharedWhenMountCachesZero(),
        gradleNoMountWithoutAHostGradleHome(),
        gradleVersionDirsDerivedFromWrapperDistsAreShared(),
        gradleUserHomeOverridesHome(),
        permissionTiersMountWithTheMatchingMode(),
        aWritablePathAlsoListedReadableMountsRwOnce(),
        coveredPermissionPathsAreSkipped(),
        noPermissionListsMountNothing(),
        duplicatePermissionEntriesAreMountedOnce(),
        permissionEntriesAreSlashNormalized(),
        hostConfigDirIsMountedReadOnlyOnAConfigTmpfs(),
        theConfigBindLandsOnTopOfTheConfigTmpfs(),
        aMissingHostConfigDirMountsNothing(),
        aConfigDirInsideTheProjectDirectoryIsSkipped(),
        profileIsForwardedAsAgentArguments(),
        noAgentConfigEnvironmentIsForwarded(),
    )
}

private fun pin(
    name: String,
    settings: SandboxSettings = SandboxSettings(),
    hostFacts: HostFacts = FIXTURE_FACTS,
    readableDirectories: List<String> = emptyList(),
    writableDirectories: List<String> = emptyList(),
    agentArgs: List<String> = emptyList(),
    envLocalMaskFile: String? = "/fixtures/env-local-mask",
    fixtureDirectories: List<String> = emptyList(),
    fixtureFiles: List<String> = emptyList(),
    checks: ArgvExpectations.() -> Unit,
) = PinCase(
    name = name,
    spec = SandboxSpec(
        hostFacts = hostFacts,
        settings = settings,
        readableDirectories = readableDirectories,
        writableDirectories = writableDirectories,
        agentArgs = agentArgs,
        envLocalMaskFile = envLocalMaskFile,
    ),
    fixtureDirectories = fixtureDirectories,
    fixtureFiles = fixtureFiles,
    checks = checks,
)

private fun tmpfsValue(path: String, exec: Boolean = false): String =
    "$path:uid=$EXPECTED_UID,gid=$EXPECTED_GID,mode=700,size=1g" + if (exec) ",exec" else ""

private fun projectIsMountedAtTheIdenticalPath() = pin("project is mounted at the identical path with workdir") {
    tokens("--workdir", FIXTURE_PROJECT)
    mount("type=bind,src=$FIXTURE_PROJECT,dst=$FIXTURE_PROJECT")
    lastToken(SANDBOX_IMAGE)
}

private fun hardeningFlagsArePresent() = pin("sandbox hardening flags are present") {
    token("--cap-drop=ALL")
    tokens("--security-opt", "no-new-privileges")
    token("--read-only")
    tokens("--rm", "--init", "-i")
    tokens("--tmpfs", "/tmp:rw,nosuid,nodev,exec,size=1g")
    tokens("--user", EXPECTED_UID_GID)
    token("--init")
}

private fun rwRootfsOptOutRelaxesReadOnly() = pin(
    "rw rootfs opt-out relaxes read-only",
    settings = SandboxSettings(rwRootfs = true),
) {
    noToken("--read-only")
    token("--cap-drop=ALL")
}

private fun hostNetworkIsTheDefault() = pin("host network is the default") {
    tokens("--network", "host")
    token("--add-host=host.docker.internal:127.0.0.1")
}

private fun explicitNetworkKeepsTheHostGatewayMapping() = pin(
    "explicit network keeps the host gateway mapping",
    settings = SandboxSettings(network = "development"),
) {
    tokens("--network", "development")
    token("--add-host=host.docker.internal:host-gateway")
}

private fun explicitNetworkIsHonored() = pin(
    "explicit docker network is honored",
    settings = SandboxSettings(network = "default"),
) {
    tokens("--network", "default")
    token("--add-host=host.docker.internal:host-gateway")
}

private fun extraArgsAreAppended() = pin(
    "extra args are appended",
    settings = SandboxSettings(extraArgs = listOf("--label", "foo=bar", "--label", "baz=qux")),
) {
    tokens("--label", "foo=bar", "--label", "baz=qux")
    lastToken(SANDBOX_IMAGE)
}

private fun capAddTokensAreEmitted() = pin(
    "cap add tokens are emitted",
    settings = SandboxSettings(capAdd = listOf("SYS_PTRACE")),
) {
    tokens("--cap-add", "SYS_PTRACE")
    token("--cap-drop=ALL")
}

private fun homeVolumeReplacesTheHomeTmpfs() = pin(
    "home volume replaces the home tmpfs",
    settings = SandboxSettings(homeVolume = "probe-volume"),
) {
    mount("type=volume,src=probe-volume,dst=$CONTAINER_HOME")
    assertTrue(
        valuesAfter("--tmpfs").none { it.startsWith("$CONTAINER_HOME:") },
        "expected no home tmpfs next to the home volume, argv: $argv",
    )
}

private fun timeZoneIsForwarded() = pin(
    "tz is forwarded",
    hostFacts = fixtureFacts(timeZone = "Europe/Berlin"),
) {
    envValue("TZ", "Europe/Berlin")
}

private fun stateBindLandsAfterTheLocalStateTmpfs() = pin(
    "state bind lands after the local state tmpfs",
    settings = SandboxSettings(stateDir = FIXTURE_STATE_DIR),
) {
    val stateMount = "type=bind,src=$FIXTURE_STATE_DIR,dst=$CONTAINER_STATE_DIR"
    mount(stateMount)
    val localTmpfs = tmpfsValue("$CONTAINER_HOME/.local")
    assertTrue(
        argv.indexOf(stateMount) > argv.indexOf(localTmpfs),
        "expected the state bind after the $localTmpfs tmpfs, argv: $argv",
    )
}

private fun homeAndLocalDirectoriesGetUidMappedTmpfsMounts() = pin(
    "home and local directories get uid mapped tmpfs mounts",
) {
    // The home tmpfs is exec-allowed (toolchains execute native binaries from $HOME);
    // ~/.local and ~/.cache keep docker's noexec default - nothing executes there.
    tokens("--tmpfs", tmpfsValue(CONTAINER_HOME, exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.local"))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.local/share"))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.local/state"))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.cache"))
}
private fun mountCachesZeroSkipsTheStoreAndCacheMounts() = pin(
    "mount caches zero skips the store and cache mounts",
    settings = SandboxSettings(mountCaches = false),
) {
    // The cache binds disappear, but never the uid-mapped tmpfs mounts: ~/.local,
    // ~/.cache and the private Gradle user-home root stay writable.
    assertTrue(mounts().none { it.contains("pnpm/store") }, "expected no pnpm store mount, argv: $argv")
    noMountContaining("/.gradle")
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.local"))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.cache"))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.gradle", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.gradle/caches", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_HOME/.gradle/wrapper", exec = true))
    // Exactly the three private Gradle depths - no per-version tmpfs without a host GUH.
    failUnless(argv.count { it.startsWith("$CONTAINER_HOME/.gradle") } == 3) {
        "expected exactly three .gradle tmpfs paths, argv: $argv"
    }
}

private fun explicitCacheDirIsIdentityMountedAndForwardedUnchanged() = pin(
    "explicit cache dir is identity mounted and forwarded unchanged",
    hostFacts = fixtureFacts(toolEnv = mapOf("UV_CACHE_DIR" to "/fixtures/altdata/cache/uv/")),
) {
    // Explicit tool config is honored as-is: the dir is created if missing,
    // identity-bound (no relocation to the container default) and the env value is
    // forwarded verbatim - trailing slash included.
    mount("type=bind,src=/fixtures/altdata/cache/uv,dst=/fixtures/altdata/cache/uv")
    envValue("UV_CACHE_DIR", "/fixtures/altdata/cache/uv/")
}

private fun explicitCacheDirInsideTheProjectIsForwardedWithoutAMount() = pin(
    "explicit cache dir inside the project is forwarded without a mount",
    hostFacts = fixtureFacts(toolEnv = mapOf("UV_CACHE_DIR" to "$FIXTURE_PROJECT/cache-uv")),
) {
    // The project is mounted rw at the identical path, so the cache needs no separate
    // mount - the env value must still be forwarded so the tool honors the configured
    // path instead of falling back to its in-container default.
    noMountContaining("src=$FIXTURE_PROJECT/cache-uv,dst=$FIXTURE_PROJECT/cache-uv")
    envValue("UV_CACHE_DIR", "$FIXTURE_PROJECT/cache-uv")
}

private fun uvLinkModeIsForwardedWhenSet() = pin(
    "uv link mode is forwarded when set",
    hostFacts = fixtureFacts(toolEnv = mapOf("UV_LINK_MODE" to "symlink")),
) {
    // Hosts with cross-device cache/venv layouts set this; the layout is identical in the
    // container once the cache is identity-bound, so the setting must travel.
    envValue("UV_LINK_MODE", "symlink")
}

private fun pnpmStoreAndCacheDirsAreIdentityMountedAndForwarded() = pin(
    "pnpm store and cache dirs are identity mounted and forwarded",
    hostFacts = fixtureFacts(
        toolEnv = mapOf(
            "PNPM_CONFIG_STORE_DIR" to "/fixtures/altdata/cache/pnpm/store",
            "PNPM_CONFIG_CACHE_DIR" to "/fixtures/altdata/cache/pnpm/cache",
        ),
    ),
) {
    // pnpm reads pnpm_config_*/PNPM_CONFIG_* env vars, so its dirs follow the same
    // explicit-config policy as every other tool cache.
    mount("type=bind,src=/fixtures/altdata/cache/pnpm/store,dst=/fixtures/altdata/cache/pnpm/store")
    mount("type=bind,src=/fixtures/altdata/cache/pnpm/cache,dst=/fixtures/altdata/cache/pnpm/cache")
    envValue("PNPM_CONFIG_STORE_DIR", "/fixtures/altdata/cache/pnpm/store")
    envValue("PNPM_CONFIG_CACHE_DIR", "/fixtures/altdata/cache/pnpm/cache")
}

private fun pnpmGlobalVirtualStoreIsForwardedWhenSet() = pin(
    "pnpm global virtual store is forwarded when set",
    hostFacts = fixtureFacts(toolEnv = mapOf("PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE" to "true")),
) {
    // node_modules then symlinks into the shared store, so host and container projects
    // only share one layout when the setting travels with the identity-bound store.
    envValue("PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE", "true")
}

private fun pnpmDefaultCandidatesAreMountedAtTheContainerDefaults() = pin(
    "pnpm default candidates are mounted at the container defaults",
    hostFacts = fixtureFacts(
        xdgDataHome = "/fixtures/xdg-data",
        xdgCacheHome = "/fixtures/xdg-cache",
    ),
    fixtureDirectories = listOf("/fixtures/xdg-data/pnpm/store", "/fixtures/xdg-cache/pnpm"),
) {
    // Without explicit config the existing default dirs are shared at the tool's
    // in-container defaults and the env vars are pinned to those dsts.
    mount("type=bind,src=/fixtures/xdg-data/pnpm/store,dst=$CONTAINER_HOME/.local/share/pnpm/store")
    mount("type=bind,src=/fixtures/xdg-cache/pnpm,dst=$CONTAINER_HOME/.cache/pnpm")
    envValue("PNPM_CONFIG_STORE_DIR", "$CONTAINER_HOME/.local/share/pnpm/store")
    envValue("PNPM_CONFIG_CACHE_DIR", "$CONTAINER_HOME/.cache/pnpm")
}

private fun mountCachesZeroIgnoresExplicitCacheConfigEntirely() = pin(
    "mount caches zero ignores explicit cache config entirely",
    settings = SandboxSettings(mountCaches = false),
    hostFacts = fixtureFacts(
        toolEnv = mapOf(
            "UV_CACHE_DIR" to "relative/cache",
            "ANDROID_HOME" to "relative/sdk",
            "UV_LINK_MODE" to "symlink",
            "PNPM_CONFIG_STORE_DIR" to "relative/store",
            "PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE" to "true",
        ),
    ),
) {
    // Total opt-out: no mounts, no env forwarding and no validation of even unusable
    // values (the tool keeps its in-container default).
    envAbsent("UV_CACHE_DIR")
    envAbsent("ANDROID_HOME")
    envAbsent("ANDROID_SDK_ROOT")
    envAbsent("UV_LINK_MODE")
    envAbsent("PNPM_CONFIG_STORE_DIR")
    envAbsent("PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE")
    assertTrue(mounts().none { it.contains("cache") }, "expected no cache mounts, argv: $argv")
}

private fun envLocalIsMaskedWithAnEmptyReadOnlyFile() = pin(
    "env local is masked with an empty read only file",
    fixtureFiles = listOf("$FIXTURE_PROJECT/.env.local"),
) {
    // The host .env.local is masked by an empty read-only file mounted over it; the temp
    // file's path is host-derived, so only the mount shape is pinned.
    val masked = mounts().filter { it.endsWith("dst=$FIXTURE_PROJECT/.env.local,ro") }
    failUnless(masked.size == 1) { "expected exactly one .env.local mask mount, argv: $argv" }
    failUnless(masked.single().startsWith("type=bind,src=")) { "expected the mask to be a bind mount, was ${masked.single()}" }
}

private fun envLocalIsNotMountedWhenAbsent() = pin("env local is not mounted when absent") {
    noMountContaining(".env.local")
}

private fun gitIdentityIsForwardedWhenConfigured() = pin(
    "git identity is forwarded when configured",
    hostFacts = fixtureFacts(gitIdentity = GitIdentity("TestUser", "test@example.com")),
) {
    envValue("GIT_AUTHOR_NAME", "TestUser")
    envValue("GIT_AUTHOR_EMAIL", "test@example.com")
    envValue("GIT_COMMITTER_NAME", "TestUser")
    envValue("GIT_COMMITTER_EMAIL", "test@example.com")
}

private fun gitIdentityIsOmittedWhenNotConfigured() = pin("git identity is omitted when not configured") {
    envAbsent("GIT_AUTHOR_NAME")
    envAbsent("GIT_COMMITTER_EMAIL")
}

private const val ANDROID_SDK = "/fixtures/android-sdk"
private const val ANDROID_SDK_ROOT_DIR = "/fixtures/android-sdk-root"

private fun explicitAndroidHomeIsIdentityMountedAndForwarded() = pin(
    "explicit android home is identity mounted and forwarded",
    hostFacts = fixtureFacts(toolEnv = mapOf("ANDROID_HOME" to ANDROID_SDK)),
    fixtureDirectories = listOf(ANDROID_SDK),
) {
    // Identity-bound at the identical in-container path; both ANDROID_* variables carry
    // the configured value (the companion is synthesized).
    mount("type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK")
    envValue("ANDROID_HOME", ANDROID_SDK)
    envValue("ANDROID_SDK_ROOT", ANDROID_SDK)
}

private fun explicitAndroidSdkRootIsForwardedWithASynthesizedHome() = pin(
    "explicit android sdk root is forwarded with a synthesized home",
    hostFacts = fixtureFacts(toolEnv = mapOf("ANDROID_SDK_ROOT" to ANDROID_SDK)),
    fixtureDirectories = listOf(ANDROID_SDK),
) {
    mount("type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK")
    envValue("ANDROID_SDK_ROOT", ANDROID_SDK)
    envValue("ANDROID_HOME", ANDROID_SDK)
}

private fun androidHomeWinsOverAndroidSdkRoot() = pin(
    "android home wins over android sdk root",
    hostFacts = fixtureFacts(
        toolEnv = mapOf("ANDROID_HOME" to ANDROID_SDK, "ANDROID_SDK_ROOT" to ANDROID_SDK_ROOT_DIR),
    ),
    fixtureDirectories = listOf(ANDROID_SDK, ANDROID_SDK_ROOT_DIR),
) {
    // ANDROID_HOME is authoritative: its dir is mounted, both variables carry its value.
    mount("type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK")
    noMountContaining(ANDROID_SDK_ROOT_DIR)
    envValue("ANDROID_HOME", ANDROID_SDK)
    envValue("ANDROID_SDK_ROOT", ANDROID_SDK)
}

private fun defaultAndroidSdkDirIsPickedUpWithoutEnv() = pin(
    "default android sdk dir is picked up without env",
    hostFacts = fixtureFacts(home = "/fixtures/android-home"),
    fixtureDirectories = listOf("/fixtures/android-home/Android/Sdk"),
) {
    // An existing $HOME/Android/Sdk is shared (never created) at the SDK's in-container
    // default, with both variables pinned to it.
    mount("type=bind,src=/fixtures/android-home/Android/Sdk,dst=$CONTAINER_HOME/Android/Sdk")
    envValue("ANDROID_HOME", "$CONTAINER_HOME/Android/Sdk")
    envValue("ANDROID_SDK_ROOT", "$CONTAINER_HOME/Android/Sdk")
}

private fun defaultAndroidSdkDirInsideTheProjectIsSkipped() = pin(
    "default android sdk dir inside the project is skipped",
    hostFacts = fixtureFacts(home = "$FIXTURE_PROJECT/home"),
    fixtureDirectories = listOf("$FIXTURE_PROJECT/home/Android/Sdk"),
) {
    // The project mount already covers it rw at the identical path; the tool-cache skip
    // policy applies unchanged - nothing is relocated or pinned.
    noMountContaining("Android/Sdk")
    envAbsent("ANDROID_HOME")
    envAbsent("ANDROID_SDK_ROOT")
}

private fun noAndroidMountWithoutAnSdk() = pin(
    "no android mount without an sdk",
    hostFacts = fixtureFacts(home = "/fixtures/empty-home"),
) {
    noMountContaining("Android/Sdk")
    envAbsent("ANDROID_HOME")
    envAbsent("ANDROID_SDK_ROOT")
}

private fun androidMountHonorsMountCachesZero() = pin(
    "android mount honors mount caches zero",
    settings = SandboxSettings(mountCaches = false),
    hostFacts = fixtureFacts(toolEnv = mapOf("ANDROID_HOME" to ANDROID_SDK)),
    fixtureDirectories = listOf(ANDROID_SDK),
) {
    noMountContaining(ANDROID_SDK)
    envAbsent("ANDROID_HOME")
}

private fun androidSdkInsideTheProjectDirPinsTheHostPath() = pin(
    "android sdk inside the project dir pins the host path",
    hostFacts = fixtureFacts(toolEnv = mapOf("ANDROID_HOME" to "$FIXTURE_PROJECT/android-sdk")),
    fixtureDirectories = listOf("$FIXTURE_PROJECT/android-sdk"),
) {
    // No mount (the project bind covers it rw); the pins keep the configured path -
    // pointing at the skipped container default would reference a nonexistent directory.
    noMountContaining("dst=$CONTAINER_HOME/Android/Sdk")
    envValue("ANDROID_HOME", "$FIXTURE_PROJECT/android-sdk")
    envValue("ANDROID_SDK_ROOT", "$FIXTURE_PROJECT/android-sdk")
}

private const val GRADLE_HOME_FIXTURE = "/fixtures/gradle-home"
private const val CONTAINER_GRADLE_HOME = "$CONTAINER_HOME/.gradle"

private val GRADLE_HOME_FIXTURE_DIRS = listOf(
    "$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2",
    "$GRADLE_HOME_FIXTURE/.gradle/caches/jars-9",
    "$GRADLE_HOME_FIXTURE/.gradle/caches/jars-8",
    "$GRADLE_HOME_FIXTURE/.gradle/caches/build-cache-1",
    "$GRADLE_HOME_FIXTURE/.gradle/caches/transforms-3",
    "$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0",
    "$GRADLE_HOME_FIXTURE/.gradle/wrapper/dists",
    "$GRADLE_HOME_FIXTURE/.gradle/jdks",
    "$GRADLE_HOME_FIXTURE/.gradle/daemon",
)

private fun gradleContentAddressedLeavesAreSharedRw() = pin(
    "gradle content addressed leaves are shared rw",
    hostFacts = fixtureFacts(home = GRADLE_HOME_FIXTURE),
    fixtureDirectories = GRADLE_HOME_FIXTURE_DIRS,
) {
    // Only the content-addressed, machine-independent leaves are bind-mounted at their
    // identical in-container path; daemon state and the GUH root stay container-private.
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2,dst=$CONTAINER_GRADLE_HOME/caches/modules-2")
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/jars-9,dst=$CONTAINER_GRADLE_HOME/caches/jars-9")
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/jars-8,dst=$CONTAINER_GRADLE_HOME/caches/jars-8")
    mount(
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/build-cache-1," +
            "dst=$CONTAINER_GRADLE_HOME/caches/build-cache-1",
    )
    mount(
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/transforms-3," +
            "dst=$CONTAINER_GRADLE_HOME/caches/transforms-3",
    )
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/wrapper/dists,dst=$CONTAINER_GRADLE_HOME/wrapper/dists")
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/jdks,dst=$CONTAINER_GRADLE_HOME/jdks")
    tokens("--tmpfs", tmpfsValue(CONTAINER_GRADLE_HOME, exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/wrapper", exec = true))
    // Version-bound content-addressed caches are shared too (issue #67); the version dir
    // itself gets a uid tmpfs so its private siblings stay writable and private.
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches/9.6.0", exec = true))
    mount(
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/transforms," +
            "dst=$CONTAINER_GRADLE_HOME/caches/9.6.0/transforms",
    )
    mount(
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/generated-gradle-jars," +
            "dst=$CONTAINER_GRADLE_HOME/caches/9.6.0/generated-gradle-jars",
    )
    noMountContaining("dst=$CONTAINER_GRADLE_HOME/daemon")
    failUnless(countMountsContaining("dst=$CONTAINER_GRADLE_HOME/caches/9.6.0") == 2) {
        "expected exactly the two version-bound leaves for 9.6.0, argv: $argv"
    }
    noMountContaining("dst=$CONTAINER_GRADLE_HOME,")
    envValue("GRADLE_USER_HOME", CONTAINER_GRADLE_HOME)
}

private fun gradleLeavesAreNotSharedWhenMountCachesZero() = pin(
    "gradle leaves are not shared when mount caches zero",
    settings = SandboxSettings(mountCaches = false),
    hostFacts = fixtureFacts(home = GRADLE_HOME_FIXTURE),
    fixtureDirectories = GRADLE_HOME_FIXTURE_DIRS,
) {
    // No leaf binds, but the private .gradle tmpfs depths and the GRADLE_USER_HOME pin
    // remain: daemon/process state stays container-private.
    noMountContaining("/.gradle")
    tokens("--tmpfs", tmpfsValue(CONTAINER_GRADLE_HOME, exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/wrapper", exec = true))
    envValue("GRADLE_USER_HOME", CONTAINER_GRADLE_HOME)
}

private fun gradleNoMountWithoutAHostGradleHome() = pin(
    "gradle no mount without a host gradle home",
    hostFacts = fixtureFacts(home = "/fixtures/empty-gradle-home"),
) {
    noMountContaining("/.gradle")
    tokens("--tmpfs", tmpfsValue(CONTAINER_GRADLE_HOME, exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/wrapper", exec = true))
    envValue("GRADLE_USER_HOME", CONTAINER_GRADLE_HOME)
}

private fun gradleVersionDirsDerivedFromWrapperDistsAreShared() = pin(
    "gradle version dirs derived from wrapper dists are shared",
    hostFacts = fixtureFacts(home = "/fixtures/dists-home"),
    fixtureDirectories = listOf(
        "/fixtures/dists-home/.gradle/caches/modules-2",
        "/fixtures/dists-home/.gradle/wrapper/dists/gradle-9.7.0-bin",
        "/fixtures/dists-home/.gradle/wrapper/dists/gradle-8.14-all",
    ),
) {
    // A version whose distribution is shared but whose caches/<ver> dir the host has not
    // created yet still gets its version-bound leaves, or every session re-derives them.
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches/9.7.0", exec = true))
    tokens("--tmpfs", tmpfsValue("$CONTAINER_GRADLE_HOME/caches/8.14", exec = true))
    mount(
        "type=bind,src=/fixtures/dists-home/.gradle/caches/9.7.0/transforms," +
            "dst=$CONTAINER_GRADLE_HOME/caches/9.7.0/transforms",
    )
    mount(
        "type=bind,src=/fixtures/dists-home/.gradle/caches/9.7.0/generated-gradle-jars," +
            "dst=$CONTAINER_GRADLE_HOME/caches/9.7.0/generated-gradle-jars",
    )
    mount(
        "type=bind,src=/fixtures/dists-home/.gradle/caches/8.14/generated-gradle-jars," +
            "dst=$CONTAINER_GRADLE_HOME/caches/8.14/generated-gradle-jars",
    )
}

private fun gradleUserHomeOverridesHome() = pin(
    "gradle user home overrides home",
    hostFacts = fixtureFacts(
        home = "/fixtures/other-home",
        toolEnv = mapOf("GRADLE_USER_HOME" to "$GRADLE_HOME_FIXTURE/.gradle"),
    ),
    fixtureDirectories = GRADLE_HOME_FIXTURE_DIRS,
) {
    mount("type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2,dst=$CONTAINER_GRADLE_HOME/caches/modules-2")
    envValue("GRADLE_USER_HOME", CONTAINER_GRADLE_HOME)
}

private const val READABLE = "/fixtures/readable"
private const val WRITABLE = "/fixtures/writable"
private const val EXTRA_PROJECT = "/fixtures/extra-project"

private fun permissionTiersMountWithTheMatchingMode() = pin(
    "permission tiers mount with the matching mode",
    readableDirectories = listOf(READABLE),
    writableDirectories = listOf(WRITABLE),
    fixtureDirectories = listOf(READABLE, WRITABLE),
) {
    mount("type=bind,src=$READABLE,dst=$READABLE,ro")
    // writable = read-write: no `,ro` suffix.
    mount("type=bind,src=$WRITABLE,dst=$WRITABLE")
    noMountContaining("src=$WRITABLE,dst=$WRITABLE,ro")
    failUnless(countMountsContaining("dst=$WRITABLE") == 1) {
        "expected exactly one mount for the writable tier, argv: $argv"
    }
}

private fun aWritablePathAlsoListedReadableMountsRwOnce() = pin(
    "a writable path also listed readable mounts rw once",
    readableDirectories = listOf(WRITABLE),
    writableDirectories = listOf(WRITABLE),
    fixtureDirectories = listOf(WRITABLE),
) {
    failUnless(countMountsContaining("dst=$WRITABLE") == 1) {
        "expected exactly one mount for the path, argv: $argv"
    }
    mount("type=bind,src=$WRITABLE,dst=$WRITABLE")
    noMountContaining("src=$WRITABLE,dst=$WRITABLE,ro")
}

private fun coveredPermissionPathsAreSkipped() = pin(
    "covered permission paths are skipped",
    hostFacts = fixtureFacts(cwd = EXTRA_PROJECT),
    readableDirectories = listOf(
        EXTRA_PROJECT,
        "$EXTRA_PROJECT/inner",
        "/tmp/sandbox-pin-skip",
        "$CONTAINER_HOME/sandbox-pin-skip",
        READABLE,
    ),
    fixtureDirectories = listOf(EXTRA_PROJECT, "$EXTRA_PROJECT/inner", READABLE),
) {
    // Paths inside the project dir / container home / tmpfs are shadowed by the covering
    // mount at the identical path: skipped, never mounted. The /home/dev fixture is
    // deliberately nonexistent - it only exists inside the agent container.
    mount("type=bind,src=$READABLE,dst=$READABLE,ro")
    noMountContaining("src=$EXTRA_PROJECT/inner")
    noMountContaining("/tmp/sandbox-pin-skip")
    noMountContaining("sandbox-pin-skip")
}

private fun noPermissionListsMountNothing() = pin("no permission lists mount nothing") {
    // Nothing outside the built-in composition appears.
    val unexpected = mounts().filterNot {
        it.contains(",dst=$FIXTURE_PROJECT") || it.contains(",dst=$CONTAINER_HOME")
    }
    failUnless(unexpected.isEmpty()) { "expected only built-in mounts, found $unexpected" }
}

private fun duplicatePermissionEntriesAreMountedOnce() = pin(
    "duplicate permission entries are mounted once",
    readableDirectories = listOf(READABLE, READABLE),
    fixtureDirectories = listOf(READABLE),
) {
    failUnless(countMountsContaining("src=$READABLE,dst=$READABLE,ro") == 1) {
        "expected exactly one mount for the duplicated entry, argv: $argv"
    }
}

private fun permissionEntriesAreSlashNormalized() = pin(
    "permission entries are slash normalized",
    readableDirectories = listOf("$READABLE/", READABLE),
    fixtureDirectories = listOf(READABLE),
) {
    // A trailing slash names the same directory: the composed argv must carry the normalized
    // path exactly once (docker applies the last --mount for a destination, silently).
    mount("type=bind,src=$READABLE,dst=$READABLE,ro")
    failUnless(countMountsContaining("dst=$READABLE") == 1) {
        "expected exactly one mount for the slash spelling variant, argv: $argv"
    }
    failUnless(mounts().none { it.contains("$READABLE/,") || it.endsWith("$READABLE/") }) {
        "expected no unnormalized mount path, argv: $argv"
    }
}

private const val CONFIG_HOME = "/fixtures/config-home"
private const val CONFIG_HOST_DIR = "$CONFIG_HOME/ddr-acp-agent"
private const val CONTAINER_CONFIG_HOME = "$CONTAINER_HOME/.config"

private fun hostConfigDirIsMountedReadOnlyOnAConfigTmpfs() = pin(
    "host config dir is mounted read only on a config tmpfs",
    hostFacts = fixtureFacts(configDir = CONFIG_HOST_DIR),
    fixtureDirectories = listOf(CONFIG_HOST_DIR),
) {
    mount("type=bind,src=$CONFIG_HOST_DIR,dst=$CONTAINER_CONFIG_DIR,ro")
    tokens("--tmpfs", "$CONTAINER_CONFIG_HOME:uid=$EXPECTED_UID,gid=$EXPECTED_GID,mode=700,size=64m")
}

private fun theConfigBindLandsOnTopOfTheConfigTmpfs() = pin(
    "the config bind lands on top of the config tmpfs",
    hostFacts = fixtureFacts(configDir = CONFIG_HOST_DIR),
    fixtureDirectories = listOf(CONFIG_HOST_DIR),
) {
    val configMount = "type=bind,src=$CONFIG_HOST_DIR,dst=$CONTAINER_CONFIG_DIR,ro"
    mount(configMount)
    val configTmpfs = "$CONTAINER_CONFIG_HOME:uid=$EXPECTED_UID,gid=$EXPECTED_GID,mode=700,size=64m"
    assertTrue(
        argv.indexOf(configMount) > argv.indexOf(configTmpfs),
        "expected the config bind after the $configTmpfs tmpfs, argv: $argv",
    )
}

private fun aMissingHostConfigDirMountsNothing() = pin(
    "a missing host config dir mounts nothing",
    hostFacts = fixtureFacts(configDir = "/fixtures/empty-config-home/ddr-acp-agent"),
) {
    // The agent then runs on built-in defaults - no bind and no ~/.config tmpfs.
    noMountContaining("dst=$CONTAINER_CONFIG_DIR")
    noToken("$CONTAINER_CONFIG_HOME:uid=$EXPECTED_UID,gid=$EXPECTED_GID,mode=700,size=64m")
}

private fun aConfigDirInsideTheProjectDirectoryIsSkipped() = pin(
    "a config dir inside the project directory is skipped",
    hostFacts = fixtureFacts(configDir = "$FIXTURE_PROJECT/.config/ddr-acp-agent"),
    fixtureDirectories = listOf("$FIXTURE_PROJECT/.config/ddr-acp-agent"),
) {
    noMountContaining("dst=$CONTAINER_CONFIG_DIR")
    noToken("$CONTAINER_CONFIG_HOME:uid=$EXPECTED_UID,gid=$EXPECTED_GID,mode=700,size=64m")
}

private fun profileIsForwardedAsAgentArguments() = pin(
    "profile is forwarded as agent arguments",
    agentArgs = listOf("--profile", "work"),
) {
    // The shared flag: both sides parse it independently (the supervisor for its config
    // resolution, the containerized agent for its own), so it reaches the agent argv.
    tokens(SANDBOX_IMAGE, "--profile", "work")
    lastToken("work")
}

/**
 * The negative closure of the environment retirement: the composed argv carries no
 * agent-config environment at all. An allowlist (not a bare "no DDR_ACP_AGENT_*") because
 * the family is deleted - a blanket assertion would be trivially true and pin nothing.
 * Only tool- and host-fact variables survive, plus the container pins.
 */
internal val ALLOWED_ENV_KEYS = setOf(
    "ANDROID_HOME", "ANDROID_SDK_ROOT", "CARGO_HOME", "COMPOSER_CACHE_DIR",
    "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_NAME", "GIT_COMMITTER_EMAIL", "GIT_COMMITTER_NAME",
    "GOMODCACHE", "GRADLE_USER_HOME", "HF_HOME", "HOME", "KONAN_DATA_DIR",
    "NPM_CONFIG_CACHE", "NUGET_PACKAGES", "PIP_CACHE_DIR", "PNPM_CONFIG_CACHE_DIR",
    "PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE", "PNPM_CONFIG_STORE_DIR", "PUB_CACHE",
    "TERM", "TZ", "UV_CACHE_DIR", "UV_LINK_MODE", "XDG_CACHE_HOME", "XDG_CONFIG_HOME",
    "XDG_DATA_HOME", "XDG_STATE_HOME", "YARN_CACHE_FOLDER",
)

private fun noAgentConfigEnvironmentIsForwarded() = pin(
    "no agent config environment is forwarded",
    settings = SandboxSettings(
        network = "development",
        stateDir = FIXTURE_STATE_DIR,
        homeVolume = "probe-volume",
        extraArgs = listOf("--label", "probe=1"),
        capAdd = listOf("SYS_PTRACE"),
        rwRootfs = true,
    ),
    hostFacts = fixtureFacts(
        timeZone = "Europe/Berlin",
        gitIdentity = GitIdentity("TestUser", "test@example.com"),
        toolEnv = mapOf(
            "UV_CACHE_DIR" to "/fixtures/altdata/cache/uv",
            "GRADLE_USER_HOME" to "/fixtures/gradle-home/.gradle",
        ),
    ),
    agentArgs = listOf("--profile", "work"),
) {
    val unexpected = envKeys().filterNot { it in ALLOWED_ENV_KEYS }
    failUnless(unexpected.isEmpty()) {
        "expected only tool and host fact --env keys, found $unexpected in $argv"
    }
    envAbsent("DDR_ACP_AGENT_CONFIG_FILE")
    envAbsent("DOCKER_BIN")
}
