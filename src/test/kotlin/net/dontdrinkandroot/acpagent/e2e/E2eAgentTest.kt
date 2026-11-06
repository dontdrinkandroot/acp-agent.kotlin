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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val sessionsStoreDir: Path = stateDir.resolve("ddr-acp-agent").resolve("sessions")

        suspend fun connect(extraEnv: Map<String, String> = emptyMap(), workingDir: File? = null): AgentConnection =
            startConnectedAgent(llmMock.port, stateHome, extraEnv, workingDir)
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
        extraEnv: Map<String, String> = emptyMap(),
        /**
         * The agent process's own working directory. Scenarios that pin a
         * session-scoped child process (stdio MCP servers, issue #74) must set
         * this to a directory other than the session `cwd`, otherwise the
         * assertion holds trivially.
         */
        workingDir: File? = null,
    ): Process {
        return ProcessBuilder(binaryPath)
            .apply {
                workingDir?.let { directory(it) }
                // The key is delivered the only way the agent accepts one: as a
                // 0600 file (issue #50).
                val keyFile = Files.createTempFile("acp-e2e-key", ".txt").toFile().apply { deleteOnExit() }
                keyFile.writeText("sk-test-phase4\n")
                Files.setPosixFilePermissions(
                    keyFile.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
                // An isolated config dir: no host config.json, no host key may leak in.
                val configDir = Files.createTempDirectory("acp-e2e-config").toFile().apply { deleteOnExit() }
                // The agent's whole config surface is the DDR_ACP_AGENT_* family (issue #50), so an
                // ambient value (e.g. a docker launcher forwarding
                // DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES, or a locally exported
                // DDR_ACP_AGENT_TOOLS_FS_PROXY=0) would silently override what a scenario configures
                // via XDG_CONFIG_HOME or expects to be the default. Strip the family, then apply the
                // harness values; scenarios opt in via extraEnv, which wins below.
                environment().keys.removeIf { it.startsWith("DDR_ACP_AGENT_") }
                environment()["XDG_CONFIG_HOME"] = configDir.absolutePath
                environment()["DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE"] = keyFile.absolutePath
                environment()["DDR_ACP_AGENT_OPEN_ROUTER_BASE_URL"] = "http://127.0.0.1:$llmPort"
                environment()["DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL"] = "test-model"
                if (xdgStateHome != null) environment()["XDG_STATE_HOME"] = xdgStateHome.absolutePath
                // A leaked key location would fail Config.load loudly at agent startup, hanging the
                // client-side initialize.
                environment().remove("OPENROUTER_API_KEY")
                environment().remove("OPENROUTER_API_KEY_FILE")
                environment().remove("OPENROUTER_BASE_URL")
                environment().remove("OPENROUTER_MODEL")
                // Any ambient legacy rename leftovers must not affect the new layering.
                listOf(
                    "FS_PROXY_ENABLED", "MCP_TRUST_ANNOTATIONS", "ACP_BASH_TIMEOUT_SECONDS",
                    "ACP_MAX_TURN_REQUESTS", "ACP_WEB_FETCH_ALLOW_PRIVATE", "ACP_EXTRA_MOUNTS",
                ).forEach { environment().remove(it) }
                extraEnv.forEach { (key, value) -> environment()[key] = value }
            }
            .start()
    }

    internal suspend fun startConnectedAgent(
        llmPort: Int,
        xdgStateHome: File,
        extraEnv: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): AgentConnection = connectProcess(startAgent(llmPort, xdgStateHome, extraEnv, workingDir))

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
     * The tool-call pairing invariant (issue #38) as seen by the chat request: every
     * assistant tool-call id must be answered by a `role: "tool"` result, or a strict
     * provider rejects the request with 400 "No tool output found for function call".
     */
    internal fun assertPairComplete(body: JsonObject, context: String) {
        val messages = body["messages"]!!.jsonArray
        val callIds = messages.asSequence()
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .flatMap { (it.jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())).jsonArray }
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .toList()
        val resultIds = messages
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
            .map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content }
        assertTrue(
            callIds.subtract(resultIds.toSet()).isEmpty(),
            "$context: every tool call in the chat request must carry a tool output " +
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
