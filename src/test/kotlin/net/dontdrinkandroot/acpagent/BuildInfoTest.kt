package net.dontdrinkandroot.acpagent

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {

    @Test
    fun `commit is a short hash, optionally dirty, or unknown`() {
        val pattern = Regex("^(unknown|[0-9a-f]{7,40}(-dirty)?)$")
        assertTrue(
            pattern.matches(BuildInfo.commit),
            "expected a git commit hash (optionally -dirty) or 'unknown', got '${BuildInfo.commit}'",
        )
    }
}
