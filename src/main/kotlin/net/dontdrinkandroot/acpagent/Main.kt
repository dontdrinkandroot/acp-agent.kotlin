package net.dontdrinkandroot.acpagent

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.dontdrinkandroot.acpagent.agent.*
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.LlmClient
import net.dontdrinkandroot.acpagent.mcp.McpServerConnection
import net.dontdrinkandroot.acpagent.mcp.McpTool
import net.dontdrinkandroot.acpagent.mcp.connectMcpServer
import net.dontdrinkandroot.acpagent.mcp.createMcpClientInfo
import net.dontdrinkandroot.acpagent.providerrouting.ProviderRouting
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.localTools
import net.dontdrinkandroot.acpagent.tools.prepareScratchDirectory
import net.dontdrinkandroot.acpagent.tools.sessionTools
import java.time.LocalDate

public fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    KotlinLoggingConfiguration.logStartupMessage = false
    runAgent()
}

public fun runAgent() {
    val logger = KotlinLogging.logger {}

    val config = Config.fromEnv()
    val sessionStore = SessionStore()

    val localRegistry = ToolRegistry().apply { registerAll(localTools()) }

    suspend fun assembleSession(
        sessionId: SessionId,
        restored: SessionRecord?,
        parameters: SessionCreationParameters,
        replay: Boolean,
    ): AgentSessionImpl {
        // Prepare (create + verify) the read-trusted scratch directory before
        // anything else: a scratch directory that cannot be created or verified
        // refuses the session (issue #10), so the trust domain is never widened
        // silently and never silently narrowed.
        val scratchDir = prepareScratchDirectory(config.scratchDir)
        val registry = ToolRegistry().apply {
            registerAll(localRegistry.all())
            val cwd = restored?.cwd ?: parameters.cwd
            registerAll(sessionTools(cwd))
        }
        val connections = mutableListOf<McpServerConnection>()
        suspend fun connect(server: McpServer) {
            val connection = connectMcpServer(server, createMcpClientInfo())
            runCatching { connection.listTools() }
                .onSuccess { tools ->
                    tools.forEach { tool ->
                        if (registry.get(tool.name) == null) {
                            registry.register(McpTool(connection, tool, config.mcpTrustAnnotations))
                        } else {
                            logger.warn {
                                "MCP server ${server.name} tool \"${tool.name}\" collides with an existing " +
                                        "tool and is ignored; local tools cannot be shadowed by MCP tools"
                            }
                        }
                    }
                }
                .onFailure { failure ->
                    runCatching { connection.close() }
                    throw failure
                }
            connections += connection
        }
        parameters.mcpServers.forEach { server ->
            runCatching {
                connect(server)
            }.onFailure {
                logger.warn(it) { "Failed to connect MCP server ${server.name}: ${it.message}" }
            }
        }
        val llm = LlmClient(
            apiKey = config.openRouterApiKey,
            baseUrl = config.openRouterBaseUrl,
        )
        val models = try {
            llm.fetchModels()
        } catch (e: Exception) {
            // The session never comes into existence, so the closeResources
            // closure of AgentSessionImpl can never run: close what was opened.
            connections.forEach { connection -> runCatching { connection.close() } }
            llm.close()
            throw e
        }
        val providerRouting = ProviderRouting(config.autoThroughputSortingEnabled, llm)
        // Best-effort seed for the provider option (cached; re-read on model
        // switches). A failed feed only hides the option - unlike fetchModels,
        // whose failure aborts session creation.
        val initialProviderOptions = providerRouting.providersFor(initialModelId(restored, config))
        return AgentSessionImpl(
            sessionId = sessionId,
            cwd = restored?.cwd ?: parameters.cwd,
            toolRegistry = registry,
            config = config,
            scratchDir = scratchDir,
            llm = llm,
            providerRouting = providerRouting,
            initialProviderOptions = initialProviderOptions,
            todayProvider = ::isoDateToday,
            closeResources = {
                connections.forEach { connection -> runCatching { connection.close() } }
                llm.close()
            },
            sessionStore = sessionStore,
            restored = restored,
            replayOnLoad = replay,
            models = models,
        )
    }

    val sessionFactory = object : AgentSessionFactory {
        override suspend fun create(parameters: SessionCreationParameters): AgentSessionImpl =
            assembleSession(
                sessionId = SessionId(randomSessionId()),
                restored = null,
                parameters = parameters,
                replay = false
            )

        override suspend fun restore(
            record: SessionRecord,
            parameters: SessionCreationParameters,
            replay: Boolean
        ): AgentSessionImpl =
            assembleSession(
                sessionId = SessionId(record.sessionId),
                restored = record,
                parameters = parameters,
                replay = replay
            )
    }

    val agentSupport = AgentSupportImpl(sessionFactory, sessionStore)

    runBlocking {
        val transport = StdioTransport(
            this,
            Dispatchers.IO,
            flow {
                val reader = System.`in`.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    emit(line)
                }
            },
            { line ->
                System.out.println(line)
            },
        )
        val protocol = Protocol(this, transport)
        Agent(protocol, agentSupport)
        protocol.start()

        while (transport.state.value != Transport.State.CLOSED) {
            delay(50)
        }
        // The client went away (typically: it closed our stdin). A turn may still
        // be running - the transport's read side hit EOF, not a cancel - so stop
        // it first, then flush every live session: the cancelled turn's own
        // persist races the JVM exit, the flush does not (issue #17).
        protocol.cancelPendingIncomingRequests(CancellationException("client closed the connection"))
        agentSupport.persistAllSessions()
        protocol.close()
    }
}

internal fun isoDateToday(): String = LocalDate.now().toString()
