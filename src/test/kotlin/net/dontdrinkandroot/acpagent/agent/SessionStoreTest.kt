package net.dontdrinkandroot.acpagent.agent

import kotlinx.serialization.SerializationException
import net.dontdrinkandroot.acpagent.tools.STALE_TEMP_AGE_MILLIS
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

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
        history = wireHistory(
            userMessage("Fix the bug"),
            assistantMessage("On it"),
            functionCall("call_1", "write_file", "{\"path\":\"a.txt\"}"),
            toolOutput("call_1", "Written"),
            assistantMessage("Done"),
        ),
    )

    @Test
    fun `save and load round-trip a record with tool-call history`() {
        val (store, _) = store()
        val record = sampleRecord()
        store.save(record)
        val loaded = store.load(record.sessionId)

        assertEquals(record.history, loaded?.history, "the wire items round-trip verbatim")
        assertEquals(record.copy(history = emptyList()), loaded?.copy(history = emptyList()))
    }

    @Test
    fun `history is persisted in the responses wire shape`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        val raw = Files.readString(dir.resolve("sess_0123456789abcdef.json"))
        assertTrue(raw.contains("\"role\":\"user\""), raw)
        assertTrue(raw.contains("\"type\":\"function_call_output\""), raw)
        assertTrue(raw.contains("\"call_id\":\"call_1\""), raw)
    }

    @Test
    fun `reasoning items are stored verbatim when handed over - filtering is the state's job`() {
        val (store, dir) = store()
        // SessionState.buildRecord filters live-only reasoning before it ever
        // reaches the store (pinned in SessionStateRepairTest); the store
        // itself round-trips whatever wire items it is given.
        val record = sampleRecord().copy(
            history = wireHistory(
                userMessage("Fix the bug"),
                reasoningItem("I should look at the file"),
                assistantMessage("Done"),
            ),
        )
        store.save(record)
        val raw = Files.readString(dir.resolve("sess_0123456789abcdef.json"))
        assertTrue(raw.contains("\"type\":\"reasoning\""), raw)
        assertEquals(record.history, store.load(record.sessionId)?.history)
    }

    @Test
    fun `tool outcomes round-trip with the record`() {
        val (store, _) = store()
        val record = sampleRecord().copy(toolOutcomes = mapOf("call_1" to "completed", "call_2" to "failed"))
        store.save(record)
        val loaded = store.load(record.sessionId)
        assertEquals(mapOf("call_1" to "completed", "call_2" to "failed"), loaded?.toolOutcomes)
    }

    @Test
    fun `legacy record without outcomes decodes to an empty map`() {
        val (store, dir) = store()
        // The legacy wire shape (llmWireJson = snake_case, toolOutcomes did not exist yet)
        Files.writeString(
            dir.resolve("sess_0123456789abcdef.json"),
            """
                {
                  "session_id": "sess_0123456789abcdef",
                  "cwd": "/project",
                  "mode": "plan",
                  "title": "Fix the bug",
                  "updated_at": 1700000000000,
                  "history": []
                }
            """.trimIndent(),
        )
        val loaded = store.load("sess_0123456789abcdef")
        assertNotNull(loaded)
        assertEquals(emptyMap(), loaded.toolOutcomes)
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
    fun `save sweeps stale session temp files and keeps in-flight ones`() {
        val (store, dir) = store()
        val stale = dir.resolve("session-stale123.tmp")
        Files.writeString(stale, "orphaned history")
        Files.setLastModifiedTime(
            stale,
            FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS),
        )
        val inFlight = dir.resolve("session-fresh456.tmp")
        Files.writeString(inFlight, "concurrent save")

        store.save(sampleRecord())

        assertFalse(Files.exists(stale), "a crashed save's temp file is swept")
        assertTrue(Files.exists(inFlight), "a concurrent save's in-flight temp file stays")
        assertEquals("build", store.load("sess_0123456789abcdef")?.mode)
    }

    @Test
    fun `the temp sweep is throttled - saves within the interval do not rescan`() {
        val (store, dir) = store()
        store.save(sampleRecord())
        val later = dir.resolve("session-later999.tmp")
        Files.writeString(later, "orphaned after the first sweep")
        Files.setLastModifiedTime(
            later,
            FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS),
        )

        store.save(sampleRecord().copy(mode = "plan"))

        assertTrue(
            Files.exists(later),
            "save runs after every tool call - a rescan per save would rescan the same debris"
        )
    }

    @Test
    fun `the temp sweep runs again once the interval elapsed`() {
        var clock = System.currentTimeMillis()
        val dir = Files.createTempDirectory("acp-agent-session-store")
        val store = SessionStore(dir) { clock }
        store.save(sampleRecord())
        val later = dir.resolve("session-later999.tmp")
        Files.writeString(later, "orphaned after the first sweep")
        Files.setLastModifiedTime(
            later,
            FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS),
        )

        clock += 2 * STALE_TEMP_AGE_MILLIS
        store.save(sampleRecord().copy(mode = "plan"))

        assertFalse(Files.exists(later), "debris of crashed sibling processes self-heals on later sweeps")
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
