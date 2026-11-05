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

        suspend fun connect(extraEnv: Map<String, String> = emptyMap()): AgentConnection =
            startConnectedAgent(llmMock.port, stateHome, extraEnv)
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
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        return ProcessBuilder(binaryPath)
            .apply {
                environment()["OPENROUTER_API_KEY"] = "sk-test-phase4"
                environment()["OPENROUTER_BASE_URL"] = "http://127.0.0.1:$llmPort"
                environment()["OPENROUTER_MODEL"] = "test-model"
                if (xdgStateHome != null) environment()["XDG_STATE_HOME"] = xdgStateHome.absolutePath
                // A leaked FS_PROXY_ENABLED=0 in the host environment must not silently force the
                // local store in scenarios that expect the proxy; scenarios opting out pass it via
                // extraEnv, which wins below.
                environment().remove("FS_PROXY_ENABLED")
                // Same for a leaked OPENROUTER_API_KEY_FILE (e.g. a staged docker-launcher key
                // in the host env): together with the harness key below it fails Config.fromEnv
                // loudly at agent startup, hanging the client-side initialize.
                environment().remove("OPENROUTER_API_KEY_FILE")
                // Same for a leaked scratch-directory override: scenarios that care set it via
                // extraEnv, and an ambient value would make the default-path pins meaningless.
                environment().remove("DDR_ACP_AGENT_SCRATCH_DIR")
                extraEnv.forEach { (key, value) -> environment()[key] = value }
            }
            .start()
    }

    internal suspend fun startConnectedAgent(
        llmPort: Int,
        xdgStateHome: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): AgentConnection = connectProcess(startAgent(llmPort, xdgStateHome, extraEnv))

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
