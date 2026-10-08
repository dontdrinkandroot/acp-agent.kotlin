package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadFileToolTest {

    @Test
    fun `write then read round trip`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/a/b/c.txt"
        val write = WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "line1\nline2\nline3") },
            testContext(dir)
        )
        assertFalse(write.isError, write.text)
        assertTrue(write.text.contains("Written"))
        assertEquals(path, write.diff?.path, "write to a new file must carry a diff with the target path")
        assertEquals(null, write.diff?.oldText, "a new file has no old content")

        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 3) }, testContext(dir))
        assertFalse(read.isError, read.text)
        // A windowed read (limit set, no line) covering the whole file is
        // numbered, with no footer.
        assertEquals("   1│line1\n   2│line2\n   3│line3", read.text)
    }

    @Test
    fun `read preserves exact indentation after the line number delimiter`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/indented.kt"
        val content = "fun foo() {\n    val x = 1\n        return x\n}"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", content) }, testContext(dir))
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 10) }, testContext(dir))
        assertFalse(read.isError, read.text)
        // Everything after the '|' is the raw line: the leading spaces belong
        // to the code, so the indentation is readable directly off the output
        // instead of having to be inferred from a whitespace-only prefix.
        assertEquals("   1│fun foo() {\n   2│    val x = 1\n   3│        return x\n   4│}", read.text)
    }

    @Test
    fun `read truncates a single line over the cap`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/minified.txt"
        val longLine = "z".repeat(5000)
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "before\n$longLine\nafter") },
            testContext(dir)
        )
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 3) }, testContext(dir))
        assertFalse(read.isError, read.text)
        val lines = read.text.split("\n")
        assertEquals(3, lines.size, read.text)
        assertTrue(lines[1].startsWith("   2│z"), lines[1])
        assertTrue(lines[1].endsWith("... [truncated]"), lines[1])
        assertEquals(2000, lines[1].removePrefix("   2│").removeSuffix("... [truncated]").length)
    }

    @Test
    fun `read refuses files over the size cap`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/huge.bin"
        java.io.FileOutputStream(path).use { it.write(ByteArray(21 * 1024 * 1024) { 0 }) } // 21 MB of NULs
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 1) }, testContext(dir))
        assertTrue(read.isError)
        assertTrue(read.text.contains("too large"), read.text)
        assertTrue(read.text.contains("bash"), read.text)
    }

    @Test
    fun `read errors when the line is past the end of the file`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc") }, testContext(dir))
        val read = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", 100); put("limit", 1) },
            testContext(dir)
        )
        assertTrue(read.isError)
        assertTrue(read.text.contains("past the end"), read.text)
        assertTrue(read.text.contains("3 lines"), read.text)
    }

    @Test
    fun `explicit json null path is rejected with a dedicated message`() = runBlocking {
        val dir = tmpDir()
        val read = ReadFileTool().execute(buildJsonObject { put("path", JsonNull); put("limit", 1) }, testContext(dir))
        assertTrue(read.isError)
        assertEquals("'path' must not be null", read.text)
    }

    @Test
    fun `read with line and limit slices`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "a\nb\nc\nd\ne") },
            testContext(dir)
        )
        val read = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", 2); put("limit", 2) },
            testContext(dir)
        )
        assertFalse(read.isError, read.text)
        // Explicit window: 1-indexed numbered lines plus a truncated-footer
        // telling the model what range was shown and where to continue.
        assertEquals(
            "   2│b\n   3│c\n\n(Showing lines 2-3 of 5. Use line=4 and limit to continue.)",
            read.text,
        )
    }

    @Test
    fun `read window reaching the end of the file omits the footer`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "a\nb\nc\nd\ne") },
            testContext(dir)
        )
        val read =
            ReadFileTool().execute(
                buildJsonObject { put("path", path); put("line", 4); put("limit", 2) },
                testContext(dir)
            )
        assertFalse(read.isError, read.text)
        assertEquals("   4│d\n   5│e", read.text)
    }

    @Test
    fun `read window reaching the last visible line of a trailing newline file omits the footer`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/trailing.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\n") }, testContext(dir))
        val read =
            ReadFileTool().execute(
                buildJsonObject { put("path", path); put("line", 2); put("limit", 1) },
                testContext(dir)
            )
        assertFalse(read.isError, read.text)
        // The trailing newline is a terminator, not a third line: the window
        // reaches the last visible line, so no footer may claim more below.
        assertEquals("   2│b", read.text)
    }

    @Test
    fun `read of a large file honors growing limits without truncation`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/large.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", (1..1000).joinToString("\n") { it.toString() }) },
            testContext(dir),
        )
        val small = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 10) }, testContext(dir))
        val large = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 500) }, testContext(dir))
        val full = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2000) }, testContext(dir))
        assertFalse(small.isError, small.text)
        assertFalse(large.isError, large.text)
        assertFalse(full.isError, full.text)
        // A stale or truncated window would return the same small excerpt for
        // every limit; the numbered line count must grow with the limit and
        // the line numbers must match the requested window.
        assertTrue(small.text.startsWith("   1│1\n"), small.text)
        assertTrue(large.text.startsWith("   1│1\n"), large.text)
        assertEquals("  10│10", small.text.lines()[9], small.text)
        assertEquals(12, small.text.lines().size, "the footer block adds lines: ${small.text}")
        assertEquals(" 500│500", large.text.lines()[499], large.text)
        assertEquals(502, large.text.lines().size, "the footer block adds lines: ${large.text}")
        // A full read whose limit covers the whole file: numbered, no footer.
        assertTrue(full.text.startsWith("   1│1\n"), full.text)
        assertEquals("1000│1000", full.text.lines()[999], full.text)
        assertEquals(1000, full.text.lines().size, "a whole-file window has no footer: ${full.text}")
    }

    @Test
    fun `read missing file errors`() = runBlocking {
        val result =
            ReadFileTool().execute(buildJsonObject { put("path", "/nonexistent/nope.txt") }, testContext("/tmp"))
        assertTrue(result.isError)
    }

    @Test
    fun `full read keeps the trailing newline`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc\n") }, testContext(dir))
        val read = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 10) }, testContext(dir))
        assertFalse(read.isError, read.text)
        // Full read (limit covers the file): numbered, trailing terminator
        // dropped so the phantom empty line is not rendered, no footer.
        assertEquals("   1│a\n   2│b\n   3│c", read.text)
    }

    @Test
    fun `crlf file slices by visual lines`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/crlf.txt"
        WriteFileTool().execute(
            buildJsonObject { put("path", path); put("content", "a\r\nb\r\nc\r\n") },
            testContext(dir)
        )
        val read = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", 2); put("limit", 1) },
            testContext(dir),
        )
        assertFalse(read.isError, read.text)
        assertEquals(
            "   2│b\n\n(Showing lines 2-2 of 3. Use line=3 and limit to continue.)",
            read.text,
        )
    }

    @Test
    fun `non-positive line and limit are rejected`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc") }, testContext(dir))
        val zeroLine =
            ReadFileTool().execute(
                buildJsonObject { put("path", path); put("line", 0); put("limit", 1) },
                testContext(dir)
            )
        assertTrue(zeroLine.isError)
        val zeroLimit = ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 0) }, testContext(dir))
        assertTrue(zeroLimit.isError)
        val negLine = ReadFileTool().execute(
            buildJsonObject { put("path", path); put("line", -1); put("limit", 1) },
            testContext(dir)
        )
        assertTrue(negLine.isError)
    }

    @Test
    fun `read requires limit and bounds it`() = runBlocking {
        val dir = tmpDir()
        val path = "$dir/f.txt"
        WriteFileTool().execute(buildJsonObject { put("path", path); put("content", "a\nb\nc") }, testContext(dir))
        val missingLimit = ReadFileTool().execute(buildJsonObject { put("path", path) }, testContext(dir))
        assertTrue(missingLimit.isError)
        assertTrue(missingLimit.text.contains("'limit'"), missingLimit.text)
        val tooLargeLimit =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2001) }, testContext(dir))
        assertTrue(tooLargeLimit.isError)
        assertTrue(tooLargeLimit.text.contains("2000"), tooLargeLimit.text)
        val atBound =
            ReadFileTool().execute(buildJsonObject { put("path", path); put("limit", 2000) }, testContext(dir))
        assertFalse(atBound.isError, atBound.text)
        assertEquals("   1│a\n   2│b\n   3│c", atBound.text)
    }

    @Test
    fun `relative read paths are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("content", "x") }, testContext(dir))
        val read =
            ReadFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("limit", 1) }, testContext(dir))
        assertFalse(read.isError, read.text)
        assertEquals("   1│x", read.text)
    }

    @Test
    fun `read_file refuses an excluded env local file`() = runBlocking {
        val dir = tmpDir()
        val file = java.io.File(dir, ".env.local").apply { writeText("SECRET_KEY=abc") }

        val read = ReadFileTool().execute(
            buildJsonObject { put("path", file.absolutePath); put("limit", 100) },
            testContext(dir)
        )
        assertTrue(read.isError, read.text)
        assertTrue(read.text.contains("excluded from tool access"), read.text)
        assertTrue(read.text.contains(".env*.local"), read.text)
        // The refusal must not leak the content.
        assertFalse(read.text.contains("SECRET_KEY"), read.text)
        assertTrue(file.isFile, "the excluded file must be untouched")
    }

    @Test
    fun `read_file refusal happens before the client fs proxy read`() = runBlocking {
        val dir = tmpDir()
        val file = java.io.File(dir, ".env.local").apply { writeText("SECRET_KEY=abc") }
        val fsProxy = RecordingFsClient()
        val ctx = testContext(dir, fileStore = ClientFileStore(fsProxy))

        val read = ReadFileTool().execute(buildJsonObject { put("path", file.absolutePath); put("limit", 100) }, ctx)
        assertTrue(read.isError, read.text)
        assertFalse(read.text.contains("SECRET_KEY"), read.text)
        assertTrue(fsProxy.readCalls.isEmpty(), "no fs-proxy round trip may happen on refusal")
    }

    @Test
    fun `read_file still reads non-excluded env files`() = runBlocking {
        val dir = tmpDir()
        val file = java.io.File(dir, ".env.example").apply { writeText("plain=1") }

        val read = ReadFileTool().execute(
            buildJsonObject { put("path", file.absolutePath); put("limit", 100) },
            testContext(dir)
        )
        assertFalse(read.isError, read.text)
        assertTrue(read.text.contains("plain=1"), read.text)
    }

    @Test
    fun `custom exclusion rules inject through the tool context`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, "secrets.env").writeText("A=1")
        val ctx = testContext(dir, fileExclusions = FileAccessExclusions.of(listOf("secrets.env")))

        val read = ReadFileTool().execute(buildJsonObject { put("path", "$dir/secrets.env"); put("limit", 100) }, ctx)
        assertTrue(read.isError, read.text)
        assertTrue(read.text.contains("exclusion rule 'secrets.env'"), read.text)
    }

    @Test
    fun `schema pins path limit line`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"File path, absolute or relative to the working directory."},"limit":{"type":"integer","description":"Maximum number of lines to return (1-2000)."},"line":{"type":"integer","description":"First line to return (1-based). Defaults to the start of the file."}},"required":["path","limit"]}""",
            llmWireJson.encodeToString(ReadFileTool().parameters),
        )
    }
}
