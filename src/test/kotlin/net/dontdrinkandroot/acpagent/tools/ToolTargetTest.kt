package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shared path-tool target resolution seam: the argument decoding,
 * the absolutization against the session cwd and the file-access exclusion
 * refusal, with the exact error texts the per-tool tests assert.
 */
class ToolTargetTest {

    private val cwd = tmpDir()

    private fun resolved(target: ToolTarget): String {
        assertTrue(target is ToolTarget.Resolved, "expected a resolved target, was: $target")
        return target.path
    }

    private fun rejected(target: ToolTarget): ToolResult {
        assertTrue(target is ToolTarget.Rejected, "expected a rejected target, was: $target")
        return target.error
    }

    @Test
    fun `required argument is resolved against the session cwd`() {
        val arguments = buildJsonObject { put("path", JsonPrimitive("sub/file.txt")) }
        val target = resolveToolTarget(testContext(cwd), arguments)
        assertEquals("$cwd/sub/file.txt", resolved(target))
    }

    @Test
    fun `absolute arguments are normalized and kept`() {
        val arguments = buildJsonObject { put("path", JsonPrimitive("$cwd/a/../file.txt")) }
        val target = resolveToolTarget(testContext(cwd), arguments)
        assertEquals("$cwd/file.txt", resolved(target))
    }

    @Test
    fun `missing argument is rejected with the shared arg error`() {
        val error = rejected(resolveToolTarget(testContext(cwd), buildJsonObject { }))
        assertEquals("Missing 'path'", error.text)
        assertTrue(error.isError)
    }

    @Test
    fun `explicit null argument is rejected`() {
        val arguments = buildJsonObject { put("path", JsonNull) }
        val error = rejected(resolveToolTarget(testContext(cwd), arguments))
        assertEquals("'path' must not be null", error.text)
    }

    @Test
    fun `non-primitive argument is rejected`() {
        val arguments = buildJsonObject { put("path", buildJsonArray { add(JsonPrimitive(1)) }) }
        val error = rejected(resolveToolTarget(testContext(cwd), arguments))
        assertEquals("'path' must be a string", error.text)
    }

    @Test
    fun `primitive arguments coerce to their content like stringArg does`() {
        // Pinned deliberately: stringArg accepts any non-null primitive and
        // reads its content, so a numeric path argument resolves as the
        // literal string. Stricter typing must change this pin on purpose.
        val arguments = buildJsonObject { put("path", JsonPrimitive(42)) }
        assertEquals("$cwd/42", resolved(resolveToolTarget(testContext(cwd), arguments)))
    }

    @Test
    fun `named arguments use their own name in the error texts`() {
        val missing = rejected(resolveToolTarget(testContext(cwd), buildJsonObject { }, "source"))
        assertEquals("Missing 'source'", missing.text)
        val arguments = buildJsonObject {
            put("source", JsonPrimitive("src.txt"))
            put("destination", JsonNull)
        }
        val nullError = rejected(resolveToolTarget(testContext(cwd), arguments, "destination"))
        assertEquals("'destination' must not be null", nullError.text)
    }

    @Test
    fun `an excluded target is rejected with the exclusion error`() {
        val arguments = buildJsonObject { put("path", JsonPrimitive(".env.local")) }
        val error = rejected(resolveToolTarget(testContext(cwd), arguments))
        assertTrue(error.isError)
        assertTrue(error.text.contains("excluded from tool access"), error.text)
        assertTrue(error.text.contains("exclusion rule '.env*.local'"), error.text)
    }

    @Test
    fun `the raw path variant resolves and refuses like the argument variant`() {
        assertEquals("$cwd/file.txt", resolved(resolveToolTarget(testContext(cwd), "file.txt")))
        val error = rejected(resolveToolTarget(testContext(cwd), ".ai/run.json"))
        assertTrue(error.text.contains("exclusion rule '.ai/run.json'"), error.text)
    }
}