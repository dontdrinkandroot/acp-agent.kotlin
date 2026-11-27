package net.dontdrinkandroot.acpagent.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.dontdrinkandroot.acpagent.spec.Pins

/**
 * The typed plan level: which directories are skipped and why. The "skipped with a warning"
 * contract is pinned here (typed warnings), not on stderr text - the argv composition is
 * covered by the pin table, the warnings by this test.
 */
@Pins("docker-sandbox", "Session state and configuration are shared the same way the agent reads them")
@Pins("docker-sandbox", "The permission lists are the mount list")
class SandboxPlanTest {

    private fun plan(
        spec: SandboxSpec,
        directories: List<String> = emptyList(),
        files: List<String> = emptyList(),
    ): SandboxPlan = resolveSandboxPlan(spec, FixtureProbes(directories, files))

    private fun spec(
        readable: List<String> = emptyList(),
        writable: List<String> = emptyList(),
        settings: SandboxSettings = SandboxSettings(),
    ) = SandboxSpec(
        hostFacts = fixtureFacts(),
        settings = settings,
        readableDirectories = readable,
        writableDirectories = writable,
    )

    @Test
    fun `covered permission paths are skipped with a typed warning each`() {
        val resolved = plan(
            spec(readable = listOf(FIXTURE_PROJECT, "$FIXTURE_PROJECT/inner", "/tmp/skip", "$CONTAINER_HOME/skip")),
            directories = listOf("$FIXTURE_PROJECT/inner"),
        )
        assertEquals(
            listOf(
                "skipping permission path '$FIXTURE_PROJECT' - inside the project directory, " +
                        "which is always mounted rw",
                "skipping permission path '$FIXTURE_PROJECT/inner' - inside the project directory, " +
                        "which is always mounted rw",
                "skipping permission path '/tmp/skip' - covered by the container tmpfs",
                "skipping permission path '$CONTAINER_HOME/skip' - covered by the container home",
            ),
            resolved.warnings.map { it.message },
        )
        assertTrue(resolved.permissionMounts.isEmpty(), "expected no permission mounts, was ${resolved.permissionMounts}")
    }

    @Test
    fun `writable wins over readable and mounts sort shallowest first`() {
        val resolved = plan(
            spec(
                readable = listOf("/fixtures/deep/nested/dir", "/fixtures/both", "/fixtures/a"),
                writable = listOf("/fixtures/both"),
            ),
            directories = listOf("/fixtures/deep/nested/dir", "/fixtures/both", "/fixtures/a"),
        )
        assertEquals(
            listOf(
                BindMount("/fixtures/a", "/fixtures/a", readOnly = true),
                BindMount("/fixtures/both", "/fixtures/both"),
                BindMount("/fixtures/deep/nested/dir", "/fixtures/deep/nested/dir", readOnly = true),
            ),
            resolved.permissionMounts,
        )
    }

    @Test
    fun `a state or config directory inside the project is skipped, but not silently`() {
        // Both are only visible at their *host* path (the project mount), while the agent uses
        // the container's XDG paths: the skip loses the sessions and the config, so it must be
        // a typed warning instead of a silent omission.
        val stateDir = "$FIXTURE_PROJECT/state"
        val configDir = "$FIXTURE_PROJECT/.config/ddr-acp-agent"
        val resolved = plan(
            SandboxSpec(
                hostFacts = fixtureFacts(configDir = configDir),
                settings = SandboxSettings(stateDir = stateDir),
            ),
            directories = listOf(stateDir, configDir),
        )
        assertNull(resolved.stateMount)
        assertNull(resolved.configMount)
        assertEquals(
            listOf(
                "skipping session state '$stateDir' - inside the project directory; " +
                        "sessions written in the container are lost with it",
                "skipping the agent config '$configDir' - inside the project directory; " +
                        "the container resolves its config from $CONTAINER_CONFIG_DIR instead",
            ),
            resolved.warnings.map { it.message },
        )
    }

    @Test
    fun `a missing configured directory refuses the sandbox`() {
        // The direct-run side of the split (a warning, not a failure) is pinned by
        // ConfigTest's "configured directories that do not exist warn but do not fail";
        // docker cannot bind-mount a missing source, so the sandbox side is fatal.
        val failure = runCatching { plan(spec(readable = listOf("/fixtures/does-not-exist"))) }.exceptionOrNull()
        assertTrue(failure is SandboxException, "sandbox mode must refuse a missing configured directory")
    }

    @Test
    fun `trailing slashes normalize and dedupe permission entries`() {
        // "/fixtures/readable/" names the same mount as "/fixtures/readable" - it must not
        // become a second --mount entry (docker applies the last one, silently).
        val resolved = plan(
            spec(readable = listOf("/fixtures/readable/", "/fixtures/readable", "/fixtures/deep/")),
            directories = listOf("/fixtures/readable", "/fixtures/deep"),
        )
        assertEquals(
            listOf(
                BindMount("/fixtures/readable", "/fixtures/readable", readOnly = true),
                BindMount("/fixtures/deep", "/fixtures/deep", readOnly = true),
            ),
            resolved.permissionMounts,
        )
    }
}
