package net.dontdrinkandroot.acpagent.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainmentTest {

    @Test
    fun `absolute path inside the project is within`() {
        val dir = Files.createTempDirectory("acp-containment")
        val inner = dir.resolve("a").resolve("b.txt")
        Files.createDirectories(inner.parent)
        Files.writeString(inner, "x")
        assertTrue(isWithin(dir.toString(), inner.toString()))
    }

    @Test
    fun `absolute path outside the project is not within`() {
        val dir = Files.createTempDirectory("acp-containment")
        val outside = Files.createTempFile("acp-outside", ".txt")
        assertFalse(isWithin(dir.toString(), outside.toString()))
    }

    @Test
    fun `sibling directory with shared prefix is not within`() {
        val base = Files.createTempDirectory("acp-containment")
        val project = Files.createDirectory(base.resolve("project"))
        val sibling = Files.createDirectory(base.resolve("project-other"))
        assertFalse(isWithin(project.toString(), sibling.resolve("x").toString()))
    }

    @Test
    fun `relative path is resolved against the session cwd`() {
        val dir = Files.createTempDirectory("acp-containment")
        val file = dir.resolve("sub").resolve("deep").resolve("f.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        assertTrue(isWithin(dir.toString(), "sub/deep/f.txt"))
    }

    @Test
    fun `relative escape via dotdot is not within`() {
        val dir = Files.createTempDirectory("acp-containment")
        val outside = Files.createTempFile("acp-outside", ".txt")
        assertFalse(isWithin(dir.toString(), "../${outside.fileName}"))
    }

    @Test
    fun `symlink escaping the project is not within`() {
        val base = Files.createTempDirectory("acp-containment")
        val project = Files.createDirectory(base.resolve("project"))
        val outside = Files.createTempFile("acp-symlink-outside", ".txt")
        val link = project.resolve("escape")
        Files.createSymbolicLink(link, outside)
        assertFalse(isWithin(project.toString(), link.toString()))
    }

    @Test
    fun `dangling symlink inside pointing outside is not within`() {
        val base = Files.createTempDirectory("acp-containment")
        val project = Files.createDirectory(base.resolve("project"))
        val target = base.resolve("nowhere").resolve("secret.txt")
        val link = project.resolve("dangling")
        Files.createSymbolicLink(link, target)
        assertFalse(isWithin(project.toString(), link.toString()))
    }

    @Test
    fun `not yet existing file inside the project is within`() {
        val dir = Files.createTempDirectory("acp-containment")
        assertTrue(isWithin(dir.toString(), dir.resolve("to-be-created.txt").toString()))
    }
}