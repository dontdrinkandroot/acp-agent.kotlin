package net.dontdrinkandroot.acpagent.tools

/**
 * Thrown for any expression that cannot be evaluated; [message] is the
 * user-facing error text (positional where applicable).
 */
internal class CalcException(message: String) : IllegalArgumentException(message)

private const val MAX_EXPRESSION_LENGTH = 1000
private const val MAX_NESTING_DEPTH = 64

private val CONSTANTS = mapOf(
    "pi" to Math.PI,
    "e" to Math.E,
)

/**
 * The supported functions: arity-checked wrappers around the JDK math. All
 * take at least one argument; only `min`/`max` accept more. An evaluator
 * result of NaN (e.g. `sqrt(-1)`) is refused by the caller.
 */
private val FUNCTIONS: Map<String, (List<Double>) -> Double> = mapOf(
    "sqrt" to unary { Math.sqrt(it) },
    "abs" to unary { kotlin.math.abs(it) },
    "min" to variadic { a, b -> kotlin.math.min(a, b) },
    "max" to variadic { a, b -> kotlin.math.max(a, b) },
    "floor" to unary { kotlin.math.floor(it) },
    "ceil" to unary { kotlin.math.ceil(it) },
    "round" to unary { kotlin.math.round(it) },
    "sin" to unary { Math.sin(it) },
    "cos" to unary { Math.cos(it) },
    "tan" to unary { Math.tan(it) },
    "log" to unary { Math.log10(it) },
    "ln" to unary { Math.log(it) },
    "exp" to unary { Math.exp(it) },
)

private fun unary(f: (Double) -> Double): (List<Double>) -> Double = { args ->
    if (args.size != 1) Double.NaN else f(args[0])
}

private fun variadic(f: (Double, Double) -> Double): (List<Double>) -> Double = { args ->
    if (args.isEmpty()) Double.NaN else args.reduce(f)
}

/**
 * Evaluates an arithmetic expression to a [Double]. Pure math: a closed
 * grammar with numbers, `+ - * / % ^`, unary sign, parentheses, a fixed set of
 * functions and constants - no variables, no assignment, no I/O, so it is safe
 * to evaluate model-supplied input.
 */
internal fun evaluateExpression(expression: String): Double {
    if (expression.isBlank()) throw CalcException("Empty expression")
    if (expression.length > MAX_EXPRESSION_LENGTH) {
        throw CalcException("Expression too long (max $MAX_EXPRESSION_LENGTH characters)")
    }
    val result = Parser(expression).parseExpression()
    if (!result.isFinite()) throw CalcException("Result is not a finite number")
    return result
}

/**
 * Renders an evaluated result: integral values without a trailing `.0`, other
 * values via [Double.toString] (full round-trip precision). Only called with
 * the finite results [evaluateExpression] returns.
 */
internal fun formatCalcResult(value: Double): String = when {
    value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15 ->
        value.toLong().toString()

    else -> value.toString()
}

private class Parser(private val source: String) {
    private var pos = 0
    private var depth = 0

    fun parseExpression(): Double {
        val value = parseTerm()
        expectEnd()
        return value
    }

    private fun parseTerm(): Double {
        var value = parseFactor()
        while (true) {
            skipWhitespace()
            when (peek()) {
                '+' -> {
                    pos++; value += parseFactor()
                }

                '-' -> {
                    pos++; value -= parseFactor()
                }

                else -> return value
            }
        }
    }

    private fun parseFactor(): Double {
        var value = parseUnary()
        while (true) {
            skipWhitespace()
            when (peek()) {
                '*' -> {
                    pos++; value *= parseUnary()
                }

                '/' -> {
                    pos++; value = divide(value, parseUnary())
                }

                '%' -> {
                    pos++; value = modulo(value, parseUnary())
                }

                else -> return value
            }
        }
    }

    private fun parseUnary(): Double {
        skipWhitespace()
        return when (peek()) {
            '-' -> {
                pos++; -parseUnary()
            }

            '+' -> {
                pos++; parseUnary()
            }

            else -> parsePower()
        }
    }

    private fun parsePower(): Double {
        val base = parsePrimary()
        skipWhitespace()
        if (peek() == '^') {
            pos++
            val exponent = parseUnary()
            val result = Math.pow(base, exponent)
            if (result.isNaN()) throw CalcException("Invalid exponentiation: $base ^ $exponent")
            return result
        }
        return base
    }

    private fun parsePrimary(): Double {
        skipWhitespace()
        val c = peek()
        return when {
            c == '(' -> {
                depth++
                if (depth > MAX_NESTING_DEPTH) {
                    throw CalcException("Expression too deeply nested (max $MAX_NESTING_DEPTH)")
                }
                pos++
                val value = parseTerm()
                skipWhitespace()
                if (peek() != ')') throw CalcException("Expected ')' at position $pos")
                pos++
                depth--
                value
            }

            c.isDigit() || c == '.' -> parseNumber()
            c.isLetter() -> parseIdentifier()
            else -> throw CalcException(unexpectedTokenMessage(c))
        }
    }

    private fun parseNumber(): Double {
        val start = pos
        while (peek().isDigit()) pos++
        if (peek() == '.') {
            pos++
            while (peek().isDigit()) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (!peek().isDigit()) throw CalcException("Expected exponent digits at position $pos")
            while (peek().isDigit()) pos++
        }
        return source.substring(start, pos).toDoubleOrNull()
            ?: throw CalcException("Invalid number at position $start")
    }

    private fun parseIdentifier(): Double {
        val start = pos
        while (peek().isLetterOrDigit() || peek() == '_') pos++
        val name = source.substring(start, pos)
        CONSTANTS[name]?.let { return it }
        val function = FUNCTIONS[name] ?: throw CalcException("Unknown name '$name' at position $start")
        skipWhitespace()
        if (peek() != '(') throw CalcException("Expected '(' after '$name' at position $pos")
        pos++
        val args = mutableListOf<Double>()
        skipWhitespace()
        if (peek() != ')') {
            args.add(parseTerm())
            while (true) {
                skipWhitespace()
                if (peek() != ',') break
                pos++
                args.add(parseTerm())
            }
        }
        skipWhitespace()
        if (peek() != ')') throw CalcException("Expected ')' at position $pos")
        pos++
        return function(args).also { result ->
            if (result.isNaN()) throw CalcException("Invalid arguments to '$name'")
        }
    }

    private fun divide(left: Double, right: Double): Double {
        if (right == 0.0) throw CalcException("Division by zero")
        return left / right
    }

    private fun modulo(left: Double, right: Double): Double {
        if (right == 0.0) throw CalcException("Modulo by zero")
        return left % right
    }

    private fun expectEnd() {
        skipWhitespace()
        if (pos < source.length) throw CalcException(unexpectedTokenMessage(peek()))
    }

    private fun unexpectedTokenMessage(c: Char): String =
        if (c == Char.MIN_VALUE) "Unexpected end of expression"
        else "Unexpected token '$c' at position $pos"

    private fun peek(): Char = if (pos < source.length) source[pos] else Char.MIN_VALUE

    private fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }
}
