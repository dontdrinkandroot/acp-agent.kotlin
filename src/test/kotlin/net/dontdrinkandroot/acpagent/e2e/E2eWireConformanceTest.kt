package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

            val session = newSession(client, tmpDir, testOps)
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
            assertTrue(
                textChunks.none { it.isEmpty() },
                "reasoning-only deltas must not emit empty agent_message_chunk, got: ${textChunks.filter { it.isEmpty() }}",
            )
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
            val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            val thoughtIds = thoughtChunks.map { it.messageId?.value }
            assertTrue(thoughtIds.isNotEmpty() && thoughtIds.all { it != null }, "thought chunks must carry a messageId")
            thoughtIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUIDv7 format, got '$id'")
            }
            assertEquals(
                2,
                thoughtIds.distinct().size,
                "each LLM iteration's thought deltas share one messageId, got $thoughtIds"
            )
            assertEquals(thoughtIds[1], thoughtIds[2], "iteration-2 thought deltas share one messageId")
            assertNotEquals(thoughtIds[0], thoughtIds[1], "a fresh LLM iteration gets a fresh thought messageId")
            println("[ok] agent_thought_chunk relayed from reasoning deltas, one UUIDv7 messageId per iteration")

            val textIds = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .map { it.messageId?.value }
            assertTrue(textIds.isNotEmpty() && textIds.all { it != null }, "message chunks must carry a messageId")
            textIds.forEach { id ->
                assertTrue(uuidRegex.matches(id!!), "messageId must be UUIDv7 format, got '$id'")
            }
            assertEquals(
                2,
                textIds.distinct().size,
                "each LLM iteration's text deltas share one messageId, got $textIds"
            )
            assertEquals(
                thoughtIds.toSet(),
                textIds.toSet(),
                "an iteration's reasoning and reply deltas share the same messageId",
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
                thoughtWireIds.size >= 3,
                "expected raw agent_thought_chunk lines on the wire, got ${thoughtWireIds.size}",
            )
            thoughtWireIds.forEach { id ->
                assertTrue(uuidRegex.matches(id), "wire messageId must be UUIDv7 format, got '$id'")
            }
            assertEquals(2, thoughtWireIds.distinct().size, "wire thought chunks must share per-iteration messageIds")
            assertEquals(thoughtIds.toList(), thoughtWireIds.toList(), "wire messageIds must match the decoded ones")
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
            assertEquals("DdrAcpAgentKotlin", appTitle)
            println("[ok] attribution headers sent (HTTP-Referer + X-OpenRouter-Title)")

            val buildCommit = Properties().apply {
                E2eWireConformanceTest::class.java.getResourceAsStream("/git.properties")!!.use { load(it) }
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
}