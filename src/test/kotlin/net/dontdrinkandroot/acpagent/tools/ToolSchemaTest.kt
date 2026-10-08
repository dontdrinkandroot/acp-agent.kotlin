package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The registry-wide schema guard: every registered local + session tool must
 * advertise an object schema with a properties object and a required string
 * array. Structure only - the per-tool schema pins live in each tool's test
 * class (the wire shape of the schema builder is pinned by ToolSchemaWireTest).
 */
class ToolSchemaTest {

    @Test
    fun `every production tool advertises an object schema with a required array of strings`() {
        val tools = localTools() + sessionTools("/tmp/schema-guard-cwd")
        assertTrue(tools.isNotEmpty(), "the production tool catalog must not be empty")
        tools.forEach { tool ->
            val parameters = tool.parameters
            assertEquals(
                "object",
                parameters["type"]?.jsonPrimitive?.contentOrNull,
                "${tool.name}: parameters.type",
            )
            assertIs<JsonObject>(parameters["properties"], "${tool.name}: parameters.properties")
            val required = parameters["required"]
            assertIs<JsonArray>(required, "${tool.name}: required must be an array")
            required.forEach { assertIs<JsonPrimitive>(it, "${tool.name}: required entries must be strings") }
        }
    }
}
