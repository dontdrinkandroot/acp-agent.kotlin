package net.dontdrinkandroot.acpagent.e2e

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * A minimal MCP server over the **stdio** transport, run as a separate OS
 * process by [E2eMcpStdioTransportTest] (the transport the ACP spec makes
 * mandatory: "All Agents MUST support the stdio transport").
 *
 * Speaks NDJSON JSON-RPC on stdin/stdout - exactly like the agent itself, so
 * stdout must stay pure protocol: diagnostics go to stderr. The tool payloads
 * come from [MockMcpTools], the same ones [MockMcpServer] serves over HTTP.
 *
 * Environment (the agent forwards `McpServer.Stdio.env` verbatim):
 *  - `MOCK_MCP_MARKER` (required) marker file appended to by the mutating tools
 *  - `MOCK_MCP_SHADOW=1` additionally exposes a tool named like a local tool
 *  - `MOCK_MCP_BROKEN=1` answers every request with unparseable JSON
 *  - `MOCK_MCP_PROBE=1` records the launch context into the marker at startup
 *    (working directory, whether the agent's API key location
 *    `DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE` reached the child, and
 *    whether `PATH` did)
 *
 * Exits on stdin EOF, so an agent that gets killed never leaves the mock behind.
 */
internal object StdioMcpMockServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val marker = System.getenv("MOCK_MCP_MARKER")
            ?: fail("MOCK_MCP_MARKER is not set")
        val markerPath = Paths.get(marker)
        val shadow = System.getenv("MOCK_MCP_SHADOW") == "1"
        val broken = System.getenv("MOCK_MCP_BROKEN") == "1"
        val appendMarker: (String) -> Unit = { line ->
            Files.writeString(markerPath, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        if (System.getenv("MOCK_MCP_PROBE") == "1") {
            appendMarker("cwd=" + Paths.get("").toAbsolutePath())
            appendMarker(
                "openrouter_api_key_file=" +
                        if (System.getenv("DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE") == null) "absent" else "present"
            )
            appendMarker("path=" + if (System.getenv("PATH") == null) "absent" else "present")
        }

        val reader = BufferedReader(InputStreamReader(System.`in`))
        val out = PrintStream(System.out, false, "UTF-8")
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: continue
            val id = request["id"] ?: continue // notification (no response expected)
            val response = if (broken) "{not json" else respond(request, id, shadow, appendMarker)
            out.println(response)
            out.flush()
        }
    }

    private fun respond(
        request: JsonObject,
        id: JsonElement,
        shadow: Boolean,
        appendMarker: (String) -> Unit,
    ): String {
        val method = (request["method"] as? JsonPrimitive)?.content ?: ""
        val params = request["params"] as? JsonObject ?: buildJsonObject { }
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", requestedProtocolVersion(params))
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                put("serverInfo", buildJsonObject {
                    put("name", "mock-mcp-stdio")
                    put("version", "0.0.1")
                })
            }

            "tools/list" -> buildJsonObject { put("tools", MockMcpTools.toolsJson(shadow)) }
            "tools/call" -> MockMcpTools.callTool(
                name = (params["name"] as? JsonPrimitive)?.content ?: "",
                arguments = params["arguments"] as? JsonObject ?: buildJsonObject { },
                appendMarker = appendMarker,
            )

            else -> buildJsonObject { }
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    /**
     * Echoes the version the client asked for: the MCP SDK refuses a server
     * version outside [io.modelcontextprotocol.kotlin.sdk.types.SUPPORTED_PROTOCOL_VERSIONS],
     * so the mock must not pin one of its own.
     */
    private fun requestedProtocolVersion(params: JsonObject): String =
        (params["protocolVersion"] as? JsonPrimitive)?.content ?: MockMcpTools.PROTOCOL_VERSION

    private fun fail(message: String): Nothing {
        System.err.println("mock-mcp-stdio: $message")
        kotlin.system.exitProcess(2)
    }
}
