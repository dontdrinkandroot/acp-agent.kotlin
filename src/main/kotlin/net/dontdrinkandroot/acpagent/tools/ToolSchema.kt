package net.dontdrinkandroot.acpagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * The JSON Schema type of a tool parameter.
 */
internal enum class PropType {
    STRING,
    INTEGER,
    ARRAY,
}

/**
 * One parameter of a tool schema: name, type, optional documentation, optional
 * enum values / nested `items` schema and whether it is required.
 */
internal data class Prop(
    val name: String,
    val type: PropType,
    val description: String? = null,
    val enumValues: List<String>? = null,
    val items: JsonObject? = null,
    val required: Boolean = false,
)

/**
 * A required parameter. Required parameters are listed first in [jsonSchema]
 * (both in `properties` and in `required`), so a schema reads top-down as
 * "mandatory, then optional".
 */
internal fun required(
    name: String,
    type: PropType,
    description: String? = null,
    enumValues: List<String>? = null,
    items: JsonObject? = null,
): Prop = Prop(name, type, description, enumValues, items, required = true)

/**
 * An optional parameter. See [required].
 */
internal fun optional(
    name: String,
    type: PropType,
    description: String? = null,
    enumValues: List<String>? = null,
    items: JsonObject? = null,
): Prop = Prop(name, type, description, enumValues, items, required = false)

/**
 * Builds the `parameters` object a tool advertises:
 * `{ "type": "object", "properties": {…}, "required": […] }`.
 *
 * The wire shape is identical to the hand-built schemas this replaces (same key
 * order `type -> properties -> required`, same property key order, same
 * `required` array) and is pinned by [jsonSchemaTest] at the raw-string level.
 * Required parameters are emitted first, in the order they are passed; optional
 * parameters follow.
 */
internal fun jsonSchema(vararg props: Prop): JsonObject {
    val ordered = props.sortedBy { !it.required }
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            ordered.forEach { prop -> put(prop.name, propSchema(prop)) }
        })
        putJsonArray("required") {
            ordered.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
        }
    }
}

private fun propSchema(prop: Prop): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(prop.type.wireValue))
    prop.description?.let { put("description", JsonPrimitive(it)) }
    prop.enumValues?.let { values ->
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
    prop.items?.let { put("items", it) }
}

/**
 * Builds the JSON Schema object for a single property (without the property
 * name): `{ "type": …, ["description": …], ["enum": […]|"items": …] }`. Shared
 * by [Prop] and by nested schemas that are built raw (e.g. the entries `items`
 * object in UpdatePlanTool).
 */
internal fun jsonSchemaProperty(
    type: PropType,
    description: String? = null,
    enumValues: List<String>? = null,
    items: JsonObject? = null,
): JsonObject =
    propSchema(Prop(name = "", type = type, description = description, enumValues = enumValues, items = items))

private val PropType.wireValue: String
    get() = when (this) {
        PropType.STRING -> "string"
        PropType.INTEGER -> "integer"
        PropType.ARRAY -> "array"
    }
