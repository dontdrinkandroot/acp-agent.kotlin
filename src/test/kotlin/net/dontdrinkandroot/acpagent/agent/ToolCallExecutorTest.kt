package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        var executedCount = 0
        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            executed = true
            executedCount++
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
        toolRegistry = ToolRegistry(),
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
        val executor = ToolCallExecutor(registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "gated", "{}"), SessionModeId("build"))

        assertEquals(false, gated.executed)
        val update = toolCallUpdates(emitter).single()
        assertEquals(ToolCallStatus.FAILED, update.status)
        assertEquals("Disabled in current mode", update.title)
        // The disabled tool result is recorded for the model and nothing else
        // is appended by the execution (no seed message: mode messages are
        // written at prompt start only, issue #36).
        val historyAfter = state.historySnapshot
        assertTrue(
            historyAfter.last() is OpenAIMessage.Tool,
            "the denied tool result must be the last history entry",
        )
        assertEquals(
            1, historyAfter.size,
            "history: denied tool result only; nothing else appended",
        )
    }

    @Test
    fun `executed and denied calls record their terminal outcome`() = runBlocking {
        val okTool = PathTool("write", mutating = true)
        val failTool = object : RecordingTool("boom", false) {
            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
                ToolResult("it broke", true)
        }
        val registry = ToolRegistry().apply {
            register(okTool)
            register(failTool)
        }
        val state = state()
        val executor = ToolCallExecutor(registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_ok", "write", """{"path":"/project/a.txt"}"""))
        execute(executor, emitter, StreamToolCall("call_2", "boom", "{}"))
        execute(executor, emitter, StreamToolCall("call_3", "no_such_tool", "{}"), SessionModeId("build"))

        val expected = mapOf(
            "call_ok" to TOOL_OUTCOME_COMPLETED,
            "call_2" to TOOL_OUTCOME_FAILED,
            "call_3" to TOOL_OUTCOME_FAILED,
        )
        assertEquals(expected, state.replaySnapshot().third)
        assertEquals(
            expected,
            state.buildRecord().toolOutcomes,
            "the outcomes must reach the persisted record",
        )
    }

    @Test
    fun `every executed or denied tool call persists the record immediately`() = runBlocking {
        val storeDir = Files.createTempDirectory("acp-agent-executor-persist")
        val store = SessionStore(storeDir)
        val sessionId = SessionId("sess_0123456789abcdef")
        val state = SessionState(
            sessionId = sessionId,
            cwd = "/project",
            toolRegistry = ToolRegistry(),
            config = Config("k", "m", "http://127.0.0.1:1"),
            restored = null,
            sessionStore = store,
            closeResources = {},
        )
        val tool = PathTool("write", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "write", """{"path":"/project/a.txt"}"""))
        execute(executor, emitter, StreamToolCall("call_2", "unknown_tool", "{}"))

        val record = store.load(sessionId.value)
        assertNotNull(record, "each tool call must have persisted the record")
        assertEquals(
            mapOf("call_1" to TOOL_OUTCOME_COMPLETED, "call_2" to TOOL_OUTCOME_FAILED),
            record.toolOutcomes,
            "the persisted record already carries the second call's outcome",
        )
        storeDir.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `in-project mutating tool executes without a permission prompt`() = runBlocking {
        val tool = PathTool("write", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val state = state()
        val executor = ToolCallExecutor(registry, state)
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
        val executor = ToolCallExecutor(registry, state)
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
        val executor = ToolCallExecutor(registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "bash", "{}"))

        assertEquals(true, tool.executed, "no client means no permission prompt and the call is allowed")
        assertTrue(toolCallUpdates(emitter).any { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `the tool receives exactly the arguments the permission flow saw`() = runBlocking {
        val received = mutableListOf<JsonObject>()
        val tool = object : RecordingTool("capture", false) {
            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                received += arguments
                return ToolResult("done")
            }
        }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state())
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "capture", """{"path":"/project/a.txt","n":2}"""))

        // The executor parses once: the object handed to the tool is the same
        // one the title, locations and permission decision consumed (the
        // permission identity "the touched file is the approved file" must not
        // depend on a second parse agreeing by chance).
        assertEquals(
            listOf<JsonObject>(buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("/project/a.txt"))
                put("n", kotlinx.serialization.json.JsonPrimitive(2))
            }),
            received,
        )
    }

    @Test
    fun `malformed arguments degrade to the wrapped form the tool receives`() = runBlocking {
        val received = mutableListOf<JsonObject>()
        val tool = object : RecordingTool("capture", false) {
            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                received += arguments
                return ToolResult("done")
            }
        }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state())
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "capture", "not json"))

        assertEquals(
            "not json",
            received.single()["arguments"]?.jsonPrimitive?.content,
            "malformed arguments must degrade to {\"arguments\": \"<raw>\"}",
        )
    }

    @Test
    fun `the same executor serves calls with different modes independently`() = runBlocking {
        val gated = object : RecordingTool("gated", false) {
            override val modes: List<SessionModeId> = listOf(SessionModeId("bash"))
        }
        val registry = ToolRegistry().apply { register(gated) }
        val state = state()
        val executor = ToolCallExecutor(registry, state)
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

    @Test
    fun `immediately repeated identical call is refused without execution`() = runBlocking {
        val tool = RecordingTool("capture", false)
        val registry = ToolRegistry().apply { register(tool) }
        val state = state()
        val executor = ToolCallExecutor(registry, state)
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "capture", """{"path": "a.txt", "limit": 115}"""))
        execute(executor, emitter, StreamToolCall("call_2", "capture", """{"path": "a.txt", "limit": 115}"""))

        assertEquals(1, tool.executedCount, "only the first call must execute")
        val update = toolCallUpdates(emitter).last()
        assertEquals(ToolCallStatus.FAILED, update.status)
        assertEquals("Repeated tool call", update.title)
        val message = (update.content.orEmpty().filterIsInstance<ToolCallContent.Content>().single().content
                as ContentBlock.Text).text
        assertTrue(message.contains("capture"), "the refusal must name the tool")
        assertTrue(message.contains("""{"path": "a.txt", "limit": 115}"""), "the refusal must carry the raw arguments")
        assertEquals(
            1, state.replaySnapshot().third.filterValues { it == TOOL_OUTCOME_COMPLETED }.size,
            "the first call completed",
        )
        assertEquals(
            "call_2", state.replaySnapshot().third.filterValues { it == TOOL_OUTCOME_FAILED }.keys.single(),
        )
        val historyAfter = state.historySnapshot
        assertEquals(2, historyAfter.size, "history: two tool results (completed + refused)")
        assertTrue(historyAfter.last() is OpenAIMessage.Tool)
        val refusedResult = historyAfter.last() as OpenAIMessage.Tool
        val refusedContent = refusedResult.content as Content.Text
        assertEquals("call_2", refusedResult.toolCallId)
        assertTrue(
            refusedContent.text().contains("""{"path": "a.txt", "limit": 115}"""),
            "the history error must carry the raw arguments so the model sees what repeated",
        )
    }

    @Test
    fun `reformatted arguments of the same call are not refused`() = runBlocking {
        val tool = RecordingTool("capture", false)
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state())
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "capture", """{"path": "a.txt", "limit": 115}"""))
        execute(executor, emitter, StreamToolCall("call_2", "capture", """{"limit":115,"path":"a.txt"}"""))

        assertEquals(2, tool.executedCount, "a formatting variant must never count as the same call")
        assertTrue(toolCallUpdates(emitter).any { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `an intervening different call resets the repeat guard`() = runBlocking {
        val tool = RecordingTool("capture", false)
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state())
        val emitter = RecordingEmitter()

        execute(executor, emitter, StreamToolCall("call_1", "capture", "{}"))
        execute(executor, emitter, StreamToolCall("call_2", "capture", """{"k": 1}"""))
        execute(executor, emitter, StreamToolCall("call_3", "capture", "{}"))

        assertEquals(3, tool.executedCount, "the call in between must reset the guard")
        assertTrue(toolCallUpdates(emitter).all { it.status == ToolCallStatus.COMPLETED })
    }

    @Test
    fun `repeating a call that was denied does not refuse it`() = runBlocking {
        val tool = PathTool("read", mutating = false)
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolCallExecutor(registry, state())
        val emitter = RecordingEmitter()
        val denyingClient = object : ClientSessionOperations {
            val requests = mutableListOf<SessionUpdate.ToolCallUpdate>()
            override suspend fun requestPermissions(
                toolCall: SessionUpdate.ToolCallUpdate,
                permissions: List<PermissionOption>,
                _meta: JsonElement?,
            ): RequestPermissionResponse {
                requests += toolCall
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("reject_once")))
            }

            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
        }

        execute(
            executor,
            emitter,
            StreamToolCall("call_1", "read", """{"path": "/etc/passwd"}"""),
            client = denyingClient
        )
        execute(
            executor,
            emitter,
            StreamToolCall("call_2", "read", """{"path": "/etc/passwd"}"""),
            client = denyingClient
        )

        assertEquals(2, denyingClient.requests.size, "the repeated call must ask for permission again")
        assertEquals(0, tool.executedCount, "both calls were denied, so neither executed")
    }
}
