package net.dontdrinkandroot.acpagent.mcp

import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
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
    clientInfo: Implementation
): McpServerConnection {
    return when (server) {
        is McpServer.Stdio -> connectStdio(server, clientInfo)
        is McpServer.Http -> connectHttp(server.name, server.url, server.headers, clientInfo)
        is McpServer.Sse -> connectSse(server.name, server.url, server.headers, clientInfo)
    }
}

private suspend fun connectStdio(
    server: McpServer.Stdio,
    clientInfo: Implementation
): McpServerConnection = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(listOf(server.command) + server.args)
        .apply {
            val env = environment()
            server.env.forEach { env[it.name] = it.value }
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
    val httpClient = HttpClient(CIO)
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
    val transport = httpClient.mcpSseTransport(url) {
        headers.forEach { header(it.name, it.value) }
    }
    val client = Client(clientInfo = clientInfo)
    client.connect(transport)
    McpServerConnection(name, client, onClose = { httpClient.close() })
}
