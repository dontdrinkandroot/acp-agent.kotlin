package net.dontdrinkandroot.acpagent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalcExpressionTest {

    @Test
    fun `adds and multiplies with standard precedence`() {
        assertEquals(14.0, evaluateExpression("2 + 3 * 4"))
    }

    @Test
    fun `subtracts and groups with parentheses`() {
        assertEquals(20.0, evaluateExpression("(2 + 3) * (8 - 4)"))
    }

    @Test
    fun `power is right-associative and binds tighter than unary minus`() {
        assertEquals(512.0, evaluateExpression("2^3^2"))
        assertEquals(-8.0, evaluateExpression("-2^3"))
    }

    @Test
    fun `modulo computes the remainder`() {
        assertEquals(1.0, evaluateExpression("7 % 3"))
    }

    @Test
    fun `division and modulo by zero fail loudly`() {
        assertFailsWithMessage("Division by zero") { evaluateExpression("1 / 0") }
        assertFailsWithMessage("Modulo by zero") { evaluateExpression("1 % 0") }
    }

    @Test
    fun `parses decimal and scientific notation numbers`() {
        assertEquals(1.5, evaluateExpression("1.5"))
        assertEquals(1500.0, evaluateExpression("1.5e3"))
        assertEquals(0.0015, evaluateExpression("1.5e-3"), 1e-12)
    }

    @Test
    fun `resolves the constants pi and e`() {
        assertEquals(Math.PI, evaluateExpression("pi"), 1e-12)
        assertEquals(Math.E, evaluateExpression("e"), 1e-12)
        assertEquals(2 * Math.PI, evaluateExpression("2 * pi"), 1e-12)
    }

    @Test
    fun `evaluates the supported functions`() {
        assertEquals(3.0, evaluateExpression("sqrt(9)"))
        assertEquals(2.5, evaluateExpression("abs(-2.5)"))
        assertEquals(2.0, evaluateExpression("min(2, 5)"))
        assertEquals(5.0, evaluateExpression("max(2, 5)"))
        assertEquals(2.0, evaluateExpression("floor(2.7)"))
        assertEquals(3.0, evaluateExpression("ceil(2.1)"))
        assertEquals(2.0, evaluateExpression("round(2.3)"))
        assertEquals(0.0, evaluateExpression("sin(0)"), 1e-12)
        assertEquals(1.0, evaluateExpression("cos(0)"), 1e-12)
        assertEquals(1.0, evaluateExpression("tan(pi / 4)"), 1e-12)
        assertEquals(2.0, evaluateExpression("log(100)"), 1e-12)
        assertEquals(1.0, evaluateExpression("ln(e)"), 1e-12)
        assertEquals(Math.E, evaluateExpression("exp(1)"), 1e-12)
    }

    @Test
    fun `unknown names and wrong arity fail loudly`() {
        assertFailsWithMessage("Unknown name 'foo' at position 0") { evaluateExpression("foo") }
        assertFailsWithMessage("Unknown name 'foo' at position 4") { evaluateExpression("2 + foo") }
        assertFailsWithMessage("Invalid arguments to 'sqrt'") { evaluateExpression("sqrt(4, 9)") }
        assertFailsWithMessage("Invalid arguments to 'min'") { evaluateExpression("min()") }
    }

    @Test
    fun `parse errors carry the offending token and position`() {
        assertFailsWithMessage("Unexpected token ')' at position 8") { evaluateExpression("(2 + 3 *))") }
        assertFailsWithMessage("Expected ')' at position 6") { evaluateExpression("(2 + 3") }
        assertFailsWithMessage("Unexpected end of expression") { evaluateExpression("2 *") }
    }

    @Test
    fun `safety caps refuse over-long and over-nested expressions`() {
        assertFailsWithMessage("Expression too long (max 1000 characters)") {
            evaluateExpression("1" + " + 1".repeat(300))
        }
        assertFailsWithMessage("Expression too deeply nested (max 64)") {
            evaluateExpression("(".repeat(65) + "1" + ")".repeat(65))
        }
    }

    @Test
    fun `results render integrals without a decimal point`() {
        assertEquals("14", formatCalcResult(14.0))
        assertEquals("-8", formatCalcResult(-8.0))
        assertEquals("1.5", formatCalcResult(1.5))
    }

    @Test
    fun `non-finite results fail loudly instead of returning Infinity`() {
        assertFailsWithMessage("Result is not a finite number") { evaluateExpression("ln(0)") }
        assertFailsWithMessage("Result is not a finite number") { evaluateExpression("1e308 * 10") }
    }

    private fun assertFailsWithMessage(expected: String, block: () -> Unit) {
        val exception = assertFailsWith<CalcException> { block() }
        assertEquals(expected, exception.message)
    }
}