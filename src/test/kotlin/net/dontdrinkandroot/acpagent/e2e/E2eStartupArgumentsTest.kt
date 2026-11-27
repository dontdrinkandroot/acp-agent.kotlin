package net.dontdrinkandroot.acpagent.e2e

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.spec.Pins

/**
 * The process-level startup contract (issue #84): the agent's argument parser is strict, and
 * `--profile <name>` is the only accepted input - a mistyped flag must never fall back to the
 * default profile, and a selected profile is really the file that gets read.
 *
 * These cases cannot go through the ACP client (the process exits before `initialize`), so
 * they launch the installed binary directly and inspect its exit code and stderr.
 */
@Pins("agent-process", "Startup arguments are strict")
@Pins("configuration", "A profile selects another configuration file")
@Pins("docker-sandbox", "The sandbox has its own entry point")
class E2eStartupArgumentsTest {

    private val binaryPath: String
        get() = System.getProperty("acp.agent.binary") ?: error("acp.agent.binary system property not set")

    /**
     * Runs the installed agent and returns its exit code plus the merged stderr/stdout.
     * [workingDir] is the launch directory - the project overlay is read from it, and the
     * process exits before `initialize`, so these cases cannot go through the ACP client.
     */
    private fun runAgent(args: List<String>, configHome: File? = null, workingDir: File? = null): Pair<Int, String> {
        val process = ProcessBuilder(listOf(binaryPath) + args)
            .apply {
                workingDir?.let { directory(it) }
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

    /**
     * An isolated config home with a valid key file and `config.json`, so a scenario's
     * failure can only come from what the scenario itself sets - never from the ambient
     * configuration of the machine running the tests.
     */
    private fun isolatedConfigHome(configJson: String = "{}"): File {
        val configHome = Files.createTempDirectory("acp-e2e-startup-home").toFile().apply { deleteOnExit() }
        val configDir = configHome.resolve(Config.APP_CONFIG_DIR).apply { mkdirs() }
        val keyFile = configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME)
        keyFile.writeText("sk-test\n")
        Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        configDir.resolve(Config.CONFIG_FILE_NAME).writeText(configJson)
        return configHome
    }

    @Test
    fun `a selected profile is the file that gets read`() {
        val configHome = isolatedConfigHome("""{"tools": {"maxTurnRequests": 7}}""")
        // The default file is valid; the selected profile is broken, so only reading the
        // profile can produce this failure - a silent fallback to the default would start.
        configHome.resolve(Config.APP_CONFIG_DIR).resolve("work.json")
            .writeText("""{"tools": {"maxTurnRequests": 0}}""")

        val (exit, output) = runAgent(listOf("--profile", "work"), configHome)
        assertEquals(2, exit, "a broken config file is a startup input error, not a crash: $output")
        assertTrue(
            output.contains("work.json") && output.contains("tools.maxTurnRequests must be at least 1"),
            "the failure must name the selected profile and the broken key: $output",
        )
    }

    @Test
    fun `a broken project overlay aborts startup with a one-line error`() {
        // Issue #85: the overlay is a checked-in startup input, so a broken one must refuse the
        // start with the file and JSON path named - never a stack trace, never a silent run
        // with fewer permissions. The config home is isolated and valid, so the overlay is the
        // only thing that can fail.
        val projectDir = Files.createTempDirectory("acp-e2e-overlay-broken").toFile().apply { deleteOnExit() }
        val overlay = projectDir.resolve(".ai").apply { mkdirs() }.resolve("ddr-acp-agent.json")
        overlay.writeText("""{"permissions": {"readableDirectories": ["relative/path"]}}""")

        val (exit, output) = runAgent(emptyList(), configHome = isolatedConfigHome(), workingDir = projectDir)
        assertEquals(2, exit, "a broken overlay must refuse startup: $output")
        assertTrue(
            output.contains("acp-agent: project file '$overlay': permissions.readableDirectories entries " +
                    "must be absolute paths, got 'relative/path'"),
            "the failure must name the file and the JSON path in one line: $output",
        )
        assertFalse(
            output.contains("at net.dontdrinkandroot"),
            "the error must not surface a stack trace: $output",
        )
    }
}
