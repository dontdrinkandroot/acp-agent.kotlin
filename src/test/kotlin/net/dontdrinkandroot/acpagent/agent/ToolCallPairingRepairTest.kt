package net.dontdrinkandroot.acpagent.agent

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the tool-call pairing repair (issue #38): a `function_call` item whose
 * output was never appended (the turn was cancelled mid-call) must not reach
 * any history consumer - LLM requests, load replay, persisted records - with
 * its call dangling, because strict providers reject the whole request with
 * 400 "No tool output found for function call <id>".
 */
class ToolCallPairingRepairTest {

    @Test
    fun `a dangling tool call gets a synthetic failed result right after its call`() {
        val history = listOf(
            userMessage("Run the config"),
            functionCall("call_1", "run", "{\"config\":\"validate\"}"),
            userMessage("What the FUCK are you doing?!"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(4, repair.history.size, "the synthetic result is inserted, not appended at the end")
        val synthetic = assertIs<HistoryItem.ToolOutput>(repair.history[2])
        assertEquals("call_1", synthetic.callId)
        assertEquals(
            "Tool call was interrupted before a result was produced; any effects may already be applied.",
            synthetic.output,
        )
        assertEquals(setOf("call_1"), repair.danglingIds, "the caller learns which ids need a FAILED outcome")
        assertEquals(history[0], repair.history[0])
        assertEquals(history[1], repair.history[1])
        assertEquals(history[2], repair.history[3], "the items after the dangling call keep their order")
    }

    @Test
    fun `a batch keeps the real result of the answered call and closes only the dangling one`() {
        val history = listOf(
            userMessage("Do both"),
            functionCall("call_1", "bash", "{}"),
            functionCall("call_2", "bash", "{}"),
            toolOutput("call_1", "first result"),
            userMessage("you were too slow"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(6, repair.history.size)
        assertEquals("call_1", assertIs<HistoryItem.ToolOutput>(repair.history[3]).callId, "the real result stays put")
        val synthetic = assertIs<HistoryItem.ToolOutput>(repair.history[4])
        assertEquals("call_2", synthetic.callId)
        assertEquals(setOf("call_2"), repair.danglingIds)
    }

    @Test
    fun `a pair-complete history passes through unchanged`() {
        val history = listOf(
            userMessage("Do it"),
            functionCall("call_1", "bash", "{}"),
            toolOutput("call_1", "result"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(history, repair.history)
        assertEquals(emptySet(), repair.danglingIds)
    }

    @Test
    fun `repair is idempotent`() {
        val dangled = listOf(
            userMessage("Run the config"),
            functionCall("call_1", "run", "{}"),
        )
        val once = repairToolCallPairing(dangled)

        val twice = repairToolCallPairing(once.history)

        assertEquals(once.history, twice.history)
        assertEquals(emptySet(), twice.danglingIds, "a repaired id is never reported dangling again")
    }

    @Test
    fun `duplicates of one call id in the result history count as answered - they are never dangling`() {
        val history = listOf(
            functionCall("call_1", "bash", "{}"),
            toolOutput("call_1", "result"),
            toolOutput("call_1", "result"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(emptySet(), repair.danglingIds, "pairing is keyed by id, not by count")
        assertEquals(history, repair.history)
    }

    @Test
    fun `historyItemFromWire re-types only what it can reproduce, and captures the rest verbatim`() {
        val typedUser = buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "hi") }) })
        }
        val imageUser = buildJsonObject {
            put("type", "message")
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", "hi") })
                    add(buildJsonObject { put("type", "input_image"); put("image_url", "data:image/png;base64,AA") })
                },
            )
        }
        val textResult = buildJsonObject {
            put("type", "function_call_output")
            put("call_id", "call_1")
            put("output", "done")
        }
        val partResult = buildJsonObject {
            put("type", "function_call_output")
            put("call_id", "call_1")
            put("output", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "done") }) })
        }

        assertIs<HistoryItem.UserMessage>(historyItemFromWire(typedUser), "fully modeled items are re-typed")
        assertIs<HistoryItem.ToolOutput>(historyItemFromWire(textResult))
        // Anything the typed model would lose stays captured and round-trips byte-for-byte.
        listOf(imageUser, partResult).forEach { raw ->
            val item = historyItemFromWire(raw)
            assertIs<HistoryItem.Captured>(item, "unlossless item must stay captured: $raw")
            assertEquals(raw, item.toWire())
        }
    }

    @Test
    fun `a reasoning item is never treated as a call and survives the repair`() {
        val history = listOf(
            userMessage("Think, then run"),
            reasoningItem("I should run the config"),
            functionCall("call_1", "run", "{}"),
            toolOutput("call_1", "ok"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(emptySet(), repair.danglingIds)
        assertEquals(history, repair.history, "reasoning items are carried along untouched")
    }

    @Test
    fun `a dangling call after a reasoning item is closed after the reasoning item and the call`() {
        val history = listOf(
            reasoningItem("thinking"),
            functionCall("call_1", "run", "{}"),
        )

        val repair = repairToolCallPairing(history)

        assertEquals(3, repair.history.size)
        assertIs<HistoryItem.Captured>(repair.history[0])
        assertEquals("call_1", assertIs<HistoryItem.ToolOutput>(repair.history[2]).callId)
        assertEquals(setOf("call_1"), repair.danglingIds)
    }
}
