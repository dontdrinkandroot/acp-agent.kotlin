package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Black-box mode enforcement: a tool that is registered but disabled in the
 * current mode (bash in build mode) must never execute, even when the model
 * emits a tool call for it (e.g. carried over from an earlier bash-mode turn).
 * The call fails with the "disabled in current mode" error instead, without a
 * permission prompt and without a side effect.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eModeRestrictionTest : E2eAgentTest() {

    @Test
    fun `e2e bash tool call in build mode is refused without executing`() = runBlocking {
        withE2eAgent("mode-restriction", { projectDir ->
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "bash",
                    buildJsonObject { put("command", "echo executed > mode-bug-marker.txt") }
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                assertEquals(
                    com.agentclientprotocol.model.SessionModeId("build"),
                    session.currentMode.value,
                )

                val events = collectPrompt(session, listOf(ContentBlock.Text("Run a shell command")))
                assertEndTurn(events)

                val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                val resultUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                assertTrue(resultUpdates.isNotEmpty(), "expected a ToolCallUpdate result")
                assertEquals(ToolCallStatus.FAILED, resultUpdates.last().status)
                val resultText = resultUpdates.last().rawOutput?.toString().orEmpty() +
                        (resultUpdates.last().content.orEmpty()
                            .filterIsInstance<ToolCallContent.Content>()
                            .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                            .joinToString("\n"))
                assertTrue(
                    resultText.contains("disabled in build mode"),
                    "expected the disabled-in-mode error, got: $resultText",
                )

                assertTrue(
                    updates.none { it is SessionUpdate.ToolCall },
                    "a disabled tool call must not reach the executing tool_call state",
                )
                assertTrue(
                    ops.permissionRequests.isEmpty(),
                    "a disabled tool must be refused before any permission prompt, got ${ops.permissionRequests}",
                )

                val marker = projectDir.resolve("mode-bug-marker.txt")
                assertFalse(marker.exists(), "the bash command must never have run")

                println("[ok] bash tool call in build mode refused with the disabled-in-mode error")
            } finally {
                connection.close()
            }
        }
    }
}
