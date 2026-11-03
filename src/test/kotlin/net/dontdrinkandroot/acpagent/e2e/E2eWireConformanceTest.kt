package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
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
import java.util.*
import kotlin.test.*

/**
 * Black-box wire conformance:
 *
 *   initialize -> session/new -> set_config_option -> prompt -> tool_call -> permission (selected) -> tool result -> final text
 *
 * plus the per-iteration UUIDv7 messageIds on the raw wire, the usage indicator
 * and the attribution headers.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eWireConformanceTest : E2eAgentTest() {

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
            val agentInfo = client.initialize(testClientInfo())
            val agentImplementation = agentInfo.implementation
            assertNotNull(agentImplementation)
            assertEquals("acp-agent.kotlin", agentImplementation.name)
            // Version negotiation (spec MUST): the client asked for v1, so the agent must
            // answer with that same version.
            assertEquals(1, agentInfo.protocolVersion, "the agent must echo the requested protocol version")
            // Capability advertisement: everything AgentSupportImpl.initialize() advertises is
            // pinned here, so a capability silently dropped at an SDK upgrade fails loudly.
            assertTrue(agentInfo.capabilities.loadSession, "loadSession must be advertised")
            assertNotNull(
                agentInfo.capabilities.sessionCapabilities.list,
                "sessionCapabilities.list must be advertised"
            )
            assertNotNull(
                agentInfo.capabilities.sessionCapabilities.resume,
                "sessionCapabilities.resume must be advertised"
            )
            assertNotNull(
                agentInfo.capabilities.sessionCapabilities.delete,
                "sessionCapabilities.delete must be advertised"
            )
            assertNotNull(
                agentInfo.capabilities.sessionCapabilities.close,
                "sessionCapabilities.close must be advertised (issue #27)"
            )
            assertNotNull(agentInfo.capabilities.mcpCapabilities)
            assertTrue(agentInfo.capabilities.promptCapabilities.image, "image prompt capability must be advertised")
            assertTrue(
                agentInfo.capabilities.promptCapabilities.embeddedContext,
                "embeddedContext prompt capability must be advertised",
            )
            assertFalse(
                agentInfo.capabilities.promptCapabilities.audio,
                "audio prompt capability must stay unadvertised"
            )
            assertTrue(agentInfo.capabilities.mcpCapabilities.http, "http mcp capability must be advertised")
            assertTrue(agentInfo.capabilities.mcpCapabilities.sse, "sse mcp capability must be advertised")
            println("[ok] initialize (protocolVersion 1, capabilities advertised)")

            val session = newSession(client, tmpDir, testOps)
            println("[ok] session/new -> ${session.sessionId}")

            assertTrue(session.modesSupported)
            assertEquals(SessionModeId("plan"), session.currentMode.value)
            assertEquals(
                listOf("plan", "build", "bash"),
                session.availableModes.map { it.id.value }) // plan->build->bash is the advertised order
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

            val events = collectPrompt(
                session,
                listOf(
                    ContentBlock.Text(
                        "Write the text 'phase4' to ${targetFile.absolutePath} using the write_file tool."
                    )
                ),
                timeoutMs = 120_000,
            )
            println("[ok] prompt completed (${events.size} events)")

            val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

            val toolCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
            assertEquals(1, toolCalls.size)
            val toolCall = toolCalls.single()
            // write_file titles now summarize the overwrite (path + content size) for
// permission prompts/progress; a bare name would hide what is being replaced.
            assertTrue(
                toolCall.title.startsWith("write_file(path:"),
                "expected summarized write_file title, got: ${toolCall.title}"
            )
            assertEquals(ToolKind.EDIT, toolCall.kind)
            assertEquals(ToolCallStatus.IN_PROGRESS, toolCall.status)
            val rawInput = requireNotNull(toolCall.rawInput) { "tool_call must carry rawInput" }
            val rawInputObj = rawInput as JsonObject
            assertEquals(targetFile.absolutePath, (rawInputObj["path"] as JsonPrimitive).content)
            assertEquals("phase4", (rawInputObj["content"] as JsonPrimitive).content)
            assertEquals(
                listOf(ToolCallLocation(targetFile.absolutePath)),
                toolCall.locations,
                "tool_call must carry the file location for the follow-along surface",
            )
            println("[ok] tool_call update (write_file, title/kind/status/rawInput/locations correct)")

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
            val resultContent = requireNotNull(resultUpdate.content) { "completed tool_call_update must carry content" }
            assertTrue(
                resultContent.any {
                    it is ToolCallContent.Content &&
                            (it.content as? ContentBlock.Text)?.text?.contains("Written") == true
                },
                "completed tool_call_update must carry the result text as content, got: $resultContent",
            )
            assertTrue(
                resultContent.any {
                    it is ToolCallContent.Diff &&
                            it.path == targetFile.absolutePath &&
                            it.newText == "phase4" &&
                            it.oldText == null
                },
                "completed tool_call_update must carry a diff block for a new file, got: $resultContent",
            )
            println("[ok] tool_call_update completed (rawOutput + text/diff content)")

            assertEquals("phase4", targetFile.readText())
            println("[ok] write_file side effect verified on disk")

            val textChunks = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .mapNotNull { (it.content as? ContentBlock.Text)?.text }
            assertTrue(textChunks.isNotEmpty(), "expected assistant text chunks")
            assertTrue(textChunks.joinToString("").contains("phase4 done"))
            assertTrue(
                textChunks.none { it.isEmpty() },
                "reasoning-only deltas must not emit empty agent_message_chunk, got: ${textChunks.filter { it.isEmpty() }}",
            )
            println("[ok] agent_message_chunk text assembled: ${textChunks.joinToString("")}")

            val promptResponse = events.filterIsInstance<Event.PromptResponseEvent>().singleOrNull()
            assertNotNull(promptResponse, "expected a PromptResponseEvent")
            assertEquals(StopReason.END_TURN, promptResponse.response.stopReason)
            println("[ok] prompt response (stopReason=end_turn)")

            // The turn's follow-up request must answer the tool call it asked for
            // (pairing invariant, #38) - the previous pin was a global LLM call total.
            assertPairComplete(
                llmMock.parseChatBody(llmMock.lastRequestBody),
                "the chat request after the write_file tool call",
            )
            println("[ok] follow-up chat request is pair-complete (tool result answered)")

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
            val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            val thoughtIds = thoughtChunks.map { it.messageId?.value }
            assertTrue(thoughtIds.isNotEmpty() && thoughtIds.all { it != null }, "thought chunks must carry a messageId")
            thoughtIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUIDv7 format, got '$id'")
            }
            // Grouped by contiguous run rather than by chunk position: the mock's
            // delta split is filler, "one id per LLM iteration" is the contract.
            assertEquals(
                2,
                thoughtIds.collapseConsecutiveDuplicates().size,
                "each LLM iteration's thought deltas share one messageId, got $thoughtIds"
            )
            println("[ok] agent_thought_chunk relayed from reasoning deltas, one UUIDv7 messageId per iteration")

            val textIds = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .map { it.messageId?.value }
            assertTrue(textIds.isNotEmpty() && textIds.all { it != null }, "message chunks must carry a messageId")
            textIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUIDv7 format, got '$id'")
            }
            assertEquals(
                2,
                textIds.collapseConsecutiveDuplicates().size,
                "each LLM iteration's text deltas share one messageId, got $textIds"
            )
            // ACP v1-only pin: v2 keys whole agent_message/agent_thought messages by
            // messageId and requires distinct ids per stream - this assertion must
            // flip to "disjoint id sets" on v2 (see the messageId pitfall in AGENTS.md).
            assertEquals(
                thoughtIds.toSet(),
                textIds.toSet(),
                "an iteration's reasoning and reply deltas share the same messageId (v1 only)",
            )
            println("[ok] agent_message_chunk deltas share the iteration's messageId")

            val thoughtWireIds = wireLines.mapNotNull { line ->
                val root = runCatching { Json.parseToJsonElement(line) }.getOrNull()?.jsonObject
                    ?: return@mapNotNull null
                val update = root["params"]?.jsonObject?.get("update")?.jsonObject ?: return@mapNotNull null
                update.takeIf { it["sessionUpdate"]?.jsonPrimitive?.content == "agent_thought_chunk" }
                    ?.get("messageId")?.jsonPrimitive?.content
            }
            assertTrue(
                thoughtWireIds.size >= 2,
                "expected raw agent_thought_chunk lines on the wire, got ${thoughtWireIds.size}",
            )
            thoughtWireIds.forEach { id ->
                assertTrue(uuidRegex.matches(id), "wire messageId must be UUIDv7 format, got '$id'")
            }
            assertEquals(
                thoughtIds.collapseConsecutiveDuplicates(),
                thoughtWireIds.collapseConsecutiveDuplicates(),
                "wire thought chunks must carry the same per-iteration messageIds as the decoded ones",
            )
            println("[ok] raw session/update wire lines carry the iteration messageId on agent_thought_chunk")

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
            assertEquals("DdrAcpAgent", appTitle)
            println("[ok] attribution headers sent (HTTP-Referer + X-OpenRouter-Title)")

            val buildCommit = Properties().apply {
                E2eWireConformanceTest::class.java.getResourceAsStream("/git.properties")!!.use { load(it) }
            }.getProperty("git.commit")
            assertTrue(
                llmMock.lastRequestBody?.contains("Agent build: $buildCommit") ?: false,
                "expected the system prompt to carry the build hash, got: ${llmMock.lastRequestBody}",
            )
            println("[ok] system prompt carries the build hash ($buildCommit)")

            // Transports (spec MUST): the agent must not write anything to stdout that is not
            // a valid ACP message. Every line the harness consumed is checked, so a stray
            // println - invisible otherwise, the SDK logs undecodable lines at trace only -
            // names itself here.
            assertStdoutHygiene(wireLines.toList())
            println("[ok] stdout carries only JSON-RPC 2.0 messages (${wireLines.size} lines)")
        } finally {
            process.outputStream.close()
            protocol.close()
            scope.cancel()
        }
    }

    /**
     * Pins the transports MUST "the agent MUST NOT write anything to stdout that is not a
     * valid ACP message". Parsed strictly (not with the lenient `llmWireJson`), because a
     * lenient parser would accept an unquoted stray token as JSON.
     */
    private fun assertStdoutHygiene(lines: List<String>) {
        assertTrue(lines.isNotEmpty(), "no stdout lines captured - the check would be vacuous")
        val offenders = lines.filterNot(::isJsonRpcMessage)
        assertTrue(offenders.isEmpty(), "every stdout line must be a JSON-RPC 2.0 message, offenders: $offenders")
    }

    private fun isJsonRpcMessage(line: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return false
        // JSON-RPC batch: every element must be a valid message itself.
        val messages = (root as? JsonArray)?.map { it.jsonObject } ?: listOf(root.jsonObject)
        return messages.isNotEmpty() && messages.all { it["jsonrpc"]?.jsonPrimitive?.content == "2.0" }
    }

    /**
     * Version negotiation (spec MUST): a requested version the agent does not speak is
     * answered with the latest version the agent supports. The SDK pins the version per
     * connection (a repeated `initialize` only logs a warning), so the probe needs its own
     * agent process; the client is wired for v1+v2 so it accepts the downgrade answer.
     */
    @Test
    fun `e2e initialize answers an unsupported requested version with the latest supported one`() = runBlocking {
        val llmMock = MockOpenAiServer("unused", textOnly = true)
        withE2eAgent("version-negotiation", llmMock) {
            val connection = connect()
            try {
                val agentInfo = connection.client.initialize(testClientInfo(protocolVersion = 2))
                assertEquals(
                    1,
                    agentInfo.protocolVersion,
                    "an unsupported requested version must be answered with the agent's latest version",
                )
                // The connection stays usable at the negotiated version.
                connection.client.newSession(
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> TestClientOperations() },
                )
                println("[ok] requested v2 -> answered v1, session/new works on the negotiated version")
            } finally {
                connection.close()
            }
        }
    }
}
