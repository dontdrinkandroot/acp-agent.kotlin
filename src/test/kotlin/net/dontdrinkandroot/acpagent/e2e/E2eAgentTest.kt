package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.config.APP_STATE_DIR
import net.dontdrinkandroot.acpagent.config.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The variable the harness plants in the agent's own environment to pin the MCP child env
 * allowlist (issue #14): no agent variable carries a secret any more (the config is
 * file-only), so a harness-owned sentinel is the thing that must not be inherited. Also read
 * by [StdioMcpMockServer], which runs in a separate JVM.
 */
internal const val AGENT_ENV_SENTINEL: String = "ACP_E2E_AGENT_SENTINEL"

/**
 * Shared harness for the black-box e2e scenarios: drives the linked installDist
 * launcher as a separate OS process with the REAL `com.agentclientprotocol:acp`
 * client over a real stdio transport, with a local mock LLM server standing in
 * for OpenRouter.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
abstract class E2eAgentTest {

    internal val binaryPath: String
        get() = System.getProperty("acp.agent.binary") ?: error("acp.agent.binary system property not set")

    /**
     * The project's test runtime classpath, injected by `build.gradle.kts`
     * (`-Dacp.test.classpath`). Needed to launch the in-tree stdio MCP mock as a
     * separate JVM: the Gradle test worker's `java.class.path` is not the test
     * classpath, so the harness cannot derive it itself.
     */
    internal val testClasspath: String
        get() = System.getProperty("acp.test.classpath") ?: error("acp.test.classpath system property not set")

    /** The JVM running the tests; used to launch the stdio MCP mock. */
    internal val javaBinary: String
        get() = ProcessHandle.current().info().command()
            .orElseGet { File(System.getProperty("java.home"), "bin/java").absolutePath }

    internal class AgentConnection(
        val process: Process,
        val client: Client,
        private val scope: CoroutineScope,
        /** Every raw stdout line the agent emitted (ACP NDJSON), in arrival order. */
        val wireLines: List<String> = Collections.synchronizedList(mutableListOf()),
    ) {
        suspend fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            scope.cancel()
        }
    }

    internal inner class E2eContext(
        val projectDir: File,
        val stateDir: Path,
        val llmMock: MockOpenAiServer,
    ) {
        val stateHome: File get() = stateDir.toFile()
        val sessionsStoreDir: Path = stateDir.resolve(APP_STATE_DIR).resolve("sessions")

        /**
         * Starts an agent against this context. [configJson] is a JSON object *fragment*
         * deep-merged over the harness base config (LLM endpoint + model), written into the
         * isolated config directory - the agent is configured exclusively through that file
         * (issue #84: no agent settings come from the environment any more).
         *
         * [workingDir] is the agent process's launch directory and defaults to the project
         * dir, like an IDE launching the agent in the project it opened. It matters beyond
         * process hygiene: the project permission overlay is read from it (issue #85), so a
         * default of "wherever the test JVM happens to run" would let this repository's own
         * `.ai/ddr-acp-agent.json` leak into every scenario.
         */
        suspend fun connect(configJson: String? = null, workingDir: File? = projectDir): AgentConnection =
            startConnectedAgent(llmMock.port, stateHome, configJson, workingDir)
    }

    /**
     * Creates the session project dir + XDG state dir, starts the mock LLM server,
     * runs [block] and tears everything down afterwards.
     */
    internal suspend fun withE2eAgent(
        prefix: String,
        llmMock: MockOpenAiServer,
        block: suspend E2eContext.() -> Unit,
    ) {
        withE2eAgent(prefix, { llmMock }, block)
    }

    /**
     * Same as above, but the mock is created by [createMock] once the project dir
     * exists (e.g. to place fixture files inside the session cwd).
     */
    internal suspend fun withE2eAgent(
        prefix: String,
        createMock: (projectDir: File) -> MockOpenAiServer,
        block: suspend E2eContext.() -> Unit,
    ) {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-$prefix").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-$prefix-state")
        val llmMock = createMock(projectDir)
        llmMock.start()
        try {
            E2eContext(projectDir, stateDir, llmMock).block()
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    internal fun startAgent(
        llmPort: Int,
        xdgStateHome: File? = null,
        /**
         * A JSON object fragment deep-merged over the harness base config, e.g.
         * `{"tools": {"maxTurnRequests": 3}}`. The agent reads its settings from
         * `config.json` only (issue #84).
         */
        configJson: String? = null,
        /**
         * The agent process's own working directory. Scenarios that pin a
         * session-scoped child process (stdio MCP servers, issue #74) must set
         * this to a directory other than the session `cwd`, otherwise the
         * assertion holds trivially.
         */
        workingDir: File? = null,
    ): Process {
        // An isolated config home: no host config.json, no host key may leak in. The agent
        // resolves `$XDG_CONFIG_HOME/ddr-acp-agent`; the key sits at its default location
        // there as a 0600 file (issue #50). One directory per launched agent, removed at JVM
        // exit (a scenario restarts the agent, so each start gets its own).
        val configHome = Files.createTempDirectory("acp-e2e-config").toFile()
        val configDir = configHome.resolve(Config.APP_CONFIG_DIR).apply { mkdirs() }
        val keyFile = configDir.resolve(Config.DEFAULT_API_KEY_FILE_NAME)
        keyFile.writeText("sk-test-phase4\n")
        Files.setPosixFilePermissions(
            keyFile.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
        configDir.resolve(Config.CONFIG_FILE_NAME).writeText(
            harnessConfig(llmPort, configJson?.let { Json.parseToJsonElement(it).jsonObject })
        )
        configHome.deleteOnExitRecursively()
        return ProcessBuilder(binaryPath)
            .apply {
                workingDir?.let { directory(it) }
                // The agent's settings are file-only; the family is stripped so a leaked
                // ambient value cannot masquerade as configuration (it is inert anyway).
                environment().keys.removeIf { it.startsWith("DDR_ACP_AGENT_") }
                environment()["XDG_CONFIG_HOME"] = configHome.absolutePath
                if (xdgStateHome != null) environment()["XDG_STATE_HOME"] = xdgStateHome.absolutePath
                environment()[AGENT_ENV_SENTINEL] = "secret-sentinel"
                // A leaked key location would fail Config.load loudly at agent startup, hanging the
                // client-side initialize.
                environment().remove("OPENROUTER_API_KEY")
                environment().remove("OPENROUTER_API_KEY_FILE")
                environment().remove("OPENROUTER_BASE_URL")
                environment().remove("OPENROUTER_MODEL")
                // Any ambient legacy rename leftovers must not affect the layering.
                listOf(
                    "FS_PROXY_ENABLED", "MCP_TRUST_ANNOTATIONS", "ACP_BASH_TIMEOUT_SECONDS",
                    "ACP_MAX_TURN_REQUESTS", "ACP_WEB_FETCH_ALLOW_PRIVATE", "ACP_EXTRA_MOUNTS",
                ).forEach { environment().remove(it) }
            }
            .start()
    }

    /**
     * Registers this file/directory and every descendant for deletion at JVM exit. Children are
     * registered after their parent, and the JVM deletes in reverse registration order, so the
     * directory is removed after its content (a plain `deleteOnExit` on the directory would
     * leave the tree behind).
     */
    private fun File.deleteOnExitRecursively() {
        deleteOnExit()
        listFiles()?.forEach { it.deleteOnExitRecursively() }
    }

    /** The harness base config, with [override] deep-merged over it. */
    private fun harnessConfig(llmPort: Int, override: JsonObject?): String {
        val base = buildJsonObject {
            putJsonObject("openRouter") {
                put("baseUrl", "http://127.0.0.1:$llmPort")
                put("defaultModel", "test-model")
            }
        }
        return Json.encodeToString(JsonObject.serializer(), deepMerge(base, override ?: JsonObject(emptyMap())))
    }

    private fun deepMerge(base: JsonObject, override: JsonObject): JsonObject =
        buildJsonObject {
            (base.keys + override.keys).forEach { key ->
                val baseValue = base[key]
                val overrideValue = override[key]
                when {
                    baseValue is JsonObject && overrideValue is JsonObject -> put(key, deepMerge(baseValue, overrideValue))
                    overrideValue != null -> put(key, overrideValue)
                    else -> put(key, baseValue!!)
                }
            }
        }

    internal suspend fun startConnectedAgent(
        llmPort: Int,
        xdgStateHome: File,
        configJson: String? = null,
        workingDir: File? = null,
    ): AgentConnection = connectProcess(startAgent(llmPort, xdgStateHome, configJson, workingDir))

    /**
     * Wraps an already started agent [process] in the real ACP client. Split out
     * of [startConnectedAgent] so a scenario can drain the agent's stderr (the
     * MCP fail-open and tool-shadowing warnings) before connecting.
     */
    internal fun connectProcess(process: Process): AgentConnection {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writeMutex = Mutex()
        val stdoutReader = process.inputStream.bufferedReader()
        val wireLines = Collections.synchronizedList(mutableListOf<String>())
        val transport = StdioTransport(
            scope,
            Dispatchers.IO,
            flow {
                while (true) {
                    val line = stdoutReader.readLine() ?: break
                    wireLines.add(line)
                    emit(line)
                }
            }.flowOn(Dispatchers.IO),
            { line ->
                writeMutex.withLock {
                    process.outputStream.write((line + "\n").toByteArray())
                    process.outputStream.flush()
                }
            },
        )
        val protocol = Protocol(scope, transport)
        val client = Client(protocol)
        protocol.start()
        return AgentConnection(process, client, scope, wireLines)
    }

    /**
     * What the harness announces as the client. [protocolVersion] is the version sent in the
     * `initialize` request (the latest the client supports); the default [ClientInfo] keeps v1
     * in `supportedProtocolVersions`, so a version probe can ask for a newer one and still
     * accept the agent's downgrade answer.
     */
    internal fun testClientInfo(
        capabilities: ClientCapabilities = ClientCapabilities(),
        protocolVersion: ProtocolVersion = 1,
    ): ClientInfo = ClientInfo(
        protocolVersion = protocolVersion,
        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
        capabilities = capabilities,
    )

    internal suspend fun newSession(
        client: Client,
        cwd: File,
        operations: ClientSessionOperations,
    ): ClientSession = client.newSession(
        SessionCreationParameters(cwd = cwd.absolutePath, mcpServers = emptyList()),
        ClientOperationsFactory { _, _ -> operations },
    )

    internal suspend fun collectPrompt(
        session: ClientSession,
        content: List<ContentBlock>,
        timeoutMs: Long = 60_000,
    ): List<Event> {
        val events = mutableListOf<Event>()
        withTimeout(timeoutMs) {
            session.prompt(content).collect { events += it }
        }
        return events
    }

    internal fun assertEndTurn(events: List<Event>) {
        assertEquals(
            StopReason.END_TURN,
            events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
        )
    }

    internal suspend fun connectTextOnly(connection: AgentConnection) {
        connection.client.initialize(testClientInfo())
    }

    internal suspend fun promptTextOnlyTurn(
        connection: AgentConnection,
        projectDir: File,
        text: String,
        session: ClientSession? = null,
    ): ClientSession {
        val activeSession = session ?: newSession(connection.client, projectDir, TestClientOperations())
        val events = collectPrompt(activeSession, listOf(ContentBlock.Text(text)))
        assertEndTurn(events)
        return activeSession
    }

    internal suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(25)
        }
    }

    /**
     * The tool-call pairing invariant (issue #38) as seen by the request: every
     * `function_call` item must be answered by a `function_call_output`, or a
     * strict provider rejects the request with 400 "No tool output found for
     * function call".
     */
    internal fun assertPairComplete(body: JsonObject, context: String) {
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        val callIds = input
            .filter { it["type"]?.jsonPrimitive?.content == "function_call" }
            .mapNotNull { it["call_id"]?.jsonPrimitive?.content }
        val resultIds = input
            .filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            .mapNotNull { it["call_id"]?.jsonPrimitive?.content }
        assertTrue(
            callIds.subtract(resultIds.toSet()).isEmpty(),
            "$context: every function call in the request must carry a function call output " +
                    "(dangling=${callIds.subtract(resultIds.toSet())}, calls=$callIds, results=$resultIds)",
        )
    }

    /**
     * Collapses consecutive equal entries, so a run of streamed deltas becomes one
     * entry. Use it instead of indexing chunks by position when the property under
     * test is "one id per iteration": it survives a mock emitting more or fewer
     * deltas while still failing on an id split within a run or a merge across runs.
     */
    internal fun <T> List<T>.collapseConsecutiveDuplicates(): List<T> =
        fold(mutableListOf()) { acc, item ->
            if (acc.lastOrNull() != item) acc.add(item)
            acc
        }

    internal fun drainStderr(process: Process): MutableList<String> {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val thread = Thread {
            process.errorStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines += line
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return lines
    }

    internal fun terminate(process: Process) {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
