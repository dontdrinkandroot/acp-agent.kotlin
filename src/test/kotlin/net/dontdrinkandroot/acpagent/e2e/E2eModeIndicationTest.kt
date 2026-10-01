package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box wire contract of the coalesced mode indication (issue #36): mode
 * switches (idle or mid-turn) emit their client notifications immediately,
 * but the `Mode:` system message for the model is written exactly once, at
 * prompt start, for the latest mode only - never one per flip, and never for
 * a mode that did not govern a turn.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eModeIndicationTest : E2eAgentTest() {

    private fun systemTexts(llmMock: MockOpenAiServer, body: String): List<String> =
        llmMock.parseChatBody(body)["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "system" }
            .map { it["content"]!!.jsonPrimitive.content }

    private fun modeTexts(texts: List<String>): List<String> =
        texts.filter { it.startsWith("Mode: ") }

    @Test
    fun `e2e idle switches coalesce into one latest-mode message at the next prompt`() = runBlocking {
        withE2eAgent("mode-indication-idle", { projectDir ->
            MockOpenAiServer(projectDir.resolve("unused.txt").absolutePath, textOnly = true)
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)

                // Flip three times without submitting a prompt.
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bash"))
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))

                val first = collectPrompt(session, listOf(ContentBlock.Text("First prompt")))
                assertEndTurn(first)

                // First prompt: exactly one message, for the latest (build) mode,
                // positioned before the user message.
                val firstBody = requireNotNull(llmMock.requestBodies.first()) { "no chat body" }
                val firstModeTexts = modeTexts(systemTexts(llmMock, firstBody))
                assertEquals(
                    1,
                    firstModeTexts.size,
                    "three idle flips must coalesce into ONE mode message, got: $firstModeTexts",
                )
                assertTrue(
                    firstModeTexts.single().startsWith("Mode: build."),
                    "the coalesced message must state the latest mode (build), got: ${firstModeTexts.single()}",
                )
                val firstMessages = llmMock.parseChatBody(firstBody)["messages"]!!.jsonArray.map { it.jsonObject }
                assertTrue(
                    firstMessages.indexOfFirst { it["role"]!!.jsonPrimitive.content == "system" } <
                            firstMessages.indexOfFirst { it["role"]!!.jsonPrimitive.content == "user" },
                    "the mode message must precede the user message",
                )

                // A second prompt without a switch adds no further mode message.
                val second = collectPrompt(session, listOf(ContentBlock.Text("Second prompt")))
                assertEndTurn(second)
                val secondModeTexts = modeTexts(systemTexts(llmMock, llmMock.requestBodies[1]))
                assertEquals(
                    firstModeTexts,
                    secondModeTexts,
                    "an unchanged mode must not append another message",
                )

                println("[ok] three idle flips coalesced into one build-mode message at first prompt")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e a switch between turns adds exactly one message for the new mode`() = runBlocking {
        withE2eAgent("mode-indication-switch", { projectDir ->
            MockOpenAiServer(projectDir.resolve("unused.txt").absolutePath, textOnly = true)
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)

                val first = collectPrompt(session, listOf(ContentBlock.Text("First prompt")))
                assertEndTurn(first)
                val firstModeTexts = modeTexts(systemTexts(llmMock, llmMock.requestBodies[0]))
                assertEquals(1, firstModeTexts.size, "the first prompt indicates the initial plan mode")
                assertTrue(firstModeTexts.single().startsWith("Mode: plan."))

                // One switch between turns, then prompt again.
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                val second = collectPrompt(session, listOf(ContentBlock.Text("Second prompt")))
                assertEndTurn(second)

                val secondModeTexts = modeTexts(systemTexts(llmMock, llmMock.requestBodies[1]))
                assertEquals(
                    firstModeTexts.size + 1,
                    secondModeTexts.size,
                    "the build switch appends exactly one message at the second prompt",
                )
                assertTrue(
                    secondModeTexts.last().startsWith("Mode: build."),
                    "the appended message must state the new build mode, got: ${secondModeTexts.last()}",
                )

                println("[ok] a between-turns switch appends exactly one message at the next prompt")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e a mid-turn switch flashes its message at the next prompt start`() = runBlocking {
        val tmpDir = Files.createTempDirectory("acp-agent-e2e-mode-indication-flush").toFile()
        val llmMock = MockOpenAiServer(File(tmpDir, "deferred.txt").absolutePath, holdFirstRequest = true)
        try {
            withE2eAgent("mode-indication-flush", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val deferred = async {
                        collectPrompt(
                            session,
                            listOf(
                                ContentBlock.Text(
                                    "Write the text 'deferred' to ${
                                        File(
                                            tmpDir,
                                            "deferred.txt"
                                        ).absolutePath
                                    } using the write_file tool."
                                )
                            ),
                            timeoutMs = 120_000,
                        )
                    }
                    withTimeoutOrNull(15_000) { llmMock.firstRequestStarted.await() }
                    // Switch while the turn is held: deferred, applied at turn end.
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    llmMock.releaseFirstRequest()
                    val events = deferred.await()
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    assertTrue(
                        updates.any { it is SessionUpdate.CurrentModeUpdate && it.currentModeId == SessionModeId("build") },
                        "the flush must notify the client at turn end",
                    )

                    // The flush message waits for the next prompt start.
                    val next = collectPrompt(session, listOf(ContentBlock.Text("Next prompt")), timeoutMs = 60_000)
                    assertEndTurn(next)
                    val modeTexts2 = modeTexts(systemTexts(llmMock, llmMock.requestBodies.last()))
                    assertTrue(
                        modeTexts2.last().startsWith("Mode: build."),
                        "the flushed build switch must be stated at the next prompt start, got: $modeTexts2",
                    )
                    assertEquals(
                        2,
                        modeTexts2.size,
                        "plan (first turn) + build (flushed switch) - one message per governing change, got: $modeTexts2",
                    )

                    println("[ok] mid-turn switch message appears once at the next prompt start")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
