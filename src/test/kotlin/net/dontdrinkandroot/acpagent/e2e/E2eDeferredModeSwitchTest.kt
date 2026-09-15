package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Black-box deferred mode switching wire contract: a mode switch requested
 * while a prompt turn is running must not apply, notify or flip the loop's
 * tool gating mid-turn. It becomes effective (single current_mode_update)
 * only when the turn hands control back; a cancelled turn drops the request.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eDeferredModeSwitchTest : E2eAgentTest() {

    @Test
    fun `e2e mode switch during a running prompt is deferred to turn end`() = runBlocking {
        val tmpDir = Files.createTempDirectory("acp-agent-e2e-deferred").toFile()
        val deferredTarget = File(tmpDir, "deferred.txt")
        val llmMock = MockOpenAiServer(deferredTarget.absolutePath, holdFirstRequest = true)
        try {
            withE2eAgent("deferred", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    // Mock behaves like the wire-conformance mock: turn 1 does a write_file
                    // tool call (outside the project, so it would prompt if gated by build vs plan).
                    val session = newSession(connection.client, projectDir, ops)
                    val deferred = async {
                        collectPrompt(
                            session,
                            listOf(
                                ContentBlock.Text(
                                    "Write the text 'deferred' to ${deferredTarget.absolutePath} using the write_file tool."
                                )
                            ),
                            timeoutMs = 120_000,
                        )
                    }
                    // Hold the first LLM response until we have switched, so the switch
                    // deterministically happens while the turn is in flight.
                    withTimeoutOrNull(15_000) { llmMock.firstRequestStarted.await() }
                    val switchResponse =
                        session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    // The switch is recorded but the session still reports the governing mode.
                    assertEquals(
                        "plan",
                        switchResponse.configOptions
                            .filterIsInstance<SessionConfigOption.Select>()
                            .first { it.id.value == "mode" }.currentValue.value,
                    )
                    // Release the held first response; the turn proceeds under plan mode,
                    // so the write_file tool call is denied before execution.
                    llmMock.releaseFirstRequest()
                    val events = deferred.await()
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

                    val denied = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                    assertTrue(
                        denied.any { it.status == ToolCallStatus.FAILED },
                        "write_file in plan mode must be denied before execution, got: $denied",
                    )
                    val denialText = denied
                        .flatMap { it.content.orEmpty() }
                        .filterIsInstance<ToolCallContent.Content>()
                        .mapNotNull { (it.content as? ContentBlock.Text)?.text }
                        .joinToString("")
                    assertTrue(
                        denialText.contains("disabled in plan mode"),
                        "the denial must carry the disabled-in-mode error, got: $denialText",
                    )
                    assertFalse(deferredTarget.exists(), "deferred mid-turn switch must not enable write_file")
                    assertTrue(
                        ops.permissionRequests.isEmpty(),
                        "denied before the permission flow - no permission prompt expected, got ${ops.permissionRequests}",
                    )
                    // The flush happens at turn end: the single current_mode_update is delivered.
                    // (During a prompt the SDK routes session updates into the event flow, so the
                    // mode change at turn end rides the prompt events, not the notify callback.)
                    val modeUpdates = updates.filterIsInstance<SessionUpdate.CurrentModeUpdate>()
                    assertEquals(
                        listOf(SessionModeId("build")),
                        modeUpdates.map { it.currentModeId },
                        "pending switch must be flushed as exactly one update at turn end, got: $modeUpdates",
                    )
                    assertTrue(
                        events.filterIsInstance<Event.PromptResponseEvent>().single().response.stopReason ==
                                StopReason.END_TURN,
                        "the deferred switch must not disturb the turn's normal end",
                    )
                    println("[ok] mid-turn mode switch deferred: denied tool call, single current_mode_update at END_TURN")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `e2e mode switch is dropped on a cancelled prompt`() = runBlocking {
        val tmpDir = Files.createTempDirectory("acp-agent-e2e-deferred-cancel").toFile()
        val targetFile = File(tmpDir, "cancel.txt")
        val llmMock = MockOpenAiServer(targetFile.absolutePath)
        try {
            withE2eAgent("deferred-cancel", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    // Start in build so write_file passes the mode gate and reaches the
                    // permission prompt (which never resolves, keeping the turn in flight).
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    val promptJob = async {
                        runCatching {
                            collectPrompt(session, listOf(ContentBlock.Text("Write the file")), timeoutMs = 60_000)
                        }
                    }
                    // Wait until the turn is stuck at the permission prompt.
                    withTimeoutOrNull(15_000) { ops.permissionRequestStarted.await() }
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("bash"))
                    // A cancelled turn never flushed: the mode stays at the mode that governed
                    // the turn (build), the requested bash is dropped.
                    session.cancel()
                    promptJob.await()
                    assertEquals(
                        "build",
                        session.configOptions.value
                            .filterIsInstance<SessionConfigOption.Select>()
                            .first { it.id.value == "mode" }.currentValue.value,
                    )
                    // Regression: the cancelled turn's pending mode must not survive into
                    // the next turn (a stale pending would apply at its end and flip bash).
                    val second = collectPrompt(session, listOf(ContentBlock.Text("Say hello")), timeoutMs = 60_000)
                    val secondUpdates = second.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    assertTrue(
                        secondUpdates.none { it is SessionUpdate.CurrentModeUpdate && it.currentModeId.value == "bash" },
                        "a cancelled turn must drop its pending mode, got: ${secondUpdates.filterIsInstance<SessionUpdate.CurrentModeUpdate>()}",
                    )
                    assertEquals(
                        "build",
                        session.configOptions.value
                            .filterIsInstance<SessionConfigOption.Select>()
                            .first { it.id.value == "mode" }.currentValue.value,
                        "the follow-up turn must still run and end in build mode",
                    )
                    println("[ok] cancelled turn drops the pending mode switch (no update, stays plan)")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `e2e mid-turn model or reasoning switch still notifies immediately`() = runBlocking {
        val tmpDir = Files.createTempDirectory("acp-agent-e2e-deferred-config").toFile()
        val llmMock = MockOpenAiServer(File(tmpDir, "x.txt").absolutePath, textOnly = true, holdFirstRequest = true)
        try {
            withE2eAgent("deferred-config", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val promptJob = async {
                        collectPrompt(session, listOf(ContentBlock.Text("Say hello")), timeoutMs = 120_000)
                    }
                    withTimeoutOrNull(15_000) { llmMock.firstRequestStarted.await() }
                    // While the turn is held: a reasoning switch applies immediately and must
                    // still reach the client (only the *mode* is deferred). During an active
                    // prompt the SDK routes session updates into the event flow.
                    session.setConfigOption(SessionConfigId("reasoning"), SessionConfigOptionValue.of("medium"))
                    llmMock.releaseFirstRequest()
                    val events = promptJob.await()
                    val updates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                    val configUpdates = updates.filterIsInstance<SessionUpdate.ConfigOptionUpdate>()
                    assertTrue(
                        configUpdates.isNotEmpty(),
                        "a mid-turn reasoning switch must still notify config_option_update, got: $updates",
                    )
                    val reasoningOption = configUpdates
                        .last()
                        .configOptions
                        .filterIsInstance<SessionConfigOption.Select>()
                        .first { it.id.value == "reasoning" }
                    assertEquals("medium", reasoningOption.currentValue.value)
                    // And the mode itself never changed: the paired current_mode_update (part of
                    // notifyModeState's normal shape) must still report the governing plan.
                    val modeUpdates = updates.filterIsInstance<SessionUpdate.CurrentModeUpdate>()
                    assertTrue(
                        modeUpdates.all { it.currentModeId.value == "plan" },
                        "no mode was requested, so the mode must stay plan, got: $modeUpdates",
                    )
                    println("[ok] mid-turn reasoning switch notified immediately (mode deferral does not swallow it)")
                } finally {
                    connection.close()
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}