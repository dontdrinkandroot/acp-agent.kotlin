package net.dontdrinkandroot.acpagent.mcp

import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

public suspend fun connectMcpServer(
    server: McpServer,
    clientInfo: Implementation,
    sessionCwd: String,
): McpServerConnection {
    return when (server) {
        is McpServer.Stdio -> connectStdio(server, sessionCwd, clientInfo)
        is McpServer.Http -> connectHttp(server.name, server.url, server.headers, clientInfo)
        is McpServer.Sse -> connectSse(server.name, server.url, server.headers, clientInfo)
    }
}

/**
 * Variables a stdio MCP server may inherit from the agent process: the process
 * basics only. Everything else - the agent's own configuration (the whole
 * `DDR_ACP_AGENT_*` family, including the API key file path), secrets, and
 * whatever else the IDE
 * or the invoking shell put into the agent environment - stays out: a server is
 * spawned automatically at `session/new`, before any permission decision, so an
 * inherited environment is an unreviewed grant (issue #14). This mirrors how the
 * docker launcher never puts the key into the agent env in the first place.
 */
internal val MCP_INHERITED_ENV: List<String> = listOf(
    "PATH", "HOME", "TMPDIR", "TMP", "TEMP",
    "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TZ",
    "USER", "LOGNAME",
)

/**
 * Builds the environment of a stdio MCP child process: an allowlist of process
 * basics from [agentEnv] ([MCP_INHERITED_ENV]) plus the client-supplied
 * [declared] variables, applied last so a server declaration can set anything -
 * including a value inherited from the agent (that is the protocol's per-server
 * escape hatch, unlike a process-wide knob).
 */
internal fun mcpChildEnvironment(
    agentEnv: Map<String, String>,
    declared: List<EnvVariable>,
): Map<String, String> = buildMap {
    MCP_INHERITED_ENV.forEach { name -> agentEnv[name]?.let { put(name, it) } }
    declared.forEach { put(it.name, it.value) }
}

private suspend fun connectStdio(
    server: McpServer.Stdio,
    sessionCwd: String,
    clientInfo: Implementation
): McpServerConnection = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(listOf(server.command) + server.args)
        .apply {
            // The session cwd is the workspace: a server that resolves relative
            // paths must see the project, not wherever the IDE spawned the agent
            // (issue #74). Never null - session creation refuses an unusable cwd.
            directory(java.io.File(sessionCwd))
            environment().apply {
                clear()
                putAll(mcpChildEnvironment(System.getenv(), server.env))
            }
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }
        .start()
    val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )
    val client = Client(clientInfo = clientInfo)
    client.connect(transport)
    McpServerConnection(server.name, client, onClose = {
        runCatching { process.destroy() }
    })
}

private suspend fun connectHttp(
    name: String,
    url: String,
    headers: List<HttpHeader>,
    clientInfo: Implementation
): McpServerConnection = withContext(Dispatchers.IO) {
    // The SSE plugin is required even for JSON-only streamable-HTTP servers:
    // after `notifications/initialized` the client probes the optional GET SSE
    // stream via `sseSession()`, which throws when the plugin is missing. A
    // 405 response then cleanly disables the stream ("stream disabled").
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }
    val transport = httpClient.mcpStreamableHttpTransport(url) {
        headers.forEach { header(it.name, it.value) }
    }
    val client = Client(clientInfo = clientInfo)
    client.connect(transport)
    McpServerConnection(name, client, onClose = { httpClient.close() })
}

private suspend fun connectSse(
    name: String,
    url: String,
    headers: List<HttpHeader>,
    clientInfo: Implementation
): McpServerConnection = withContext(Dispatchers.IO) {
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }
    // The MCP SDK deprecates classic SSE in favour of streamable HTTP (SEP-2596),
    // but ACP's `mcpServers` still carries an `sse` transport variant that a client
    // may hand us, so the transport stays until ACP drops it.
    @Suppress("DEPRECATION")
    val transport = httpClient.mcpSseTransport(url) {
        headers.forEach { header(it.name, it.value) }
    }
    val client = Client(clientInfo = clientInfo)
    client.connect(transport)
    McpServerConnection(name, client, onClose = { httpClient.close() })
}
