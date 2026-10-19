package net.dontdrinkandroot.acpagent.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
