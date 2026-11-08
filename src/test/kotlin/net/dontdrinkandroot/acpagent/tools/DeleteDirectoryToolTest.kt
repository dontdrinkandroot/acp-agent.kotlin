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
    fun `delete_directory keeps VCS metadata out of the diff evidence`() = runBlocking {
        // Issue #79: `.git` content is noise the search walker already skips;
        // as diff evidence it is worse than noise - `.git/*` sorts before the
        // source files, so it eats the bounded tree budget and the real files
        // lose their rollback baseline.
        val dir = tmpDir()
        val target = "$dir/repo"
        SystemFileSystem.createDirectories(Path("$target/src"))
        SystemFileSystem.createDirectories(Path("$target/.git/objects"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/src/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/.git/config"); put("content", "[core]") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/.git/objects/ab"); put("content", "packed") },
            testContext(dir)
        )

        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", target) }, testContext(dir))

        assertFalse(result.isError, result.text)
        assertEquals(listOf(ToolResultDiff("$target/src/a.txt", "", "ay")), result.diffs)
        assertTrue(result.diffs.none { it.path.contains("/.git/") }, "VCS metadata must never be diff evidence")
        assertEquals(
            "Deleted directory $target",
            result.text,
            "skipped .git files must not count in the M of N note either",
        )
        assertFalse(SystemFileSystem.exists(Path("$target/.git")), "the deletion itself still removes .git")
    }

    @Test
    fun `delete_directory reports the removed content of every file as diffs`() = runBlocking {
        // Issue #56: the tree deletion is local-disk-only - the client renders
        // nothing for it - so every deleted file needs its removal diff as the
        // rollback baseline, in deterministic sorted path order.
        val dir = tmpDir()
        val target = "$dir/gone"
        SystemFileSystem.createDirectories(Path("$target/sub"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/b.txt"); put("content", "bee") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/sub/c.txt"); put("content", "sea") },
            testContext(dir)
        )
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", target) }, testContext(dir))
        assertFalse(result.isError, result.text)
        assertEquals("Deleted directory $target", result.text)
        assertEquals(
            listOf(
                ToolResultDiff("$target/a.txt", "", "ay"),
                ToolResultDiff("$target/b.txt", "", "bee"),
                ToolResultDiff("$target/sub/c.txt", "", "sea"),
            ),
            result.diffs,
        )
    }

    @Test
    fun `delete_directory never reports the content of excluded files`() = runBlocking {
        // File-access exclusion boundary: .env*.local content must never flow
        // into tool results. The tree deletion may remove it (issue #71's
        // containing-directory residual), but its content is not diff evidence:
        // the file is skipped, counted in the note, and its bytes stay out of
        // every diff block and the result text.
        val dir = tmpDir()
        val target = "$dir/gone"
        SystemFileSystem.createDirectories(Path(target))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        java.io.File("$target/.env.local").writeText("SECRET_TOKEN=hunter2\n")
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", target) }, testContext(dir))
        assertFalse(result.isError, result.text)
        assertTrue(result.text.contains("(original content reported for 1 of 2 files)"), result.text)
        assertFalse(result.text.contains("hunter2"), "the secret must not appear in the result text")
        assertEquals(
            listOf(ToolResultDiff("$target/a.txt", "", "ay")),
            result.diffs,
            "the excluded file must not produce a diff block",
        )
    }

    @Test
    fun `delete_directory notes the files whose content could not be reported`() = runBlocking {
        // Issues #56/#57: a skipped file (binary here) gets no diff and the
        // result states the fact - "original content reported for M of N" -
        // so the partial baseline is visible instead of silent.
        val dir = tmpDir()
        val target = "$dir/gone"
        SystemFileSystem.createDirectories(Path(target))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/a.txt"); put("content", "ay") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$target/b.txt"); put("content", "bee") },
            testContext(dir)
        )
        java.io.File("$target/bin.dat").writeBytes(byteArrayOf(1, 0, 2))
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", target) }, testContext(dir))
        assertFalse(result.isError, result.text)
        assertTrue(result.text.startsWith("Deleted directory $target "), result.text)
        assertTrue(result.text.contains("(original content reported for 2 of 3 files)"), result.text)
        assertEquals(
            listOf(
                ToolResultDiff("$target/a.txt", "", "ay"),
                ToolResultDiff("$target/b.txt", "", "bee"),
            ),
            result.diffs,
        )
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
