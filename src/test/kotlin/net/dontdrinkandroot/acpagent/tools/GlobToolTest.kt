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

class GlobToolTest {

    @Test
    fun `glob finds files matching the pattern`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/nested/b.kt"); put("content", "val alpha = 1") },
            testContext(dir)
        )
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/nested/c.txt"); put("content", "beta") },
            testContext(dir)
        )

        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "**/*.kt") }, testContext(dir))
        assertFalse(glob.isError, glob.text)
        assertEquals(listOf("nested/b.kt"), glob.text.split("\n"))
    }

    @Test
    fun `glob caps entries`() = runBlocking {
        val dir = tmpDir()
        repeat(600) { i ->
            WriteFileTool().execute(
                buildJsonObject { put("path", "$dir/f$i.txt"); put("content", "x") },
                testContext(dir)
            )
        }
        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "*.txt") }, testContext(dir))
        assertFalse(glob.isError, glob.text)
        assertEquals(501, glob.text.split("\n").size, "500 entries plus the omission marker")
        assertTrue(glob.text.endsWith("...(100 more entries omitted)"), glob.text)
    }

    @Test
    fun `glob root not found errors`() = runBlocking {
        val result = GlobTool().execute(
            buildJsonObject { put("root", "/nonexistent-root"); put("pattern", "*") },
            testContext("/tmp")
        )
        assertTrue(result.isError)
        assertEquals("Root not found: /nonexistent-root", result.text)
    }

    @Test
    fun `glob with a file as root fails loudly instead of silently matching nothing`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(
            buildJsonObject { put("path", "$dir/a.txt"); put("content", "needle") },
            testContext(dir)
        )

        val glob =
            GlobTool().execute(buildJsonObject { put("root", "$dir/a.txt"); put("pattern", "**/*") }, testContext(dir))
        assertTrue(glob.isError, "glob with a file as root must fail loudly, was: ${glob.text}")
        assertEquals("Not a directory: $dir/a.txt", glob.text)
    }

    @Test
    fun `glob fails loudly on an unreadable root directory`() = runBlocking {
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

        val glob =
            GlobTool().execute(buildJsonObject { put("root", "$dir/locked"); put("pattern", "**/*") }, testContext(dir))
        assertTrue(glob.isError, "glob on an unreadable directory must fail loudly, was: ${glob.text}")
        assertEquals("Not readable: $dir/locked", glob.text)
    }

    @Test
    fun `relative glob roots are resolved against the session cwd`() = runBlocking {
        val dir = tmpDir()
        WriteFileTool().execute(buildJsonObject { put("path", "sub/a.txt"); put("content", "x") }, testContext(dir))
        val glob =
            GlobTool().execute(buildJsonObject { put("root", "."); put("pattern", "**/*.txt") }, testContext(dir))
        assertFalse(glob.isError, glob.text)
        assertTrue(glob.text.contains("sub/a.txt"), glob.text)
    }

    @Test
    fun `glob skips dot git directories`() = runBlocking {
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

        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "**/*") }, testContext(dir))
        assertFalse(glob.isError, glob.text)
        assertFalse(glob.text.contains(".git"), "glob must skip .git: ${glob.text}")
        assertTrue(glob.text.contains("a.txt"), glob.text)
    }

    @Test
    fun `glob does not follow symlinks out of the search root`() = runBlocking {
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

        val glob = GlobTool().execute(
            buildJsonObject { put("root", project); put("pattern", "**/*") },
            testContext(project),
        )
        assertFalse(glob.isError, glob.text)
        assertFalse(glob.text.contains("secret"), "glob must not escape via symlinks: ${glob.text}")
        assertTrue(glob.text.contains("real.txt"), glob.text)
    }

    @Test
    fun `glob hides excluded files`() = runBlocking {
        val dir = tmpDir()
        java.io.File(dir, ".env.local").writeText("SECRET=1")
        java.io.File(dir, "code.txt").writeText("SECRET=1")

        val glob = GlobTool().execute(buildJsonObject { put("root", dir); put("pattern", "**/*") }, testContext(dir))
        assertFalse(glob.isError, glob.text)
        assertTrue(glob.text.contains("code.txt"), glob.text)
        assertFalse(glob.text.contains(".env.local"), glob.text)
    }

    @Test
    fun `schema pins pattern with optional root`() {
        assertEquals(
            """{"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern (e.g. src/**/*.kt); ** crosses directory boundaries."},"root":{"type":"string","description":"Directory to search, absolute or relative; defaults to the working directory."}},"required":["pattern"]}""",
            llmWireJson.encodeToString(GlobTool().parameters),
        )
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
