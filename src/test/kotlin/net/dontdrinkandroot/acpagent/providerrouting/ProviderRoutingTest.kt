package net.dontdrinkandroot.acpagent.providerrouting

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.llm.LlmClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderRoutingTest {

    private class EndpointsServer(private val endpointPrices: String, private var failures: Int = 0) {
        val requestCount = AtomicInteger(0)
        private var server: HttpServer? = null
        val port: Int
            get() = server!!.address.port

        fun start() {
            val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            http.executor = Executors.newCachedThreadPool()
            http.createContext("/models/a/m/endpoints") { exchange ->
                requestCount.incrementAndGet()
                if (failures > 0) {
                    failures--
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                    return@createContext
                }
                val body = """{"data":{"endpoints":$endpointPrices}}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            http.start()
            server = http
        }

        fun stop() {
            server?.stop(0)
        }
    }

    private fun routing(enabled: Boolean, server: EndpointsServer): ProviderRouting {
        val llm = LlmClient("key", "http://127.0.0.1:${server.port}", "m")
        return ProviderRouting(enabled, llm)
    }

    @Test
    fun `median completion price scales to usd per million`() {
        assertEquals(60.0, medianCompletionPriceUsdPerMillion(listOf(0.00006, 0.00001, 0.00012)), 1e-9)
        assertEquals(70.0, medianCompletionPriceUsdPerMillion(listOf(0.00002, 0.0001, 0.00006, 0.00008)), 1e-9)
        assertEquals(50.0, medianCompletionPriceUsdPerMillion(listOf(0.00005)), 1e-9)
        assertEquals(0.0, medianCompletionPriceUsdPerMillion(emptyList()))
    }

    @Test
    fun `disabled routing returns null without touching the network`() = runBlocking {
        val server = EndpointsServer("""[{"name":"p1","pricing":{"completion":"0.00006"}}]""")
        server.start()
        try {
            val r = routing(false, server)
            assertNull(r.providerFor("a/m"))
            assertEquals(0, server.requestCount.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `enabled routing caps at the median and caches per model`() = runBlocking {
        val server = EndpointsServer(
            """[
                {"name":"p1","pricing":{"completion":"0.00002"}},
                {"name":"p2","pricing":{"completion":"0.0001"}}
            ]"""
        )
        server.start()
        try {
            val r = routing(true, server)
            val first = r.providerFor("a/m")
            assertEquals("throughput", first?.sort)
            assertEquals(60.0, requireNotNull(first?.maxPrice?.completion), 1e-9)
            val second = r.providerFor("a/m")
            assertEquals(60.0, second?.maxPrice?.completion!!, 1e-9)
            assertEquals(1, server.requestCount.get(), "second lookup must hit the cache")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `empty endpoints feed fails open and is not cached`() = runBlocking {
        val server = EndpointsServer("[]")
        server.start()
        try {
            val r = routing(true, server)
            assertNull(r.providerFor("a/m"))
            assertNull(r.providerFor("a/m"))
            assertEquals(2, server.requestCount.get(), "empty-looking results must not be cached")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `endpoints failure fails open and is not cached`() = runBlocking {
        val server = EndpointsServer("""[{"name":"p1","pricing":{"completion":"0.00006"}}]""", failures = 1)
        server.start()
        try {
            val r = routing(true, server)
            assertNull(r.providerFor("a/m"), "endpoints error must yield no provider preferences")
            val recovered = r.providerFor("a/m")
            assertEquals(
                60.0,
                requireNotNull(recovered?.maxPrice?.completion),
                1e-9,
                "a later successful fetch must work"
            )
            val cached = r.providerFor("a/m")
            assertEquals(60.0, requireNotNull(cached?.maxPrice?.completion), 1e-9, "successful fetches must be cached")
            assertEquals(2, server.requestCount.get(), "failed and success fetch once each, then cached")
        } finally {
            server.stop()
        }
    }
}
