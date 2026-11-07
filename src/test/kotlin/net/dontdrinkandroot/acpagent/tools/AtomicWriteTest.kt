package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the stale-temp sweep for crashed atomic writes (issue #20): a crash
 * between write and move strands `<name><random>.tmp` files that hold content
 * forever and are invisible to every listing. Only stale temps of the writer's
 * own naming may be swept - a concurrent writer's fresh temp must survive.
 */
class AtomicWriteTest {

    @Test
    fun `sweep deletes stale temps of the name prefix and keeps fresh and unrelated files`() {
        val dir = Files.createTempDirectory("acp-atomic-write")
        val stale = Files.writeString(dir.resolve("session-stale123.tmp"), "orphaned")
        val fresh = Files.writeString(dir.resolve("session-fresh456.tmp"), "in-flight")
        val otherPrefix = Files.writeString(dir.resolve("other-stale.tmp"), "unrelated")
        val wrongSuffix = Files.writeString(dir.resolve("session-stale123.json"), "record")
        val twoHoursAgo = FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS)
        listOf(stale, otherPrefix, wrongSuffix).forEach { Files.setLastModifiedTime(it, twoHoursAgo) }

        sweepStaleTempFiles(dir, "session-")

        assertFalse(Files.exists(stale), "a crashed write's stale temp file is swept")
        assertTrue(Files.exists(fresh), "a concurrent write's fresh temp file stays")
        assertTrue(Files.exists(otherPrefix), "another writer's temp naming is untouched")
        assertTrue(Files.exists(wrongSuffix), "records and other suffixes are untouched")
    }

    @Test
    fun `sweep never touches symlinks or directories matching the temp naming`() {
        val dir = Files.createTempDirectory("acp-atomic-write")
        val target = Files.writeString(dir.resolve("keep.txt"), "target")
        val link = dir.resolve("session-link123.tmp")
        Files.createSymbolicLink(link, target)
        val subDir = dir.resolve("session-dir123.tmp")
        Files.createDirectory(subDir)
        val twoHoursAgo = FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS)
        listOf(link, subDir).forEach { Files.setLastModifiedTime(it, twoHoursAgo) }

        sweepStaleTempFiles(dir, "session-")

        assertTrue(Files.isSymbolicLink(link), "a symlink is never deleted - the target it names stays")
        assertTrue(Files.isDirectory(subDir), "a directory is never deleted by a file-temp sweep")
        assertTrue(Files.exists(target))
    }

    @Test
    fun `writeAtomically sweeps stale temp files of the target name`() {
        val dir = Files.createTempDirectory("acp-atomic-write")
        val target = dir.resolve("run.json")
        val stale = dir.resolve("run.jsonstale123.tmp")
        Files.writeString(stale, "orphaned run config")
        Files.setLastModifiedTime(
            stale,
            FileTime.fromMillis(System.currentTimeMillis() - 2 * STALE_TEMP_AGE_MILLIS),
        )
        val fresh = dir.resolve("run.jsonfresh456.tmp")
        Files.writeString(fresh, "in-flight write")

        writeAtomically(target, "{}")

        assertEquals("{}", Files.readString(target))
        assertFalse(Files.exists(stale), "a crashed run-config write's temp file is swept")
        assertTrue(Files.exists(fresh), "a concurrent write's in-flight temp file stays")
    }
}