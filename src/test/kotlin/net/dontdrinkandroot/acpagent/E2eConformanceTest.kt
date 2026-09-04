package net.dontdrinkandroot.acpagent

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.agent.SessionStore
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Black-box e2e conformance harness.
 *
 * Drives the linked installDist launcher as a separate OS process with the REAL
 * `com.agentclientprotocol:acp` client over a real stdio transport, with a local mock LLM
 * server standing in for OpenRouter. Asserts the wire flow:
 *
 *   initialize -> session/new -> set_config_option -> prompt -> tool_call -> permission (selected) -> tool result -> final text
 *
 * Run via `./gradlew test`.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eConformanceTest {

    private val binaryPath: String
        get() = System.getProperty("acp.agent.binary") ?: error("acp.agent.binary system property not set")

    @Test
    fun `e2e wire conformance - initialize, session, tool call, permission, result`() = runBlocking {
        val tmpDir = Files.createTempDirectory("acp-agent-e2e").toFile()
        val targetFile = File(tmpDir, "phase4.txt")
        val llmMock = MockOpenAiServer(targetFile.absolutePath, firstTurnStreamDeltas = true)
        llmMock.start()
        try {
            val process = startAgent(llmMock.port)
            val stderrLines = drainStderr(process)
            try {
                runScenario(process, tmpDir, targetFile, llmMock)
            } finally {
                println("---- agent stderr (last 40) ----")
                stderrLines.takeLast(40).forEach(::println)
                terminate(process)
            }
        } finally {
            llmMock.stop()
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Black-box persistence flow across three agent processes sharing one XDG state dir:
     *
     *   process 1: session/new -> prompt (text-only) -> set_config_option(build)
     *   process 2: session/list -> session/load (replay) -> prompt (history continuity)
     *   process 3: session/resume (no replay) -> load guards -> session/delete (idempotent)
     */
    @Test
    fun `e2e session persistence - record, list, load, resume, delete across restart`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-project").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-state")
        val sessionsStoreDir = stateDir.resolve("ddr-acp-agent").resolve("sessions")
        val llmMock = MockOpenAiServer("unused", textOnly = true)
        llmMock.start()
        try {
            val sessionId: SessionId
            val first = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                first.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val ops = TestClientOperations()
                val session = first.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                sessionId = session.sessionId
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("Say hello"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
                )
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                println("[ok] session/new + prompt + mode switch on first agent")
            } finally {
                first.close()
            }

            val store = SessionStore(sessionsStoreDir)
            val record = store.load(sessionId.value)
            assertNotNull(record, "expected persisted session record")
            assertEquals(projectDir.absolutePath, record.cwd)
            assertEquals("build", record.mode)
            assertEquals("Say hello", record.title)
            val rawRecord =
                sessionsStoreDir.resolve("${sessionId.value}.json").let { java.nio.file.Files.readString(it) }
            assertTrue(
                !rawRecord.contains("pondering the request"),
                "reasoning deltas must not be persisted to history",
            )
            println("[ok] session record persisted (mode=${record.mode}, ${record.history.size} messages, no reasoning text)")

            val second = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                second.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val listed = second.client.listSessions(projectDir.absolutePath).toList()
                assertEquals(1, listed.size)
                assertEquals(sessionId, listed.first().sessionId)
                assertEquals("Say hello", listed.first().title)
                assertNotNull(listed.first().updatedAt)
                println("[ok] session/list after restart (cwd filter, recency)")

                val loadOps = TestClientOperations()
                val loaded = second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                assertEquals(SessionModeId("build"), loaded.currentMode.value)
                // Replay is emitted by the agent after the load response; wait for it to arrive.
                awaitUntil(5_000) { loadOps.notifications.any { it is SessionUpdate.UserMessageChunk } }
                val userChunks = loadOps.notifications.filterIsInstance<SessionUpdate.UserMessageChunk>()
                assertEquals(1, userChunks.size)
                assertEquals("Say hello", (userChunks.single().content as ContentBlock.Text).text)
                val agentText = loadOps.notifications.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                    .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                    .joinToString("")
                assertTrue(agentText.contains("phase4 done"), "expected replayed agent text, got '$agentText'")
                println("[ok] session/load replays user + agent message chunks, mode=build")

                val continuation = mutableListOf<Event>()
                withTimeout(60_000) {
                    loaded.prompt(listOf(ContentBlock.Text("Continue"))).collect { continuation += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    continuation.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
                )
                println("[ok] prompt on loaded session (history continuity)")
            } finally {
                second.close()
            }

            val third = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                third.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val resumeOps = TestClientOperations()
                val resumed = third.client.resumeSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> resumeOps },
                )
                assertEquals(SessionModeId("build"), resumed.currentMode.value)
                assertTrue(resumeOps.notifications.isEmpty(), "resume must not replay history")
                println("[ok] session/resume restores mode without replay")

                assertFailsWith<AcpExpectedError>("cwd mismatch must fail") {
                    third.client.loadSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.parent.toString(), mcpServers = emptyList()),
                        ClientOperationsFactory { _, _ -> TestClientOperations() },
                    )
                }
                assertFailsWith<AcpExpectedError>("double load must fail") {
                    third.client.loadSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                        ClientOperationsFactory { _, _ -> TestClientOperations() },
                    )
                }
                println("[ok] session/load rejected for cwd mismatch and already-active session")

                third.client.deleteSession(sessionId)
                assertTrue(!Files.exists(sessionsStoreDir.resolve("${sessionId.value}.json")))
                assertTrue(third.client.listSessions(projectDir.absolutePath).toList().isEmpty())
                third.client.deleteSession(sessionId)
                println("[ok] session/delete removes the record and is idempotent")
            } finally {
                third.close()
            }

            assertEquals(2, llmMock.requestCount.toInt())
            println("[ok] LLM called exactly 2 times across both prompts (load/resume make no LLM calls)")
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    private suspend fun runScenario(
        process: Process,
        tmpDir: File,
        targetFile: File,
        llmMock: MockOpenAiServer,
    ) {
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
        val testOps = TestClientOperations()
        try {
            val agentInfo = client.initialize(
                ClientInfo(
                    protocolVersion = 1,
                    implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                )
            )
            val agentImplementation = agentInfo.implementation
            assertNotNull(agentImplementation)
            assertEquals("acp-agent.kotlin", agentImplementation.name)
            assertNotNull(agentInfo.capabilities.sessionCapabilities.delete)
            assertNotNull(agentInfo.capabilities.mcpCapabilities)
            assertTrue(agentInfo.capabilities.promptCapabilities.image, "image prompt capability must be advertised")
            assertTrue(
                agentInfo.capabilities.promptCapabilities.embeddedContext,
                "embeddedContext prompt capability must be advertised",
            )
            assertTrue(agentInfo.capabilities.mcpCapabilities.http, "http mcp capability must be advertised")
            assertTrue(agentInfo.capabilities.mcpCapabilities.sse, "sse mcp capability must be advertised")
            println("[ok] initialize")

            val session = client.newSession(
                SessionCreationParameters(cwd = tmpDir.absolutePath, mcpServers = emptyList()),
                ClientOperationsFactory { _, _ -> testOps },
            )
            println("[ok] session/new -> ${session.sessionId}")

            assertTrue(session.modesSupported)
            assertEquals(SessionModeId("plan"), session.currentMode.value)
            assertEquals(listOf("build", "plan", "bash"), session.availableModes.map { it.id.value })
            assertTrue(session.configOptionsSupported)
            val modeOption = session.configOptions.value
                .filterIsInstance<SessionConfigOption.Select>()
                .first { it.id.value == "mode" }
            assertEquals("mode", modeOption.id.value)
            assertEquals("plan", modeOption.currentValue.value)
            val modelOption = session.configOptions.value
                .filterIsInstance<SessionConfigOption.Select>()
                .first { it.id.value == "model" }
            assertEquals("test-model", modelOption.currentValue.value)
            println("[ok] session/new advertises modes (plan default) + mode/model config options")

            val switchResponse = session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
            val switchOption = switchResponse.configOptions
                .filterIsInstance<SessionConfigOption.Select>()
                .first { it.id.value == "mode" }
            assertEquals("build", switchOption.currentValue.value)
            assertEquals(SessionModeId("build"), session.currentMode.value)
            assertTrue(
                testOps.notifications.any { it is SessionUpdate.CurrentModeUpdate && it.currentModeId.value == "build" },
                "expected current_mode_update notification",
            )
            assertTrue(
                testOps.notifications.any {
                    it is SessionUpdate.ConfigOptionUpdate &&
                            (it.configOptions.filterIsInstance<SessionConfigOption.Select>()
                                .first { option -> option.id.value == "mode" }).currentValue.value == "build"
                },
                "expected config_option_update notification",
            )
            println("[ok] set_config_option -> build (response + current_mode_update + config_option_update)")

            val reasoningSwitch =
                session.setConfigOption(SessionConfigId("reasoning"), SessionConfigOptionValue.of("medium"))
            val reasoningOption = reasoningSwitch.configOptions
                .filterIsInstance<SessionConfigOption.Select>()
                .first { it.id.value == "reasoning" }
            assertEquals("medium", reasoningOption.currentValue.value)
            assertEquals(
                listOf("high", "medium", "none"),
                (reasoningOption.options as SessionConfigSelectOptions.Flat).options.map { it.value.value },
            )
            println("[ok] set_config_option -> reasoning medium (high/medium/none options)")

            val events = mutableListOf<Event>()
            withTimeout(120_000) {
                session.prompt(
                    listOf(
                        ContentBlock.Text(
                            "Write the text 'phase4' to ${targetFile.absolutePath} using the write_file tool."
                        )
                    )
                ).collect { events += it }
            }
            println("[ok] prompt completed (${events.size} events)")

            val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

            val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
            assertEquals(1, toolCalls.size)
            val toolCall = toolCalls.single()
            assertEquals("write_file", toolCall.title)
            assertEquals(ToolKind.EDIT, toolCall.kind)
            assertEquals(ToolCallStatus.IN_PROGRESS, toolCall.status)
            val rawInput = requireNotNull(toolCall.rawInput) { "tool_call must carry rawInput" }
            val rawInputObj = rawInput as JsonObject
            assertEquals(targetFile.absolutePath, (rawInputObj["path"] as JsonPrimitive).content)
            assertEquals("phase4", (rawInputObj["content"] as JsonPrimitive).content)
            println("[ok] tool_call update (write_file, title/kind/status/rawInput correct)")

            assertTrue(
                testOps.permissionRequests.isEmpty(),
                "an in-project write must not ask for permission, got ${testOps.permissionRequests}",
            )
            println("[ok] in-project write_file ran without a session/request_permission")

            val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                .filter { it.toolCallId == toolCall.toolCallId }
            assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
            val resultUpdate = resultUpdates.last()
            assertEquals(ToolCallStatus.COMPLETED, resultUpdate.status)
            assertTrue(resultUpdate.rawOutput.toString().contains("Written"))
            println("[ok] tool_call_update completed (rawOutput=${resultUpdate.rawOutput})")

            assertEquals("phase4", targetFile.readText())
            println("[ok] write_file side effect verified on disk")

            val textChunks = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .mapNotNull { (it.content as? ContentBlock.Text)?.text }
            assertTrue(textChunks.isNotEmpty(), "expected assistant text chunks")
            assertTrue(textChunks.joinToString("").contains("phase4 done"))
            println("[ok] agent_message_chunk text assembled: ${textChunks.joinToString("")}")

            val promptResponse = events.filterIsInstance<Event.PromptResponseEvent>().singleOrNull()
            assertNotNull(promptResponse, "expected a PromptResponseEvent")
            assertEquals(StopReason.END_TURN, promptResponse.response.stopReason)
            println("[ok] prompt response (stopReason=end_turn)")

            assertEquals(2, llmMock.requestCount.toInt())
            println("[ok] LLM called exactly 2 turns (tool-call turn + final turn)")

            assertTrue(
                llmMock.lastRequestBody?.contains("\"reasoning\":{\"effort\":\"medium\"}") ?: false,
                "expected reasoning effort in the chat request, got: ${llmMock.lastRequestBody}",
            )
            awaitUntil(5_000) {
                updates.any { it is SessionUpdate.UsageUpdate } ||
                        testOps.notifications.any { it is SessionUpdate.UsageUpdate }
            }
            val usageUpdate = (
                    updates.filterIsInstance<SessionUpdate.UsageUpdate>() +
                            testOps.notifications.filterIsInstance<SessionUpdate.UsageUpdate>()
                    ).lastOrNull()
            assertNotNull(usageUpdate, "expected a usage_update notification")
            assertEquals(42L, usageUpdate.used)
            assertEquals(16384L, usageUpdate.size)
            println("[ok] chat request carries reasoning effort; usage_update reports 42/16384")

            val thoughtChunks = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
                .filter { (it.content as? ContentBlock.Text)?.text?.isNotEmpty() == true }
            assertTrue(
                thoughtChunks.size >= 2,
                "expected multiple agent_thought_chunk deltas, got ${thoughtChunks.size}"
            )
            assertEquals(
                "priming the writepondering the request",
                thoughtChunks.mapNotNull { (it.content as ContentBlock.Text).text }.joinToString("")
            )
            val uuidRegex =
                Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val thoughtIds = thoughtChunks.map { it.messageId?.value }
            assertTrue(thoughtIds.isNotEmpty() && thoughtIds.all { it != null }, "thought chunks must carry a messageId")
            thoughtIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUID format, got '$id'")
            }
            assertEquals(
                2,
                thoughtIds.distinct().size,
                "each LLM iteration's thought block gets its own messageId, got $thoughtIds"
            )
            assertEquals(thoughtIds[1], thoughtIds[2], "iteration-2 thought deltas share one messageId")
            assertNotEquals(thoughtIds[0], thoughtIds[1], "a fresh LLM iteration gets a fresh thought messageId")
            println("[ok] agent_thought_chunk relayed from reasoning deltas, one messageId per iteration")

            val textIds = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .map { it.messageId?.value }
            assertTrue(textIds.isNotEmpty() && textIds.all { it != null }, "message chunks must carry a messageId")
            textIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUID format, got '$id'")
            }
            assertEquals(
                2,
                textIds.distinct().size,
                "each LLM iteration's text block gets its own messageId, got $textIds"
            )
            assertTrue(
                thoughtIds.toSet().intersect(textIds.toSet()).isEmpty(),
                "thought and text blocks use distinct messageIds",
            )
            println("[ok] agent_message_chunk deltas share one messageId per iteration")

            val thoughtWireIds = wireLines.mapNotNull { line ->
                val root = runCatching { Json.parseToJsonElement(line) }.getOrNull()?.jsonObject
                    ?: return@mapNotNull null
                val update = root["params"]?.jsonObject?.get("update")?.jsonObject ?: return@mapNotNull null
                update.takeIf { it["sessionUpdate"]?.jsonPrimitive?.content == "agent_thought_chunk" }
                    ?.get("messageId")?.jsonPrimitive?.content
            }
            assertTrue(
                thoughtWireIds.size >= 3,
                "expected raw agent_thought_chunk lines on the wire, got ${thoughtWireIds.size}",
            )
            thoughtWireIds.forEach { id ->
                assertTrue(uuidRegex.matches(id), "wire messageId must be UUID format, got '$id'")
            }
            assertEquals(2, thoughtWireIds.distinct().size, "wire thought chunks must share per-iteration messageIds")
            assertEquals(thoughtIds.toList(), thoughtWireIds.toList(), "wire messageIds must match the decoded ones")
            println("[ok] raw session/update wire lines carry messageId on agent_thought_chunk")

            val headers = llmMock.lastRequestHeaders
            val referer =
                headers.entries.firstOrNull { it.key.equals("HTTP-Referer", ignoreCase = true) }?.value?.firstOrNull()
            val appTitle = headers.entries.firstOrNull {
                it.key.equals(
                    "X-OpenRouter-Title",
                    ignoreCase = true
                )
            }?.value?.firstOrNull()
            assertEquals("https://github.com/dontdrinkandroot/acp-agent.kotlin", referer)
            assertEquals("DdrAcpAgentKotlin", appTitle)
            println("[ok] attribution headers sent (HTTP-Referer + X-OpenRouter-Title)")

            val buildCommit = Properties().apply {
                E2eConformanceTest::class.java.getResourceAsStream("/git.properties")!!.use { load(it) }
            }.getProperty("git.commit")
            assertTrue(
                llmMock.lastRequestBody?.contains("Agent build: $buildCommit") ?: false,
                "expected the system prompt to carry the build hash, got: ${llmMock.lastRequestBody}",
            )
            println("[ok] system prompt carries the build hash ($buildCommit)")
        } finally {
            process.outputStream.close()
            protocol.close()
            scope.cancel()
        }
    }

    private fun startAgent(
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

    private class AgentConnection(
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

    private suspend fun startConnectedAgent(
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

    /**
     * Black-box update_plan flow across two agent processes:
     *
     *   process 1: prompt with an update_plan tool-call turn -> plan update + persisted plan
     *   process 2: session/load -> replay ends with the plan update
     */
    @Test
    fun `e2e update_plan - plan update, persistence and replay`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-plan").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-plan-state")
        val sessionsStoreDir = stateDir.resolve("ddr-acp-agent").resolve("sessions")
        val llmMock = MockOpenAiServer("unused", planMode = true)
        llmMock.start()
        try {
            val sessionId: SessionId
            val first = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                first.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val ops = TestClientOperations()
                val session = first.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                sessionId = session.sessionId
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("Plan the fix"))).collect { events += it }
                }
                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

                val planUpdates = updates.filterIsInstance<SessionUpdate.PlanUpdate>()
                assertEquals(1, planUpdates.size)
                val entries = planUpdates.single().entries
                assertEquals(
                    listOf("Investigate the bug", "Fix the bug"),
                    entries.map { it.content },
                )
                assertEquals(
                    listOf(PlanEntryStatus.IN_PROGRESS, PlanEntryStatus.PENDING),
                    entries.map { it.status },
                )
                println("[ok] update_plan emitted a plan update with 2 entries")

                val toolCall = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
                assertEquals("update_plan", toolCall.title)
                assertEquals(ToolKind.THINK, toolCall.kind)
                assertEquals(
                    ToolCallStatus.COMPLETED,
                    updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last().status
                )
                assertTrue(
                    ops.permissionRequests.isEmpty(),
                    "update_plan is non-mutating and must not request permission",
                )
                println("[ok] update_plan tool call (kind=think, no permission, completed)")
            } finally {
                first.close()
            }

            val store = SessionStore(sessionsStoreDir)
            val record = store.load(sessionId.value)
            assertNotNull(record, "expected persisted session record")
            assertEquals(2, record.plan.size)
            println("[ok] plan persisted in the session record")

            val second = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                second.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val loadOps = TestClientOperations()
                second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                awaitUntil(5_000) { loadOps.notifications.any { it is SessionUpdate.PlanUpdate } }
                assertEquals(
                    listOf("Investigate the bug", "Fix the bug"),
                    loadOps.notifications.filterIsInstance<SessionUpdate.PlanUpdate>()
                        .single().entries.map { it.content },
                )
                assertTrue(
                    loadOps.notifications.last() is SessionUpdate.PlanUpdate,
                    "the plan update must be replayed after the history",
                )
                println("[ok] session/load replays the persisted plan")
            } finally {
                second.close()
            }

            assertEquals(2, llmMock.requestCount.toInt())
            println("[ok] LLM called for the plan turn + final text turn (load/replay makes no LLM calls)")
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box AGENTS.md injection and multimodal prompt conversion: the session
     * cwd's AGENTS.md is re-read into the system prompt, an image content block is
     * converted to a base64 data URI (the mock model advertises image input), and a
     * text resource is inlined.
     */
    @Test
    fun `e2e project instructions injection and multimodal prompt conversion`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-multimodal").toFile()
        projectDir.resolve("AGENTS.md").writeText("Follow the repository project rules.")
        val stateDir = Files.createTempDirectory("acp-agent-e2e-multimodal-state")
        val llmMock = MockOpenAiServer("unused", textOnly = true, imageSupport = true)
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connection.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(
                        listOf(
                            ContentBlock.Text("Analyze this"),
                            ContentBlock.Image("aGVsbG8=", "image/png"),
                            ContentBlock.Resource(
                                EmbeddedResourceResource.TextResourceContents("embedded snippet", "file:///embed.txt")
                            ),
                        )
                    ).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
                )
                val body = requireNotNull(llmMock.lastRequestBody) { "no chat request captured" }
                assertTrue(body.contains("## Project Instructions (from AGENTS.md)"), "AGENTS.md section missing")
                assertTrue(body.contains("Follow the repository project rules."), "AGENTS.md content missing")
                assertTrue(body.contains("data:image/png;base64,aGVsbG8="), "image must become a data URI")
                assertTrue(body.contains("embedded snippet"), "text resource must be inlined")
                assertTrue(body.contains("file:///embed.txt"), "resource uri must be rendered")
                println("[ok] AGENTS.md injected into the system prompt + image/resource converted")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box $/cancel_request parity: cancelling a prompt while a mutating tool
     * waits on the permission prompt dismisses the permission request on the client
     * side (the SDK propagates $/cancel_request) and cancels the turn without a
     * spurious "Permission denied" tool result.
     */
    @Test
    fun `e2e session cancel dismisses a stuck permission prompt`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-cancel").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-cancel-state")
        val targetDir = Files.createTempDirectory("acp-agent-e2e-cancel-target").toFile()
        val llmMock = MockOpenAiServer(File(targetDir, "phase4.txt").absolutePath)
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connection.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val ops = SuspendingPermissionOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = mutableListOf<Event>()
                val promptJob = async {
                    runCatching {
                        withTimeout(60_000) {
                            session.prompt(listOf(ContentBlock.Text("Write the file"))).collect { events += it }
                        }
                    }
                }
                ops.permissionRequestStarted.await()
                session.cancel()
                assertNotNull(ops.permissionCancelled.await(), "permission prompt must be cancelled")
                val outcome = promptJob.await()
                val stopReason = events.filterIsInstance<Event.PromptResponseEvent>().lastOrNull()?.response?.stopReason
                assertTrue(
                    outcome.exceptionOrNull() is CancellationException || stopReason == StopReason.CANCELLED,
                    "prompt must end cancelled, outcome=$outcome stopReason=$stopReason",
                )
                val failedUpdates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    .filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    .filter { it.status == ToolCallStatus.FAILED }
                assertTrue(
                    failedUpdates.isEmpty(),
                    "no spurious permission-denied update expected, got $failedUpdates",
                )
                println("[ok] session/cancel dismissed the permission prompt ($/cancel_request) and cancelled the turn")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            targetDir.deleteRecursively()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box client fs proxy: with read+write fs capabilities the read_file
     * tool runs via the client fs (unsaved editor state, reviewable diffs) for
     * an in-project read, without asking for permission.
     */
    @Test
    fun `e2e read_file uses the client fs proxy in project`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-fsproxy").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-fsproxy-state")
        val targetFile = projectDir.resolve("known.txt")
        targetFile.writeText("proxy content")
        val llmMock = MockOpenAiServer(
            "unused",
            toolCall = MockToolCall("read_file", pathArgs(targetFile.absolutePath)),
        )
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connection.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true
                            )
                        ),
                    )
                )
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("Read the file"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
                )
                assertEquals(
                    listOf(targetFile.absolutePath),
                    ops.fsReadCalls,
                    "the client fs read must be used for an in-project read",
                )
                assertTrue(ops.permissionRequests.isEmpty(), "in-project read must not ask permission")
                println("[ok] read_file routed through the client fs (no prompt)")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box client fs proxy disabled: FS_PROXY_ENABLED=0 falls back to the
     * local store even when the client advertises fs capabilities, so the
     * client fs is never called.
     */
    @Test
    fun `e2e read_file uses the local store when the fs proxy is disabled`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-fsproxy-off").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-fsproxy-off-state")
        val targetFile = projectDir.resolve("known.txt")
        targetFile.writeText("local content")
        val llmMock = MockOpenAiServer(
            "unused",
            toolCall = MockToolCall("read_file", pathArgs(targetFile.absolutePath)),
        )
        llmMock.start()
        try {
            val connection = startConnectedAgent(
                llmMock.port,
                stateDir.toFile(),
                extraEnv = mapOf("FS_PROXY_ENABLED" to "0"),
            )
            try {
                connection.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true
                            )
                        ),
                    )
                )
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("Read the file"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
                )
                assertTrue(ops.fsReadCalls.isEmpty(), "disabled fs proxy must not call the client fs")
                assertTrue(ops.permissionRequests.isEmpty(), "in-project read must not prompt permission")
                println("[ok] fs proxy disabled -> local store used, no client fs calls")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box out-of-project read: a read_file call whose path lies outside
     * the session directory asks the user for permission.
     */
    @Test
    fun `e2e out of project read asks permission`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-outread").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-outread-state")
        val outsideDir = Files.createTempDirectory("acp-agent-e2e-outread-target").toFile()
        val targetFile = outsideDir.resolve("secret.txt")
        targetFile.writeText("secret")
        val llmMock = MockOpenAiServer(
            "unused",
            toolCall = MockToolCall("read_file", pathArgs(targetFile.absolutePath)),
        )
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connection.client.initialize(
                    ClientInfo(
                        protocolVersion = 1,
                        implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
                    )
                )
                val ops = TestClientOperations()
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> ops },
                )
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("Read the file"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
                )
                assertEquals(1, ops.permissionRequests.size, "an out-of-project read must ask permission")
                assertEquals("read_file", ops.permissionRequests.single().title)
                val rawInput = ops.permissionRequests.single().rawInput as JsonObject
                assertEquals(targetFile.absolutePath, (rawInput["path"] as JsonPrimitive).content)
                println("[ok] out-of-project read routed through session/request_permission")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            outsideDir.deleteRecursively()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box auto provider routing: enabled by default, the chat request
     * carries a throughput sort with a median completion cap (the mock feed is
     * 0.00002 + 0.0001 USD/token -> 60 $/M), fetched once and cached per model.
     */
    @Test
    fun `e2e auto provider routing caps at the median completion price`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-routing").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-routing-state")
        val llmMock = MockOpenAiServer("unused", textOnly = true)
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connectTextOnly(connection)
                val session = promptTextOnlyTurn(connection, projectDir, "first")
                promptTextOnlyTurn(connection, projectDir, "again", session)
                val bodies = llmMock.requestBodies.map { llmMock.parseChatBody(it) }
                assertTrue(bodies.all { it["provider"] != null }, "every chat request must carry provider preferences")
                for (body in bodies) {
                    val provider = body["provider"]!!.jsonObject
                    assertEquals("throughput", provider["sort"]?.jsonPrimitive?.content)
                    assertEquals(
                        60.0,
                        provider["max_price"]?.jsonObject?.get("completion")?.jsonPrimitive?.content?.toDouble()
                    )
                }
                assertEquals(1, llmMock.endpointRequestCount.toInt(), "endpoints must be fetched once per model")
                println("[ok] auto provider routing (throughput sort, median cap, cached)")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box auto provider routing fail-open: an endpoints failure must omit
     * the provider preferences entirely and never break the turn.
     */
    @Test
    fun `e2e auto provider routing fails open`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-routing-failopen").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-routing-failopen-state")
        val llmMock = MockOpenAiServer("unused", textOnly = true, failEndpoints = true)
        llmMock.start()
        try {
            val connection = startConnectedAgent(llmMock.port, stateDir.toFile())
            try {
                connectTextOnly(connection)
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> TestClientOperations() },
                )
                llmMock.requestCount.set(0)
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("hi"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
                )
                val provider = llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]
                assertNull(provider, "endpoints failure must omit provider preferences")
                println("[ok] auto provider routing fails open on endpoints error")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    /**
     * Black-box auto provider routing disabled: `OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED=0`
     * omits the provider field and never fetches the endpoints feed.
     */
    @Test
    fun `e2e auto provider routing disabled`() = runBlocking {
        val projectDir = Files.createTempDirectory("acp-agent-e2e-routing-disabled").toFile()
        val stateDir = Files.createTempDirectory("acp-agent-e2e-routing-disabled-state")
        val llmMock = MockOpenAiServer("unused", textOnly = true)
        llmMock.start()
        try {
            val connection = startConnectedAgent(
                llmMock.port,
                stateDir.toFile(),
                extraEnv = mapOf("OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "0"),
            )
            try {
                connectTextOnly(connection)
                val session = connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> TestClientOperations() },
                )
                val events = mutableListOf<Event>()
                withTimeout(60_000) {
                    session.prompt(listOf(ContentBlock.Text("hi"))).collect { events += it }
                }
                assertEquals(
                    StopReason.END_TURN,
                    events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
                )
                val body = llmMock.parseChatBody(llmMock.lastRequestBody)
                assertNull(body["provider"], "disabled routing must omit the provider preferences")
                assertEquals(0, llmMock.endpointRequestCount.toInt(), "disabled routing must not fetch endpoints")
                println("[ok] auto provider routing disabled via env")
            } finally {
                connection.close()
            }
        } finally {
            llmMock.stop()
            stateDir.toFile().deleteRecursively()
            projectDir.deleteRecursively()
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(25)
        }
    }

    private suspend fun connectTextOnly(connection: AgentConnection) {
        connection.client.initialize(
            ClientInfo(
                protocolVersion = 1,
                implementation = Implementation(name = "acp-agent-e2e-test", version = "0.0.1"),
            )
        )
    }

    private suspend fun promptTextOnlyTurn(
        connection: AgentConnection,
        projectDir: File,
        text: String,
        session: ClientSession? = null,
    ): ClientSession {
        val activeSession = session ?: connection.client.newSession(
            SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
            ClientOperationsFactory { _, _ -> TestClientOperations() },
        )
        val events = mutableListOf<Event>()
        withTimeout(60_000) {
            activeSession.prompt(listOf(ContentBlock.Text(text))).collect { events += it }
        }
        assertEquals(
            StopReason.END_TURN,
            events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason
        )
        return activeSession
    }

    private fun drainStderr(process: Process): MutableList<String> {
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

    private fun terminate(process: Process) {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}

/**
 * Client operations whose permission request never resolves on its own; used to
 * pin the $/cancel_request behavior: the running prompt is cancelled while the
 * permission prompt is stuck, and the request is dismissed on the client side.
 */
private class SuspendingPermissionOperations : ClientSessionOperations {
    val permissionRequestStarted = CompletableDeferred<Unit>()
    val permissionCancelled = CompletableDeferred<CancellationException?>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequestStarted.complete(Unit)
        return try {
            CompletableDeferred<Unit>().await()
            error("permission request must never resolve on its own")
        } catch (e: CancellationException) {
            permissionCancelled.complete(e)
            throw e
        }
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}

private class TestClientOperations : ClientSessionOperations {
    val permissionRequests = mutableListOf<SessionUpdate.ToolCallUpdate>()
    val permissionOptions = mutableListOf<List<PermissionOption>>()
    val notifications = mutableListOf<SessionUpdate>()
    val fsReadCalls = mutableListOf<String>()
    val fsWriteCalls = mutableListOf<String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequests += toolCall
        permissionOptions += permissions
        return RequestPermissionResponse(
            RequestPermissionOutcome.Selected(PermissionOptionId("allow_once"))
        )
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        notifications += notification
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val file = File(path)
        val content = if (file.isFile) file.readText() else ""
        fsReadCalls += path
        return ReadTextFileResponse(content)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        fsWriteCalls += path
        return WriteTextFileResponse()
    }
}

