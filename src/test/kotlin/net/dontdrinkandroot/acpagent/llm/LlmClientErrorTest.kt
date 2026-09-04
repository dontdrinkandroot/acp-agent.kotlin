package net.dontdrinkandroot.acpagent.llm

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

    private fun client(port: Int) = LlmClient("sk-test", "http://127.0.0.1:$port/v1", "test-model")

    @Test
    fun `non-2xx chat completion response raises a diagnostic exception`() = runBlocking {
        val server = server { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).chatCompletion(
                    messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
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
        val body = """data: {"error":{"message":"upstream provider failed","code":502}}

data: [DONE]

"""
        val server = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).chatCompletion(
                    messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("upstream provider failed"), error.message)
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
    fun `stream ending without done or finish reason raises`() = runBlocking {
        val chunk = """{"id":"c","object":"chat.completion.chunk","created":0,"model":"m",""" +
                """"choices":[{"index":0,"delta":{"content":"par"},"finish_reason":null}]}"""
        val server = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = ("data: $chunk\n\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        try {
            val error = assertFailsWith<LlmException> {
                client(server.address.port).chatCompletion(
                    messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                    tools = emptyList(),
                ).toList()
            }
            assertTrue(error.message!!.contains("ended unexpectedly"), error.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stream with finish reason but no done sentinel completes`() = runBlocking {
        val chunk = """{"id":"c","object":"chat.completion.chunk","created":0,"model":"m",""" +
                """"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}"""
        val server = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = ("data: $chunk\n\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        try {
            val events = client(server.address.port).chatCompletion(
                messages = listOf(OpenAIMessage.User(Content.Text("hi"))),
                tools = emptyList(),
            ).toList()
            assertEquals(1, events.size)
            assertEquals("hi", events.single().choices.single().delta.content)
        } finally {
            server.stop(0)
        }
    }
}