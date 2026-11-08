package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveDirectoryToolTest {

    @Test
    fun `move_directory moves the whole tree`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/src/nested"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/src/a.txt"); put("content", "a") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/src/nested/b.txt"); put("content", "b") },
            testContext(dir),
        )
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", "$dir/src"); put("destination", "$dir/dst") },
            testContext(dir),
        )
        assertFalse(result.isError, result.text)
        assertFalse(SystemFileSystem.exists(Path("$dir/src")))
        assertTrue(SystemFileSystem.exists(Path("$dir/dst/nested/b.txt")))
    }

    @Test
    fun `move_directory reports a removal and an addition diff per file`() = runBlocking {
        // Issue #56 (delete+add encoding): every moved file needs both sides of
        // the rename as rollback baseline, in deterministic sorted path order.
        val dir = tmpDir()
        val source = "$dir/old"
        val destination = "$dir/new"
        SystemFileSystem.createDirectories(Path("$source/sub"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$source/b.txt"); put("content", "bee") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$source/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            testContext(dir),
        )
        assertFalse(result.isError, result.text)
        assertEquals("Moved directory $source to $destination (content unchanged)", result.text)
        assertEquals(
            listOf(
                ToolResultDiff("$source/a.txt", "", "ay"),
                ToolResultDiff("$destination/a.txt", "ay", null),
                ToolResultDiff("$source/b.txt", "", "bee"),
                ToolResultDiff("$destination/b.txt", "bee", null),
            ),
            result.diffs,
        )
    }

    @Test
    fun `move_directory keeps VCS metadata out of the diff evidence`() = runBlocking {
        // Issue #79: `.git` sorts before the source files, so as diff evidence
        // it would consume the bounded tree budget the real files need.
        val dir = tmpDir()
        val source = "$dir/repo"
        val destination = "$dir/moved"
        SystemFileSystem.createDirectories(Path("$source/src"))
        SystemFileSystem.createDirectories(Path("$source/.git/objects"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$source/src/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$source/.git/config"); put("content", "[core]") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$source/.git/objects/ab"); put("content", "packed") },
            testContext(dir)
        )

        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            testContext(dir),
        )

        assertFalse(result.isError, result.text)
        assertEquals("Moved directory $source to $destination (content unchanged)", result.text)
        assertEquals(
            listOf(
                ToolResultDiff("$source/src/a.txt", "", "ay"),
                ToolResultDiff("$destination/src/a.txt", "ay", null),
            ),
            result.diffs,
        )
        assertTrue(result.diffs.none { it.path.contains("/.git/") }, "VCS metadata must never be diff evidence")
        assertTrue(
            SystemFileSystem.exists(Path("$destination/.git/config")),
            "the move itself still carries .git along",
        )
    }

    @Test
    fun `move_directory reports diffs for at most 50 files`() = runBlocking {
        // Issue #56's file-count cap: the fact phrase states how many of the
        // moved files kept a baseline; the caps report a deterministic sorted
        // prefix of 50 files (100 diff blocks - removal + addition each).
        val dir = tmpDir()
        val source = "$dir/old"
        val destination = "$dir/new"
        SystemFileSystem.createDirectories(Path(source))
        for (i in 0 until 60) {
            java.io.File(source, "f$i.txt").writeText("c$i")
        }
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            testContext(dir),
        )
        assertFalse(result.isError, result.text)
        assertTrue(
            result.text.contains("(content unchanged; original content reported for 50 of 60 files)"),
            result.text,
        )
        assertEquals(100, result.diffs.size, "50 files x removal + addition")
    }

    @Test
    fun `move_directory caps the aggregate diff payload per call`() = runBlocking {
        // The caps compose: 50 files x 100k per side would be ~5 MB, so the
        // aggregate budget (1 MiB per call) bounds the whole tool call. A move
        // costs twice its content (removal + addition): 20 files x 60k chars
        // fit 8 of them (8 x 120k = 960k <= 1 MiB), the rest are counted in the
        // fact note.
        val dir = tmpDir()
        val source = "$dir/old"
        val destination = "$dir/new"
        SystemFileSystem.createDirectories(Path(source))
        for (i in 0 until 20) {
            java.io.File(source, "f$i.txt").writeText("c".repeat(60_000))
        }
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            testContext(dir),
        )
        assertFalse(result.isError, result.text)
        assertTrue(
            result.text.contains("(content unchanged; original content reported for 8 of 20 files)"),
            result.text,
        )
        assertEquals(16, result.diffs.size, "8 files x removal + addition")
    }

    @Test
    fun `move_directory refuses a file source`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "a") }, testContext(dir))
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", "$dir/a.txt"); put("destination", "$dir/dst") },
            testContext(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("move_file"), result.text)
    }

    @Test
    fun `schema pins source destination`() {
        assertEquals(
            """{"type":"object","properties":{"source":{"type":"string","description":"Directory to move, absolute or relative to the working directory."},"destination":{"type":"string","description":"New directory location, absolute or relative to the working directory; missing parent directories are created."}},"required":["source","destination"]}""",
            llmWireJson.encodeToString(MoveDirectoryTool().parameters),
        )
    }
}
