package net.dontdrinkandroot.acpagent.spec

/**
 * Declares that this test class covers the named spec requirement - the test-side author of the
 * pin index, mirroring a `- **Pinned by**` line in `openspec/specs/<capability>/spec.md`.
 *
 * [SpecPinsTest] resolves both directions, so a typo, a renamed requirement or a requirement
 * that lost its last pin fails the build instead of leaving a stale claim in the spec corpus.
 *
 * A pin states intent, never strength: it says the class is *about* the requirement, not that
 * its assertions prove every scenario of it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Pins(val capability: String, val requirement: String)
