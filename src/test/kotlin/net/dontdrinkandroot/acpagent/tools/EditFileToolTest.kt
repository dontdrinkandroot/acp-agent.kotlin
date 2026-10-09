package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.*

class EditFileToolTest {

    @Test
    fun `edit carries a whole-file diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", "kotlin") },
            testContext(dir),
        )
        assertFalse(edit.isError, edit.text)
        // The diff describes the whole file (consistent with write_file and
        // delete_file), not just the replaced fragment.
        assertEquals(ToolResultDiff(path, "hello kotlin", "hello world"), edit.diff)
    }

    @Test
    fun `edit skips the diff for oversized content`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/big.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "y" + "x".repeat(100_001)) },
            testContext(dir),
        )
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "yx"); put("new_string", "yy") },
            testContext(dir),
        )
        assertFalse(edit.isError, edit.text)
        assertNull(edit.diff, "oversized content must not be pushed onto the wire")
    }

    @Test
    fun `edit skips the diff when the client fs proxy is active`() = runBlocking {
        val dir = tmpDir()
        // Proxy active: the client renders the change itself, so no diff and
        // no extra proxy pre-read round-trip.
        val proxyCtx = testContext(dir, fileStore = ClientFileStore(RecordingFsClient()))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/p.txt"); put("content", "proxy") }, proxyCtx)
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", "$dir/p.txt"); put("old_string", "proxy"); put("new_string", "edited") },
            proxyCtx,
        )
        assertFalse(edit.isError, edit.text)
        assertNull(edit.diff, "the client fs proxy renders the change; no diff block")
    }

    @Test
    fun `edit replaces unique substring`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "hello world hello") },
            testContext(dir)
        )
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", "kotlin") },
            testContext(dir),
        )
        assertFalse(edit.isError, edit.text)
        val content = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals("hello kotlin hello", content)
    }

    @Test
    fun `edit missing old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "abc") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "zzz"); put("new_string", "x") },
            testContext(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("not found"))
    }

    @Test
    fun `argument validation follows the strict null conventions`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world") }, testContext(dir))

        val missing = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world") },
            testContext(dir),
        )
        assertTrue(missing.isError)
        assertEquals("Missing 'new_string'", missing.text)

        val explicitNull = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", JsonNull) },
            testContext(dir),
        )
        assertTrue(explicitNull.isError)
        assertEquals("'new_string' must not be null", explicitNull.text)

        // Neither failure may touch the file: the lenient coercion used to
        // silently delete old_string instead of failing (issue #44).
        val content = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals("hello world", content)
    }

    @Test
    fun `empty new_string still removes old_string`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", " world"); put("new_string", "") },
            testContext(dir),
        )
        assertFalse(edit.isError, edit.text)
        val content = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals("hello", content)
    }

    @Test
    fun `edit counts non-overlapping occurrences`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        // "aa" occurs twice in "aaaa" non-overlapping, but also overlaps;
        // the overlap must not inflate the ambiguity count to three.
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "aaaa") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "aa"); put("new_string", "b") },
            testContext(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("matches 2 times"), edit.text)

        // When only the first of two overlapping occurrences is a real
        // non-overlapping match, replace is deterministic and the edit must
        // succeed.
        val unique = path + "2"
        WriteFileTool().execute(buildJsonObject { put("path", unique); put("content", "aaa") }, testContext(dir))
        val single = EditFileTool().execute(
            buildJsonObject { put("path", unique); put("old_string", "aa"); put("new_string", "b") },
            testContext(dir),
        )
        assertFalse(single.isError, single.text)
        val content = SystemFileSystem.source(Path(unique)).buffered().use { it.readString() }
        assertEquals("ba", content)
    }

    @Test
    fun `edit ambiguous old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a a a") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "a"); put("new_string", "b") },
            testContext(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("matches"))
    }

    @Test
    fun `relative edit paths are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("content", "x") }, testContext(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("old_string", "x"); put("new_string", "y") },
            testContext(dir),
        )
        assertFalse(edit.isError, edit.text)
    }

    @Test
    fun `edit_file refuses an excluded target`() = runBlocking {
        val dir = tmpDir()
        val file = java.io.File(dir, ".env.development.local").apply { writeText("A=1\n") }

        val edit = EditFileTool().execute(
            buildJsonObject {
                put("path", file.absolutePath)
                put("old_string", "A=1")
                put("new_string", "B=2")
            },
            testContext(dir),
        )
        assertTrue(edit.isError, edit.text)
        assertTrue(edit.text.contains("excluded from tool access"), edit.text)
        assertEquals("A=1\n", file.readText(), "the excluded file must be unchanged")
    }

    @Test
    fun `schema pins path old_string new_string`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"File path, absolute or relative to the working directory."},"old_string":{"type":"string","description":"Exact substring to replace; must match exactly once in the file."},"new_string":{"type":"string","description":"Replacement text; empty removes the old_string."}},"required":["path","old_string","new_string"]}""",
            llmWireJson.encodeToString(EditFileTool().parameters),
        )
    }
}
