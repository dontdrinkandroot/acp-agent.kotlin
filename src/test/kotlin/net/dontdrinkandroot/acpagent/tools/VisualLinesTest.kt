package net.dontdrinkandroot.acpagent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shared numbered-window rendering (issue #81): the one
 * implementation of the `N│line` prefix, the `(empty content)` marker and the
 * continue footer that read_file and web_fetch both render through, plus the
 * shared start-line bounds guard (issue #45).
 */
class VisualLinesTest {

    private fun render(
        lines: List<String>,
        start: Int = 1,
        total: Int? = null,
        complete: Boolean = false,
        lineArg: String = "line",
        limitArg: String = "limit",
    ): String = renderNumberedWindow(lines, start, total, complete, lineArg, limitArg)

    @Test
    fun `lines are numbered with a fixed width prefix and a bar delimiter`() {
        assertEquals("   1│a\n   2│b\n   3│c", render(listOf("a", "b", "c"), complete = true))
    }

    @Test
    fun `the number column grows past four digits without truncation`() {
        val lines = (1..1000).map { it.toString() }
        assertEquals("  10│10", render(lines.subList(0, 10), complete = true).lines()[9])
        assertEquals(" 500│500", render(lines.subList(0, 500), complete = true).lines()[499])
        assertEquals("1000│1000", render(lines, complete = true).lines()[999])
    }

    @Test
    fun `a trailing carriage return is stripped so CRLF content renders by visual lines`() {
        assertEquals("   1│a\n   2│b", render(listOf("a\r", "b\r"), complete = true))
    }

    @Test
    fun `over-long lines are clamped with the shared truncation marker`() {
        val rendered = render(listOf("x".repeat(MAX_LINE_CHARS + 50)), complete = true)
        assertEquals(2000, rendered.removePrefix("   1│").removeSuffix(TRUNCATED_LINE_SUFFIX).length)
        assertTrue(rendered.endsWith(TRUNCATED_LINE_SUFFIX), rendered.takeLast(40))
    }

    @Test
    fun `an empty window renders the explicit empty marker instead of a phantom line`() {
        assertEquals("(empty content)", render(emptyList(), complete = true))
        assertEquals("(empty content)", render(emptyList(), total = 0, complete = false))
    }

    @Test
    fun `a complete window carries no footer`() {
        assertEquals("   1│a\n   2│b", render(listOf("a", "b"), total = 2, complete = true))
    }

    @Test
    fun `a truncated window carries the continue footer with the tool's argument labels`() {
        assertEquals(
            "   2│b\n   3│c\n\n(Showing lines 2-3 of 5. Use line=4 and limit to continue.)",
            render(listOf("b", "c"), start = 2, total = 5, complete = false),
        )
        assertEquals(
            "   1│a\n\n(Showing lines 1-1 of 30. Use startLine=2 and maxLines to continue.)",
            render(listOf("a"), start = 1, total = 30, complete = false, lineArg = "startLine", limitArg = "maxLines"),
        )
    }

    @Test
    fun `an unknown total renders as a question mark in the footer`() {
        assertEquals(
            "   1│a\n\n(Showing lines 1-1 of ?. Use line=2 and limit to continue.)",
            render(listOf("a"), total = null, complete = false),
        )
    }

    @Test
    fun `an unset start line passes the bounds guard`() {
        assertNull(lineWindowStartError("line", null))
        assertNull(lineWindowStartError("startLine", null))
    }

    @Test
    fun `the start line must be positive and within the int range`() {
        assertEquals("'line' must be a positive integer (1-based)", lineWindowStartError("line", 0))
        assertEquals("'startLine' must be a positive integer (1-based)", lineWindowStartError("startLine", -3))
        assertEquals(
            "'line' must be at most ${Int.MAX_VALUE}",
            lineWindowStartError("line", Int.MAX_VALUE.toLong() + 1)
        )
        assertNull(lineWindowStartError("line", Int.MAX_VALUE.toLong()))
    }
}
