package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePlanToolTest {

    private val tool = UpdatePlanTool()
    private val context = ToolContext(
        cwd = "/tmp",
        client = null,
        clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
        sessionId = com.agentclientprotocol.model.SessionId("sess_plantool000001"),
    )

    @Test
    fun `tool is think kind, non-mutating and available in every mode`() {
        assertEquals("update_plan", tool.name)
        assertEquals(ToolKind.THINK, tool.kind)
        assertFalse(tool.mutating)
        assertTrue(tool.modes.isEmpty())
    }

    @Test
    fun `schema declares a required entries array`() {
        val schema = tool.parameters
        assertEquals("object", schema["type"]?.toString().orEmpty().trim('"'))
        val properties = schema["properties"] as JsonObject
        val entries = properties["entries"] as JsonObject
        assertEquals("array", entries["type"].toString().trim('"'))
        val required = schema["required"] as JsonArray
        assertEquals("entries", required.single().toString().trim('"'))
    }

    @Test
    fun `valid entries are emitted and answered with Plan updated`() = runBlocking {
        var received: List<PlanEntry>? = null
        val context = ToolContext(
            cwd = "/tmp",
            client = null,
            clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
            sessionId = com.agentclientprotocol.model.SessionId("sess_plantool000001"),
            updatePlan = { received = it },
        )
        val result = tool.execute(
            buildJsonObject {
                put("entries", buildJsonArray {
                    add(buildJsonObject {
                        put("content", "Investigate the bug")
                        put("priority", "high")
                        put("status", "pending")
                    })
                    add(buildJsonObject {
                        put("content", "Fix it")
                        put("priority", "medium")
                        put("status", "in_progress")
                    })
                })
            },
            context,
        )
        assertEquals("Plan updated.", result.text)
        assertFalse(result.isError)
        assertEquals(
            listOf(
                PlanEntry("Investigate the bug", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING),
                PlanEntry("Fix it", PlanEntryPriority.MEDIUM, PlanEntryStatus.IN_PROGRESS),
            ),
            received,
        )
    }

    @Test
    fun `entries with empty content are rejected`() = runBlocking {
        var emitted = false
        val context = ToolContext(
            cwd = "/tmp",
            client = null,
            clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
            sessionId = com.agentclientprotocol.model.SessionId("sess_plantool000001"),
            updatePlan = { emitted = true },
        )
        val result = tool.execute(
            buildJsonObject {
                put("entries", buildJsonArray {
                    add(buildJsonObject {
                        put("content", "")
                        put("priority", "low")
                        put("status", "pending")
                    })
                })
            },
            context,
        )
        assertTrue(result.isError)
        assertFalse(emitted, "nothing must be emitted for invalid entries")
    }

    @Test
    fun `invalid priority or status values are rejected`() = runBlocking {
        val context = ToolContext(
            cwd = "/tmp",
            client = null,
            clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
            sessionId = com.agentclientprotocol.model.SessionId("sess_plantool000001"),
        )
        for (entry in listOf(
            buildJsonObject {
                put("content", "Step"); put("priority", "urgent"); put("status", "pending")
            },
            buildJsonObject {
                put("content", "Step"); put("priority", "high"); put("status", "ongoing")
            },
        )) {
            val result = tool.execute(
                buildJsonObject { put("entries", buildJsonArray { add(entry) }) },
                context,
            )
            assertTrue(result.isError, "invalid entry must be rejected: $entry")
        }
    }

    @Test
    fun `missing entries argument is rejected`() = runBlocking {
        val context = ToolContext(
            cwd = "/tmp",
            client = null,
            clientCapabilities = com.agentclientprotocol.model.ClientCapabilities(),
            sessionId = com.agentclientprotocol.model.SessionId("sess_plantool000001"),
        )
        val result = tool.execute(buildJsonObject { }, context)
        assertTrue(result.isError)
    }
}
