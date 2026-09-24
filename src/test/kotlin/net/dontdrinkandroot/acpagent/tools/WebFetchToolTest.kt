package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolKind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebFetchToolTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2)
        server.start() // create() only binds; without start() no request is ever answered
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun context() = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = ClientCapabilities(),
        sessionId = SessionId("sess_test"),
        webFetchAllowPrivate = true, // the loopback mock server is the point of these tests
    )

    private fun serve(path: String, contentType: String, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun serve(
        path: String,
        contentType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) = serve(path, contentType, body.toByteArray(Charsets.UTF_8), headers)

    private suspend fun fetch(url: String, context: ToolContext = context()): ToolResult =
        WebFetchTool().execute(buildJsonObject { put("url", url) }, context)

    @Test
    fun `html is converted to numbered lines with scripts and styles stripped`() = runBlocking {
        serve(
            "/page",
            "text/html; charset=utf-8",
            "<html><head><script>var x=1;</script><style>p{color:red}</style></head>" +
                "<body><h1>Title</h1><p>Hello <b>world</b></p><ul><li>one</li><li>two</li></ul></body></html>",
        )
        val result = fetch("$baseUrl/page")
        assertFalse(result.isError)
        val lines = result.text.lines()
        assertTrue(lines[0].endsWith("│Title"), "first line is the heading, got: ${lines[0]}")
        assertContains(result.text, "Hello world")
        assertContains(result.text, "• one")
        assertContains(result.text, "• two")
        assertFalse(result.text.contains("var x=1"))
        assertFalse(result.text.contains("color:red"))
    }

    @Test
    fun `non-html text types pass through`() = runBlocking {
        serve("/data.json", "application/json", "{\"a\":1}")
        val result = fetch("$baseUrl/data.json")
        assertFalse(result.isError)
        assertContains(result.text, "│{\"a\":1}")
    }

    @Test
    fun `output is line-paged with the read_file footer`() = runBlocking {
        serve("/big", "text/plain", (1..30).joinToString("\n") { "line $it" })
        // A full read is complete: numbered, no footer (nothing below).
        val full = fetch("$baseUrl/big")
        assertFalse(full.isError)
        assertFalse(full.text.contains("(Showing lines"))
        assertTrue(full.text.contains("│line 30"))
        // A partial window carries the continue footer.
        val paged = WebFetchTool().execute(
            buildJsonObject {
                put("url", "$baseUrl/big")
                put("startLine", 10)
                put("maxLines", 5)
            },
            context(),
        )
        assertFalse(paged.isError)
        // 5 body lines + blank separator + footer line.
        val lines = paged.text.lines()
        assertEquals(7, lines.size)
        assertTrue(lines[0].trimStart().startsWith("10│"))
        assertTrue(lines[4].trimStart().startsWith("14│"))
        assertContains(paged.text, "(Showing lines 10-14 of 30. Use startLine=15 and maxLines to continue.)")
    }

    @Test
    fun `declared binary content types are refused before download`() = runBlocking {
        serve("/doc.pdf", "application/pdf", "%PDF-1.4 fake".toByteArray())
        val result = fetch("$baseUrl/doc.pdf")
        assertTrue(result.isError)
        assertContains(result.text, "binary content (application/pdf")
        assertContains(result.text, "bash")
    }

    @Test
    fun `mislabeled binary payloads are caught by the NUL sniff`() = runBlocking {
        serve("/liar", "text/plain", "text\u0000with-NUL".encodeToByteArray())
        val result = fetch("$baseUrl/liar")
        assertTrue(result.isError)
        assertContains(result.text, "binary content (text/plain")
    }

    @Test
    fun `gzip responses are decompressed`() = runBlocking {
        val body = "plain text body".toByteArray(Charsets.UTF_8)
        val gzip = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(body) }
        }.toByteArray()
        server.createContext("/gzipped") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, gzip.size.toLong())
            exchange.responseBody.use { it.write(gzip) }
        }
        val result = fetch("$baseUrl/gzipped")
        assertFalse(result.isError, "expected success, got: ${result.text.take(200)}")
        assertContains(result.text, "│plain text body")
    }

    @Test
    fun `redirects are followed and validated per hop`() = runBlocking {
        // The redirect response carries a short body with Content-Length (like
        // real servers): a bodyless 302 (sendResponseHeaders(..., -1)) leaves
        // the client waiting for EOF on a keep-alive connection.
        val body = "moved".toByteArray()
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target-redirect")
            exchange.sendResponseHeaders(302, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        serve("/target-redirect", "text/plain", "after redirect")
        val result = fetch("$baseUrl/redirect")
        assertFalse(result.isError, "expected success, got: ${result.text}")
        assertContains(result.text, "after redirect")
    }

    @Test
    fun `loopback host is blocked by default and allowed on opt-out`() {
        // validateHop is called with allowPrivate=false for a loopback URL:
        // blocked; with the opt-out: allowed.
        val url = "http://127.0.0.1:9/x"
        val blocked = runCatching { WebFetcher.validateHop(url, allowPrivate = false) }
        assertTrue(blocked.isFailure)
        assertContains(blocked.exceptionOrNull()!!.message!!, "ACP_WEB_FETCH_ALLOW_PRIVATE")
        runCatching { WebFetcher.validateHop(url, allowPrivate = true) }.getOrThrow()
    }

    @Test
    fun `localhost by name is blocked even with the private opt-out off`() {
        val blocked = runCatching { WebFetcher.validateHop("http://localhost/x", allowPrivate = false) }
        assertTrue(blocked.isFailure)
    }

    @Test
    fun `non-http schemes are refused`() = runBlocking {
        for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "data:text/plain,hi")) {
            val result = fetch(url)
            assertTrue(result.isError, "expected error for $url")
            assertContains(result.text, "scheme")
        }
    }

    @Test
    fun `oversize Content-Length is refused before reading the body`() = runBlocking {
        // The declared length exceeds the cap; the tiny body only satisfies
        // the JDK server, which always writes its own Content-Length.
        val oversized = ByteArray((21L * 1024 * 1024).toInt())
        serve("/huge", "text/plain", oversized)
        val result = fetch("$baseUrl/huge")
        assertTrue(result.isError, "expected error, got: ${result.text.take(200)}")
        assertContains(result.text, "response too large")
    }

    @Test
    fun `argument validation follows the strict null conventions`() = runBlocking {
        serve("/x", "text/plain", "x")
        suspend fun result(arguments: kotlinx.serialization.json.JsonObject): ToolResult =
            WebFetchTool().execute(arguments, context())
        assertEquals("Missing 'url'", result(buildJsonObject { }).text)
        assertEquals("'url' must not be null", result(buildJsonObject { put("url", JsonNull) }).text)
        assertEquals(
            "'maxLines' must not be null",
            result(buildJsonObject { put("url", "$baseUrl/x"); put("maxLines", JsonNull) }).text,
        )
        assertEquals(
            "'maxLines' must be between 1 and 2000",
            result(buildJsonObject { put("url", "$baseUrl/x"); put("maxLines", 0) }).text,
        )
        assertEquals(
            "'startLine' must be a positive integer (1-based)",
            result(buildJsonObject { put("url", "$baseUrl/x"); put("startLine", 0) }).text,
        )
    }

    @Test
    fun `http error statuses surface the status in the error`() = runBlocking {
        val body = "not found".toByteArray()
        server.createContext("/missing") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val result = fetch("$baseUrl/missing")
        assertTrue(result.isError)
        assertContains(result.text, "404")
    }

    @Test
    fun `startLine past the content end is a clean error`() = runBlocking {
        serve("/short", "text/plain", "one\ntwo")
        val result = WebFetchTool().execute(
            buildJsonObject {
                put("url", "$baseUrl/short")
                put("startLine", 99)
            },
            context(),
        )
        assertTrue(result.isError)
        assertContains(result.text, "startLine 99 is past the end")
    }

    @Test
    fun `an empty body is a successful empty read, not an error`() = runBlocking {
        serve("/empty", "text/html; charset=utf-8", "<html><body></body></html>")
        val result = fetch("$baseUrl/empty")
        assertFalse(result.isError, "empty content must not be a tool error, got: ${result.text}")
        assertEquals("(empty content)", result.text)
    }

    @Test
    fun `tool metadata pins the fetch kind and prompt-free surface`() {
        val tool = WebFetchTool()
        assertEquals("web_fetch", tool.name)
        assertFalse(tool.mutating)
        assertEquals(ToolKind.FETCH, tool.kind)
        assertTrue(tool.targetPaths(buildJsonObject { put("url", "http://example.com") }).isEmpty())
        assertEquals("web_fetch(url: http://example.com)", tool.title(buildJsonObject { put("url", "http://example.com") }))
    }

    @Test
    fun `schema pins url with optional paging parameters`() {
        assertEquals(
            """{"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to fetch."},"startLine":{"type":"integer","description":"First line to return (1-based). Defaults to the start."},"maxLines":{"type":"integer","description":"Maximum number of lines to return (1-2000). Defaults to 500."}},"required":["url"]}""",
            llmWireJson.encodeToString(WebFetchTool().parameters),
        )
    }
}
