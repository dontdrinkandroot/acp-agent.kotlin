package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

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

    internal class AgentConnection(
        val process: Process,
        val client: Client,
        private val scope: CoroutineScope,
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
                extraEnv.forEach { (key, value) -> environment()[key] = value }
            }
            .start()
    }

    internal suspend fun startConnectedAgent(
        llmPort: Int,
        xdgStateHome: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): AgentConnection {
        val process = startAgent(llmPort, xdgStateHome, extraEnv)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writeMutex = Mutex()
        val stdoutReader = process.inputStream.bufferedReader()
        val transport = StdioTransport(
            scope,
            Dispatchers.IO,
            flow {
                while (true) {
                    val line = stdoutReader.readLine() ?: break
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
        return AgentConnection(process, client, scope)
    }

    internal fun testClientInfo(capabilities: ClientCapabilities = ClientCapabilities()): ClientInfo = ClientInfo(
        protocolVersion = 1,
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