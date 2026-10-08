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

        val grep = GrepTool().execute(buildJsonObject { put("path", dir); put("pattern", "alpha") }, testContext(dir))
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
        val grep = GrepTool().execute(buildJsonObject { put("path", dir); put("pattern", "y") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        val match = grep.text.split("\n").single()
        assertTrue(match.startsWith("long.txt:1:"), match)
        assertTrue(match.endsWith("..."), match)
        assertEquals(500, match.removePrefix("long.txt:1:").removeSuffix("...").length)
    }

    @Test
    fun `grep root not found errors`() = runBlocking {
        val result = GrepTool().execute(
            buildJsonObject { put("path", "/nonexistent-root"); put("pattern", "needle") },
            testContext("/tmp")
        )
        assertTrue(result.isError)
        assertEquals("Path not found: /nonexistent-root", result.text)
    }

    @Test
    fun `a missing path wins over an invalid regex`() = runBlocking {
        // Both broken: the path error must win (pre-regression precedence).
        val result = GrepTool().execute(
            buildJsonObject { put("path", "/nonexistent-root"); put("pattern", "[invalid") },
            testContext("/tmp")
        )
        assertTrue(result.isError)
        assertEquals("Path not found: /nonexistent-root", result.text)
    }

    @Test
    fun `grep with a file as path searches that file`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )
        // A sibling carrying the same needle pins that ONLY the named file is searched.
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/b.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("pattern", "needle") },
            testContext(dir)
        )
        assertFalse(grep.isError, grep.text)
        // The match line echoes the path argument as given (rg-style).
        assertEquals("$dir/a.txt:1:needle", grep.text)
        assertFalse(grep.text.contains("b.txt"), "sibling must not be searched: ${grep.text}")
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
            GrepTool().execute(buildJsonObject { put("path", "$dir/locked"); put("pattern", "x") }, testContext(dir))
        assertTrue(grep.isError, "grep on an unreadable directory must fail loudly, was: ${grep.text}")
        assertEquals("Not readable: $dir/locked", grep.text)
    }

    @Test
    fun `relative grep roots are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("content", "y") }, testContext(dir))
        val grep = GrepTool().execute(buildJsonObject { put("path", "."); put("pattern", "y") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("sub/a.txt:1:y"), grep.text)
    }

    @Test
    fun `grep ignores the glob filter when the path is a single file`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("pattern", "needle"); put("glob", "*.kt") },
            testContext(dir)
        )
        assertFalse(grep.isError, "a mismatching glob must not error on a file path: ${grep.text}")
        assertEquals("$dir/a.txt:1:needle", grep.text)
    }

    @Test
    fun `grep refuses an excluded file as path`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, ".env.local").writeText("SECRET=needle")

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/.env.local"); put("pattern", "needle") },
            testContext(dir)
        )
        assertTrue(grep.isError, "an excluded file must be refused, was: ${grep.text}")
        assertEquals(
            "'$dir/.env.local' is excluded from tool access (matches exclusion rule '.env*.local')",
            grep.text
        )
    }

    @Test
    fun `grep refuses a symlink as path instead of following it`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/real.txt"); put("content", "needle") },
            testContext(dir)
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$dir/link.txt"),
            java.nio.file.Path.of("$dir/real.txt")
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/link.txt"); put("pattern", "needle") },
            testContext(dir)
        )
        assertTrue(grep.isError, "a symlink path must be refused, was: ${grep.text}")
        assertEquals("Refusing to search a symlink: $dir/link.txt", grep.text)
    }

    @Test
    fun `grep skips a binary file as path with the skip note`() = runBlocking {
        val dir = tmpDir()
        java.io.FileOutputStream("$dir/blob.bin").use { it.write(byteArrayOf(0, 1, 2, 3)) }

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/blob.bin"); put("pattern", "needle") },
            testContext(dir)
        )
        assertFalse(grep.isError, "a binary file must be skipped, not an error: ${grep.text}")
        assertEquals("No matches\n...(1 binary, oversized or excluded files skipped)", grep.text)
    }

    @Test
    fun `grep skips an oversized or unreadable file as path with the skip note`() = runBlocking {
        val dir = tmpDir()
        // over the 1 MB cap
        java.io.FileOutputStream("$dir/big.log").use { it.write(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }) }
        java.io.FileOutputStream("$dir/locked.txt").use { it.write("needle".toByteArray()) }
        val nioLocked = java.nio.file.Path.of("$dir/locked.txt")
        nioLocked.toFile().setReadable(false)
        // Read-trusting environments (e.g. tests running as root) cannot exercise
        // the unreadable half; the oversized half still runs everywhere.
        if (java.nio.file.Files.isReadable(nioLocked)) {
            println("Skipping unreadable-file case: process can read chmod-000 files (likely running as root)")
        }

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/big.log"); put("pattern", "x") },
            testContext(dir)
        )
        assertFalse(grep.isError, "an oversized file must be skipped, not an error: ${grep.text}")
        assertEquals("No matches\n...(1 binary, oversized or excluded files skipped)", grep.text)
        if (!java.nio.file.Files.isReadable(nioLocked)) {
            val locked = GrepTool().execute(
                buildJsonObject { put("path", "$dir/locked.txt"); put("pattern", "needle") },
                testContext(dir)
            )
            assertFalse(locked.isError, "an unreadable file must be skipped, not an error: ${locked.text}")
            assertEquals("No matches\n...(1 binary, oversized or excluded files skipped)", locked.text)
        }
    }

    @Test
    fun `grep caps matches from a single file at 500`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "hit\n".repeat(600)) },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("pattern", "hit") },
            testContext(dir)
        )
        assertFalse(grep.isError, grep.text)
        assertEquals(500, grep.text.split("\n").size, "the walk-mode match cap applies to single files, too")
    }

    @Test
    fun `grep with a relative file path echoes the relative match lines`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val grep = GrepTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("pattern", "needle") },
            testContext(dir)
        )
        assertFalse(grep.isError, grep.text)
        assertEquals("sub/a.txt:1:needle", grep.text)
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

        val grep = GrepTool().execute(buildJsonObject { put("path", dir); put("pattern", "needle") }, testContext(dir))
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
            GrepTool().execute(buildJsonObject { put("path", dir); put("pattern", "findme|x") }, testContext(dir))
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
            buildJsonObject { put("path", project); put("pattern", "secret") },
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

        val grep = GrepTool().execute(buildJsonObject { put("path", dir); put("pattern", "SECRET") }, testContext(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("code.txt:1:SECRET=1"), grep.text)
        assertFalse(grep.text.contains(".env.local"), grep.text)
        assertTrue(grep.text.contains("binary, oversized or excluded files skipped"), grep.text)
    }

    @Test
    fun `schema pins pattern with optional path and glob`() {
        assertEquals(
            """{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression matched against each line."},"path":{"type":"string","description":"Directory or single file to search, absolute or relative to the working directory; defaults to the working directory."},"glob":{"type":"string","description":"Optional glob filter; only files matching it are searched. Ignored when the search path is a single file."}},"required":["pattern"]}""",
            llmWireJson.encodeToString(GrepTool().parameters),
        )
    }
}
