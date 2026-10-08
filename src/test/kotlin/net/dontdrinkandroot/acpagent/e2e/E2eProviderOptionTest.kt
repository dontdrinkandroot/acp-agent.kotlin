package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dontdrinkandroot.acpagent.agent.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Black-box `provider` config option (issue #42): the option advertises
 * `auto` + the model's provider slugs from the endpoints feed, a manual pick
 * reaches the chat request as `provider.order` (without sort/max_price),
 * switching back to auto restores the throughput/median-cap routing, an
 * unavailable feed hides the option, the option stays visible with the auto
 * routing env toggle off while a manual pick still routes the request, and
 * the selection survives restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eProviderOptionTest : E2eAgentTest() {

    private fun providerOption(options: List<SessionConfigOption>) =
        options.filterIsInstance<SessionConfigOption.Select>().firstOrNull { it.id.value == "provider" }

    @Test
    fun `e2e provider option advertises auto plus the model slugs and pins the request order`() = runBlocking {
        withE2eAgent("provider-option", MockOpenAiServer("unused", textOnly = true)) {
            val connection = connect()
            try {
                connectTextOnly(connection)
                val session = newSession(connection.client, projectDir, TestClientOperations())

                val option = assertNotNull(providerOption(session.configOptions.value))
                assertEquals(
                    listOf("auto", "p1", "p2"),
                    (option.options as SessionConfigSelectOptions.Flat).options.map { it.value.value },
                )
                assertEquals("auto", option.currentValue.value)
                assertEquals(
                    listOf("Auto", "Provider One", "Provider Two"),
                    (option.options as SessionConfigSelectOptions.Flat).options.map { it.name },
                )
                println("[ok] provider option advertised (auto + p1/Provider One + p2/Provider Two)")

                // Manual pick: the next chat request carries provider.order only.
                val switched = session.setConfigOption(SessionConfigId("provider"), SessionConfigOptionValue.of("p1"))
                assertEquals("p1", assertNotNull(providerOption(switched.configOptions)).currentValue.value)
                val events = collectPrompt(session, listOf(com.agentclientprotocol.model.ContentBlock.Text("hi")))
                assertEndTurn(events)
                val provider = llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]!!.jsonObject
                assertEquals(
                    listOf("p1"),
                    provider["order"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                assertNull(provider["sort"], "a manual pick must not carry the auto sort")
                assertNull(provider["max_price"], "a manual pick must not carry the median price cap")
                println("[ok] manual pick p1 sent as provider.order=[p1] without sort/max_price")

                // Back to auto: the throughput/median-cap routing returns.
                session.setConfigOption(SessionConfigId("provider"), SessionConfigOptionValue.of("auto"))
                val autoEvents =
                    collectPrompt(session, listOf(com.agentclientprotocol.model.ContentBlock.Text("again")))
                assertEndTurn(autoEvents)
                val autoProvider = llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]!!.jsonObject
                assertEquals("throughput", autoProvider["sort"]?.jsonPrimitive?.content)
                assertEquals(
                    60.0,
                    autoProvider["max_price"]?.jsonObject?.get("completion")?.jsonPrimitive?.content?.toDouble(),
                )
                assertNull(autoProvider["order"], "auto must not carry an order list")
                println("[ok] auto restores throughput sort + median cap")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e provider option is hidden when the endpoints feed fails and the session keeps working`() = runBlocking {
        withE2eAgent("provider-failopen", MockOpenAiServer("unused", textOnly = true, failEndpoints = true)) {
            val connection = connect()
            try {
                connectTextOnly(connection)
                val session = newSession(connection.client, projectDir, TestClientOperations())
                assertNull(
                    providerOption(session.configOptions.value),
                    "an unavailable endpoints feed must hide the provider option",
                )
                println("[ok] endpoints failure hides the provider option (fail-open)")

                // Setting a model re-reads the (still failing) feed; the session keeps working.
                session.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.of("test-model"))
                assertNull(providerOption(session.configOptions.value))
                val events = collectPrompt(session, listOf(com.agentclientprotocol.model.ContentBlock.Text("hi")))
                assertEndTurn(events)
                println("[ok] session keeps working with the option hidden")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e the provider option stays visible with auto routing disabled and a manual pick still routes`() =
        runBlocking {
            withE2eAgent("provider-env", MockOpenAiServer("unused", textOnly = true)) {
                val sessionId: SessionId
                // Agent 1 (auto routing enabled): pick p1 and persist it.
                val first = connect()
                try {
                    connectTextOnly(first)
                    val ops = TestClientOperations()
                    val session = newSession(first.client, projectDir, ops)
                    sessionId = session.sessionId
                    session.setConfigOption(SessionConfigId("provider"), SessionConfigOptionValue.of("p1"))
                    val events = collectPrompt(session, listOf(com.agentclientprotocol.model.ContentBlock.Text("hi")))
                    assertEndTurn(events)
                    println("[ok] pick p1 applied with auto routing enabled")
                } finally {
                    first.close()
                }

                // Agent 2 (auto routing disabled): the option stays visible (only
                // `auto` degrades), and the persisted pick still routes the request.
                val second = connect(extraEnv = mapOf("OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "0"))
                try {
                    second.client.initialize(testClientInfo())
                    val resumeOps = TestClientOperations()
                    val resumed = second.client.resumeSession(
                        sessionId,
                        SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                        ClientOperationsFactory { _, _ -> resumeOps },
                    )
                    val option = assertNotNull(providerOption(resumed.configOptions.value))
                    assertEquals("p1", option.currentValue.value, "the persisted pick is still the current value")
                    val events = collectPrompt(resumed, listOf(com.agentclientprotocol.model.ContentBlock.Text("hi")))
                    assertEndTurn(events)
                    assertEquals(
                        listOf("p1"),
                        llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]!!.jsonObject["order"]!!
                            .jsonArray.map { it.jsonPrimitive.content },
                    )
                    println("[ok] provider option visible with routing disabled; persisted pick beats the env toggle")
                } finally {
                    second.close()
                }
            }
        }

    @Test
    fun `e2e provider selection persists across a restart and restores on load`() = runBlocking {
        withE2eAgent("provider-persist", MockOpenAiServer("unused", textOnly = true)) {
            val sessionId: SessionId
            val first = connect()
            try {
                connectTextOnly(first)
                val ops = TestClientOperations()
                val session = newSession(first.client, projectDir, ops)
                sessionId = session.sessionId
                session.setConfigOption(SessionConfigId("provider"), SessionConfigOptionValue.of("p2"))
                val events = collectPrompt(session, listOf(com.agentclientprotocol.model.ContentBlock.Text("hi")))
                assertEndTurn(events)
                println("[ok] pick p2 on the first agent process")
            } finally {
                first.close()
            }

            val record = assertNotNull(SessionStore(sessionsStoreDir).load(sessionId.value))
            assertEquals("p2", record.provider, "the selection must persist with the record")

            val second = connect()
            try {
                second.client.initialize(testClientInfo())
                val loadOps = TestClientOperations()
                val loaded = second.client.loadSession(
                    sessionId,
                    SessionCreationParameters(cwd = projectDir.absolutePath, mcpServers = emptyList()),
                    ClientOperationsFactory { _, _ -> loadOps },
                )
                val option = assertNotNull(providerOption(loaded.configOptions.value))
                assertEquals("p2", option.currentValue.value, "load must restore the selection")
                val events = collectPrompt(loaded, listOf(com.agentclientprotocol.model.ContentBlock.Text("again")))
                assertEndTurn(events)
                assertEquals(
                    listOf("p2"),
                    llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]!!.jsonObject["order"]!!
                        .jsonArray.map { it.jsonPrimitive.content },
                )
                println("[ok] session/load restores p2 and the request still routes to p2")
            } finally {
                second.close()
            }
        }
    }
}
