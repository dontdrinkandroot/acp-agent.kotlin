package net.dontdrinkandroot.acpagent.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.dontdrinkandroot.acpagent.spec.Pins

/**
 * The fail-loudly contract of the plan resolution: an input the host cannot honor refuses the
 * sandbox **before any `docker run`** instead of silently degrading into a container with a
 * different trust domain. Ported from `tests/bash/test_error_paths.bash`; the missing-docker
 * case is a supervisor pin (a `PATH` lookup), not a plan input.
 */
@Pins("docker-sandbox", "Host tool caches are shared at their own paths")
@Pins("docker-sandbox", "The Android SDK is mounted only when it is real")
@Pins("docker-sandbox", "The permission lists are the mount list")
class SandboxFailLoudlyTest {

    private fun spec(
        settings: SandboxSettings = SandboxSettings(),
        toolEnv: Map<String, String> = emptyMap(),
        readable: List<String> = emptyList(),
        writable: List<String> = emptyList(),
    ) = SandboxSpec(
        hostFacts = fixtureFacts(toolEnv = toolEnv),
        settings = settings,
        readableDirectories = readable,
        writableDirectories = writable,
    )

    private fun probes(vararg directories: String, creatable: Boolean = true) =
        FixtureProbes(directories.toList(), emptyList(), creatable = creatable)

    private fun refusal(spec: SandboxSpec, probes: HostProbes = probes()): String =
        assertFailsWith<SandboxException> { resolveSandboxPlan(spec, probes) }.message!!

    @Test
    fun `a relative permission path is refused`() {
        assertEquals(
            "permissions.readableDirectories: 'relative/path' is not an absolute host path",
            refusal(spec(readable = listOf("relative/path"))),
        )
    }

    @Test
    fun `the filesystem root is refused as a permission path`() {
        assertEquals(
            "permissions.writableDirectories: / cannot be mounted",
            refusal(spec(writable = listOf("/"))),
        )
    }

    @Test
    fun `a missing permission path is refused`() {
        assertEquals(
            "permissions.readableDirectories: '/fixtures/does-not-exist' does not exist on the host",
            refusal(spec(readable = listOf("/fixtures/does-not-exist"))),
        )
    }

    @Test
    fun `a relative explicit cache dir is refused`() {
        assertEquals(
            "UV_CACHE_DIR: relative/cache is not an absolute host path",
            refusal(spec(toolEnv = mapOf("UV_CACHE_DIR" to "relative/cache"))),
        )
    }

    @Test
    fun `the filesystem root is refused as an explicit cache dir`() {
        assertEquals(
            "UV_CACHE_DIR: / cannot be used as a cache directory",
            refusal(spec(toolEnv = mapOf("UV_CACHE_DIR" to "/"))),
        )
    }

    @Test
    fun `an uncreatable explicit cache dir is refused`() {
        // A fixture that cannot create directories stands in for a path blocked by a regular
        // file on the host - the config cannot be honored and must not fall back.
        assertEquals(
            "UV_CACHE_DIR: /fixtures/not-a-dir/cache could not be created on the host",
            refusal(
                spec(toolEnv = mapOf("UV_CACHE_DIR" to "/fixtures/not-a-dir/cache")),
                probes(creatable = false),
            ),
        )
    }

    @Test
    fun `a relative pnpm store dir is refused`() {
        assertEquals(
            "PNPM_CONFIG_STORE_DIR: relative/store is not an absolute host path",
            refusal(spec(toolEnv = mapOf("PNPM_CONFIG_STORE_DIR" to "relative/store"))),
        )
    }

    @Test
    fun `relative and root Android SDK config is refused`() {
        assertEquals(
            "ANDROID_HOME: relative/sdk is not an absolute host path",
            refusal(spec(toolEnv = mapOf("ANDROID_HOME" to "relative/sdk"))),
        )
        assertEquals(
            "ANDROID_HOME: / cannot be the Android SDK directory",
            refusal(spec(toolEnv = mapOf("ANDROID_HOME" to "/"))),
        )
    }

    @Test
    fun `a missing Android SDK dir is refused naming the set variable`() {
        assertEquals(
            "ANDROID_HOME: /fixtures/does-not-exist is not a directory on the host",
            refusal(spec(toolEnv = mapOf("ANDROID_HOME" to "/fixtures/does-not-exist"))),
        )
        assertEquals(
            "ANDROID_SDK_ROOT: /fixtures/does-not-exist is not a directory on the host",
            refusal(spec(toolEnv = mapOf("ANDROID_SDK_ROOT" to "/fixtures/does-not-exist"))),
        )
    }

    @Test
    fun `an uncreatable state dir is refused`() {
        assertEquals(
            "sandbox.stateDir: '/fixtures/state' could not be created on the host",
            refusal(
                spec(settings = SandboxSettings(stateDir = "/fixtures/state")),
                probes(creatable = false),
            ),
        )
    }

    @Test
    fun `the mount caches opt-out ignores unusable cache config entirely`() {
        // Total opt-out: nothing is shared, so nothing is validated either.
        val plan = resolveSandboxPlan(
            spec(
                settings = SandboxSettings(mountCaches = false),
                toolEnv = mapOf(
                    "UV_CACHE_DIR" to "relative/cache",
                    "ANDROID_HOME" to "relative/sdk",
                    "PNPM_CONFIG_STORE_DIR" to "relative/store",
                ),
            ),
            probes(),
        )
        assertTrue(plan.cacheMounts.isEmpty(), "expected no shared cache mounts, was ${plan.cacheMounts}")
        assertTrue(plan.cacheEnv.isEmpty(), "expected no cache env, was ${plan.cacheEnv}")
    }

    @Test
    fun `a relative sandbox state dir is refused`() {
        assertEquals(
            "sandbox.stateDir must be an absolute path, got 'relative'",
            refusal(spec(settings = SandboxSettings(stateDir = "relative"))),
        )
    }

    @Test
    fun `a root launch directory is refused`() {
        // A root cwd would compose an empty `--workdir`/project bind and treat every permission
        // path as "inside the project"; fail loudly instead of handing docker an empty source.
        val spec = SandboxSpec(hostFacts = fixtureFacts(cwd = "/"))
        assertEquals(
            "the launch directory must not be the filesystem root, got '/'",
            refusal(spec),
        )
    }
}
