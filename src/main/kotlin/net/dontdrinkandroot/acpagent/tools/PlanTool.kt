package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.*

/**
 * Create or update the execution plan shown to the user. The model sends the
 * complete list of entries on every call; the agent stores them on the session
 * (for persistence and replay) and forwards them to the client as a plan update.
 */
public class UpdatePlanTool : AgentTool {
    override val name = "update_plan"
    override val description =
        "Create or update the execution plan shown to the user. " +
                "Send the complete list of entries on every call, and give every entry a content describing the step."
    override val kind = ToolKind.THINK
    override val mutating = false
    override val parameters: JsonObject = jsonSchema(
        required(
            "entries",
            PropType.ARRAY,
            "Complete list of plan entries; send all entries on every call.",
            items = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("A step in the execution plan."))
                put("properties", buildJsonObject {
                    put(
                        "content",
                        jsonSchemaProperty(PropType.STRING, "Description of the step.")
                    )
                    put(
                        "priority",
                        jsonSchemaProperty(
                            PropType.STRING,
                            "Priority of the step: high (critical), medium (important), low (nice to have).",
                            enumValues = PRIORITIES,
                        )
                    )
                    put(
                        "status",
                        jsonSchemaProperty(
                            PropType.STRING,
                            "Status of the step; exactly one entry may be in_progress at a time.",
                            enumValues = STATUSES,
                        )
                    )
                })
                putJsonArray("required") {
                    REQUIRED_FIELDS.forEach { add(JsonPrimitive(it)) }
                }
            },
        ),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val entriesJson = arguments["entries"]
            ?: return ToolResult(INVALID_ARGUMENTS_MESSAGE, true)
        val entries = runCatching {
            ACPJson.decodeFromJsonElement<List<PlanEntry>>(entriesJson)
        }.getOrElse { return ToolResult(INVALID_ARGUMENTS_MESSAGE, true) }
        if (entries.any { it.content.isBlank() }) {
            return ToolResult(INVALID_ARGUMENTS_MESSAGE, true)
        }
        context.updatePlan(entries)
        return ToolResult("Plan updated.")
    }

    private companion object {
        private val INVALID_ARGUMENTS_MESSAGE =
            "Error: invalid arguments for update_plan; entries must be a list of objects with a non-empty content, " +
                    "a priority (high, medium, low) and a status (pending, in_progress, completed)."

        private val PRIORITIES = listOf("high", "medium", "low")
        private val STATUSES = listOf("pending", "in_progress", "completed")
        private val REQUIRED_FIELDS = listOf("content", "priority", "status")
    }
}
