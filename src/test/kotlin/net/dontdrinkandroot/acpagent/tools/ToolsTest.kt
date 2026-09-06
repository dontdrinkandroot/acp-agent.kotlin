package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.*

private fun tmpDir(): String {
    val dir = "/tmp/acp-agent-test-${Random.nextBytes(6).toHex()}"
    SystemFileSystem.createDirectories(Path(dir))
    return dir
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun context(cwd: String) = ToolContext(
    cwd = cwd,
    client = null,
    clientCapabilities = ClientCapabilities(),
    sessionId = SessionId("sess_test"),
)

class FileToolsTest {

    @Test
    fun `write then read round trip`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/a/b/c.txt"
        val write = WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "line1\nline2\nline3") }, context(dir))
        assertFalse(write.isError, write.text)
        assertTrue(write.text.contains("Written"))
        assertEquals(path, write.diff?.path, "write to a new file must carry a diff with the target path")
        assertEquals(null, write.diff?.oldText, "a new file has no old content")

        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 3) }, context(dir))
        assertFalse(read.isError, read.text)
        // A windowed read (limit set, no line) covering the whole file is
        // numbered, with no footer.
        assertEquals("   1  line1\n   2  line2\n   3  line3", read.text)
    }

    @Test
    fun `write to an existing file carries the old content in the diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "old") }, context(dir))
        val write = WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "new") }, context(dir))
        assertFalse(write.isError, write.text)
        assertEquals(ToolResultDiff(path, "new", "old"), write.diff)
    }

    @Test
    fun `edit carries an argument-derived diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", "kotlin") },
            context(dir),
        )
        assertFalse(edit.isError, edit.text)
        assertEquals(ToolResultDiff(path, "kotlin", "world"), edit.diff)
    }

    @Test
    fun `write skips the diff for oversized old content`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/big.txt"
        val big = "x".repeat(100_001)
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", big) }, context(dir))
        val write =
            WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "small") }, context(dir))
        assertFalse(write.isError, write.text)
        assertEquals(null, write.diff, "oversized old content must not be pushed onto the wire")
    }

    @Test
    fun `write skips the diff when the old content cannot be read`() = runBlocking {
        val dir = tmpDir()
        val failingReadStore = object : FileStore {
            override suspend fun readFile(path: String, line: Int?, limit: Int?): ReadResult =
                throw RuntimeException("boom")

            override suspend fun writeFile(path: String, content: String) = Unit
        }
        val ctx = ToolContext(
            cwd = dir,
            client = null,
            clientCapabilities = ClientCapabilities(),
            sessionId = SessionId("sess_test"),
            fileStore = failingReadStore,
        )
        val write = WriteFileTool().execute(buildJsonObject { put("path", "$dir/f.txt"); put("content", "x") }, ctx)
        assertFalse(write.isError, write.text)
        assertEquals(null, write.diff, "a read failure must not fail the write or emit a diff")
    }

    @Test
    fun `read with line and limit slices`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc\nd\ne") }, context(dir))
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("line", 2); put("limit", 2) }, context(dir))
        assertFalse(read.isError, read.text)
        // Explicit window: 1-indexed numbered lines plus a truncated-footer
        // telling the model what range was shown and where to continue.
        assertEquals(
            "   2  b\n   3  c\n\n(Showing lines 2-3 of 5. Use line=4 and limit to continue.)",
            read.text,
        )
    }

    @Test
    fun `read window reaching the end of the file omits the footer`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc\nd\ne") }, context(dir))
        val read =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("line", 4); put("limit", 2) }, context(dir))
        assertFalse(read.isError, read.text)
        assertEquals("   4  d\n   5  e", read.text)
    }

    @Test
    fun `read window reaching the last visible line of a trailing newline file omits the footer`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/trailing.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\n") }, context(dir))
        val read =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("line", 2); put("limit", 1) }, context(dir))
        assertFalse(read.isError, read.text)
        // The trailing newline is a terminator, not a third line: the window
        // reaches the last visible line, so no footer may claim more below.
        assertEquals("   2  b", read.text)
    }

    @Test
    fun `read of a large file honors growing limits without truncation`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/large.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", (1..1000).joinToString("\n") { it.toString() }) },
            context(dir),
        )
        val small = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 10) }, context(dir))
        val large = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 500) }, context(dir))
        val full = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2000) }, context(dir))
        assertFalse(small.isError, small.text)
        assertFalse(large.isError, large.text)
        assertFalse(full.isError, full.text)
        // A stale or truncated window would return the same small excerpt for
        // every limit; the numbered line count must grow with the limit and
        // the line numbers must match the requested window.
        assertTrue(small.text.startsWith("   1  1\n"), small.text)
        assertTrue(large.text.startsWith("   1  1\n"), large.text)
        assertEquals("  10  10", small.text.lines()[9], small.text)
        assertEquals(12, small.text.lines().size, "the footer block adds lines: ${small.text}")
        assertEquals(" 500  500", large.text.lines()[499], large.text)
        assertEquals(502, large.text.lines().size, "the footer block adds lines: ${large.text}")
        // A full read whose limit covers the whole file: numbered, no footer.
        assertTrue(full.text.startsWith("   1  1\n"), full.text)
        assertEquals("1000  1000", full.text.lines()[999], full.text)
        assertEquals(1000, full.text.lines().size, "a whole-file window has no footer: ${full.text}")
    }

    @Test
    fun `read missing file errors`() = runBlocking {
        val result = ReadFileTool().execute(buildJsonObject { put("path", "/nonexistent/nope.txt") }, context("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `write missing path errors`() = runBlocking {
        val result = WriteFileTool().execute(buildJsonObject { put("content", "x") }, context("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `edit replaces unique substring`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "hello world hello") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "world"); put("new_string", "kotlin") },
            context(dir),
        )
        assertFalse(edit.isError, edit.text)
        val content = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        assertEquals("hello kotlin hello", content)
    }

    @Test
    fun `edit missing old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "abc") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "zzz"); put("new_string", "x") },
            context(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("not found"))
    }

    @Test
    fun `full read keeps the trailing newline`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc\n") }, context(dir))
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 10) }, context(dir))
        assertFalse(read.isError, read.text)
        // Full read (limit covers the file): numbered, trailing terminator
        // dropped so the phantom empty line is not rendered, no footer.
        assertEquals("   1  a\n   2  b\n   3  c", read.text)
    }

    @Test
    fun `crlf file slices by visual lines`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/crlf.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\r\nb\r\nc\r\n") }, context(dir))
        val read = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", 2); put("limit", 1) },
            context(dir),
        )
        assertFalse(read.isError, read.text)
        assertEquals(
            "   2  b\n\n(Showing lines 2-2 of 3. Use line=3 and limit to continue.)",
            read.text,
        )
    }

    @Test
    fun `non-positive line and limit are rejected`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc") }, context(dir))
        val zeroLine =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("line", 0); put("limit", 1) }, context(dir))
        assertTrue(zeroLine.isError)
        val zeroLimit = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 0) }, context(dir))
        assertTrue(zeroLimit.isError)
        val negLine = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", -1); put("limit", 1) },
            context(dir)
        )
        assertTrue(negLine.isError)
    }

    @Test
    fun `read requires limit and bounds it`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc") }, context(dir))
        val missingLimit = ReadFileTool().execute(buildJsonObject { put("path", path) }, context(dir))
        assertTrue(missingLimit.isError)
        assertTrue(missingLimit.text.contains("'limit'"), missingLimit.text)
        val tooLargeLimit =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2001) }, context(dir))
        assertTrue(tooLargeLimit.isError)
        assertTrue(tooLargeLimit.text.contains("2000"), tooLargeLimit.text)
        val atBound = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2000) }, context(dir))
        assertFalse(atBound.isError, atBound.text)
        assertEquals("   1  a\n   2  b\n   3  c", atBound.text)
    }

    @Test
    fun `edit counts non-overlapping occurrences`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        // "aa" occurs twice in "aaaa" non-overlapping, but also overlaps;
        // the overlap must not inflate the ambiguity count to three.
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "aaaa") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "aa"); put("new_string", "b") },
            context(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("matches 2 times"), edit.text)

        // When only the first of two overlapping occurrences is a real
        // non-overlapping match, replace is deterministic and the edit must
        // succeed.
        val unique = path + "2"
        WriteFileTool().execute(buildJsonObject { put("path", unique); put("content", "aaa") }, context(dir))
        val single = EditFileTool().execute(
            buildJsonObject { put("path", unique); put("old_string", "aa"); put("new_string", "b") },
            context(dir),
        )
        assertFalse(single.isError, single.text)
        val content = SystemFileSystem.source(Path(unique)).buffered().use { it.readString() }
        assertEquals("ba", content)
    }

    @Test
    fun `edit ambiguous old_string errors`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/e.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a a a") }, context(dir))
        val edit = EditFileTool().execute(
            buildJsonObject { put("path", path); put("old_string", "a"); put("new_string", "b") },
            context(dir),
        )
        assertTrue(edit.isError)
        assertTrue(edit.text.contains("matches"))
    }

    @Test
    fun `list dir and glob and grep`() = runBlocking {
        val dir = tmpDir()
        val fs = SystemFileSystem
        fs.createDirectories(Path("$dir/nested"))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "alpha") }, context(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/nested/b.kt"); put("content", "val alpha = 1") }, context(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/nested/c.txt"); put("content", "beta") }, context(dir))

        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, context(dir))
        assertFalse(list.isError, list.text)
        assertEquals(listOf("a.txt", "nested"), list.text.split("\n"))

        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "**/*.kt") }, context(dir))
        assertFalse(glob.isError, glob.text)
        assertEquals(listOf("nested/b.kt"), glob.text.split("\n"))

        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "alpha") }, context(dir))
        assertFalse(grep.isError, grep.text)
        assertEquals(listOf("a.txt:1:alpha", "nested/b.kt:1:val alpha = 1"), grep.text.split("\n"))
    }

    @Test
    fun `list dir caps entries`() = runBlocking {
        val dir = tmpDir()
        val fs = SystemFileSystem
        repeat(600) { i ->
            WriteFileTool().execute(buildJsonObject { put("path", "$dir/f$i.txt"); put("content", "x") }, context(dir))
        }
        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, context(dir))
        assertFalse(list.isError, list.text)
        assertEquals(501, list.text.split("\n").size, "500 entries plus the omission marker")
        assertTrue(list.text.endsWith("...(100 more entries omitted)"), list.text)
    }

    @Test
    fun `glob caps entries`() = runBlocking {
        val dir = tmpDir()
        val fs = SystemFileSystem
        repeat(600) { i ->
            WriteFileTool().execute(buildJsonObject { put("path", "$dir/f$i.txt"); put("content", "x") }, context(dir))
        }
        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "*.txt") }, context(dir))
        assertFalse(glob.isError, glob.text)
        assertEquals(501, glob.text.split("\n").size, "500 entries plus the omission marker")
        assertTrue(glob.text.endsWith("...(100 more entries omitted)"), glob.text)
    }

    @Test
    fun `grep truncates long matched lines`() = runBlocking {
        val dir = tmpDir()
        val longLine = "y".repeat(1000)
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/long.txt"); put("content", longLine) },
            context(dir)
        )
        val grep = GrepTool().execute(buildJsonObject { put("root", dir); put("pattern", "y") }, context(dir))
        assertFalse(grep.isError, grep.text)
        val match = grep.text.split("\n").single()
        assertTrue(match.startsWith("long.txt:1:"), match)
        assertTrue(match.endsWith("..."), match)
        assertEquals(500, match.removePrefix("long.txt:1:").removeSuffix("...").length)
    }

    @Test
    fun `glob root not found errors`() = runBlocking {
        val result = GlobTool().execute(buildJsonObject { put("root", "/nonexistent-root"); put("pattern", "*") }, context("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `relative paths are resolved against the session cwd not the process cwd`() = runBlocking {
        val dir = tmpDir()
        val write = WriteFileTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("content", "x") },
            context(dir),
        )
        assertFalse(write.isError, write.text)
        assertTrue(
            SystemFileSystem.exists(Path("$dir/sub/a.txt")),
            "file must be written under the session cwd, not the process cwd",
        )

        val read = ReadFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("limit", 1) }, context(dir))
        assertFalse(read.isError, read.text)
        assertEquals("   1  x", read.text)

        val edit = EditFileTool().execute(
            buildJsonObject { put("path", "sub/a.txt"); put("old_string", "x"); put("new_string", "y") },
            context(dir),
        )
        assertFalse(edit.isError, edit.text)

        val list = ListDirTool().execute(buildJsonObject { put("path", ".") }, context(dir))
        assertFalse(list.isError, list.text)
        assertTrue(list.text.split("\n").contains("sub"), list.text)

        val glob = GlobTool().execute(buildJsonObject { put("root", "."); put("pattern", "**/*.txt") }, context(dir))
        assertFalse(glob.isError, glob.text)
        assertTrue(glob.text.contains("sub/a.txt"), glob.text)

        val grep = GrepTool().execute(buildJsonObject { put("root", "."); put("pattern", "y") }, context(dir))
        assertFalse(grep.isError, grep.text)
        assertTrue(grep.text.contains("sub/a.txt:1:y"), grep.text)
    }

    @Test
    fun `glob and grep do not follow symlinks out of the search root`() = runBlocking {
        val base = tmpDir()
        val project = "$base/project"
        val outside = "$base/outside"
        SystemFileSystem.createDirectories(Path(project))
        SystemFileSystem.createDirectories(Path(outside))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$project/real.txt"); put("content", "real") },
            context(project),
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$outside/secret.txt"); put("content", "secret") },
            context(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/linkdir"),
            java.nio.file.Path.of(outside),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/leak.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )

        val glob = GlobTool().execute(
            buildJsonObject { put("root", project); put("pattern", "**/*") },
            context(project),
        )
        assertFalse(glob.isError, glob.text)
        assertFalse(glob.text.contains("secret"), "glob must not escape via symlinks: ${glob.text}")
        assertTrue(glob.text.contains("real.txt"), glob.text)

        val grep = GrepTool().execute(
            buildJsonObject { put("root", project); put("pattern", "secret") },
            context(project),
        )
        assertFalse(grep.isError, grep.text)
        assertFalse(grep.text.contains("secret.txt"), "grep must not read through symlinks: ${grep.text}")
    }
}

class GlobRegexTest {

    @Test
    fun `simple star does not cross directories`() {
        val regex = globToRegex("src/*.kt")
        assertTrue(regex.matches("src/Main.kt"))
        assertFalse(regex.matches("src/sub/Main.kt"))
    }

    @Test
    fun `double star crosses directories`() {
        val regex = globToRegex("src/**/*.kt")
        assertTrue(regex.matches("src/Main.kt"))
        assertTrue(regex.matches("src/a/b/Main.kt"))
    }

    @Test
    fun `question mark matches single char`() {
        val regex = globToRegex("?.txt")
        assertTrue(regex.matches("a.txt"))
        assertFalse(regex.matches("ab.txt"))
    }

    @Test
    fun `regex metacharacters are escaped`() {
        val regex = globToRegex("a+b.kt")
        assertTrue(regex.matches("a+b.kt"))
        assertFalse(regex.matches("ab.kt"))
        assertFalse(regex.matches("aaa.kt"))
    }
}

class MoveDeleteToolsTest {

    @Test
    fun `move_file moves content and creates destination parents`() = runBlocking {
        val dir = tmpDir()
        val source = "$dir/a.txt"
        val destination = "$dir/sub/b.txt"
        WriteFileTool().execute(buildJsonObject { put("path", source); put("content", "hello") }, context(dir))
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", source); put("destination", destination) },
            context(dir),
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
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "a") }, context(dir))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/b.txt"); put("content", "b") }, context(dir))
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", "$dir/a.txt"); put("destination", "$dir/b.txt") },
            context(dir),
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
            context(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("move_directory"), result.text)
    }

    @Test
    fun `move_file missing source errors`() = runBlocking {
        val dir = tmpDir()
        val result = MoveFileTool().execute(
            buildJsonObject { put("source", "$dir/nope.txt"); put("destination", "$dir/x.txt") },
            context(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Source not found"), result.text)
    }

    @Test
    fun `move_directory moves the whole tree`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/src/nested"))
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/src/a.txt"); put("content", "a") }, context(dir))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/src/nested/b.txt"); put("content", "b") },
            context(dir),
        )
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", "$dir/src"); put("destination", "$dir/dst") },
            context(dir),
        )
        assertFalse(result.isError, result.text)
        assertFalse(SystemFileSystem.exists(Path("$dir/src")))
        assertTrue(SystemFileSystem.exists(Path("$dir/dst/nested/b.txt")))
    }

    @Test
    fun `move_directory refuses a file source`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "$dir/a.txt"); put("content", "a") }, context(dir))
        val result = MoveDirectoryTool().execute(
            buildJsonObject { put("source", "$dir/a.txt"); put("destination", "$dir/dst") },
            context(dir),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("move_file"), result.text)
    }

    @Test
    fun `delete_file deletes and carries the removed content as a diff`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/gone.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "bye") }, context(dir))
        val result = DeleteFileTool().execute(buildJsonObject { put("path", path) }, context(dir))
        assertFalse(result.isError, result.text)
        assertFalse(SystemFileSystem.exists(Path(path)))
        assertEquals(ToolResultDiff(path, "", "bye"), result.diff)
    }

    @Test
    fun `delete_file refuses directories and skips oversized diffs`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/sub"))
        val dirResult = DeleteFileTool().execute(buildJsonObject { put("path", "$dir/sub") }, context(dir))
        assertTrue(dirResult.isError)
        assertTrue(dirResult.text.contains("delete_directory"), dirResult.text)

        val big = "x".repeat(100_001)
        val bigPath = "$dir/big.txt"
        WriteFileTool().execute(buildJsonObject { put("path", bigPath); put("content", big) }, context(dir))
        val bigResult = DeleteFileTool().execute(buildJsonObject { put("path", bigPath) }, context(dir))
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
            context(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/link.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )
        val result = DeleteFileTool().execute(buildJsonObject { put("path", "$project/link.txt") }, context(project))
        assertTrue(result.isError)
        assertTrue(result.text.contains("symlink"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$project/link.txt")), "the link must survive the refusal")
        assertTrue(SystemFileSystem.exists(Path("$outside/secret.txt")), "the target must survive")
    }

    @Test
    fun `delete_directory deletes recursively`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/tree/deep"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/tree/deep/f.txt"); put("content", "x") },
            context(dir),
        )
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", "$dir/tree") }, context(dir))
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
            context(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/sub/link.txt"),
            java.nio.file.Path.of("$outside/secret.txt"),
        )
        val result = DeleteDirectoryTool().execute(buildJsonObject { put("path", "$project/sub") }, context(project))
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
            context(project),
        )
        java.nio.file.Files.createSymbolicLink(
            java.nio.file.Path.of("$project/linkdir"),
            java.nio.file.Path.of(outside),
        )
        val result =
            DeleteDirectoryTool().execute(buildJsonObject { put("path", "$project/linkdir") }, context(project))
        assertTrue(result.isError)
        assertTrue(result.text.contains("symlink"), result.text)
        assertTrue(SystemFileSystem.exists(Path("$outside/secret.txt")), "the symlink target must survive")
    }

    @Test
    fun `move_delete tools are local disk only and never touch the file store`() = runBlocking {
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
        val ctx = ToolContext(
            cwd = dir,
            client = null,
            clientCapabilities = ClientCapabilities(),
            sessionId = SessionId("sess_test"),
            fileStore = recordingStore,
        )
        val source = "$dir/a.txt"
        WriteFileTool().execute(buildJsonObject { put("path", source); put("content", "x") }, context(dir))
        val move = MoveFileTool().execute(
            buildJsonObject { put("source", source); put("destination", "$dir/b.txt") },
            ctx,
        )
        assertFalse(move.isError, move.text)
        val delete = DeleteFileTool().execute(buildJsonObject { put("path", "$dir/b.txt") }, ctx)
        assertFalse(delete.isError, delete.text)
        assertTrue(recordingStore.writes.isEmpty(), "move/delete must operate on the local disk")
    }
}

class ToolSchemaTest {

    @Test
    fun `required is a json array in every tool schema`() {
        val tools = listOf(
            ReadFileTool(),
            WriteFileTool(),
            EditFileTool(),
            MoveFileTool(),
            MoveDirectoryTool(),
            DeleteFileTool(),
            DeleteDirectoryTool(),
            ListDirTool(),
            GlobTool(),
            GrepTool(),
            BashTool(),
        )
        tools.forEach { tool ->
            val required = tool.parameters["required"]
            assertIs<JsonArray>(required, "${tool.name} required must be an array")
            required.forEach { assertIs<JsonPrimitive>(it) }
        }
    }
}

class ToolRegistryTest {
    @Test
    fun `register get all and override`() = runBlocking {
        val registry = ToolRegistry()
        val read = ReadFileTool()
        registry.register(read)
        registry.register(WriteFileTool())
        assertEquals(2, registry.all().size)
        assertEquals(read, registry.get("read_file"))
        assertEquals(null, registry.get("missing"))

        val override = object : AgentTool {
            override val name = "read_file"
            override val description = "override"
            override val parameters = buildJsonObject { put("type", JsonPrimitive("object")) }
            override val kind = com.agentclientprotocol.model.ToolKind.READ
            override val mutating = false
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject, context: ToolContext): ToolResult = ToolResult("x")
        }
        registry.register(override)
        assertEquals(2, registry.all().size)
        assertEquals("override", registry.get("read_file")?.description)
    }

    @Test
    fun `mode filtering withholds write and bash tools per mode`() = runBlocking {
        val registry = ToolRegistry()
        registry.registerAll(
            listOf(
                ReadFileTool(),
                WriteFileTool(),
                EditFileTool(),
                MoveFileTool(),
                MoveDirectoryTool(),
                DeleteFileTool(),
                DeleteDirectoryTool(),
                ListDirTool(),
                GlobTool(),
                GrepTool(),
                BashTool(),
            )
        )
        val plan = registry.availableForMode(SessionModeId("plan"))
        assertEquals(
            listOf("read_file", "list_dir", "glob", "grep").sorted(),
            plan.map { it.name }.sorted(),
        )
        assertTrue(registry.disabledInMode("write_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("edit_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("move_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("delete_file", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("bash", SessionModeId("plan")) != null)
        assertTrue(registry.disabledInMode("read_file", SessionModeId("plan")) == null)

        val build = registry.availableForMode(SessionModeId("build"))
        assertTrue(build.any { it.name == "write_file" })
        assertTrue(build.any { it.name == "edit_file" })
        assertTrue(build.any { it.name == "move_file" })
        assertTrue(build.any { it.name == "move_directory" })
        assertTrue(build.any { it.name == "delete_file" })
        assertTrue(build.any { it.name == "delete_directory" })
        assertTrue(build.none { it.name == "bash" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("build")) != null)

        val bash = registry.availableForMode(SessionModeId("bash"))
        assertTrue(bash.any { it.name == "bash" })
        assertTrue(bash.any { it.name == "write_file" })
        assertTrue(bash.any { it.name == "delete_directory" })
        assertTrue(registry.disabledInMode("bash", SessionModeId("bash")) == null)
        assertTrue(registry.disabledInMode("unknown_tool", SessionModeId("bash")) == null)
    }
}
