package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Collects emitted events for assertions. */
private class RecordingEmitter : FlowCollector<Event> {
    val events = mutableListOf<Event>()
    override suspend fun emit(value: Event) {
        events += value
    }
}

/** Client whose permission prompt always resolves to allow_once. */
private class AllowOnceClient : ClientSessionOperations {
    val permissionRequests = mutableListOf<SessionUpdate.ToolCallUpdate>()
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequests += toolCall
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow_once")))
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}

class ToolCallExecutorTest {

    private open class RecordingTool(
        override val name: String,
        override val mutating: Boolean,
    ) : AgentTool {
        override val description = "test tool"
        override val parameters: JsonObject = buildJsonObject { }
        override val kind: ToolKind = ToolKind.OTHER
        var executed = false
        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            executed = true
            return ToolResult("done")
        }
    }

    private class PathTool(name: String, mutating: Boolean) : RecordingTool(name, mutating) {
        override val modes: List<SessionModeId> = emptyList()
        override fun targetPath(arguments: JsonObject): String? = arguments["path"]?.jsonPrimitive?.content
    }

    private fun state() = SessionState(
        sessionId = SessionId("sess_tooltest000001"),
        cwd = "/project",
        config = Config("k", "m", "http://127.0.0.1:1"),
        restored = null,
        sessionStore = null,
        closeResources = {},
    )

    private fun toolContext(client: ClientSessionOperations? = null) = ToolContext(
        cwd = "/project",
        client = client,
        clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
        sessionId = SessionId("sess_tooltest000001"),
    )

    private suspend fun execute(
        executor: ToolCallExecutor,
        emitter: RecordingEmitter,
        call: StreamToolCall,
        mode: SessionModeId = SessionModeId("build"),
        client: ClientSessionOperations? = null,
    ) {
        executor.execute(mode, toolContext(client), emitter, call)
    }

    private fun toolCallUpdates(emitter: RecordingEmitter): List<SessionUpdate.ToolCallUpdate> =
        emitter.events.filterIsInstance<Event.SessionUpdateEvent>()
            .map { it.update }
            .filterIsInstance<SessionUpdate.ToolCallUpdate>()

    @Test
    fun `disabled tool is refused before execution and does not reach history`() = runBlocking {
        val gated = object : RecordingTool("gated", false) {
            override val modes: List<SessionModeId> = listOf(SessionModeId("bash"))
        }
        val registry = ToolRegistry().apply { register(gated) }
        val state = state()
        val executor = ToolCallExecutor("/project", registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "gated", "{}"), SessionModeId("build"))

        assertEquals(false, gated.executed)
        val update = toolCallUpdates(emitter).single()
        assertEquals(ToolCallStatus.FAILED, update.status)
        assertEquals("Disabled in current mode", update.title)
        assertEquals(1, state.historySnapshot.size, "the disabled tool result is recorded for the model")
    }

    @Test
    fun `in-project mutating tool executes without a permission prompt`() = runBlocking {
        val tool = PathTool("write", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val state = state()
        val executor = ToolCallExecutor("/project", registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "write", """{"path":"/project/a.txt"}"""))

        assertEquals(true, tool.executed)
        assertTrue(toolCallUpdates(emitter).any { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `out-of-project target prompts and executes when allowed`() = runBlocking {
        val tool = PathTool("read", mutating = false)
        val registry = ToolRegistry().apply { register(tool) }
        val state = state()
        val executor = ToolCallExecutor("/project", registry, state)
        val emitter = RecordingEmitter()
        val client = AllowOnceClient()

        execute(executor, emitter, StreamToolCall("call_1", "read", """{"path":"/etc/passwd"}"""), client = client)

        assertEquals(true, tool.executed)
        assertEquals(1, client.permissionRequests.size, "out-of-project reads must ask permission")
        assertTrue(toolCallUpdates(emitter).any { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `mutating tool without a client executes (fail-open like the original shouldAllow)`() = runBlocking {
        val tool = RecordingTool("bash", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val state = state()
        val executor = ToolCallExecutor("/project", registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "bash", "{}"))

        assertEquals(true, tool.executed, "no client means no permission prompt and the call is allowed")
        assertTrue(toolCallUpdates(emitter).any { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `the same executor serves calls with different modes independently`() = runBlocking {
        val gated = object : RecordingTool("gated", false) {
            override val modes: List<SessionModeId> = listOf(SessionModeId("bash"))
        }
        val registry = ToolRegistry().apply { register(gated) }
        val state = state()
        val executor = ToolCallExecutor("/project", registry, state)
        val photoBuild = RecordingEmitter()
        val emitterBash = RecordingEmitter()

        // First call in build mode: gated tool is refused.
        execute(executor, photoBuild, StreamToolCall("call_1", "gated", "{}"), SessionModeId("build"))
        assertTrue(toolCallUpdates(photoBuild).any { it.status == ToolCallStatus.FAILED })

        // Second call on the same executor in bash mode: the same tool must run,
        // proving no per-call mode/client is leaking across executions.
        execute(executor, emitterBash, StreamToolCall("call_2", "gated", "{}"), SessionModeId("bash"))
        assertEquals(true, gated.executed, "second call must execute independently of the first mode")
        assertTrue(toolCallUpdates(emitterBash).any { it.status == ToolCallStatus.COMPLETED })
    }
}
