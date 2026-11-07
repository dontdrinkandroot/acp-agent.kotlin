package net.dontdrinkandroot.acpagent.tools

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.test.*

/**
 * Pins the SSRF guard of [WebFetcher] against a ktor MockEngine (no DNS, no
 * sockets): the special-purpose address ranges are recognised at the address
 * level, and a public first hop redirecting into a private/loopback host is
 * refused at the hop boundary unless the opt-out is set (then it is followed,
 * in-process). `1.1.1.1` is a public IP literal, so the guard's name check and
 * `getAllByName` resolve it locally without a real DNS query.
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
        val failure = runCatching { WebFetcher.fetch("http://1.1.1.1/hop", allowPrivate = false) }
        assertTrue(failure.isFailure, "the redirected hop must be refused")
        val message = failure.exceptionOrNull()!!.message!!
        assertTrue(message.contains("non-public"), message)
        assertTrue(message.contains("127.0.0.1"), message)
        assertEquals(1, requests.size, "the fetch must stop before the redirected hop")
    }

    @Test
    fun `redirect into a private host is followed with the opt-out`() = runBlocking {
        val result = WebFetcher.fetch("http://1.1.1.1/hop", allowPrivate = true)
        assertEquals("http://127.0.0.1:9/private", result.url)
        assertEquals(listOf("private content"), result.lines)
        assertEquals(2, requests.size)
        assertEquals("127.0.0.1", requests[1].host)
    }

    @Test
    fun `special-purpose address ranges are non-public`() {
        for (literal in NON_PUBLIC_ADDRESSES) {
            val address = InetAddress.getByName(literal)
            assertTrue(WebFetcher.isNonPublic(address), "$literal must be refused as non-public")
        }
    }

    @Test
    fun `addresses adjacent to the special-purpose ranges stay public`() {
        for (literal in PUBLIC_ADDRESSES) {
            val address = InetAddress.getByName(literal)
            assertFalse(WebFetcher.isNonPublic(address), "$literal must be accepted as public")
        }
    }

    @Test
    fun `bracketed IPv6 literal of a special-purpose range is refused`() {
        // fd00:ec2::254 is the IPv6 metadata service; the URL host keeps the
        // brackets, so this pins that the guard resolves and checks it.
        val failure = runCatching { WebFetcher.validateHop("http://[fd00:ec2::254]/latest", allowPrivate = false) }
        assertTrue(failure.isFailure, "the ULA host must be refused")
        assertContains(failure.exceptionOrNull()!!.message!!, "non-public")
        runCatching { WebFetcher.validateHop("http://[fd00:ec2::254]/latest", allowPrivate = true) }.getOrThrow()
    }

    @Test
    fun `normalize keeps the brackets of an IPv6 literal host`() {
        // normalize rebuilds the URL from URI.host; an unbracketed IPv6 host
        // would make the rebuilt URL unparseable for every later hop.
        assertEquals("http://[fd00:ec2::254]/latest", WebFetcher.normalize("http://[fd00:ec2::254]/latest"))
        assertEquals("http://[2606:4700::1111]/", WebFetcher.normalize("http://[2606:4700::1111]/"))
    }

    private companion object {
        /**
         * Flag-covered rows first (regression: they were blocked before the
         * range table), including the legacy numeric IPv4 spellings the JDK
         * still parses (`2130706433`, `127.1` -> 127.0.0.1) that the issue's
         * probe table verified. Then one sample per special-purpose range the
         * InetAddress flags alone miss. The last block covers the transition
         * prefixes that embed an IPv4 address.
         */
        val NON_PUBLIC_ADDRESSES = listOf(
            "127.0.0.1", "::1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.169.254", "fe80::1", "0.0.0.0",
            "2130706433", "127.1",
            "0.0.0.1", "0.255.255.255",
            "100.64.0.0", "100.127.255.255",
            "192.0.0.1", "192.0.2.1", "192.88.99.1",
            "198.18.0.0", "198.19.255.255",
            "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "239.255.255.255",
            "240.0.0.1", "255.255.255.254",
            "100::1", "100:0:0:1::1",
            "2001::1", "2001:2::1", "2001:db8::1", "3fff::1", "5f00::1",
            "fc00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "64:ff9b::1", "64:ff9b:1::1", "2002:7f00:1::",
            // IPv4-mapped literal: the JDK converts it to an Inet4Address, so
            // the Inet4 flag checks must still see loopback (guards a future
            // refactor that inspects raw bytes without the conversion).
            "::ffff:127.0.0.1",
        )

        /**
         * The addresses directly outside every range of the table: an
         * off-by-one in a prefix mask passes the per-range sample above but
         * fails here (or vice versa).
         */
        val PUBLIC_ADDRESSES = listOf(
            "1.0.0.1", "1.1.1.1", "100.63.255.255", "100.128.0.0", "128.0.0.1",
            "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "100:0:0:2::1",
            "2001:200::1", "2001:db9::1",
            "2003::1", "3fff:1000::1", "5f01::1",
            "64:ff9b:0:1::1", "64:ff9b:2::1",
            "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1",
            "2606:4700::1111",
        )
    }
}
