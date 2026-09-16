package net.dontdrinkandroot.acpagent.tools

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the redirect-hop SSRF re-validation of [WebFetcher] against a ktor
 * MockEngine (no DNS, no sockets): a public first hop redirecting into a
 * private/loopback host is refused at the hop boundary unless the opt-out is
 * set, and followed (in-process) with it. `192.0.2.1` is the RFC 5737
 * documentation range - an IP literal, so the guard's name check and
 * getAllByName resolve locally without a real DNS query.
 */
class WebFetcherTest {

    private val requests = mutableListOf<Url>()
    private val originalFactory = WebFetcher.clientFactory

    @BeforeTest
    fun setUp() {
        WebFetcher.clientFactory = {
            // followRedirects must match production (defaultClient): ktor's
            // client-side redirect plugin is on by default and would consume
            // the 30x itself, so the manual redirect loop - and the per-hop
            // SSRF re-validation under test - never sees the Location header.
            HttpClient(MockEngine) {
                followRedirects = false
                engine {
                    addHandler { request ->
                        requests.add(request.url)
                        when (request.url.encodedPath) {
                            "/hop" -> respondRedirect("http://127.0.0.1:9/private")
                            "/private" -> respond(
                                content = "private content",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                            )
                            else -> respondError(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        WebFetcher.clientFactory = originalFactory
    }

    @Test
    fun `redirect from a public host into a private host is refused at the hop`() = runBlocking {
        val failure = runCatching { WebFetcher.fetch("http://192.0.2.1/hop", allowPrivate = false) }
        assertTrue(failure.isFailure, "the redirected hop must be refused")
        val message = failure.exceptionOrNull()!!.message!!
        assertTrue(message.contains("private or loopback"), message)
        assertTrue(message.contains("127.0.0.1"), message)
        assertEquals(1, requests.size, "the fetch must stop before the redirected hop")
    }

    @Test
    fun `redirect into a private host is followed with the opt-out`() = runBlocking {
        val result = WebFetcher.fetch("http://192.0.2.1/hop", allowPrivate = true)
        assertEquals("http://127.0.0.1:9/private", result.url)
        assertEquals(listOf("private content"), result.lines)
        assertEquals(2, requests.size)
        assertEquals("127.0.0.1", requests[1].host)
    }
}
