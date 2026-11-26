package net.dontdrinkandroot.acpagent.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The supervisor's process-level contract, exercised through the **installed** entry point
 * (`acp.agent.sandbox`) with the fixture docker on `PATH`: this exec surface (byte-transparent
 * relay, stdout/stderr separation, stdin-EOF half-close, signal forwarding, exit-code
 * propagation, fail-loudly paths) cannot be unit-tested.
 */
class SandboxSupervisorTest {

    private val sandboxBinary: String
        get() = System.getProperty("acp.agent.sandbox") ?: error("acp.agent.sandbox system property not set")

    private val javaBinary: String
        get() = ProcessHandle.current().info().command()
            .orElseGet { File(System.getProperty("java.home"), "bin/java").absolutePath }

    private val testClasspath: String
        get() = System.getProperty("acp.test.classpath") ?: error("acp.test.classpath system property not set")

    private class Fixture(
        val workspace: Path,
        val docker: FakeDocker,
        val log: Path,
        val configHome: Path,
    )

    private fun fixture(prefix: String, configJson: String? = null): Fixture {
        val workspace = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "sandbox-supervisor-$prefix-")
        val docker = FakeDocker.install(workspace.resolve("fixture-docker"))
        val configHome = workspace.resolve("config-home").also { Files.createDirectories(it) }
        val configDir = configHome.resolve("ddr-acp-agent").also { Files.createDirectories(it) }
        if (configJson != null) configDir.resolve("config.json").toFile().writeText(configJson)
        return Fixture(workspace, docker, workspace.resolve("docker.log"), configHome)
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(
        fixture: Fixture,
        args: List<String> = emptyList(),
        stdin: String = "",
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
    ): Run {
        val project = fixture.workspace.resolve("project").also { Files.createDirectories(it) }
        val process = ProcessBuilder(listOf(sandboxBinary) + args)
            .directory(project.toFile())
            .also { builder ->
                val env = builder.environment()
                env.keys.removeIf { it.startsWith("DDR_ACP_AGENT_") }
                env["XDG_CONFIG_HOME"] = fixture.configHome.toString()
                env["XDG_STATE_HOME"] = fixture.workspace.resolve("state").toString()
                // On Linux the JVM's user.home follows HOME, so this moves the supervisor's own
                // home too (its cache candidates are empty); the XDG roots above are pinned
                // explicitly for isolation.
                env["HOME"] = fixture.workspace.resolve("home").toString()
                env["PATH"] = "${fixture.docker.binary.parent}:${System.getenv("PATH")}"
                env["FAKE_DOCKER_LOG"] = fixture.log.toString()
                env["GIT_CONFIG_GLOBAL"] = "/dev/null"
                extraEnv.forEach { (key, value) -> env[key] = value }
            }
            .start()
        try {
            process.outputStream.use { it.write(stdin.toByteArray()); it.flush() }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "the supervisor did not exit")
            return Run(process.exitValue(), stdout, stderr)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `the relay is byte-transparent and keeps the streams apart`() {
        val fixture = fixture("relay")
        try {
            val run = run(fixture, stdin = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""" + "\n")
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            assertEquals(
                """{"jsonrpc":"2.0","id":1,"method":"initialize"}""" + "\n",
                run.stdout,
                "stdout must carry exactly the relayed bytes - every supervisor byte goes to stderr",
            )
            assertEquals(1, FakeDocker.runInvocations(fixture.log).size, "expected exactly one docker run")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stdin eof half-closes and the supervisor waits for the child`() {
        val fixture = fixture("half-close")
        try {
            // The fixture sleeps after EOF, then answers: killing the child on EOF would lose
            // the "after-eof" line and/or the exit 0.
            val run = run(fixture, stdin = "hello\n", extraEnv = mapOf("FAKE_DOCKER_RUN_AFTER_EOF_SLEEP" to "0.3"))
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            assertEquals("hello\nafter-eof\n", run.stdout)
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the child exit code is propagated`() {
        val fixture = fixture("exit-code")
        try {
            val run = run(fixture, extraEnv = mapOf("FAKE_DOCKER_RUN_EXIT" to "7"))
            assertEquals(7, run.exitCode)
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `SIGTERM is forwarded to the container process`() {
        val fixture = fixture("signal")
        try {
            val marker = fixture.workspace.resolve("term-marker")
            val project = fixture.workspace.resolve("project").also { Files.createDirectories(it) }
            val process = ProcessBuilder(listOf(sandboxBinary))
                .directory(project.toFile())
                .redirectOutput(fixture.workspace.resolve("stdout.txt").toFile())
                .redirectError(fixture.workspace.resolve("stderr.txt").toFile())
                .also { builder ->
                    val env = builder.environment()
                    env["XDG_CONFIG_HOME"] = fixture.configHome.toString()
                    env["XDG_STATE_HOME"] = fixture.workspace.resolve("state").toString()
                    env["HOME"] = fixture.workspace.resolve("home").toString()
                    env["PATH"] = "${fixture.docker.binary.parent}:${System.getenv("PATH")}"
                    env["FAKE_DOCKER_LOG"] = fixture.log.toString()
                    env["FAKE_DOCKER_TERM_MARKER"] = marker.toString()
                }
                .start()
            try {
                // Keep the supervisor's stdin open (a ping line, never closed): the fixture
                // must still be blocked when the signal arrives.
                process.outputStream.write("ping\n".toByteArray())
                process.outputStream.flush()
                // Wait for the fixture's "ready" line (written once its SIGTERM trap is
                // installed, so the signal cannot race the trap), then send SIGTERM.
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    if (runCatching { marker.toFile().readText() }.getOrDefault("").contains("ready")) break
                    Thread.sleep(20)
                }
                assertTrue(
                    runCatching { marker.toFile().readText() }.getOrDefault("").contains("ready"),
                    "the fixture docker never became ready; stderr: " +
                            fixture.workspace.resolve("stderr.txt").toFile().readText(),
                )
                process.destroy()
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the supervisor did not exit after SIGTERM")
                assertEquals(
                    listOf("ready", "term"),
                    marker.toFile().readLines(),
                    "the supervisor must forward SIGTERM to the docker process",
                )
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the env local mask file is removed after the run`() {
        val fixture = fixture("mask")
        try {
            val project = fixture.workspace.resolve("project").also { Files.createDirectories(it) }
            project.resolve(".env.local").toFile().writeText("SECRET=1\n")
            val run = run(fixture, stdin = "")
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            val masks = FakeDocker.runInvocations(fixture.log).single()
                .filter { it.startsWith("type=bind,src=") && it.contains("dst=${project}/.env.local,ro") }
            assertEquals(1, masks.size, "expected exactly one .env.local mask mount, argv: $masks")
            val maskSource = masks.single().removePrefix("type=bind,src=").substringBefore(',')
            assertTrue(!Files.exists(Path.of(maskSource)), "the mask temp file must be removed on exit")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing docker CLI fails with 127 and a named message`() {
        val fixture = fixture("no-docker")
        try {
            val emptyPath = fixture.workspace.resolve("empty-path").also { Files.createDirectories(it) }
            // No docker anywhere on PATH: the supervisor must fail loudly instead of surfacing
            // a raw IOException. The JVM start script needs uname/xargs from PATH, so an empty
            // PATH cannot even start it - run the main class directly (the subject here is the
            // docker lookup).
            val process = ProcessBuilder(
                javaBinary,
                "-cp",
                testClasspath,
                "net.dontdrinkandroot.acpagent.sandbox.SandboxMainKt",
            )
                .directory(fixture.workspace.toFile())
                .also { builder ->
                    val env = builder.environment()
                    env["PATH"] = emptyPath.toString()
                    env["XDG_CONFIG_HOME"] = fixture.configHome.toString()
                    env["HOME"] = fixture.workspace.resolve("home").toString()
                }
                .start()
            try {
                process.outputStream.close()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the supervisor did not exit")
                assertEquals(127, process.exitValue())
                assertTrue(
                    stderr.contains("docker CLI not found on PATH"),
                    "expected a named 127 error, stderr: $stderr",
                )
                assertEquals("", stdout, "failures must not write to stdout")
                assertTrue(!Files.exists(fixture.log), "a refused startup must never compose a docker run")
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an unknown argument fails with 2 and names it`() {
        val fixture = fixture("args")
        try {
            val run = run(fixture, args = listOf("--skip-pull"))
            assertEquals(2, run.exitCode)
            assertTrue(run.stderr.contains("unknown argument '--skip-pull'"), "stderr: ${run.stderr}")
            assertTrue(!Files.exists(fixture.log), "a refused startup must never compose a docker run")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an invalid config fails loudly before any docker run`() {
        val fixture = fixture("bad-config", configJson = """{"sandbox": {"mountCaches": "nope"}}""")
        try {
            val run = run(fixture)
            assertEquals(1, run.exitCode)
            assertTrue(
                run.stderr.contains("sandbox.mountCaches must be a boolean"),
                "expected the config error naming the JSON path, stderr: ${run.stderr}",
            )
            assertTrue(!Files.exists(fixture.log), "a refused startup must never compose a docker run")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the profile is forwarded to the containerized agent`() {
        val fixture = fixture("profile")
        try {
            Files.createDirectories(fixture.configHome.resolve("ddr-acp-agent"))
            fixture.configHome.resolve("ddr-acp-agent/work.json").toFile().writeText("{}")
            val run = run(fixture, args = listOf("--profile", "work"))
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            val argv = FakeDocker.runInvocations(fixture.log).single()
            assertEquals(listOf(SANDBOX_IMAGE, "--profile", "work"), argv.takeLast(3))
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the profile also drives the supervisor's own composition`() {
        // The flag is parsed on both sides: the supervisor must read work.json for its mounts,
        // not the default file it sits next to (a sibling, never a merge base).
        val fixture = fixture(
            "profile-config",
            configJson = """{"sandbox": {"network": "development"}}""",
        )
        try {
            fixture.configHome.resolve("ddr-acp-agent/work.json").toFile()
                .writeText("""{"sandbox": {"capAdd": ["SYS_PTRACE"]}}""")
            val run = run(fixture, args = listOf("--profile", "work"))
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            val argv = FakeDocker.runInvocations(fixture.log).single()
            assertTrue(argv.contains("SYS_PTRACE"), "expected the profile's capability: $argv")
            assertTrue(!argv.contains("development"), "the default file must not leak in: $argv")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plan-level refusal happens before any docker run`() {
        val fixture = fixture(
            "missing-dir",
            configJson = """{"permissions": {"readableDirectories": ["/does/not/exist"]}}""",
        )
        try {
            val run = run(fixture)
            assertEquals(1, run.exitCode)
            assertTrue(
                run.stderr.contains("'/does/not/exist' does not exist on the host"),
                "expected the plan's refusal naming the path, stderr: ${run.stderr}",
            )
            assertTrue(!Files.exists(fixture.log), "a refused startup must never compose a docker run")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the mapped uid and gid equal the host ids`() {
        val fixture = fixture("uid")
        try {
            val run = run(fixture)
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            val argv = FakeDocker.runInvocations(fixture.log).single()
            val userIndex = argv.indexOf("--user")
            assertTrue(userIndex >= 0, "expected --user in $argv")
            val hostIds = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim() +
                    ":" + ProcessBuilder("id", "-g").start().inputStream.bufferedReader().readText().trim()
            assertEquals(hostIds, argv[userIndex + 1], "the mapped ids must match id -u/id -g")
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sandbox config drives the composition`() {
        val fixture = fixture(
            "settings",
            configJson = """{"sandbox": {"network": "development", "capAdd": ["SYS_PTRACE"], "rwRootfs": true}}""",
        )
        try {
            val stateDir = fixture.workspace.resolve("state").resolve("ddr-acp-agent")
            val run = run(fixture)
            assertEquals(0, run.exitCode, "stderr: ${run.stderr}")
            val argv = FakeDocker.runInvocations(fixture.log).single()
            assertTrue(argv.contains("development"), "expected the configured network: $argv")
            assertTrue(argv.contains("SYS_PTRACE"), "expected the configured capability: $argv")
            assertTrue(!argv.contains("--read-only"), "rwRootfs must relax the read-only rootfs")
            assertTrue(
                argv.contains("type=bind,src=$stateDir,dst=$CONTAINER_STATE_DIR"),
                "the default state dir must resolve from XDG_STATE_HOME: $argv",
            )
        } finally {
            fixture.workspace.toFile().deleteRecursively()
        }
    }
}
