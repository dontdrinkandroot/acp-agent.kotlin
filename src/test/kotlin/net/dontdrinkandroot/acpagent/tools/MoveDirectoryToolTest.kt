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
