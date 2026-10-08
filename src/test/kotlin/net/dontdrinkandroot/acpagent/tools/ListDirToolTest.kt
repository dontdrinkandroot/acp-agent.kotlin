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

class ListDirToolTest {

    @Test
    fun `list dir lists the directory entries`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/nested"))
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "alpha") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/nested/b.kt"); put("content", "val alpha = 1") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/nested/c.txt"); put("content", "beta") },
            testContext(dir)
        )

        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, testContext(dir))
        assertFalse(list.isError, list.text)
        assertEquals(listOf("a.txt", "nested"), list.text.split("\n"))
    }

    @Test
    fun `list dir caps entries`() = runBlocking {
        val dir = tmpDir()
        repeat(600) { i ->
            WriteFileTool().execute(
                buildJsonObject { put("path", "$dir/f$i.txt"); put("content", "x") },
                testContext(dir)
            )
        }
        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, testContext(dir))
        assertFalse(list.isError, list.text)
        assertEquals(501, list.text.split("\n").size, "500 entries plus the omission marker")
        assertTrue(list.text.endsWith("...(100 more entries omitted)"), list.text)
    }

    @Test
    fun `list dir fails loudly on an unreadable root directory`() = runBlocking {
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

        val list = ListDirTool().execute(buildJsonObject { put("path", "$dir/locked") }, testContext(dir))
        assertTrue(list.isError, "list_dir on an unreadable directory must fail loudly, was: ${list.text}")
        assertEquals("Not readable: $dir/locked", list.text)
    }

    @Test
    fun `relative list paths are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        SystemFileSystem.createDirectories(Path("$dir/sub"))
        val list = ListDirTool().execute(buildJsonObject { put("path", ".") }, testContext(dir))
        assertFalse(list.isError, list.text)
        assertTrue(list.text.split("\n").contains("sub"), list.text)
    }

    @Test
    fun `list_dir hides excluded entries before the cap`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, ".env.local").writeText("A=1")
        java.io.File(dir, ".env").writeText("plain")
        java.io.File(dir, "readme.md").writeText("x")

        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, testContext(dir))
        assertFalse(list.isError, list.text)
        assertTrue(list.text.contains("readme.md"), list.text)
        assertTrue(list.text.contains(".env"), list.text)
        assertFalse(list.text.contains(".env.local"), list.text)
    }

    @Test
    fun `nested excluded file is hidden in listings at any depth`() = runBlocking {
        val dir = tmpDir()
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir, "config"))
        java.io.File(dir, "config/.env.local").writeText("A=1")

        val list = ListDirTool().execute(buildJsonObject { put("path", "$dir/config") }, testContext(dir))
        assertFalse(list.isError, list.text)
        assertFalse(list.text.contains(".env.local"), list.text)
    }

    @Test
    fun `custom exclusion rules inject through the tool context`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, "secrets.env").writeText("A=1")
        java.io.File(dir, "plain.txt").writeText("x")
        val ctx = testContext(dir, fileExclusions = FileAccessExclusions.of(listOf("secrets.env")))

        val list = ListDirTool().execute(buildJsonObject { put("path", dir) }, ctx)
        assertFalse(list.text.contains("secrets.env"), list.text)
        assertTrue(list.text.contains("plain.txt"), list.text)
    }

    @Test
    fun `schema pins path`() {
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"Directory path, absolute or relative to the working directory."}},"required":["path"]}""",
            llmWireJson.encodeToString(ListDirTool().parameters),
        )
    }
}
