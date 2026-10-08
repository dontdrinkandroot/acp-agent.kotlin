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

class GrepToolTest {

    @Test
    fun `grep finds the pattern across files`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "alpha") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/nested/b.kt"); put("content", "val alpha = 1") },
            testContext(dir)
        )

        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "alpha") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertEquals(listOf("a.txt:1:alpha", "nested/b.kt:1:val alpha = 1"), grep.text.split("\n"))
    }

    @Test
    fun `grep truncates long matched lines`() = runBlocking {
        val dir = tmpDir()
        val longLine = "y".repeat(1000)
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/long.txt"); put("content", longLine) },
            testContext(dir)
        )
        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "y") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        val match = grep.text.split("\n").single()
        assertTrue(match.startsWith("long.txt:1:"), match)
        assertTrue(match.endsWith("..."), match)
        assertEquals(500, match.removePrefix("long.txt:1:").removeSuffix("...").length)
    }

    @Test
    fun `grep root not found errors`() = runBlocking {
        val result = GrepTool().execute(
            buildJsonObject { put("root", "/nonexistent-root"); put("pattern", "needle") },
            testContext("/tmp")
        )
        assertTrue(result.isError)
        assertEquals("Root not found: /nonexistent-root", result.text)
    }

    @Test
    fun `grep with a file as path searches that file`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("pattern", "needle") },
            testContext(dir)
        )
        assertFalse(grep.isError, grep.text)
        assertEquals("a.txt:1:needle", grep.text)
    }

    @Test
    fun `grep with a file as root fails loudly instead of silently matching nothing`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("root", "$dir/a.txt"); put("pattern", "needle") },
            testContext(dir)
        )
        assertTrue(grep.isError, "grep with a file as root must fail loudly, was: ${grep.text}")
        assertEquals("Not a directory: $dir/a.txt", grep.text)
    }

    @Test
    fun `grep fails loudly on an unreadable root directory`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/locked"))
        val nioLocked = java.nio.file.Path.of("$dir/locked")
        nioLocked.toFile().setReadable(false)
        // Read-trusting environments (e.g. tests running as root) cannot exercise
        // this; skip honestly instead of failing there.
        if (java.nio.file.Files.isReadable(nioLocked)) {
            println("Skipping unreadable-root test: process can read chmod-000 dirs (likely running as root)")
            return@runBlocking
        }

        val grep =
            GrepTool().execute(buildJsonObject { put("root", "$dir/locked"); put("pattern", "x") }, testContext(dir))
        assertTrue(grep.isError, "grep on an unreadable directory must fail loudly, was: ${grep.text}")
        assertEquals("Not readable: $dir/locked", grep.text)
    }

    @Test
    fun `relative grep roots are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("content", "y") }, testContext(dir))
        val grep = GrepTool().execute(buildJsonObject { put("root", "."); put("pattern", "y") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("sub/a.txt:1:y"), grep.text)
    }

    @Test
    fun `grep skips dot git directories`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/.git/objects"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/.git/objects/ab"); put("content", "needle") },
            testContext(dir),
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "needle") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertEquals(listOf("a.txt:1:needle"), grep.text.split("\n").filter { it.isNotBlank() }, grep.text)
    }

    @Test
    fun `grep skips binary and oversized files with a note`() = runBlocking {
        val dir = tmpDir()
        // NUL byte -> binary sniff
        java.io.FileOutputStream("$dir/blob.bin").use { it.write(byteArrayOf(0, 1, 2, 3)) }
        // over the 1 MB cap
        java.io.FileOutputStream("$dir/big.log").use { it.write(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }) }
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "findme") },
            testContext(dir)
        )

        val grep =
            GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "findme|x") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("a.txt:1:findme"), grep.text)
        assertFalse(grep.text.contains("blob.bin"), grep.text)
        assertFalse(grep.text.contains("big.log"), grep.text)
        assertTrue(grep.text.contains("binary, oversized or excluded files skipped"), grep.text)
    }

    @Test
    fun `grep does not follow symlinks out of the search root`() = runBlocking {
        val base = tmpDir()
        val project = "$base/project"
        val outside = "$base/outside"
        SystemFileSystem.createDirectories(Path(project))
        SystemFileSystem.createDirectories(Path(outside))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$project/real.txt"); put("content", "real") },
            testContext(project),
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$outside/secret.txt"); put("content", "secret") },
            testContext(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/linkdir"),
            java.nio.file.Path.of(outside),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/leak.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("root", project); put("pattern", "secret") },
            testContext(project),
        )
        assertFalse(grep.isError, grep.text)
        assertFalse(grep.text.contains("secret.txt"), "grep must not read through symlinks: ${grep.text}")
    }

    @Test
    fun `grep hides excluded files`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, ".env.local").writeText("SECRET=1")
        java.io.File(dir, "code.txt").writeText("SECRET=1")

        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "SECRET") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("code.txt:1:SECRET=1"), grep.text)
        assertFalse(grep.text.contains(".env.local"), grep.text)
        assertTrue(grep.text.contains("binary, oversized or excluded files skipped"), grep.text)
    }

    @Test
    fun `schema pins pattern with optional root and glob`() {
        assertEquals(
            """{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression matched against each line."},"root":{"type":"string","description":"Directory to search, absolute or relative; defaults to the working directory."},"glob":{"type":"string","description":"Optional glob filter; only files matching it are searched."}},"required":["pattern"]}""",
            llmWireJson.encodeToString(GrepTool().parameters),
        )
    }
}
