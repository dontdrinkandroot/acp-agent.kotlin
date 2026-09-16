package net.dontdrinkandroot.acpagent.tools

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.Locale

/**
 * The outcome of one [WebFetcher.fetch]: the final URL after redirects, the
 * HTTP status, the Content-Type header and the body decoded + converted to
 * lines.
 */
internal data class WebFetchResult(
    val url: String,
    val status: Int,
    val contentType: String,
    val lines: List<String>,
)

/**
 * Thrown when the URL fails validation or the response cannot be rendered as
 * text (binary payload, oversize). The message doubles as the tool error text.
 */
internal class WebFetchException(message: String) : Exception(message)

/**
 * Fetches a URL as text lines for the `web_fetch` tool. Owns the ktor client
 * (CIO, `requestTimeout = 0`: the CIO 15s aggregate default would silently
 * kill slow fetches - see AGENTS.md Pitfalls), the manual redirect loop with
 * per-hop SSRF re-validation, transparent gzip/deflate handling, the
 * progressive 20 MB body cap and the NUL sniff for lying content types.
 */
internal object WebFetcher {

    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY_BYTES: Long = 20L * 1024 * 1024
    private const val SOCKET_TIMEOUT_MILLIS = 60_000L
    private const val USER_AGENT = "acp-agent.kotlin web_fetch"

    /**
     * Builds the HTTP client used for a fetch. Seam for tests (the redirect /
     * SSRF tests drive the pipeline against a ktor [io.ktor.client.engine.mock.MockEngine]
     * without DNS or sockets); production keeps the CIO client configured in
     * [defaultClient] (no aggregate request timeout - the CIO 15s default
     * would kill slow fetches, see AGENTS.md Pitfalls).
     */
    internal var clientFactory: () -> HttpClient = ::defaultClient

