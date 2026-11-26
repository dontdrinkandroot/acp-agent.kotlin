package net.dontdrinkandroot.acpagent.sandbox

import net.dontdrinkandroot.acpagent.config.parseAgentArguments
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The sandbox supervisor: resolves the plan, composes `docker run`, and relays ACP over the
 * container's stdio **untouched** - it never parses, re-emits or inspects protocol traffic,
 * and every byte it produces itself goes to stderr. Neither the agent nor the IDE knows it is
 * there.
 *
 * Lifecycle: stdin EOF half-closes the container's stdin (never kills it), so the agent's
 * shutdown flush can finish; SIGTERM/SIGINT are forwarded; `--rm` reaps the container; the
 * mask temp file is removed; the agent's exit code is propagated.
 */
public fun main(args: Array<String>) {
    val exitCode = runSandbox(args.toList(), System.getenv(), Path.of("").toAbsolutePath())
    exitProcess(exitCode)
}

/**
 * Runs one sandboxed agent session. Split from [main] so the wiring is testable through the
 * process boundary without inheriting the test JVM's environment.
 */
internal fun runSandbox(args: List<String>, env: Map<String, String>, cwd: Path): Int {
    val arguments = try {
        parseAgentArguments(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("acp-agent-sandbox: ${e.message}")
        return 2
    }

    // The docker CLI is resolved from PATH (no DOCKER_BIN seam, no absolute-path ladder): an
    // IDE-inherited PATH without docker is a hard, named failure.
    val dockerBinary = resolveDockerBinary(env)
    if (dockerBinary == null) {
        System.err.println("acp-agent-sandbox: docker CLI not found on PATH")
        return 127
    }

    val home = Path.of(System.getProperty("user.home"))
    // The mask is only needed when the project actually has a .env.local: creating a temp file
    // on every start would fail spuriously on a host without a writable tmpdir.
    val envLocalExists = Files.isRegularFile(cwd.resolve(".env.local"))
    val maskFile = if (envLocalExists) {
        runCatching { Files.createTempFile("acp-agent-env-local-mask", ".empty") }
            .getOrElse { failure ->
                System.err.println("acp-agent-sandbox: cannot create the .env.local mask file: ${failure.message}")
                return 1
            }
    } else {
        null
    }

    try {
        val spec = loadSandboxSpec(env, home, cwd, arguments, maskFile?.toString(), args)
        val plan = resolveSandboxPlan(spec)
        plan.warnings.forEach { System.err.println("acp-agent-sandbox: ${it.message}") }
        return try {
            relay(dockerBinary, composeDockerInvocation(plan))
        } catch (e: IOException) {
            System.err.println("acp-agent-sandbox: cannot run '$dockerBinary': ${e.message}")
            1
        }
    } catch (e: SandboxException) {
        System.err.println("acp-agent-sandbox: ${e.message}")
        return 1
    } finally {
        maskFile?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

/** `docker` from `PATH` (first executable regular file), or null. */
internal fun resolveDockerBinary(env: Map<String, String>): String? =
    env["PATH"].orEmpty()
        .split(':')
        .filter { it.isNotEmpty() }
        .map { Path.of(it, "docker") }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?.toString()

/**
 * Starts `docker` with [argv] and relays: child's stdout to ours, child's stderr to ours
 * (never merged), stdin until EOF and then a **half-close**. The child's exit code is
 * returned; a TERM/INT hook forwards the signal instead of leaving the container orphaned.
 */
private fun relay(dockerBinary: String, argv: List<String>): Int {
    val process = ProcessBuilder(listOf(dockerBinary) + argv)
        .redirectErrorStream(false)
        .start()

    val shutdownHook = Thread {
        // Forward the signal, but never let a TERM-ignoring child hold the JVM hostage.
        runCatching { process.destroy() }
        if (runCatching { process.waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false).not()) {
            runCatching { process.destroyForcibly() }
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    pump("sandbox-stdin") {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = System.`in`.read(buffer)
                if (read < 0) break
                process.outputStream.write(buffer, 0, read)
                process.outputStream.flush()
            }
            // Half-close only: the agent still has to flush its session records and answer, so the
            // container must be allowed to finish on its own.
            process.outputStream.close()
        } catch (_: IOException) {
            // The child is gone (docker refused to start, or the container exited first): there
            // is nothing left to forward. Without this, the daemon thread's uncaught exception
            // would only add a broken-pipe stack trace to the stderr log.
        }
    }
    val stdoutPump = pump("sandbox-stdout") { process.inputStream.copyTo(System.out, 8192); System.out.flush() }
    val stderrPump = pump("sandbox-stderr") { process.errorStream.copyTo(System.err, 8192); System.err.flush() }

    // The child is gone once waitFor returns; the stdout/stderr pumps then see EOF and finish.
    // The stdin pump stays blocked on System.in (interrupting a thread in a stream read does
    // not unblock it) - it is a daemon thread and dies with the JVM, like the old `exec`.
    val exitCode = process.waitFor()
    stdoutPump.join()
    stderrPump.join()
    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    return exitCode
}

private fun pump(name: String, body: () -> Unit): Thread =
    Thread(body, name).apply {
        isDaemon = true
        start()
    }
