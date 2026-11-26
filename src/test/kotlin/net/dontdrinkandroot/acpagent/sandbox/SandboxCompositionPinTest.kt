package net.dontdrinkandroot.acpagent.sandbox

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `docker run` composition pin: every [SandboxPins] row is resolved (fixture host facts,
 * [FixtureProbes] - no I/O on the real host) and its composed argv is checked. The rows were
 * characterized against the bash launcher the supervisor replaced, so the table encodes
 * "behaviour-identical", not "whatever the composer happens to emit".
 */
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
