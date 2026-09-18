package net.dontdrinkandroot.acpagent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAccessExclusionsTest {

    @Test
    fun `default policy matches env local variants`() {
        val exclusions = FileAccessExclusions.DEFAULT
        assertEquals(".env*.local", exclusions.matchingRule(".env.local"))
        assertEquals(".env*.local", exclusions.matchingRule(".env.development.local"))
        assertEquals(".env*.local", exclusions.matchingRule("config/.env.local"))
        assertEquals(".env*.local", exclusions.matchingRule("a/b/c/.env.production.local"))
    }

    @Test
    fun `default policy does not overmatch`() {
        val exclusions = FileAccessExclusions.DEFAULT
        assertNull(exclusions.matchingRule(".env"))
        assertNull(exclusions.matchingRule(".env.example"))
        assertNull(exclusions.matchingRule(".envlocal"))
        assertNull(exclusions.matchingRule("foo.env.local"))
        assertNull(exclusions.matchingRule(".ENV.LOCAL"))
        assertNull(exclusions.matchingRule("env.local"))
        assertNull(exclusions.matchingRule("notes.txt"))
    }

    @Test
    fun `empty policy matches nothing`() {
        assertNull(FileAccessExclusions.EMPTY.matchingRule(".env.local"))
        assertNull(FileAccessExclusions.EMPTY.matchingRule("anything/at/all"))
    }

    @Test
    fun `custom bare-name rule matches at any depth`() {
        val exclusions = FileAccessExclusions.of(listOf("secrets.env"))
        assertEquals("secrets.env", exclusions.matchingRule("secrets.env"))
        assertEquals("secrets.env", exclusions.matchingRule("deep/nested/secrets.env"))
        assertNull(exclusions.matchingRule("src/secret.env"))
    }

    @Test
    fun `custom rooted rule matches from the cwd only`() {
        val exclusions = FileAccessExclusions.of(listOf("secrets/**"))
        assertEquals("secrets/**", exclusions.matchingRule("secrets/api/key.txt"))
        assertEquals("secrets/**", exclusions.matchingRule("secrets/key.txt"))
        assertNull(exclusions.matchingRule("src/secrets/api/key.txt"))
    }

    @Test
    fun `rooted rule does not reach outside the cwd`() {
        val cwd = "/tmp/excl-test-cwd"
        val rooted = FileAccessExclusions.of(listOf("secrets/**"))
        assertNull(rooted.matchingRuleForPath(cwd, "/elsewhere/secrets/api/key.txt"))
        // Basename fallback outside the cwd: only bare-name rules can match.
        assertNull(rooted.matchingRuleForPath(cwd, "/trusted-mount/secrets.env"))

        val bare = FileAccessExclusions.of(listOf("secrets.env"))
        assertEquals("secrets.env", bare.matchingRuleForPath(cwd, "/trusted-mount/secrets.env"))
        assertEquals(
            "secrets/**",
            rooted.matchingRuleForPath(cwd, "$cwd/secrets/api/key.txt"),
        )
    }

    @Test
    fun `matchingRuleForTarget resolves symlink aliases`() {
        val dir = java.nio.file.Files.createTempDirectory("excl-target").toFile()
        val target = java.io.File(dir, ".env.local").apply { writeText("SECRET=1") }
        val link = java.io.File(dir, "alias.env.local")
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of(link.absolutePath),
            java.nio.file.Path.of(target.absolutePath),
        )
        try {
            val exclusions = FileAccessExclusions.DEFAULT
            // Direct name match.
            assertEquals(
                ".env*.local",
                exclusions.matchingRuleForTarget(dir.absolutePath, target.absolutePath),
            )
            // Alias link: literal name does not carry the dot prefix, but the
            // resolved target does.
            assertEquals(
                ".env*.local",
                exclusions.matchingRuleForTarget(dir.absolutePath, link.absolutePath),
            )
        } finally {
            link.delete()
            target.delete()
            dir.delete()
        }
    }

    @Test
    fun `error message names the matched rule`() {
        val message = exclusionError("/tmp/.env.local", ".env*.local")
        assertTrue(message.contains("excluded from tool access"), message)
        assertTrue(message.contains("exclusion rule '.env*.local'"), message)
    }
}
