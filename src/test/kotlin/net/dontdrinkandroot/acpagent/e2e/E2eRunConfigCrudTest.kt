package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.llm.llmWireJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Black-box run-configuration management: `create_run_config` is mutating and
 * build/bash-only (asks permission, persists to `.ai/run.json`), while
 * `list_run_configs` is read-only and available in plan mode alongside `run`,
 * with the write tools absent there.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eRunConfigCrudTest : E2eAgentTest() {

    private fun toolNamesIn(body: String?): Set<String> {
        val request = llmWireJson.parseToJsonElement(body ?: return emptySet()).jsonObject
        val tools = request["tools"]?.jsonArray ?: return emptySet()
        return tools.mapNotNull { tool ->
            (tool.jsonObject["function"]?.jsonObject?.get("name") as? JsonPrimitive)?.content
        }.toSet()
    }

    @Test
    fun `e2e create_run_config asks permission and persists to ai run json in build mode`() = runBlocking {
        withE2eAgent("run-crud-create", { projectDir ->
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "create_run_config",
                    buildJsonObject {
                        put("name", "test")
                        put("command", "echo hello > marker.txt")
                        put("description", "run the test suite")
                    },
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                val events = collectPrompt(session, listOf(ContentBlock.Text("Create a test run config")))
                assertEndTurn(events)

                assertEquals(1, ops.permissionRequests.size, "create_run_config is mutating and must ask permission")
                val permissionTitle = requireNotNull(ops.permissionRequests.single().title)
                assertTrue(
                    permissionTitle.startsWith("create_run_config(name: test, command: "),
                    permissionTitle,
                )
                assertTrue(permissionTitle.endsWith("...)"), permissionTitle)

                val runJson = File(projectDir, ".ai/run.json")
                assertTrue(runJson.isFile, "create_run_config must persist .ai/run.json")
                val parsed = llmWireJson.parseToJsonElement(runJson.readText()).jsonObject
                val entry = parsed["test"] as JsonObject
                assertEquals("echo hello > marker.txt", (entry["command"] as JsonPrimitive).content)
                assertEquals("run the test suite", (entry["description"] as JsonPrimitive).content)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                assertTrue(resultUpdates.last().rawOutput.toString().contains("Created"), resultUpdates.last().rawOutput.toString())

                assertTrue(toolNamesIn(llmMock.lastRequestBody).contains("create_run_config"))
                assertTrue(toolNamesIn(llmMock.lastRequestBody).contains("list_run_configs"))
                println("[ok] create_run_config persisted .ai/run.json after permission (build mode)")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e plan mode disables write tools and list_run_configs runs without permission`() = runBlocking {
        withE2eAgent("run-cfg-plan", { projectDir ->
            val aiDir = projectDir.resolve(".ai").apply { mkdirs() }
            aiDir.resolve("run.json").writeText("""{"test":{"command":"npm test","description":"unit"}}""")
            MockOpenAiServer("unused", toolCall = MockToolCall("list_run_configs", buildJsonObject {}))
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                assertEquals(SessionModeId("plan"), session.currentMode.value)
                val events = collectPrompt(session, listOf(ContentBlock.Text("List run configs")))
                assertEndTurn(events)

                assertTrue(ops.permissionRequests.isEmpty(), "list_run_configs is read-only and must not prompt")

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.COMPLETED, resultUpdates.last().status)
                assertTrue(resultUpdates.last().rawOutput.toString().contains("test - unit"), resultUpdates.last().rawOutput.toString())

                val offererTools = toolNamesIn(llmMock.lastRequestBody)
                assertTrue(offererTools.contains("list_run_configs"))
                assertTrue(offererTools.contains("run"))
                assertFalse(offererTools.contains("create_run_config"))
                assertFalse(offererTools.contains("update_run_config"))
                assertFalse(offererTools.contains("delete_run_config"))
                println("[ok] plan mode offered list_run_configs and run, no write tools, no permission prompt")
            } finally {
                connection.close()
            }
        }
    }
}
