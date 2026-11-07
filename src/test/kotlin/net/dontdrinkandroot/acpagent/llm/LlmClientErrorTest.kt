package net.dontdrinkandroot.acpagent.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class LlmClientErrorTest {

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            executor = Executors.newSingleThreadExecutor()
            start()
        }

    private fun client(port: Int) = LlmClient("sk-test", "http://127.0.0.1:$port/v1")

    private fun userInput() = listOf<JsonObject>(
        buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("type", "input_text"); put("text", "hi") })
            })
        }
    )

    private fun sse(body: String): (HttpExchange) -> Unit = { exchange ->
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun `non-2xx responses request raises a diagnostic exception`() = runBlocking {
        val server = server { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).responsesStream(
                    input = userInput(),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("401"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `error object inside the stream raises a diagnostic exception`() = runBlocking {
        val server = server(
            sse(
                """data: {"error":{"message":"upstream provider failed","code":502}}

data: [DONE]

"""
            )
        )
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).responsesStream(
                    input = userInput(),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("upstream provider failed"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `response failed event raises a diagnostic exception`() = runBlocking {
        val server = server(
            sse(
                """data: {"type":"response.failed","response":{"id":"r","status":"failed","error":{"code":"server_error","message":"Invalid credentials"}}}

data: [DONE]

"""
            )
        )
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).responsesStream(
                    input = userInput(),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("Invalid credentials"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `non-2xx models response raises a diagnostic exception`() = runBlocking {
        val server = server { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        try {
            val error = assertFailsWith<LlmException> { client(server.address.port).fetchModels() }
            assertTrue(error.message!!.contains("500"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stream ending without response done raises`() = runBlocking {
        val server = server(
            sse(
                """data: {"type":"response.content_part.delta","delta":"par"}

"""
            )
        )
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).responsesStream(
                    input = userInput(),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("ended unexpectedly"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a stream ending right after the done sentinel counts as complete`() = runBlocking {
        // An SSE event is terminated by a blank line, but a server that closes
        // the connection immediately after `data: [DONE]` (no trailing blank
        // line) has completed the stream - the sentinel alone says so. Only the
        // blank-line boundary used to honour it, so such a stream failed as dead.
        val server = server(
            sse(
                """data: {"type":"response.content_part.delta","delta":"hi"}

data: [DONE]"""
            )
        )
        try {
            val events = client(server.address.port).responsesStream(
                input = userInput(),
                tools = emptyList(),
            ).toList()
            assertEquals(1, events.size)
            assertEquals("response.content_part.delta", events.single()["type"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stream with response done completes and unknown events are skipped`() = runBlocking {
        val server = server(
            sse(
                """data: {"type":"response.created","response":{"id":"r","status":"in_progress"}}

data: {"type":"some.unknown.future.event","payload":{"x":1}}

data: {"type":"response.content_part.delta","delta":"hi"}

data: {"type":"response.completed","response":{"id":"r","status":"completed","usage":{"input_tokens":3}}}

data: [DONE]

"""
            )
        )
        try {
            val events = client(server.address.port).responsesStream(
                input = userInput(),
                tools = emptyList(),
            ).toList()
            assertEquals(4, events.size)
            assertEquals(
                "some.unknown.future.event",
                events[1]["type"]!!.jsonPrimitive.content,
                "unknown events must be passed through, not fail the stream",
            )
            assertEquals("response.completed", events.last()["type"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }
}
