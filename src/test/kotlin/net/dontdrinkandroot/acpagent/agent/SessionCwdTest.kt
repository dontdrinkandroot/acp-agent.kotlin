package net.dontdrinkandroot.acpagent.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionCwdTest {

    @Test
    fun `an existing directory is a usable session cwd`() {
        val dir = Files.createTempDirectory("acp-agent-session-cwd")

        assertNull(sessionCwdProblem(dir.toString()))
    }

    @Test
    fun `a relative cwd is rejected`() {
        assertEquals(
            "cwd \"project\" must be an absolute path",
            sessionCwdProblem("project"),
        )
    }

    @Test
    fun `a missing cwd is rejected`() {
        val missing = Files.createTempDirectory("acp-agent-session-cwd").resolve("gone").toFile().absolutePath

        assertEquals(
            "cwd \"$missing\" does not exist",
            sessionCwdProblem(missing),
        )
    }

    @Test
    fun `a file is not a usable session cwd`() {
        val file = Files.createTempFile("acp-agent-session-cwd", ".txt").toFile()

        assertEquals(
            "cwd \"${file.absolutePath}\" is not a directory",
            sessionCwdProblem(file.absolutePath),
        )
    }

    @Test
    fun `an unparseable cwd is rejected instead of throwing`() {
        val problem = sessionCwdProblem("/tmp/nul\u0000byte")

        assertEquals("invalid cwd \"/tmp/nul\u0000byte\": Nul character not allowed", problem)
    }
}
