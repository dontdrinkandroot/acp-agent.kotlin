package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `trims the argument part at fifty characters`() {
        val title = formatToolTitle("bash", buildJsonObject { put("command", "a".repeat(60)) })
        assertEquals(56, title.length)
        assertEquals("bash(command: ${"a".repeat(38)}...)", title)
    }

    @Test
    fun `keeps argument part up to fifty characters untrimmed`() {
        val command = "a".repeat(41)
        val title = formatToolTitle("bash", buildJsonObject { put("command", command) })
        assertEquals(56, title.length)
        assertEquals("bash(command: $command)", title)
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
}