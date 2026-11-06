package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.ChatCompleter
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ProviderPreferences
import net.dontdrinkandroot.acpagent.llm.models.FunctionTool
import net.dontdrinkandroot.acpagent.providerrouting.ProviderOption
import net.dontdrinkandroot.acpagent.tools.AgentTool
import net.dontdrinkandroot.acpagent.tools.ToolContext
import net.dontdrinkandroot.acpagent.tools.ToolRegistry
import net.dontdrinkandroot.acpagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Collects emitted events for assertions. */
private class PromptRecordingEmitter : FlowCollector<Event> {
    val events = mutableListOf<Event>()
    override suspend fun emit(value: Event) {
        events += value
    }
}

/** Texts of all agent message chunks emitted to the client, in order. */
private fun emittedMessageTexts(emitter: PromptRecordingEmitter): List<String> =
    emitter.events.filterIsInstance<Event.SessionUpdateEvent>()
        .map { it.update }
        .filterIsInstance<SessionUpdate.AgentMessageChunk>()
        .mapNotNull { (it.content as? ContentBlock.Text)?.text }

/** Polls [condition] until it holds, failing loudly on timeout. */
private suspend fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean) {
    withTimeout(timeoutMillis) {
        while (!condition()) delay(10)
    }
}

private fun SessionState.userTexts(): List<String> =
    historySnapshot.filterIsInstance<HistoryItem.UserMessage>().mapNotNull { it.textOrNull() }

private fun SessionState.assistantTexts(): List<String> =
    historySnapshot.mapNotNull { (it as? HistoryItem.Captured)?.assistantTextOrNull() }

private fun SessionState.toolOutputs(): List<HistoryItem.ToolOutput> =
    historySnapshot.filterIsInstance<HistoryItem.ToolOutput>()

// ---------------------------------------------------------------------------
// Responses-API event scripts
// ---------------------------------------------------------------------------

private fun contentDelta(text: String): JsonObject = buildJsonObject {
    put("type", "response.content_part.delta")
    put("output_index", 0)
    put("delta", text)
}

private fun reasoningDelta(text: String): JsonObject = buildJsonObject {
    put("type", "response.reasoning.delta")
    put("output_index", 0)
    put("delta", text)
}

private fun assistantItem(text: String): JsonObject = buildJsonObject {
    put("type", "message")
    put("id", "msg_test")
    put("role", "assistant")
    put("status", "completed")
    put("content", buildJsonArray {
        add(buildJsonObject {
            put("type", "output_text")
            put("text", text)
        })
    })
}

private fun functionCallItem(callId: String, name: String, arguments: String): JsonObject = buildJsonObject {
    put("type", "function_call")
    put("id", "fc_$callId")
    put("call_id", callId)
    put("name", name)
    put("arguments", arguments)
}

private fun reasoningItemEvent(): JsonObject = buildJsonObject {
    put("type", "reasoning")
    put("id", "rs_test")
    put("summary", "thinking about it")
}

private fun doneEvent(
    status: String = "completed",
    incompleteReason: String? = null,
    errorCode: String? = null,
): JsonObject = buildJsonObject {
    put("type", if (status == "incomplete") "response.incomplete" else "response.completed")
    put("response", buildJsonObject {
        put("id", "resp_test")
        put("model", "test-model")
        put("status", status)
        incompleteReason?.let {
            put("incomplete_details", buildJsonObject { put("reason", it) })
        }
        errorCode?.let {
            put("error", buildJsonObject { put("code", it); put("message", "blocked") })
        }
    })
}

/** A text iteration: deltas, the completed message item, then the terminal event. */
private fun textScript(
    content: String? = null,
    reasoning: String? = null,
    status: String = "completed",
    incompleteReason: String? = null,
    errorCode: String? = null,
): List<JsonObject> = buildList {
    reasoning?.let { add(reasoningDelta(it)) }
    content?.let { add(contentDelta(it)) }
    if (!content.isNullOrEmpty()) add(doneEvent(status, incompleteReason, errorCode).let {
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 0)
            put("item", assistantItem(content))
        }
    })
    add(doneEvent(status, incompleteReason, errorCode))
}

