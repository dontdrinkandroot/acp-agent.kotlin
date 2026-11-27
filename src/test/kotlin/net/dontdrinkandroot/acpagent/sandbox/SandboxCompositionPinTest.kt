package net.dontdrinkandroot.acpagent.sandbox

import kotlin.test.Test
import kotlin.test.assertTrue
import net.dontdrinkandroot.acpagent.spec.Pins

/**
 * The `docker run` composition pin: every [SandboxPins] row is resolved (fixture host facts,
 * [FixtureProbes] - no I/O on the real host) and its composed argv is checked. The rows were
 * characterized against the bash launcher the supervisor replaced, so the table encodes
 * "behaviour-identical", not "whatever the composer happens to emit".
 */
@Pins("docker-sandbox", "Gradle shares only its caches")
@Pins("docker-sandbox", "Host networking is the default")
@Pins("docker-sandbox", "Host tool caches are shared at their own paths")
@Pins("docker-sandbox", "Only host facts, never agent configuration, are forwarded")
@Pins("docker-sandbox", "Session state and configuration are shared the same way the agent reads them")
@Pins("docker-sandbox", "The Android SDK is mounted only when it is real")
@Pins("docker-sandbox", "The container home is a private overlay")
@Pins("docker-sandbox", "The container is hardened")
@Pins("docker-sandbox", "The permission lists are the mount list")
@Pins("docker-sandbox", "The project is mounted at its identical path")
@Pins("docker-sandbox", "The sandbox has its own entry point")
class SandboxCompositionPinTest {

    @Test
    fun `the composed docker run argv matches every pinned row`() {
        val failures = SandboxPins.all.mapNotNull { pin ->
            runCatching {
                pin.checks(
                    ArgvExpectations(
                        composeDockerInvocation(
                            resolveSandboxPlan(pin.spec, FixtureProbes(pin.fixtureDirectories, pin.fixtureFiles)),
                        ),
                    ),
                )
            }.exceptionOrNull()?.let { "${pin.name}:\n  ${it.message}" }
        }
        assertTrue(
            failures.isEmpty(),
            "composed argv deviates from the pin table:\n${failures.joinToString("\n")}",
        )
    }

    @Test
    fun `every row is named and the table keeps its size`() {
        val names = SandboxPins.all.map { it.name }
        assertTrue(names.size == names.distinct().size, "duplicate pin names: $names")
        // A floor, not an exact count: deleting a row must not silently shrink the coverage
        // (the coverage invariant in SandboxPins' KDoc names what each row stands for).
        assertTrue(names.size >= 49, "expected the full ported table, found ${names.size} rows")
    }
}
