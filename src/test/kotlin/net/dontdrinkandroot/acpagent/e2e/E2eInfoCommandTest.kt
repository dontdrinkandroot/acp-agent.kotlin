package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dontdrinkandroot.acpagent.spec.Pins
import kotlin.test.*

/**
 * Black-box wire contract of the `/info` slash command: advertised right after
 * session setup, answered from the agent's own state (no LLM call, no history,
 * no record) and strict about what counts as an invocation.
 */
@Pins("agent-process", "/info is advertised and answered locally")
@Pins("configuration", "Session state lives in the XDG state home")
@Pins("configuration", "The API key is read from a file only")
class E2eInfoCommandTest : E2eAgentTest() {

    /** The reply text, pinning that the dump is one client message. */
    private fun agentText(events: List<Event>): String {
        val chunks = events.filterIsInstance<Event.SessionUpdateEvent>()
            .map { it.update }
            .filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(1, chunks.size, "the dump must arrive as exactly one client message, got: $chunks")
        return (chunks.single().content as ContentBlock.Text).text
    }

    private fun recordsIn(sessionsDir: Path): List<Path> =
        if (Files.isDirectory(sessionsDir)) {
            Files.list(sessionsDir).use { it.toList() }
        } else {
            emptyList()
        }

    @Test
    fun `e2e session setup advertises info and the command answers without the model`() = runBlocking {
        withE2eAgent(
            "info-command",
            { projectDir -> MockOpenAiServer(projectDir.resolve("unused.txt").absolutePath, textOnly = true) },
        ) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)

                // postInitialize runs after the session/new response is dispatched.
                awaitUntil(10_000) { ops.notifications.any { it is SessionUpdate.AvailableCommandsUpdate } }
                val commands = ops.notifications.filterIsInstance<SessionUpdate.AvailableCommandsUpdate>()
                    .single().availableCommands
                assertEquals(listOf("info"), commands.map { it.name })
                assertTrue(commands.single().description.isNotBlank(), "the command needs a description")
                println("[ok] session setup advertised the info command")

                val events = collectPrompt(session, listOf(ContentBlock.Text("/info")))
                assertEndTurn(events)
                val text = agentText(events)
                assertContains(text, "- Cwd: `${projectDir.absolutePath}`")
                assertContains(text, "- Session model: test-model")
                assertContains(text, "- Base URL: http://127.0.0.1:${llmMock.port}")
                assertFalse(text.contains("sk-test-phase4"), "the API key value must never reach the client")
                assertEquals(0, llmMock.requestCount.get(), "/info must not call the model")
                assertTrue(recordsIn(sessionsStoreDir).isEmpty(), "a /info turn must not write a record")
                println("[ok] /info answered without an LLM call and without a record")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e extra text after the command is reported as discarded`() = runBlocking {
        withE2eAgent(
            "info-discarded",
            { projectDir -> MockOpenAiServer(projectDir.resolve("unused.txt").absolutePath, textOnly = true) },
        ) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val session = newSession(connection.client, projectDir, TestClientOperations())

                val events = collectPrompt(session, listOf(ContentBlock.Text("/info now check the logs")))
                assertEndTurn(events)
                assertTrue(
                    agentText(events).startsWith(
                        "**Note:** `/info` ignores additional text; discarded: \"now check the logs\""
                    ),
                    "the user must see that their extra text was ignored",
                )
                assertEquals(0, llmMock.requestCount.get(), "/info must not call the model")
                println("[ok] discarded text reported")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e a longer word is an ordinary prompt and info never enters the history`() = runBlocking {
        withE2eAgent(
            "info-word-boundary",
            { projectDir -> MockOpenAiServer(projectDir.resolve("unused.txt").absolutePath, textOnly = true) },
        ) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val session = newSession(connection.client, projectDir, TestClientOperations())

                assertEndTurn(collectPrompt(session, listOf(ContentBlock.Text("/info"))))
                assertEquals(0, llmMock.requestCount.get(), "/info must not call the model")

                // `/infofoo` is prose: it reaches the model as an ordinary prompt.
                assertEndTurn(collectPrompt(session, listOf(ContentBlock.Text("/infofoo"))))
                assertEquals(1, llmMock.requestCount.get(), "/infofoo must reach the model")

                val userTexts = llmMock.parseChatBody(llmMock.lastRequestBody)["input"]!!.jsonArray
                    .map { it.jsonObject }
                    .filter { it["role"]?.jsonPrimitive?.content == "user" }
                    .flatMap { item ->
                        item["content"]!!.jsonArray.map { part -> part.jsonObject["text"]!!.jsonPrimitive.content }
                    }
                assertEquals(listOf("/infofoo"), userTexts, "the /info turn must not become conversation history")
                println("[ok] /infofoo reached the model, /info stayed out of the history")
            } finally {
                connection.close()
            }
        }
    }
}
