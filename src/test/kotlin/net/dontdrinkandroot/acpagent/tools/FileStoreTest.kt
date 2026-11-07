package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.*

/**
 * Pins [LocalFileStore]'s file-type guard. Only regular files may be opened:
 * a FIFO inside the project (or `/dev/zero`) passes the size check - its
 * metadata size is reported as -1 - and then blocks on `open(2)` or streams
 * without end, hanging the turn. A directory is the cheap stand-in for the
 * same guard in a test.
 */
class FileStoreTest {

    @Test
    fun `raw read of a directory is refused instead of opening it`() = runBlocking {
        val dir = tmpDir()
        val error = assertFailsWith<FileStoreException> { LocalFileStore().readRaw(dir) }
        assertEquals("not a regular file: $dir", error.message)
    }

    /**
     * The per-line accumulation cap (issue #24): a file with no newlines (or
     * one huge line) must not materialize as one giant string once the size
     * caps are gone - the reader keeps the first [MAX_LINE_ACCUMULATION_BYTES]
     * bytes, marks the line truncated and advances to the next line normally.
     */
    @Test
    fun `a huge single line is clamped at the accumulation cap and marked truncated`() {
        val dir = tmpDir()
        val file = "$dir/huge-line.txt"
        writeTestFile(file, "x".repeat(100_000) + "\nsecond\n")
        val reader = StreamingLineReader(Path(file))
        val first = reader.readLine()
        requireNotNull(first)
        assertEquals(MAX_LINE_ACCUMULATION_BYTES, first.text.length)
        assertTrue(first.truncated, "a clamped line must be marked truncated")
        assertTrue(first.terminated)
        val second = reader.readLine()
        requireNotNull(second)
        assertEquals("second", second.text)
        assertFalse(second.truncated, "an intact line must not be marked truncated")
        assertNull(reader.readLine())
        assertEquals(2, reader.lineCount)
        reader.close()
    }

    /**
     * The whole-file size cap lives only on the raw path (issue #24):
     * [readRaw] materializes the entire content for edit_file matching and
     * diff pre-reads and must stay bounded, while windowed reads stream.
     */
    @Test
    fun `raw read still refuses files over the size cap`() = runBlocking {
        val dir = tmpDir()
        val file = "$dir/huge.bin"
        java.io.FileOutputStream(file).use { it.write(ByteArray(21 * 1024 * 1024)) }
        val error = assertFailsWith<FileTooLargeException> { LocalFileStore().readRaw(file) }
        assertTrue(error.message!!.contains("too large"), error.message!!)
        assertTrue(error.message!!.contains("bash"), error.message!!)
    }

    /**
     * The total-line derivation (issue #24): [countVisualLines] must agree
     * with the [StreamingLineReader] convention exactly - a trailing newline
     * is a terminator, a blank line between terminators is real, an empty
     * file has zero lines - so the footer can report the exact total from a
     * newline byte scan without decoding the file.
     */
    @Test
    fun `countVisualLines counts by the visual-line convention and agrees with the reader`() {
        val dir = tmpDir()
        val cases = mapOf(
            "" to 0,
            "a\n" to 1,
            "a\n\n" to 2,
            "a\nb" to 2,
            "a" to 1,
            "\n" to 1,
            "\n\n\n" to 3,
        )
        for ((content, expected) in cases) {
            val file = "$dir/case-${content.length}-${expected}.txt"
            writeTestFile(file, content)
            assertEquals(expected, countVisualLines(Path(file)), "byte scan of ${content.replace("\n", "\\n")}")
            val reader = StreamingLineReader(Path(file))
            var scanned = 0
            while (reader.readLine() != null) scanned++
            reader.close()
            assertEquals(expected, scanned, "reader scan of ${content.replace("\n", "\\n")}")
        }
    }

    /**
     * The binary sniff must cover the whole line, not just the accumulated
     * prefix: a NUL past the accumulation cap (inside a clamped tail) must
     * still be seen, or grep would scan a binary blob as text.
     */
    @Test
    fun `the reader records NUL bytes even inside a clamped line tail`() {
        val dir = tmpDir()
        val file = "$dir/nul-in-tail.txt"
        writeTestFile(file, "x".repeat(MAX_LINE_ACCUMULATION_BYTES + 50) + "\u0000" + "tail\nok\n")
        val reader = StreamingLineReader(Path(file))
        val first = reader.readLine()
        requireNotNull(first)
        assertTrue(first.truncated)
        assertFalse(first.text.contains('\u0000'), "the clamped prefix must not contain the tail NUL")
        assertTrue(reader.sawNul, "a NUL in the skipped tail must still be recorded")
        assertEquals("ok", reader.readLine()?.text)
        reader.close()
    }
}

private fun writeTestFile(path: String, content: String) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
}
