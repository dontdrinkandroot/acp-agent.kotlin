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

class DeleteDirectoryToolTest {

    @Test
    fun `delete_directory deletes recursively`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/tree/deep"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/tree/deep/f.txt"); put("content", "x") },
            testContext(dir),
        )
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", "$dir/tree") }, testContext(dir))
        assertFalse(result.isError, result.text)
        assertFalse(SystemFileSystem.exists(Path("$dir/tree")))
    }

    @Test
    fun `delete_directory refuses symlinks inside the tree`() = runBlocking {
        val base = tmpDir()
        val project = "$base/project"
        val outside = "$base/outside"
        SystemFileSystem.createDirectories(Path("$project/sub"))
        SystemFileSystem.createDirectories(Path(outside))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$outside/secret.txt"); put("content", "secret") },
            testContext(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/sub/link.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )
        val result =
            DeleteDirectoryTool().execute(buildJsonObject { put("path", "$project/sub") }, testContext(project))
        assertTrue(result.isError)
        assertTrue(result.text.contains("symlink"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$project/sub")), "nothing must be deleted on refusal")
        assertTrue(SystemFileSystem.exists(Path("$outside/secret.txt")), "the symlink target must survive")
    }

    @Test
    fun `delete_directory refuses a symlink root`() = runBlocking {
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
            java.nio.file.Path.of("$project/linkdir"),
            java.nio.file.Path.of(outside),
        )
        val result =
            DeleteDirectoryTool().execute(buildJsonObject { put("path", "$project/linkdir") }, testContext(project))
        assertTrue(result.isError)
        assertTrue(result.text.contains("symlink"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$outside/secret.txt")), "the symlink target must survive")
    }

    @Test
    fun `schema pins path`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"Directory to delete, absolute or relative to the working directory."}},"required":["path"]}""",
            llmWireJson.encodeToString(DeleteDirectoryTool().parameters),
        )
    }
}
