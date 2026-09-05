package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box auto provider routing: by default every chat request carries a
 * throughput sort with a median completion price cap (cached per model), an
 * endpoints failure omits the provider preferences (fail-open), and
 * `OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED=0` disables routing entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eProviderRoutingTest : E2eAgentTest() {

    @Test
    fun `e2e auto provider routing caps at the median completion price`() = runBlocking {
        withE2eAgent("routing", MockOpenAiServer("unused", textOnly = true)) {
            val connection = connect()
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
        }
    }

    @Test
    fun `e2e auto provider routing fails open`() = runBlocking {
        withE2eAgent("routing-failopen", MockOpenAiServer("unused", textOnly = true, failEndpoints = true)) {
            val connection = connect()
            try {
                connectTextOnly(connection)
                val session = newSession(connection.client, projectDir, TestClientOperations())
                llmMock.requestCount.set(0)
                val events = collectPrompt(session, listOf(ContentBlock.Text("hi")))
                assertEndTurn(events)
                val provider = llmMock.parseChatBody(llmMock.lastRequestBody)["provider"]
                assertNull(provider, "endpoints failure must omit provider preferences")
                println("[ok] auto provider routing fails open on endpoints error")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e auto provider routing disabled`() = runBlocking {
        withE2eAgent("routing-disabled", MockOpenAiServer("unused", textOnly = true)) {
            val connection = connect(extraEnv = mapOf("OPENROUTER_AUTO_THROUGHPUT_SORTING_ENABLED" to "0"))
            try {
                connectTextOnly(connection)
                val session = newSession(connection.client, projectDir, TestClientOperations())
                val events = collectPrompt(session, listOf(ContentBlock.Text("hi")))
                assertEndTurn(events)
                val body = llmMock.parseChatBody(llmMock.lastRequestBody)
                assertNull(body["provider"], "disabled routing must omit the provider preferences")
                assertEquals(0, llmMock.endpointRequestCount.toInt(), "disabled routing must not fetch endpoints")
                println("[ok] auto provider routing disabled via env")
            } finally {
                connection.close()
            }
        }
    }
}