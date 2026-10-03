package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the tool-call pairing repair (issue #38): an assistant tool call whose
 * result was never appended (the turn was cancelled mid-call) must not reach
 * any history consumer - LLM requests, load replay, persisted records - with
 * its call dangling, because strict providers (Azure/OpenAI) reject the whole
 * request with 400 "No tool output found for function call <id>".
 */
class ToolCallPairingRepairTest {

    @Test
    fun `a dangling tool call gets a synthetic failed result right after its assistant message`() {
        val history = listOf(
            OpenAIMessage.User(Content.Text("Run the config")),
            OpenAIMessage.Assistant(
                content = Content.Text(""),
                toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("run", "{\"config\":\"validate\"}"))),
            ),
            OpenAIMessage.User(Content.Text("What the FUCK are you doing?!")),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(4, repair.history.size, "the synthetic result is inserted, not appended at the end")
        val synthetic = assertIs<OpenAIMessage.Tool>(repair.history[2])
        assertEquals("call_1", synthetic.toolCallId)
        assertEquals(
            "Tool call was interrupted before a result was produced; any effects may already be applied.",
            (synthetic.content as Content.Text).text(),
        )
        assertEquals(setOf("call_1"), repair.danglingIds, "the caller learns which ids need a FAILED outcome")
        assertEquals(history[0], repair.history[0])
        assertEquals(history[1], repair.history[1])
        assertEquals(history[2], repair.history[3], "the messages after the dangling call keep their order")
    }

    @Test
    fun `a batch keeps the real result of the answered call and closes only the dangling one`() {
        val history = listOf(
            OpenAIMessage.User(Content.Text("Do both")),
            OpenAIMessage.Assistant(
                content = Content.Text(""),
                toolCalls = listOf(
                    OpenAIToolCall("call_1", OpenAIFunction("bash", "{}")),
                    OpenAIToolCall("call_2", OpenAIFunction("bash", "{}")),
                ),
            ),
            OpenAIMessage.Tool(Content.Text("first result"), toolCallId = "call_1"),
            OpenAIMessage.User(Content.Text("you were too slow")),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(5, repair.history.size)
        assertEquals("call_1", assertIs<OpenAIMessage.Tool>(repair.history[2]).toolCallId, "the real result stays put")
        val synthetic = assertIs<OpenAIMessage.Tool>(repair.history[3])
        assertEquals("call_2", synthetic.toolCallId)
        assertEquals(setOf("call_2"), repair.danglingIds)
    }

    @Test
    fun `a pair-complete history passes through unchanged`() {
        val history = listOf(
            OpenAIMessage.User(Content.Text("Do it")),
            OpenAIMessage.Assistant(
                content = Content.Text(""),
                toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("bash", "{}"))),
            ),
            OpenAIMessage.Tool(Content.Text("result"), toolCallId = "call_1"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(history, repair.history)
        assertEquals(emptySet(), repair.danglingIds)
    }

    @Test
    fun `repair is idempotent`() {
        val dangled = listOf(
            OpenAIMessage.User(Content.Text("Run the config")),
            OpenAIMessage.Assistant(
                content = Content.Text(""),
                toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("run", "{}"))),
            ),
        )
        val once = repairToolCallPairing(dangled)

        val twice = repairToolCallPairing(once.history)

        assertEquals(once.history, twice.history)
        assertEquals(emptySet(), twice.danglingIds, "a repaired id is never reported dangling again")
    }

    @Test
    fun `duplicates of one call id in the result history count as answered - they are never dangling`() {
        val history = listOf(
            OpenAIMessage.Assistant(
                content = Content.Text(""),
                toolCalls = listOf(OpenAIToolCall("call_1", OpenAIFunction("bash", "{}"))),
            ),
            OpenAIMessage.Tool(Content.Text("result"), toolCallId = "call_1"),
            OpenAIMessage.Tool(Content.Text("result"), toolCallId = "call_1"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(emptySet(), repair.danglingIds, "pairing is keyed by id, not by count")
        assertEquals(history, repair.history)
    }
}
