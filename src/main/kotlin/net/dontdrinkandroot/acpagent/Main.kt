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
import net.dontdrinkandroot.acpagent.tools.*
import java.time.LocalDate

public fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    KotlinLoggingConfiguration.logStartupMessage = false
    runAgent(args)
}

public fun runAgent(args: Array<String>) {
    val logger = KotlinLogging.logger {}

    val config = Config.fromEnv()
    val sessionStore = SessionStore()

    val localRegistry = ToolRegistry().apply {
        register(ReadFileTool())
        register(WriteFileTool())
        register(EditFileTool())
        register(ListDirTool())
        register(GlobTool())
        register(GrepTool())
        register(BashTool())
        register(UpdatePlanTool())
    }

    suspend fun assembleSession(
        sessionId: SessionId,
        restored: SessionRecord?,
        parameters: SessionCreationParameters,
        replay: Boolean,
    ): AgentSessionImpl {
        val registry = ToolRegistry().apply {
            registerAll(localRegistry.all())
        }
        val connections = mutableListOf<McpServerConnection>()
        suspend fun connect(server: McpServer) {
            val connection = connectMcpServer(server, createMcpClientInfo())
            runCatching { connection.listTools() }
                .onSuccess { tools ->
                    tools.forEach { tool -> registry.register(McpTool(connection, tool)) }
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
            model = config.openRouterModel,
        )
        val models = llm.fetchModels()
        return AgentSessionImpl(
            sessionId = sessionId,
            cwd = restored?.cwd ?: parameters.cwd,
            toolRegistry = registry,
            config = config,
            llm = llm,
            providerRouting = ProviderRouting(config.autoThroughputSortingEnabled, llm),
            todayProvider = ::isoDateToday,
            closeResources = {
                connections.forEach { connection -> runCatching { connection.close() } }
                llm.close()
            },
            sessionStore = sessionStore,
            restored = restored,
            replayOnInitialize = replay,
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
        val agent = Agent(protocol, agentSupport)
        protocol.start()

        while (transport.state.value != Transport.State.CLOSED) {
            delay(50)
        }
        protocol.close()
    }
}

internal fun isoDateToday(): String = LocalDate.now().toString()
