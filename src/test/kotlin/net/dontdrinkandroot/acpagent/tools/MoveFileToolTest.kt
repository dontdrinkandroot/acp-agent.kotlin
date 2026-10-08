package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveFileToolTest {

    @Test
    fun `move_file moves content and creates destination parents`() = runBlocking {
        val dir = tmpDir()
        val source = "$dir/a.txt"
        val destination = "$dir/sub/b.txt"
        WriteFileTool().execute(buildJsonObject { put("path", source); put("content", "hello") }, testContext(dir))
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            testContext(dir),
        )
        assertFalse(result.isError, result.text)
        assertTrue(result.text.contains("Moved"), result.text)
        assertFalse(SystemFileSystem.exists(Path(source)), "the source must be moved away")
        val content = SystemFileSystem.source(Path(destination)).buffered().use { it.readString() }
        assertEquals("hello", content)
    }

    @Test
    fun `move_file refuses an existing destination and keeps the source`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "a") }, testContext(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/b.txt"); put("content", "b") }, testContext(dir))
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", "$dir/a.txt"); put("destination", "$dir/b.txt") },
            testContext(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Destination exists"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$dir/a.txt")), "the source must survive a refused move")
    }

    @Test
    fun `move_file refuses directories`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/sub"))
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", "$dir/sub"); put("destination", "$dir/other") },
            testContext(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("move_directory"), result.text)
    }

    @Test
    fun `move_file missing source errors`() = runBlocking {
        val dir = tmpDir()
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", "$dir/nope.txt"); put("destination", "$dir/x.txt") },
            testContext(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Source not found"), result.text)
    }

    @Test
    fun `move_file is local disk only and never touches the file store`() = runBlocking {
        val dir = tmpDir()
        val recordingStore = object : FileStore {
            val reads = mutableListOf<String>()
            val writes = mutableListOf<String>()
            override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult {
                reads += path
                throw RuntimeException("must not be used")
            }

            override suspend fun writeFile(path: String, content: String) {
                writes += path
                throw RuntimeException("must not be used")
            }
        }
        val ctx = testContext(dir, fileStore = recordingStore)
        val source = "$dir/a.txt"
        WriteFileTool().execute(buildJsonObject { put("path", source); put("content", "x") }, testContext(dir))
        val move = MoveFileTool().execute(
            buildJsonObject { put("source", source); put("destination", "$dir/b.txt") },
            ctx,
        )
        assertFalse(move.isError, move.text)
        assertTrue(recordingStore.writes.isEmpty(), "move must operate on the local disk")
    }

    @Test
    fun `schema pins source destination`() {
        assertEquals(
            """{"type":"object","properties":{"source":{"type":"string","description":"File to move, absolute or relative to the working directory."},"destination":{"type":"string","description":"New file location, absolute or relative to the working directory; missing parent directories are created."}},"required":["source","destination"]}""",
            llmWireJson.encodeToString(MoveFileTool().parameters),
        )
    }
}
