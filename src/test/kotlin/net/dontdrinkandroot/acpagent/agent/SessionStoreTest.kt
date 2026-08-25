package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionStoreTest {

    private fun store(): Pair<SessionStore, java.nio.file.Path> {
        val dir = Files.createTempDirectory("acp-agent-session-store")
        return SessionStore(dir) to dir
    }

    private fun sampleRecord(sessionId: String = "sess_0123456789abcdef") = SessionRecord(
        sessionId = sessionId,
        cwd = "/project",
        mode = "build",
        title = "Fix the bug",
        updatedAt = 1_700_000_000_000,
        history = listOf(
            OpenAIMessage.User(Content.Text("Fix the bug")),
            OpenAIMessage.Assistant(
                content = Content.Text("On it"),
                toolCalls = listOf(
                    OpenAIToolCall("call_1", OpenAIFunction("write_file", "{\"path\":\"a.txt\"}"))
                ),
            ),
            OpenAIMessage.Tool(Content.Text("Written"), toolCallId = "call_1"),
            OpenAIMessage.Assistant(content = Content.Text("Done")),
        ),
    )

    @Test
    fun `save and load round-trip a record with tool-call history`() {
        val (store, _) = store()
        val record = sampleRecord()
        store.save(record)
        val loaded = store.load(record.sessionId)

        // OpenAIMessage subclasses have no structural equals; compare the wire shape instead.
        assertEquals(
            llmWireJson.encodeToString(record.history),
            llmWireJson.encodeToString(loaded?.history ?: emptyList()),
        )
        assertEquals(record.copy(history = emptyList()), loaded?.copy(history = emptyList()))
    }

    @Test
    fun `history is persisted in wire shape with role discriminator`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        val raw = Files.readString(dir.resolve("sess_0123456789abcdef.json"))
        assertTrue(raw.contains("\"role\":\"user\""), raw)
        assertTrue(raw.contains("\"tool_call_id\":\"call_1\""), raw)
    }

    @Test
    fun `save overwrites atomically without leftover temp files`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        store.save(sampleRecord().copy(mode = "plan"))
        assertEquals("plan", store.load("sess_0123456789abcdef")?.mode)
        assertEquals(1, dir.toFile().list { _, name -> name.endsWith(".json") }?.size)
        assertEquals(0, dir.toFile().list { _, name -> name.endsWith(".tmp") }?.size)
    }

    @Test
    fun `load returns null for a missing session`() {
        val (store, _) = store()
        assertEquals(null, store.load("sess_0123456789abcdef"))
    }

    @Test
    fun `load throws for a corrupt record`() {
        val (store, dir) = store()
        Files.writeString(dir.resolve("sess_0123456789abcdef.json"), "{not json")
        assertFailsWith<SerializationException> { store.load("sess_0123456789abcdef") }
    }

    @Test
    fun `list skips corrupt records and non-json files`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        store.save(sampleRecord("sess_ffffffffffffffff").copy(title = "Second"))
        Files.writeString(dir.resolve("sess_aaaaaaaaaaaaaaaa.json"), "{not json")
        Files.writeString(dir.resolve("notes.txt"), "ignore me")

        val sessions = store.list()
        assertEquals(2, sessions.size)
        assertEquals(setOf("sess_0123456789abcdef", "sess_ffffffffffffffff"), sessions.map { it.sessionId }.toSet())
    }

    @Test
    fun `list returns empty when the sessions directory does not exist`() {
        val (store, _) = store()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete removes the record and succeeds silently for a missing session`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        store.delete("sess_0123456789abcdef")
        assertTrue(!Files.exists(dir.resolve("sess_0123456789abcdef.json")))
        store.delete("sess_0123456789abcdef")
    }

    @Test
    fun `all operations reject ids that could escape the sessions directory`() {
        val (store, dir) = store()
        val hostile = listOf(
            "sess_0123456789abcdeg",
            "sess_0123456789ABCDEF",
            "sess_0123456789abcde",
            "sess_0123456789abcdegf",
            "",
            "s-1757000000000000",
            "../etc/passwd",
            "sess_/../../x",
            "sess_0123456789abcdef.json",
        )
        for (id in hostile) {
            assertFailsWith<Exception>("id \"$id\" must be rejected") {
                store.save(sampleRecord(id))
            }
            assertFailsWith<IllegalArgumentException>("id \"$id\" must be rejected") { store.load(id) }
            assertFailsWith<IllegalArgumentException>("id \"$id\" must be rejected") { store.delete(id) }
        }
        assertTrue(dir.toFile().list()?.all { it == "sessions" || it.endsWith(".json") || !it.startsWith("sess") }
            ?: true)
    }
}
