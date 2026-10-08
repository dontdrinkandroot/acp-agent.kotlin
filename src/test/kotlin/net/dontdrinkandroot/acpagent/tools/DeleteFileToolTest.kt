package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.*

class DeleteFileToolTest {

    @Test
    fun `delete_file deletes and carries the removed content as a diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/gone.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "bye") }, testContext(dir))
        val result = DeleteFileTool().execute(buildJsonObject { put("path", path) }, testContext(dir))
        assertFalse(result.isError, result.text)
        assertFalse(SystemFileSystem.exists(Path(path)))
        assertEquals(ToolResultDiff(path, "", "bye"), result.diff)
    }

    @Test
    fun `delete_file refuses directories and skips oversized diffs`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/sub"))
        val dirResult = DeleteFileTool().execute(buildJsonObject { put("path", "$dir/sub") }, testContext(dir))
        assertTrue(dirResult.isError)
        assertTrue(dirResult.text.contains("delete_directory"), dirResult.text)

        val big = "x".repeat(100_001)
        val bigPath = "$dir/big.txt"
        WriteFileTool().execute(buildJsonObject { put("path", bigPath); put("content", big) }, testContext(dir))
        val bigResult = DeleteFileTool().execute(buildJsonObject { put("path", bigPath) }, testContext(dir))
        assertFalse(bigResult.isError, bigResult.text)
        assertNull(bigResult.diff, "oversized content must not be pushed onto the wire")
    }

    @Test
    fun `delete_file refuses symlinks`() = runBlocking {
        val base = tmpDir()
        val project = "$base/project"
        val outside = "$base/outside"
        SystemFileSystem.createDirectories(Path(project))
        SystemFileSystem.createDirectories(Path(outside))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$outside/secret.txt"); put("content", "secret") },
            testContext(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/link.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )
        val result =
            DeleteFileTool().execute(buildJsonObject { put("path", "$project/link.txt") }, testContext(project))
        assertTrue(result.isError)
        assertTrue(result.text.contains("symlink"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$project/link.txt")), "the link must survive the refusal")
        assertTrue(SystemFileSystem.exists(Path("$outside/secret.txt")), "the target must survive")
    }

    @Test
    fun `delete_file is local disk only and never touches the file store`() = runBlocking {
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
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "x") }, testContext(dir))
        val delete = DeleteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt") }, ctx)
        assertFalse(delete.isError, delete.text)
        assertTrue(recordingStore.writes.isEmpty(), "delete must operate on the local disk")
    }

    @Test
    fun `schema pins path`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"File to delete, absolute or relative to the working directory."}},"required":["path"]}""",
            llmWireJson.encodeToString(DeleteFileTool().parameters),
        )
    }

    @Test
    fun `delete_file refuses an excluded target`() = runBlocking {
        val dir = tmpDir()
        val file = java.io.File(dir, ".env.local").apply { writeText("A=1\n") }

        val delete = DeleteFileTool().execute(buildJsonObject { put("path", file.absolutePath) }, testContext(dir))
        assertTrue(delete.isError, delete.text)
        assertTrue(delete.text.contains("excluded from tool access"), delete.text)
        assertTrue(file.isFile, "the excluded file must survive the refused delete")
    }
}
