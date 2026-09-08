package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterChatCompletionStreamResponse
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterStreamChoice
import ai.koog.prompt.executor.clients.openrouter.models.OpenRouterStreamDelta
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.config.Config
import net.dontdrinkandroot.acpagent.llm.ChatCompleter
import net.dontdrinkandroot.acpagent.llm.OpenRouterModel
import net.dontdrinkandroot.acpagent.llm.ProviderPreferences
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

/** Fake stream that replays the given chunk scripts, one per request. */
private class FakeCompleter(
    vararg val scripts: List<OpenRouterChatCompletionStreamResponse>,
) : ChatCompleter {
    val requests = mutableListOf<List<OpenAIMessage>>()
    val toolRequests = mutableListOf<List<OpenAITool>>()
    private var next = 0

    override fun chatCompletion(
        messages: List<OpenAIMessage>,
        tools: List<OpenAITool>,
        reasoning: String?,
        model: String,
        provider: ProviderPreferences?,
    ): Flow<OpenRouterChatCompletionStreamResponse> = flow {
        requests += messages
        toolRequests += tools
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
        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            executed = true
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

    private fun chunk(
        content: String? = null,
        reasoning: String? = null,
        finishReason: String? = null,
        nativeFinishReason: String? = null,
    ): OpenRouterChatCompletionStreamResponse =
        OpenRouterChatCompletionStreamResponse(
            choices = listOf(
                OpenRouterStreamChoice(
                    delta = OpenRouterStreamDelta(content = content, reasoning = reasoning),
                    finishReason = finishReason,
                    nativeFinishReason = nativeFinishReason,
                )
            ),
            created = 0,
            id = "chunk-id",
            model = "test-model",
        )

    private fun toolChunk(
        index: Int,
        id: String?,
        functionName: String?,
        arguments: String?,
        finishReason: String? = null,
    ): OpenRouterChatCompletionStreamResponse =
        OpenRouterChatCompletionStreamResponse(
            choices = listOf(
                OpenRouterStreamChoice(
                    delta = OpenRouterStreamDelta(
                        toolCalls = listOf(
                            OpenAIStreamToolCall(
                                index = index,
                                id = id,
                                function = OpenAIStreamFunction(functionName, arguments),
                            )
                        )
                    ),
                    finishReason = finishReason,
                )
            ),
            created = 0,
            id = "chunk-id",
            model = "test-model",
        )

    private fun runner(
        fake: FakeCompleter,
        state: SessionState,
        registry: ToolRegistry = ToolRegistry()
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
            toolCallExecutor = ToolCallExecutor("/project", registry, state),
            maxTurnRequests = 2,
            models = listOf(testModel),
        )

    @Test
    fun `no tool call ends the turn with END_TURN and persists history`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(content = "Hello "), chunk(content = "world"), chunk(reasoning = "thinking")),
        )
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
        val assistant = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>().single()
        assertEquals("Hello world", assistant.content?.text())
    }

    @Test
    fun `tool calls execute and the loop continues until END_TURN`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            listOf(
                toolChunk(
                    index = 0,
                    id = "call_1",
                    functionName = "write_text",
                    arguments = """{"path":"/project/a.txt"}"""
                ),
            ),
            listOf(chunk(content = "Done.")),
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
    fun `exhausted budget produces a wind-down pass with MAX_TURN_REQUESTS`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            listOf(
                toolChunk(
                    index = 0,
                    id = "call_1",
                    functionName = "write_text",
                    arguments = """{"path":"/project/a.txt"}"""
                ),
            ),
            listOf(chunk(content = "Summary.")),
        )
        val state = state(registry)
        val runner = PromptRunner(
            state = state,
            systemPrompt = SystemPromptBuilder("/project", { "2026-09-03" }),
            chatCompleter = fake,
            providerRouting = null,
            sessionConfigOptions = SessionConfigOptions(
                listOf(SessionMode(SessionModeId("build"), "Build", "desc")),
                listOf(testModel),
                state,
            ),
            toolRegistry = registry,
            toolCallExecutor = ToolCallExecutor("/project", registry, state),
            maxTurnRequests = 1,
            models = listOf(testModel),
        )
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.MAX_TURN_REQUESTS, response.response.stopReason)
        assertEquals(2, fake.requests.size, "one tool iteration plus one wind-down pass")
        assertTrue(
            state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>().any { it.content?.text() == "Summary." },
            "wind-down text must be appended to history",
        )
    }

    @Test
    fun `empty completion with stop reason retries once with a continuation prompt`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            listOf(chunk(finishReason = "stop")),
            listOf(chunk(content = "Actually done.")),
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
        val users = state.historySnapshot.filterIsInstance<OpenAIMessage.User>()
        assertEquals(1, users.size, "a continuation user message must be appended")
        assertTrue(users.single().content?.text()!!.contains("empty"), users.single().content?.text())
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertEquals(listOf("Actually done."), assistants.map { it.content?.text() })
    }

    @Test
    fun `truncated length keeps the partial text and retries with a continue prompt`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(content = "Partial", finishReason = "length")),
            listOf(chunk(content = " done.")),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(2, fake.requests.size)
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertEquals(listOf("Partial", " done."), assistants.map { it.content?.text() })
        val users = state.historySnapshot.filterIsInstance<OpenAIMessage.User>()
        assertEquals(1, users.size)
        assertTrue(users.single().content?.text()!!.contains("cut off"), users.single().content?.text())
    }

    @Test
    fun `truncated length drops a complete tool call so the tool never executes`() = runBlocking {
        val tool = RecordingTool("write_text", mutating = true)
        val registry = ToolRegistry().apply { register(tool) }
        val fake = FakeCompleter(
            listOf(toolChunk(0, "call_1", "write_text", """{"path":"/project/a.txt"}""", finishReason = "length")),
            listOf(chunk(content = "Done.")),
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
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertEquals(
            listOf("Done."),
            assistants.map { it.content?.text() },
            "the dropped call must not appear in history"
        )
    }

    @Test
    fun `a second truncation ends the turn with a note instead of an exception`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(finishReason = "length")),
            listOf(chunk(finishReason = "length")),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(2, fake.requests.size, "exactly one retry")
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertEquals(1, assistants.size)
        assertTrue(assistants.single().content?.text()!!.contains("interrupted"), assistants.single().content?.text())
    }

    @Test
    fun `content filter ends the turn immediately with a notice and no retry`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(content = "Some", finishReason = "content_filter")),
            listOf(chunk(content = "must not be requested")),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(1, fake.requests.size, "content_filter must not be retried")
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertEquals(
            listOf("Some", "The response was blocked by content filtering."),
            assistants.map { it.content?.text() })
    }

    @Test
    fun `empty completion in the last iteration still gets its one retry`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(finishReason = "stop")),
            listOf(chunk(finishReason = "length")),
        )
        val state = state()
        val runner = PromptRunner(
            state = state,
            systemPrompt = SystemPromptBuilder("/project", { "2026-09-03" }),
            chatCompleter = fake,
            providerRouting = null,
            sessionConfigOptions = SessionConfigOptions(
                listOf(SessionMode(SessionModeId("build"), "Build", "desc")),
                listOf(testModel),
                state,
            ),
            toolRegistry = ToolRegistry(),
            toolCallExecutor = ToolCallExecutor("/project", ToolRegistry(), state),
            maxTurnRequests = 1,
            models = listOf(testModel),
        )
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("build"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        assertEquals(
            2,
            fake.requests.size,
            "the truncated last iteration must still get its one retry despite the cap",
        )
        val assistants = state.historySnapshot.filterIsInstance<OpenAIMessage.Assistant>()
        assertTrue(assistants.single().content?.text()!!.contains("interrupted"), assistants.single().content?.text())
    }

    @Test
    fun `renderLog dumps the unfiltered completion including empty-but-present fields`() {
        val iteration = StreamedIteration(
            rawContent = "Partial\nrest",
            toolCalls = emptyList(),
            usage = null,
            finishReason = "length",
            nativeFinishReason = "length",
            rawReasoning = "",
            reasoningDetails = listOf(JsonPrimitive("redacted")),
        )
        val log = iteration.renderLog()
        assertTrue(log.contains("finishReason=length"), log)
        assertTrue(log.contains("nativeFinishReason=length"), log)
        assertTrue(log.contains("content=Partial\\nrest"), log)
        assertTrue(log.contains("reasoning=<empty>"), log)
        assertTrue(log.contains("""reasoningDetails="redacted""""), log)
        assertEquals("Partial\nrest", iteration.text)
    }

    @Test
    fun `renderLog marks absent fields distinctly from empty ones`() {
        val iteration = StreamedIteration(
            rawContent = "Partial",
            toolCalls = emptyList(),
            usage = null,
            finishReason = "length",
        )
        val log = iteration.renderLog()
        assertTrue(log.contains("reasoning=<absent>"), log)
        assertTrue(log.contains("reasoningDetails=<absent>"), log)
        assertTrue(log.contains("nativeFinishReason=<none>"), log)
        assertEquals("Partial", iteration.text)
    }

    @Test
    fun `empty-but-present reasoning in a truncation still gets its one retry`() = runBlocking {
        val fake = FakeCompleter(
            listOf(chunk(content = "Partial", reasoning = "", finishReason = "length", nativeFinishReason = "length")),
            listOf(chunk(content = " done.")),
        )
        val state = state()
        val runner = runner(fake, state)
        val emitter = PromptRecordingEmitter()

        runner.run(emitter, SessionModeId("plan"), null, toolContext())

        val response = emitter.events.filterIsInstance<Event.PromptResponseEvent>().single()
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        val users = state.historySnapshot.filterIsInstance<OpenAIMessage.User>()
        assertEquals(1, users.size, "a continuation user message must be appended")
    }
}
