package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box $/cancel_request parity: cancelling a prompt while a mutating tool
 * waits on the permission prompt dismisses the permission request on the client
 * side (the SDK propagates $/cancel_request) and cancels the turn without a
 * spurious "Permission denied" tool result.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eCancelTest : E2eAgentTest() {

    @Test
    fun `e2e session cancel dismisses a stuck permission prompt`() = runBlocking {
        val targetDir = Files.createTempDirectory("acp-agent-e2e-cancel-target").toFile()
        val llmMock = MockOpenAiServer(File(targetDir, "phase4.txt").absolutePath)
        try {
            withE2eAgent("cancel", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = SuspendingPermissionOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    // write_file is a build/bash-mode tool; the permission prompt comes from the
                    // out-of-project target, not from a mode switch.
                    session.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.of("build"))
                    val events = mutableListOf<Event>()
                    val promptJob = async {
                        runCatching {
                            withTimeout(60_000) {
                                session.prompt(listOf(ContentBlock.Text("Write the file"))).collect { events += it }
                            }
                        }
                    }
                    withTimeout(60_000) { ops.permissionRequestStarted.await() }
                    session.cancel()
                    assertNotNull(
                        withTimeout(60_000) { ops.permissionCancelled.await() },
                        "permission prompt must be cancelled",
                    )
                    val outcome = promptJob.await()
                    val stopReason =
                        events.filterIsInstance<Event.PromptResponseEvent>().lastOrNull()?.response?.stopReason
                    assertTrue(
                        outcome.exceptionOrNull() is CancellationException || stopReason == StopReason.CANCELLED,
                        "prompt must end cancelled, outcome=$outcome stopReason=$stopReason",
                    )
                    val failedUpdates = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
                        .filterIsInstance<SessionUpdate.ToolCallUpdate>()
                        .filter { it.status == ToolCallStatus.FAILED }
                    assertTrue(
                        failedUpdates.isEmpty(),
                        "no spurious permission-denied update expected, got $failedUpdates",
                    )
                    println("[ok] session/cancel dismissed the permission prompt ($/cancel_request) and cancelled the turn")
                } finally {
                    connection.close()
                }
            }
        } finally {
            targetDir.deleteRecursively()
        }
    }
}
