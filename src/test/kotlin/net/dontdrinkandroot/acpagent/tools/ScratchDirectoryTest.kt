package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class ScratchDirectoryTest {

    private lateinit var base: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("acp-scratch")
    }

    @AfterTest
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    private fun permissionsOf(dir: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))

    @Test
    fun `missing directory is created with owner-only permissions`() {
        val dir = base.resolve("scratch")
        assertEquals(dir.toString(), prepareScratchDirectory(dir.toString()))
        assertTrue(Files.isDirectory(dir), "the directory must have been created")
        assertEquals("rwx------", permissionsOf(dir))
    }

    @Test
    fun `existing directory owned by us with a wider mode is repaired`() {
        val dir = Files.createDirectory(base.resolve("scratch"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
        assertEquals(dir.toString(), prepareScratchDirectory(dir.toString()))
        assertEquals("rwx------", permissionsOf(dir), "a wider mode must be repaired, not trusted")
    }

    @Test
    fun `symbolic link is refused instead of followed`() {
        val target = Files.createDirectory(base.resolve("target"))
        val link = base.resolve("scratch")
        Files.createSymbolicLink(link, target)
        val e = assertFailsWith<ScratchDirectoryException> { prepareScratchDirectory(link.toString()) }
        assertTrue(e.message!!.contains("symbolic link"), e.message!!)
    }

    @Test
    fun `non-directory is refused`() {
        val file = Files.createFile(base.resolve("scratch"))
        val e = assertFailsWith<ScratchDirectoryException> { prepareScratchDirectory(file.toString()) }
        assertTrue(e.message!!.contains("not a directory"), e.message!!)
    }

    @Test
    fun `uncreatable directory fails loudly`() {
        val blocker = Files.createFile(base.resolve("blocker"))
        val e = assertFailsWith<ScratchDirectoryException> {
            prepareScratchDirectory(blocker.resolve("scratch").toString())
        }
        assertTrue(e.message!!.contains("cannot create scratch directory"), e.message!!)
    }

    @Test
    fun `missing intermediate parent is refused instead of materialized`() {
        val nested = base.resolve("missing").resolve("scratch")
        val e = assertFailsWith<ScratchDirectoryException> { prepareScratchDirectory(nested.toString()) }
        assertTrue(e.message!!.contains("parent"), e.message!!)
        assertFalse(Files.exists(base.resolve("missing")), "no unvalidated parent may be created")
    }

    @Test
    fun `symlinked parent is resolved into the returned trust root`() {
        val realParent = Files.createDirectory(base.resolve("real"))
        val link = base.resolve("link")
        Files.createSymbolicLink(link, realParent)
        val prepared = prepareScratchDirectory(link.resolve("scratch").toString())
        // The returned root is the symlink-resolved path, so the trust domain is
        // the real directory rather than whatever the link happens to name later.
        assertEquals(realParent.toRealPath().resolve("scratch").toString(), prepared)
        assertTrue(Files.isDirectory(realParent.resolve("scratch")), "the directory must exist behind the link")
    }
}