/** A tool-call iteration: one completed `function_call` item plus the terminal event. */
private fun toolScript(
    callId: String,
    name: String,
    arguments: String,
    status: String = "completed",
    incompleteReason: String? = null,
): List<JsonObject> = listOf(
    buildJsonObject {
        put("type", "response.output_item.done")
        put("output_index", 0)
        put("item", functionCallItem(callId, name, arguments))
    },
    doneEvent(status, incompleteReason),
)

/** Fake stream that replays the given event scripts, one per request. */
private class FakeCompleter(
    vararg val scripts: List<JsonObject>,
) : ChatCompleter {
    val requests = mutableListOf<List<JsonObject>>()
    val toolRequests = mutableListOf<List<FunctionTool>>()
    val providers = mutableListOf<ProviderPreferences?>()
    private var next = 0

    override fun responsesStream(
        input: List<JsonObject>,
        tools: List<FunctionTool>,
        reasoning: String?,
        model: String,
        provider: ProviderPreferences?,
    ): Flow<JsonObject> = flow {
        requests += input
        toolRequests += tools
        providers += provider
        scripts[next++ % scripts.size].forEach { emit(it) }
    }
}

class PromptRunnerTest {

    private val testModel = OpenRouterModel(
        id = "test-model",
        name = "Test Model",
        supportedParameters = listOf("tools"),
        contextLength = 100_000,
    )

