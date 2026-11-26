package net.dontdrinkandroot.acpagent.e2e

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.dontdrinkandroot.acpagent.config.Config

/**
 * The process-level startup contract (issue #84): the agent's argument parser is strict, and
 * `--profile <name>` is the only accepted input - a mistyped flag must never fall back to the
 * default profile, and a selected profile is really the file that gets read.
 *
 * These cases cannot go through the ACP client (the process exits before `initialize`), so
 * they launch the installed binary directly and inspect its exit code and stderr.
 */
class E2eStartupArgumentsTest {

    private val binaryPath: String
        get() = System.getProperty("acp.agent.binary") ?: error("acp.agent.binary system property not set")

    private fun runAgent(args: List<String>, configHome: File? = null): Pair<Int, String> {
        val process = ProcessBuilder(listOf(binaryPath) + args)
            .apply {
                environment().keys.removeIf { it.startsWith("DDR_ACP_AGENT_") }
                if (configHome != null) environment()["XDG_CONFIG_HOME"] = configHome.absolutePath
            }
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the agent did not exit")
        return process.exitValue() to output
    }

    @Test
    fun `an unknown argument fails loudly instead of running on the default profile`() {
        val (exit, output) = runAgent(listOf("--profil", "work"))
        assertEquals(2, exit, "a mistyped argument must refuse startup: $output")
        assertTrue(
            output.contains("acp-agent: unknown argument '--profil'"),
            "the error must name the offending argument: $output",
        )
        assertTrue(output.contains("only --profile <name>"), "the error must state the accepted form: $output")
    }

    @Test
    fun `the removed launcher flag is not special-cased`() {
        val (exit, output) = runAgent(listOf("--skip-pull"))
        assertEquals(2, exit, "the removed flag must fail the strict parser: $output")
        assertTrue(output.contains("unknown argument '--skip-pull'"), "the error must name the flag: $output")
    }

    @Test
    fun `a selected profile is the file that gets read`() {
        val configHome = Files.createTempDirectory("acp-e2e-profile-home").toFile().apply { deleteOnExit() }
        val configDir = configHome.resolve(Config.APP_CONFIG_DIR).apply { mkdirs() }
        val keyFile = configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME)
        keyFile.writeText("sk-test\n")
        Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        // The default file is valid; the selected profile is broken, so only reading the
        // profile can produce this failure - a silent fallback to the default would start.
        configDir.resolve(Config.CONFIG_FILE_NAME).writeText("""{"tools": {"maxTurnRequests": 7}}""")
        configDir.resolve("work.json").writeText("""{"tools": {"maxTurnRequests": 0}}""")

        val (exit, output) = runAgent(listOf("--profile", "work"), configHome)
        assertTrue(exit != 0, "the broken selected profile must fail startup, was exit $exit")
        assertTrue(
            output.contains("work.json") && output.contains("tools.maxTurnRequests must be at least 1"),
            "the failure must name the selected profile and the broken key: $output",
        )
    }
}
