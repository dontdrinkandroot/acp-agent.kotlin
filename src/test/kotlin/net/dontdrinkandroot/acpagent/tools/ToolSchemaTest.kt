package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wire shape of [jsonSchema]: the same strings the hand-built schemas
 * produced before the helper existed (key order, required-first ordering, enum
 * and items variants, empty schema). JsonObject equality is order-insensitive,
 * so these tests assert the serialized string instead. The class name avoids the
 * existing [ToolSchemaTest] in ToolsTest (which pins the `required` array on the
 * live tools).
 */
internal class ToolSchemaWireTest {

    @Test
    fun `jsonSchema emits type properties required in the current wire order`() {
        val schema = jsonSchema(
            required("path", PropType.STRING, "File path, absolute or relative to the working directory."),
            optional("line", PropType.INTEGER, "First line to return (1-based)."),
            required("limit", PropType.INTEGER, "Maximum number of lines to return (1-2000)."),
        )
        assertEquals(
            """{"type":"object","properties":{"path":{"type":"string","description":"File path, absolute or relative to the working directory."},"limit":{"type":"integer","description":"Maximum number of lines to return (1-2000)."},"line":{"type":"integer","description":"First line to return (1-based)."}},"required":["path","limit"]}""",
            llmWireJson.encodeToString(schema),
        )
    }

    @Test
    fun `required parameters are emitted first in declaration order`() {
        val schema = jsonSchema(
            optional("opt", PropType.STRING, "optional"),
            required("must", PropType.STRING, "required"),
        )
        assertEquals(
            """{"type":"object","properties":{"must":{"type":"string","description":"required"},"opt":{"type":"string","description":"optional"}},"required":["must"]}""",
            llmWireJson.encodeToString(schema),
        )
    }

    @Test
    fun `every property type maps to its wire type`() {
        val schema = jsonSchema(
            required("s", PropType.STRING),
            required("i", PropType.INTEGER),
            required("a", PropType.ARRAY),
        )
        assertEquals(
            """{"type":"object","properties":{"s":{"type":"string"},"i":{"type":"integer"},"a":{"type":"array"}},"required":["s","i","a"]}""",
            llmWireJson.encodeToString(schema),
        )
    }

    @Test
    fun `enum and items are emitted after the type and description`() {
        val enumSchema = jsonSchema(
            required("priority", PropType.STRING, "Priority of the step.", enumValues = listOf("high", "low")),
        )
        assertEquals(
            """{"type":"object","properties":{"priority":{"type":"string","description":"Priority of the step.","enum":["high","low"]}},"required":["priority"]}""",
            llmWireJson.encodeToString(enumSchema),
        )
        val itemsSchema = jsonSchema(
            required(
                "entries",
                PropType.ARRAY,
                "Complete list of entries.",
                items = buildJsonObject { put("type", JsonPrimitive("object")) },
            ),
        )
        assertEquals(
            """{"type":"object","properties":{"entries":{"type":"array","description":"Complete list of entries.","items":{"type":"object"}}},"required":["entries"]}""",
            llmWireJson.encodeToString(itemsSchema),
        )
    }

    @Test
    fun `empty schema emits an empty properties and required array`() {
        assertEquals(
            """{"type":"object","properties":{},"required":[]}""",
            llmWireJson.encodeToString(jsonSchema()),
        )
    }

    @Test
    fun `jsonSchema with all optional props emits an empty required array`() {
        assertEquals(
            """{"type":"object","properties":{"a":{"type":"string"}},"required":[]}""",
            llmWireJson.encodeToString(jsonSchema(optional("a", PropType.STRING))),
        )
    }
}