    private class RecordingTool(
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
            return ToolResult("ran")
        }
    }

    private fun state(toolRegistry: ToolRegistry = ToolRegistry()) = SessionState(
        sessionId = SessionId("sess_prompttest0001"),
        cwd = "/project",
        toolRegistry = toolRegistry,
        config = Config("k", "test-model", "http://127.0.0.1:1"),
        restored = null,
        sessionStore = null,
        closeResources = {},
    )

    private fun toolContext() = ToolContext(
        cwd = "/project",
        client = null,
        clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
        sessionId = SessionId("sess_prompttest0001"),
    )

    private fun runner(
        fake: ChatCompleter,
        state: SessionState,
        registry: ToolRegistry = ToolRegistry(),
        maxTurnRequests: Int = 2,
    ): PromptRunner =
        PromptRunner(
            state = state,
            systemPrompt = SystemPromptBuilder("/project", { "2026-09-03" }),
            chatCompleter = fake,
            providerRouting = null,
            sessionConfigOptions = SessionConfigOptions(
                listOf(
                    SessionMode(SessionModeId("build"), "Build", "desc"),
                    SessionMode(SessionModeId("plan"), "Plan", "desc"),
                ),
                listOf(testModel),
                state,
            ),
            toolRegistry = registry,
            toolCallExecutor = ToolCallExecutor(registry, state),
            maxTurnRequests = maxTurnRequests,
            models = listOf(testModel),
        )

    @Test
    fun `a manual provider pick is sent as order preferences`() = runBlocking {
        val fake = FakeCompleter(textScript(content = "Hello"))
        val state = state()
        val runner = PromptRunner(
            state = state,
            systemPrompt = SystemPromptBuilder("/project", { "2026-09-03" }),
            chatCompleter = fake,
            providerRouting = null,
            sessionConfigOptions = SessionConfigOptions(
                listOf(SessionMode(SessionModeId("plan"), "Plan", "desc")),
                listOf(testModel),
                state,
                initialProviderOptions = listOf(ProviderOption("azure", "Azure")),
            ).apply { state.providerSelection = "azure" },
            toolRegistry = ToolRegistry(),
            toolCallExecutor = ToolCallExecutor(ToolRegistry(), state),
            maxTurnRequests = 2,
            models = listOf(testModel),
        )
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(listOf<ProviderPreferences?>(ProviderPreferences(order = listOf("azure"))), fake.providers)
    }

    @Test
    fun `auto selection without routing omits provider preferences`() = runBlocking {
        val fake = FakeCompleter(textScript(content = "Hello"))
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(listOf<ProviderPreferences?>(null), fake.providers)
    }

    @Test
    fun `no tool call ends the turn with END_TURN and persists history`() = runBlocking {
        val fake = FakeCompleter(textScript(content = "Hello world", reasoning = "thinking"))
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertTrue(
            emitter.events.any { it is Event.SessionUpdateEvent && it.update is SessionUpdate.AgentThoughtChunk },
            "reasoning deltas must be relayed as agent thought chunks",
        )
        assertEquals(listOf("Hello world"), state.assistantTexts())
    }

    @Test
    fun `tool calls execute and the loop continues until END_TURN`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(content = "Done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertEquals(true, tool.executed, "tool call must execute")
        assertEquals(
            StopReason.END_TURN,
            emitter.events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
        )
        assertEquals(2, fake.requests.size, "tool iteration plus final plain-text iteration")
    }

    @Test
    fun `reasoning and message items of an iteration are replayed to the next iteration`() = runBlocking {
        // Issue #75: iteration n+1 must carry what iteration n produced.
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val firstIteration = listOf(
            buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", reasoningItemEvent())
            },
        ) + toolScript("call_1", "write_text", """{"path":"/project/a.txt"}""")
        val fake = FakeCompleter(firstIteration, textScript(content = "Done."))
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val secondRequest = fake.requests[1]
        val reasoningInRequest = secondRequest.filter { it.stringField("type") == "reasoning" }
        assertEquals(1, reasoningInRequest.size, "iteration 2 must replay iteration 1's reasoning item")
        assertTrue(
            reasoningInRequest.single().toString().contains("thinking about it"),
            "the reasoning payload must travel verbatim",
        )
        val calls = secondRequest.filter { it.stringField("type") == "function_call" }
        assertEquals(listOf("call_1"), calls.map { it.stringField("call_id") })
        val outputs = secondRequest.filter { it.stringField("type") == "function_call_output" }
        assertEquals(listOf("call_1"), outputs.map { it.stringField("call_id") })
        assertTrue(
            secondRequest.indexOf(outputs.single()) > secondRequest.indexOf(calls.single()),
            "the tool output must follow the call it answers",
        )
    }

    @Test
    fun `reasoning items are dropped when the turn ends`() = runBlocking {
        // Live-only: no chain-of-thought outlives the turn, and none is persisted.
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val firstIteration = listOf(
            buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", reasoningItemEvent())
            },
        ) + toolScript("call_1", "write_text", """{"path":"/project/a.txt"}""")
        val fake = FakeCompleter(firstIteration, textScript(content = "Done."))
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertTrue(
            state.historySnapshot.none { (it as? HistoryItem.Captured)?.raw?.stringField("type") == "reasoning" },
            "reasoning items must be gone once the turn ended",
        )
        assertTrue(
            state.buildRecord().history.none { it.stringField("type") == "reasoning" },
            "the persisted record must never carry reasoning",
        )
    }

    @Test
    fun `the assistant message item replayed to the next iteration is the provider's own item`() = runBlocking {
        // The Responses API requires `id` and `status` on assistant history
        // messages, so the item must be captured, not rebuilt from deltas.
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val firstIteration = listOf(contentDelta("Looking.")) +
                listOf(
                    buildJsonObject {
                        put("type", "response.output_item.done")
                        put("output_index", 0)
                        put("item", assistantItem("Looking."))
                    },
                ) + toolScript("call_1", "write_text", """{"path":"/project/a.txt"}""")
        val fake = FakeCompleter(firstIteration, textScript(content = "Done."))
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val replayed =
            fake.requests[1].filter { it.stringField("type") == "message" && it.stringField("role") == "assistant" }
        assertEquals(1, replayed.size, "the provider's assistant item must be replayed")
        assertEquals(
            assistantItem("Looking.").toString(),
            replayed.single().toString(),
            "replayed verbatim, with id and status"
        )
        assertEquals("msg_test", replayed.single().stringField("id"))
        assertEquals("completed", replayed.single().stringField("status"))
    }

    @Test
    fun `reasoning items are dropped when a cancelled turn ends`() = runBlocking {
        // Live-only means live-only on every exit, including cancellation: a
        // cancelled turn's reasoning must not be replayed into the next prompt.
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val reasoningThenCall = listOf(
            buildJsonObject {
                put("type", "response.output_item.done")
                put("output_index", 0)
                put("item", reasoningItemEvent())
            },
        ) + toolScript("call_1", "write_text", """{"path":"/project/a.txt"}""")
        // The second iteration streams forever, so the test cancels the turn
        // while it is in flight.
        val fake = object : ChatCompleter {
            var calls = 0
            override fun responsesStream(
                input: List<JsonObject>,
                tools: List<FunctionTool>,
                reasoning: String?,
                model: String,
                provider: ProviderPreferences?,
            ): Flow<JsonObject> = flow {
                if (calls++ == 0) {
                    reasoningThenCall.forEach { emit(it) }
                } else {
                    emit(reasoningDelta("still thinking"))
                    awaitCancellation()
                }
            }
        }
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        val turn = launch(Dispatchers.Default) {
            runner.run(emitter, SessionModeId("build"), null, toolContext())
        }
        // The first iteration's items (reasoning + call) are on the history once
        // the tool ran; cancel from that point on.
        awaitUntil(5_000) { tool.executed }
        turn.cancelAndJoin()

        assertTrue(
            state.historySnapshot.none { (it as? HistoryItem.Captured)?.raw?.stringField("type") == "reasoning" },
            "a cancelled turn must not leave live reasoning in the history",
        )
    }

    @Test
    fun `third identical tool call within a turn gets refused and the turn continues`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            // A single identical retry is allowed - it may reveal a new result.
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}"""),
            toolScript("call_2", "write_text", """{"path":"/project/a.txt"}"""),
            // Third identical call with the same result again: refused.
            toolScript("call_3", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(content = "Done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry, maxTurnRequests = 4)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertEquals(
            2, tool.executedCount,
            "the first two identical calls execute (a repeat may reveal new results), the third is refused",
        )
        assertEquals(
            StopReason.END_TURN,
            emitter.events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason,
        )
        val updates = emitter.events.filterIsInstance<Event.SessionUpdateEvent>()
            .map { it.update }
            .filterIsInstance<SessionUpdate.ToolCallUpdate>()
        assertEquals(ToolCallStatus.FAILED, updates.last().status)
        assertEquals(
            TOOL_OUTCOME_FAILED,
            state.replaySnapshot().third["call_3"],
            "the refused call must be recorded as failed"
        )
        assertTrue(
            state.toolOutputs().any { it.callId == "call_3" },
            "the refusal must land in the history so the model sees it",
        )
    }

    @Test
    fun `an identical call in a new turn executes again`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(content = "First turn done."),
            toolScript("call_3", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(content = "Second turn done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())
        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertEquals(2, tool.executedCount, "the guard is turn-scoped: a new turn starts clean")
    }

    @Test
    fun `exhausted budget produces a wind-down pass with MAX_TURN_REQUESTS`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(content = "Summary."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry, maxTurnRequests = 1)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.MAX_TURN_REQUESTS, response.response.stopReason)
        assertEquals(2, fake.requests.size, "one tool iteration plus one wind-down pass")
        assertTrue(
            state.assistantTexts().any { it == "Summary." },
            "wind-down text must be appended to history",
        )
    }

    @Test
    fun `a truncated wind-down pass ends with the note instead of an empty turn`() = runBlocking {
        // Issue #30 lockstep: every abnormal-end branch must tell the client.
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}"""),
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry, maxTurnRequests = 1)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertEquals(2, fake.requests.size, "one tool iteration plus one wind-down pass")
        assertEquals(
            listOf("The response was interrupted before completion; the task may need a new prompt."),
            emittedMessageTexts(emitter),
            "the note must reach the client even when the wind-down pass is truncated",
        )
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.MAX_TURN_REQUESTS, response.response.stopReason)
    }

    @Test
    fun `empty completion retries once with a continuation prompt`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
            textScript(content = "Actually done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(2, fake.requests.size, "the empty completion must be retried once")
        assertTrue(
            fake.toolRequests[1].isNotEmpty(),
            "the retry must keep tools available so the model can resume tool work",
        )
        val users = state.userTexts()
        assertEquals(1, users.size, "a continuation user message must be appended")
        assertTrue(users.single().contains("empty"), users.single())
        assertEquals(listOf("Actually done."), state.assistantTexts())
    }

    @Test
    fun `truncated completion keeps the partial text and retries with a continue prompt`() = runBlocking {
        val fake = FakeCompleter(
            textScript(content = "Partial", status = "incomplete", incompleteReason = "max_output_tokens"),
            textScript(content = " done."),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(2, fake.requests.size)
        assertEquals(listOf("Partial", " done."), state.assistantTexts())
        val users = state.userTexts()
        assertEquals(1, users.size)
        assertTrue(users.single().contains("cut off"), users.single())
    }

    @Test
    fun `truncated completion drops a complete tool call so the tool never executes`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript(
                "call_1", "write_text", """{"path":"/project/a.txt"}""",
                status = "incomplete", incompleteReason = "max_output_tokens",
            ),
            textScript(content = "Done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertFalse(tool.executed, "a truncated tool call must never execute")
        assertTrue(
            emitter.events.none { it is Event.SessionUpdateEvent && it.update is SessionUpdate.ToolCall },
            "dropped tool calls must not surface as tool_call updates",
        )
        assertEquals(2, fake.requests.size)
        assertEquals(
            listOf("Done."),
            state.assistantTexts(),
            "the dropped call must not appear in history",
        )
    }

    @Test
    fun `an undocumented status with a complete tool call executes it instead of dropping it`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            toolScript("call_1", "write_text", """{"path":"/project/a.txt"}""", status = "pause_turn"),
            textScript(content = "Done."),
        )
        val state = state(registry)
        val runner = runner(fake, state, registry)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        assertTrue(tool.executed, "a complete tool call behind an unknown status must execute (issue #16)")
        assertEquals(2, fake.requests.size, "the loop continues normally - no continuation retry")
        assertEquals(
            0,
            state.userTexts().size,
            "a completion with usable output must not get a synthetic continue prompt"
        )
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
    }

    @Test
    fun `an undocumented status with usable text ends the turn normally`() = runBlocking {
        val fake = FakeCompleter(textScript(content = "All done.", status = "pause_turn"))
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(1, fake.requests.size, "a complete completion must not trigger the continuation retry")
        assertEquals(0, state.userTexts().size)
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
    }

    @Test
    fun `an undocumented status with empty output still retries once conservatively`() = runBlocking {
        val fake = FakeCompleter(
            textScript(status = "pause_turn"),
            textScript(content = "Recovered."),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(2, fake.requests.size, "nothing usable arrived - the empty-completion retry applies")
        val users = state.userTexts()
        assertEquals(1, users.size)
        assertTrue(users.single().contains("empty"), users.single())
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
    }

    @Test
    fun `an incomplete completion with the max_output_tokens reason is classified as truncated`() = runBlocking {
        val fake = FakeCompleter(
            textScript(content = "Partial", status = "incomplete", incompleteReason = "max_output_tokens"),
            textScript(content = " continued."),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(2, fake.requests.size, "max_output_tokens means the completion was cut off")
        val users = state.userTexts()
        assertEquals(1, users.size)
        assertTrue(users.single().contains("cut off"), users.single())
        assertEquals(listOf("Partial", " continued."), state.assistantTexts())
    }

    @Test
    fun `a failed status is treated as truncation and retried once`() = runBlocking {
        val fake = FakeCompleter(
            textScript(status = "incomplete", incompleteReason = "error"),
            textScript(content = "Recovered after error."),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(2, fake.requests.size, "an error-like completion retries once like a truncation")
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
    }

    @Test
    fun `a dangling tool call is closed in the request - strict providers never see it dangling`() = runBlocking {
        val fake = FakeCompleter(textScript(content = "Understood."))
        val state = state()
        // The in-memory state a cancelled turn leaves behind (issue #38): the
        // call was appended to the history, its result never was.
        state.appendToHistory(userMessage("Run the config"))
        state.appendToHistory(functionCall("call_1", "run", "{\"config\":\"validate\"}"))
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val request = fake.requests.single()
        val synthetic = request.filter { it.stringField("type") == "function_call_output" }.single()
        assertEquals("call_1", synthetic.stringField("call_id"))
        assertEquals(CANCELLED_TOOL_RESULT_TEXT, synthetic.stringField("output"))
    }

    @Test
    fun `a second truncation ends the turn with a note instead of an exception`() = runBlocking {
        val fake = FakeCompleter(
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(2, fake.requests.size, "exactly one retry")
        assertEquals(
            listOf("The response was interrupted before completion; the task may need a new prompt."),
            emittedMessageTexts(emitter),
            "the note must reach the client even without partial text (issue #30 lockstep)",
        )
        val assistants = state.assistantTexts()
        assertEquals(1, assistants.size)
        assertTrue(assistants.single().contains("interrupted"), assistants.single())
    }

    @Test
    fun `content filter without partial text still tells the client why the turn ended`() = runBlocking {
        // Issue #30: with no partial text the note used to reach only the
        // persisted history - the client saw an empty turn ending in END_TURN.
        val fake = FakeCompleter(
            textScript(status = "incomplete", incompleteReason = "content_filter"),
            textScript(content = "must not be requested"),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        assertEquals(1, fake.requests.size, "content_filter must not be retried")
        assertEquals(
            listOf("The response was blocked by content filtering."),
            emittedMessageTexts(emitter),
            "the notice must reach the client even without partial text",
        )
        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(listOf("The response was blocked by content filtering."), state.assistantTexts())
    }

    @Test
    fun `content filter ends the turn immediately with a notice and no retry`() = runBlocking {
        val fake = FakeCompleter(
            textScript(content = "Some", status = "incomplete", incompleteReason = "content_filter"),
            textScript(content = "must not be requested"),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(1, fake.requests.size, "content_filter must not be retried")
        assertEquals(
            listOf("Some", "The response was blocked by content filtering."),
            emittedMessageTexts(emitter),
            "the streamed partial text is relayed live and must not be re-emitted; the notice exactly once",
        )
        assertEquals(
            listOf("Some", "The response was blocked by content filtering."),
            state.assistantTexts(),
        )
    }

    @Test
    fun `empty completion in the last iteration still gets its one retry`() = runBlocking {
        val fake = FakeCompleter(
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
            textScript(status = "incomplete", incompleteReason = "max_output_tokens"),
        )
        val state = state()
        val runner = runner(fake, state, ToolRegistry(), maxTurnRequests = 1)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(
            2,
            fake.requests.size,
            "the truncated last iteration must still get its one retry despite the cap",
        )
        val assistants = state.assistantTexts()
        assertTrue(assistants.single().contains("interrupted"), assistants.single())
    }

    @Test
    fun `renderLog dumps the unfiltered completion including empty-but-present fields`() {
        val iteration = StreamedIteration(
            rawContent = "Partial\nrest",
            functionCalls = emptyList(),
            usage = null,
            status = "incomplete",
            incompleteReason = "max_output_tokens",
            rawReasoning = "",
        )
        val log = iteration.renderLog()
        assertTrue(log.contains("status=incomplete"), log)
        assertTrue(log.contains("incompleteReason=max_output_tokens"), log)
        assertTrue(log.contains("content=Partial\\nrest"), log)
        assertTrue(log.contains("reasoning=<empty>"), log)
        assertEquals("Partial\nrest", iteration.text)
    }

    @Test
    fun `renderLog marks absent fields distinctly from empty ones`() {
        val iteration = StreamedIteration(
            rawContent = "Partial",
            functionCalls = emptyList(),
            usage = null,
            status = "incomplete",
        )
        val log = iteration.renderLog()
        assertTrue(log.contains("reasoning=<absent>"), log)
        assertTrue(log.contains("incompleteReason=<none>"), log)
        assertEquals("Partial", iteration.text)
    }

    @Test
    fun `empty-but-present reasoning in a truncation still gets its one retry`() = runBlocking {
        val fake = FakeCompleter(
            textScript(
                content = "Partial",
                reasoning = "",
                status = "incomplete",
                incompleteReason = "max_output_tokens"
            ),
            textScript(content = " done."),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        val users = state.userTexts()
        assertEquals(1, users.size, "a continuation user message must be appended")
    }
}