internal data class MockToolCall(val name: String, val arguments: JsonObject)

private fun pathArgs(path: String): JsonObject = buildJsonObject { put("path", path) }

/**
 * Minimal OpenAI-compatible streaming server:
 *  - 1st completion request  -> a tool_call for `write_file` (writes 'phase4' to the target path)
 *  - subsequent completions  -> plain text chunks "phase4 done"
 * With [textOnly] every request is answered with plain text instead.
 */
internal class MockOpenAiServer(
    private val targetPath: String,
    private val textOnly: Boolean = false,
    private val planMode: Boolean = false,
    private val imageSupport: Boolean = false,
    private val failEndpoints: Boolean = false,
    private val toolCall: MockToolCall? = null,
    private val firstTurnStreamDeltas: Boolean = false,
) {
    val requestCount = AtomicInteger(0)
    var lastRequestBody: String? = null
    var lastRequestHeaders: Map<String, List<String>> = emptyMap()
    val endpointRequestCount = AtomicInteger(0)
    val requestBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var server: HttpServer? = null
    val port: Int
        get() = server!!.address.port

    fun start() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/chat/completions") { exchange ->
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            lastRequestBody = exchange.requestBody.readBytes().decodeToString()
            lastRequestHeaders = exchange.requestHeaders
            requestBodies += lastRequestBody!!
            val n = requestCount.incrementAndGet()
            val body = when {
                planMode && n == 1 -> planSse()
                toolCall != null && n == 1 -> toolCallSse(toolCall.name, toolCall.arguments)
                textOnly || n > 1 -> textSse()
                else -> toolCallSse("write_file", writeFileArguments())
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/models") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val body = modelsJson()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/models/test-model/endpoints") { exchange ->
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            endpointRequestCount.incrementAndGet()
            if (failEndpoints) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return@createContext
            }
            val body =
                """{"data":{"endpoints":[{"name":"p1","pricing":{"completion":"0.00002"}},{"name":"p2","pricing":{"completion":"0.0001"}}]}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.start()
        server = http
    }

    private fun modelsJson(): String {
        val inputModalities = if (imageSupport) "[\"text\",\"image\"]" else "[\"text\"]"
        return """{"data":[{"id":"test-model","name":"Test Model","description":"A test model","supported_parameters":["tools"],""" +
                """"architecture":{"input_modalities":$inputModalities,"output_modalities":["text"]},""" +
                """"context_length":16384,"reasoning":{"supported_efforts":["high","medium"],"default_effort":"high"}}]}"""
    }

    fun stop() {
        server?.stop(0)
    }

    fun parseChatBody(body: String?): JsonObject =
        llmWireJson.parseToJsonElement(body ?: error("no chat body captured")).jsonObject

    private fun writeFileArguments(): JsonObject = JsonObject(
        mapOf(
            "path" to JsonPrimitive(targetPath),
            "content" to JsonPrimitive("phase4"),
        )
    )

    private fun toolCallSse(name: String, arguments: JsonObject): String {
        val firstTurnDeltas = if (firstTurnStreamDeltas) {
            "data: " + "{\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"priming the write\"},\"finish_reason\":null}]}\n\n" +
                "data: " + "{\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"writing the file\"},\"finish_reason\":null}]}\n\n"
        } else {
            ""
        }
        val chunk = buildJsonObject {
            put("id", "chatcmpl-ph4-1")
            put("object", "chat.completion.chunk")
            put("created", 0)
            put("model", "test-model")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("finish_reason", "tool_calls")
                            put(
                                "delta",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("index", 0)
                                                    put("id", "call_write")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", name)
                                                            put("arguments", arguments.toString())
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return firstTurnDeltas +
            "data: $chunk\n\n" +
            "data: {\"id\":\"chatcmpl-ph4-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
    }

    private fun planSse(): String {
        val arguments = JsonObject(
            mapOf(
                "entries" to buildJsonArray {
                    add(buildJsonObject {
                        put("content", "Investigate the bug")
                        put("priority", "high")
                        put("status", "in_progress")
                    })
                    add(buildJsonObject {
                        put("content", "Fix the bug")
                        put("priority", "medium")
                        put("status", "pending")
                    })
                }
            )
        )
        val chunk = buildJsonObject {
            put("id", "chatcmpl-plan-1")
            put("object", "chat.completion.chunk")
            put("created", 0)
            put("model", "test-model")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("finish_reason", "tool_calls")
                            put(
                                "delta",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("index", 0)
                                                    put("id", "call_plan")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", "update_plan")
                                                            put("arguments", arguments.toString())
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        return "data: $chunk\n\n" +
                "data: {\"id\":\"chatcmpl-plan-1\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
    }

    private fun textSse(): String {
        return buildString {
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"pondering \"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"the request\"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"phase4 \"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                    "\"model\":\"test-model\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            )
            append(
                "data: " + "{\"id\":\"chatcmpl-ph4-2\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                        "\"model\":\"test-model\",\"choices\":[]," +
                        "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":5,\"total_tokens\":47}}\n\n"
            )
            append("data: [DONE]\n\n")
        }
    }
}