    private fun defaultClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        engine {
            requestTimeout = 0
            endpoint {
                socketTimeout = SOCKET_TIMEOUT_MILLIS
            }
        }
        install(ContentEncoding) {
            // The encoders must be registered via the config block: the
            // bare plugin has an empty encoder map and rejects any
            // encoded response ("Content-Encoding: gzip unsupported").
            gzip()
            deflate()
        }
    }

    suspend fun fetch(rawUrl: String, allowPrivate: Boolean): WebFetchResult {
        var current = normalize(rawUrl)
        val client = clientFactory()
        try {
            var redirects = 0
            while (true) {
                validateHop(current, allowPrivate)
                val response = client.get(current) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                }
                val status = response.status.value
                val location = response.headers[HttpHeaders.Location]
                if (status in 300..399 && !location.isNullOrBlank()) {
                    if (++redirects > MAX_REDIRECTS) throw WebFetchException("too many redirects (> $MAX_REDIRECTS)")
                    // Drain (not cancel) the redirect body: cancelling poisons
                    // the pooled CIO connection and the follow-up request on it
                    // hangs until the socket timeout.
                    response.discardRemaining()
                    current = resolve(current, location)
                    continue
                }
                if (status !in 200..299) {
                    // HTTP errors fail loudly (Claude-Code-WebFetch behavior):
                    // a 404 page body is noise, the status is the signal. The
                    // redirect response is already drained, so no leak.
                    response.discardRemaining()
                    throw WebFetchException("HTTP $status for $current")
                }
                return renderResponse(current, status, response)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Reads and converts the non-redirect response. The body cap applies
     * before (Content-Length) and during (progressive read) the download, and
     * the decoded text is NUL-sniffed so a mislabeled binary payload fails
     * loudly instead of flooding the context with mojibake.
     */
    private suspend fun renderResponse(url: String, status: Int, response: HttpResponse): WebFetchResult {
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (!isTextLike(contentType)) {
            // Declared binary (PDF, images, archives, ...): refuse before the
            // body is downloaded - the model cannot render it as text, and a
            // prompt-free read-only tool must not materialize files on disk.
            throw WebFetchException(binaryMessage(contentType, declaredLength))
        }
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            throw WebFetchException(oversizeMessage(declaredLength))
        }
        val channel = response.bodyAsChannel()
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read == -1) break
            total += read
            if (total > MAX_BODY_BYTES) throw WebFetchException(oversizeMessage(total))
            buffer.write(chunk, 0, read)
        }
        val body = buffer.toByteArray()
        val text = decode(body, contentType)
        if (text.contains('\u0000')) throw WebFetchException(binaryMessage(contentType, body.size.toLong()))
        return WebFetchResult(
            url = url,
            status = status,
            contentType = contentType,
            lines = WebContentConverter.toLines(text, contentType),
        )
    }

    /**
     * Strips the fragment and validates the scheme. Only http(s) are fetched;
     * file:, ftp:, data: etc. are refused.
     */
    internal fun normalize(rawUrl: String): String {
        val uri = java.net.URI(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw WebFetchException("unsupported URL scheme (only http/https): $rawUrl")
        if (scheme != "http" && scheme != "https") {
            throw WebFetchException("unsupported URL scheme '$scheme:' (only http/https)")
        }
        val host = uri.host ?: throw WebFetchException("URL has no host: $rawUrl")
        val port = uri.port
        val portText = if (port != -1 && port != (if (scheme == "https") 443 else 80)) ":$port" else ""
        val pathAndQuery = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
        return "$scheme://${host.lowercase(Locale.ROOT)}$portText$pathAndQuery"
    }

    /**
     * Resolves a Location header against the current URL (relative locations
     * are legal); `URL(base, spec)` applies the RFC 3986 dot-segment rules.
     */
    internal fun resolve(base: String, location: String): String =
        normalize(java.net.URI(base).resolve(location).toString())

    /**
     * SSRF guard: only http(s) URLs, and hosts resolving to loopback,
     * link-local, site-local (private ranges) or any-local (0.0.0.0) addresses
     * are refused - unless [allowPrivate] opted out
     * (`ACP_WEB_FETCH_ALLOW_PRIVATE=1`, also what tests against a local mock
     * server need). `localhost` and friends are blocked by name even without
     * DNS. Every redirect hop is validated again: `resolve` + this prevent a
     * public URL from redirecting the fetch into the private network.
     * Residual risk (documented, accepted for v1): validate-then-fetch has a
     * TOCTOU window a DNS rebinding attack could exploit; full DNS pinning is
     * not exposed by the ktor CIO engine.
     */
    internal fun validateHop(url: String, allowPrivate: Boolean) {
        val parsed = java.net.URI(url).toURL()
        val host = parsed.host ?: throw WebFetchException("URL has no host: $url")
        if (allowPrivate) return
        if (looksPrivateByName(host)) throw WebFetchException(blockedMessage(host))
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        if (addresses.isEmpty()) return // DNS unavailable: cannot prove it private; caps still bound the fetch
        if (addresses.any { it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isAnyLocalAddress }) {
            throw WebFetchException(blockedMessage(host))
        }
    }

    private fun looksPrivateByName(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT)
        return name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")
    }

    /**
     * Content types the tool can render as text. Anything else (PDF, images,
     * archives, ...) is refused before the body is downloaded. Text-ish
     * application types that are common on the web (JSON, XML, JavaScript,
     * YAML/TOML, RSS) pass through; the NUL sniff covers mislabeled binaries.
     */
    private fun isTextLike(contentType: String): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.isEmpty()) return true // no type declared: rely on the NUL sniff
        if (mime.startsWith("text/")) return true
        if (WebContentConverter.isHtml(mime)) return true
        return mime in TEXT_MIME_TYPES || mime.endsWith("+xml") || mime.endsWith("+json")
    }

    private val TEXT_MIME_TYPES = setOf(
        "application/json",
        "application/javascript",
        "application/x-javascript",
        "application/xml",
        "application/rss+xml",
        "application/yaml",
        "application/x-yaml",
        "application/toml",
        "application/ld+json",
        "application/x-ndjson",
        "application/sql",
        "application/x-sh",
        "application/xhtml+xml",
        "application/graphql",
    )

    private fun decode(bytes: ByteArray, contentType: String): String {
        val charset = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset) // malformed input is replaced, never thrown
    }

    private fun oversizeMessage(bytes: Long): String =
        "response too large (${formatBytes(bytes)}; limit ${formatBytes(MAX_BODY_BYTES)}); " +
                "download it via bash (e.g. curl) instead"

    private fun binaryMessage(contentType: String, bytes: Long?): String {
        val size = bytes?.let { ", ${formatBytes(it)}" } ?: ""
        return "binary content (${"$contentType".ifBlank { "unknown type" }}$size) cannot be rendered as " +
                "text; download and process it via bash if needed (e.g. curl)"
    }

    private fun blockedMessage(host: String): String =
        "refusing to fetch private or loopback host '$host' " +
                "(set ACP_WEB_FETCH_ALLOW_PRIVATE=1 to allow)"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }
}
