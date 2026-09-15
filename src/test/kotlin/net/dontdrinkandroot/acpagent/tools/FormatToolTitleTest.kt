package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatToolTitleTest {

    @Test
    fun `renders name with key value pairs in argument order`() {
        val title = formatToolTitle(
            "bash",
            buildJsonObject {
                put("command", "./gradlew test")
                put("args", "--info")
            },
        )
        assertEquals("bash(command: ./gradlew test, args: --info)", title)
    }

    // The JetBrains ACP client elides long titles down to the first
    // key:value-looking fragment; essential info must therefore lead.
    @Test
    fun `trims the argument part at one hundred characters`() {
        val title = formatToolTitle("bash", buildJsonObject { put("command", "a".repeat(120)) })
        assertEquals(5 + 9 + 97 + 3 + 1, title.length)
        assertEquals("bash(command: ${"a".repeat(97)}...)", title)
    }

    @Test
    fun `keeps argument part up to one hundred characters untrimmed`() {
        val command = "a".repeat(88)
        val title = formatToolTitle("bash", buildJsonObject { put("command", command) })
        assertEquals("bash(command: $command)", title)
    }

    @Test
    fun `falls back to bare name without arguments`() {
        assertEquals("run", formatToolTitle("run", buildJsonObject {}))
    }

    @Test
    fun `skips blank and null values`() {
        val title = formatToolTitle(
            "run",
            buildJsonObject {
                put("config", "marker")
                put("args", "")
            },
        )
        assertEquals("run(config: marker)", title)
    }

    @Test
    fun `flattens newlines so the title stays a single line`() {
        val title = formatToolTitle(
            "bash",
            buildJsonObject { put("command", "echo one\necho two\r\necho three") },
        )
        assertFalse(title.contains('\n'), title)
        assertFalse(title.contains('\r'), title)
        assertEquals("bash(command: echo one echo two echo three)", title)
    }

    @Test
    fun `run title leads with the config name`() {
        assertEquals("run(marker)", formatRunToolTitle("marker", null))
        assertEquals("run(marker: --watch)", formatRunToolTitle("marker", "--watch"))
        assertEquals("run", formatRunToolTitle(null, null))
        assertEquals("run(marker)", formatRunToolTitle("marker", ""))
        assertEquals("run(marker)", formatRunToolTitle("marker", "   "))
    }

    // The config name identifies what runs and must survive even a very long
    // args value; only the args part is capped.
    @Test
    fun `run title truncates args but never the config name`() {
        val args = "x".repeat(200)
        val title = formatRunToolTitle("marker", args)
        assertTrue(title.startsWith("run(marker: "), title)
        assertEquals("run(marker: ${"x".repeat(97)}...)", title)
    }

    @Test
    fun `run title is order-independent`() {
        val first = RunTool("").title(
            buildJsonObject {
                put("config", "test")
                put("args", "The quick brown fox jumps over the lazy dog and then some more")
            },
        )
        val second = RunTool("").title(
            buildJsonObject {
                put("args", "The quick brown fox jumps over the lazy dog and then some more")
                put("config", "test")
            },
        )
        assertEquals("run(test: The quick brown fox jumps over the lazy dog and then some more)", first)
        assertEquals(first, second)
    }

    @Test
    fun `run config write title leads with the config name`() {
        assertEquals(
            "create_run_config(echo: echo {args})",
            formatRunConfigToolTitle("create_run_config", "echo", "echo {args}", null),
        )
        assertEquals(
            "create_run_config(echo: echo {args}, description: echo it back)",
            formatRunConfigToolTitle("create_run_config", "echo", "echo {args}", "echo it back"),
        )
        assertEquals("delete_run_config(echo)", formatRunConfigToolTitle("delete_run_config", "echo", null, null))
    }

    @Test
    fun `run config write title truncates command but never the name`() {
        val command = "c".repeat(150)
        val title = formatRunConfigToolTitle("update_run_config", "test", command, null)
        assertTrue(title.startsWith("update_run_config(test: "), title)
        assertEquals("update_run_config(test: ${"c".repeat(97)}...)", title)
    }
}