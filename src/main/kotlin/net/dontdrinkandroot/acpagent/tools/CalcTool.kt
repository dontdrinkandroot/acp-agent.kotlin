package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

/**
 * Evaluates an arithmetic expression with the safe expression language of
 * [evaluateExpression] - pure math, no variables, no assignment, no I/O. The description
 * advertises the full language (operators, functions, constants) so the model
 * knows exactly what it may write.
 */
public class CalcTool : AgentTool {
    override val name = "calc"
    override val description =
        "Evaluate an arithmetic expression and return the numeric result. " +
                "Safe pure-math expression language: numbers (decimal, e.g. 1.5 or 1.5e3), " +
                "operators + - * / % ^ (standard precedence, ^ right-associative), unary +/- , " +
                "parentheses, and the functions " +
                "sqrt, abs, min, max, floor, ceil, round, sin, cos, tan, log (base 10), ln, exp " +
                "(min/max take one or more arguments, all others exactly one) " +
                "and the constants pi and e. " +
                "No variables, no assignment, no strings - math only. " +
                "Use it for arithmetic instead of computing results yourself."
    override val kind = ToolKind.OTHER
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required(
            "expression",
            PropType.STRING,
            "Arithmetic expression to evaluate, e.g. '2 + 3 * 4' or 'sqrt(min(9, 16))'."
        ),
    )

    override fun title(arguments: JsonObject): String? = formatToolTitle(name, arguments)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments.isNullArg("expression")) return ToolResult(arguments.argError("expression"), true)
        val expression = arguments.stringArg("expression") ?: return ToolResult(arguments.argError("expression"), true)
        return executeSafely("Calculation failed") {
            val value = try {
                evaluateExpression(expression)
            } catch (e: CalcException) {
                return@executeSafely ToolResult("Cannot evaluate '$expression': ${e.message}", true)
            }
            ToolResult(formatCalcResult(value))
        }
    }
}