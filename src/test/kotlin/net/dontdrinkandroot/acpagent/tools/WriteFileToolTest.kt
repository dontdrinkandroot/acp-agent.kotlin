package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.*

class WriteFileToolTest {

    @Test
    fun `write to an existing file carries the old content in the diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "old") }, testContext(dir))
        val write =
            WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "new") }, testContext(dir))
        assertFalse(write.isError, write.text)
        assertEquals(ToolResultDiff(path, "new", "old"), write.diff)
    }

    @Test
    fun `write skips the diff for oversized old content`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/big.txt"
        val big = "x".repeat(100_001)
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", big) }, testContext(dir))
        val write =
            WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "small") }, testContext(dir))
        assertFalse(write.isError, write.text)
        assertEquals(null, write.diff, "oversized old content must not be pushed onto the wire")
    }

    @Test
    fun `write with a failing raw read still writes and skips the diff`() = runBlocking {
        val dir = tmpDir()
        val failingRawStore = object : FileStore {
            override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult =
                ReadResult("display content")

            override suspend fun readRaw(path: String): String = throw RuntimeException("boom")

            override suspend fun writeFile(path: String, content: String) = Unit
        }
        val ctx = ToolContext(
            cwd = dir,
            client = null,
            clientCapabilities = ClientCapabilities(),
            sessionId = SessionId("sess_test"),
            fileStore = failingRawStore,
        )
        val write = WriteFileTool().execute(buildJsonObject { put("path", "$dir/f.txt"); put("content", "x") }, ctx)
        assertFalse(write.isError, write.text)
        assertEquals(null, write.diff, "a read failure must not fail the write or emit a diff")
    }

    @Test
    fun `write skips the diff when the client fs proxy is active`() = runBlocking {
        val dir = tmpDir()
        // Proxy active: the client renders the change itself, so no diff and
        // no extra proxy pre-read round-trip.
        val proxyCtx = testContext(dir, fileStore = ClientFileStore(RecordingFsClient()))
        val write = WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/p.txt"); put("content", "proxy") },
            proxyCtx,
        )
        assertFalse(write.isError, write.text)
        assertNull(write.diff, "the client fs proxy renders the change; no diff block")
    }

    @Test
    fun `explicit json null content is rejected and does not touch the disk`() = runBlocking {
        val dir = tmpDir()
        val write = WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/n.txt"); put("content", JsonNull) },
            testContext(dir),
        )
        assertTrue(write.isError)
        assertEquals("'content' must not be null", write.text)
        assertFalse(SystemFileSystem.exists(Path("$dir/n.txt")), "a null write must not touch the disk")
    }

    @Test
    fun `write missing path errors`() = runBlocking {
        val result = WriteFileTool().execute(buildJsonObject { put("content", "x") }, testContext("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `relative write paths are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        val write = WriteFileTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("content", "x") },
            testContext(dir),
        )
        assertFalse(write.isError, write.text)
        assertTrue(
            SystemFileSystem.exists(Path("$dir/sub/a.txt")),
            "file must be written under the session cwd, not the process cwd",
        )
    }

    @Test
    fun `write_file refuses new and existing excluded targets`() = runBlocking {
        val dir = tmpDir()
        val existing = java.io.File(dir, ".env.local").apply { writeText("A=1\n") }
        val fresh = java.io.File(dir, ".env.new.local")

        val writeExisting = WriteFileTool().execute(
            buildJsonObject { put("path", existing.absolutePath); put("content", "X=9") },
            testContext(dir),
        )
        assertTrue(writeExisting.isError, writeExisting.text)
        assertEquals("A=1\n", existing.readText(), "the excluded file must be unchanged")

        val writeNew = WriteFileTool().execute(
            buildJsonObject { put("path", fresh.absolutePath); put("content", "X=9") },
            testContext(dir),
        )
        assertTrue(writeNew.isError, writeNew.text)
        assertFalse(fresh.exists(), "a new excluded file must not be created")
    }

    @Test
    fun `schema pins path content`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"File path, absolute or relative to the working directory."},"content":{"type":"string","description":"Full file content; replaces existing content."}},"required":["path","content"]}""",
            llmWireJson.encodeToString(WriteFileTool().parameters),
        )
    }
}
