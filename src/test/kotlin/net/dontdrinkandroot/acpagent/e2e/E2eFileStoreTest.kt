package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box path-aware permissions and the client fs proxy: in-project
 * reads/writes run without a prompt and route through the client fs when it is
 * advertised (unless disabled via `FS_PROXY_ENABLED=0`), while out-of-project
 * reads ask the user for permission.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
class E2eFileStoreTest : E2eAgentTest() {

    @Test
    fun `e2e read_file uses the client fs proxy in project`() = runBlocking {
        var targetFile: File? = null
        withE2eAgent("fsproxy", { projectDir ->
            val file = projectDir.resolve("known.txt")
            file.writeText("proxy content")
            targetFile = file
            MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(file.absolutePath)))
        }) {
            val connection = connect()
            try {
                connection.client.initialize(
                    testClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true
                            )
                        ),
                    )
                )
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Read the file")))
                assertEndTurn(events)
                val file = requireNotNull(targetFile)
                assertEquals(
                    listOf(file.absolutePath),
                    ops.fsReadCalls,
                    "the client fs read must be used for an in-project read",
                )
                assertTrue(ops.permissionRequests.isEmpty(), "in-project read must not ask permission")
                println("[ok] read_file routed through the client fs (no prompt)")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e read_file uses the local store when the fs proxy is disabled`() = runBlocking {
        var targetFile: File? = null
        withE2eAgent("fsproxy-off", { projectDir ->
            val file = projectDir.resolve("known.txt")
            file.writeText("local content")
            targetFile = file
            MockOpenAiServer("unused", toolCall = MockToolCall("read_file", readFileArgs(file.absolutePath)))
        }) {
            val connection = connect(extraEnv = mapOf("FS_PROXY_ENABLED" to "0"))
            try {
                connection.client.initialize(
                    testClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(
                                readTextFile = true,
                                writeTextFile = true
                            )
                        ),
                    )
                )
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Read the file")))
                assertEndTurn(events)
                assertTrue(ops.fsReadCalls.isEmpty(), "disabled fs proxy must not call the client fs")
                assertTrue(ops.permissionRequests.isEmpty(), "in-project read must not prompt permission")
                println("[ok] fs proxy disabled -> local store used, no client fs calls")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e out of project read asks permission`() = runBlocking {
        val outsideDir = Files.createTempDirectory("acp-agent-e2e-outread-target").toFile()
        val targetFile = outsideDir.resolve("secret.txt")
        targetFile.writeText("secret")
        val llmMock = MockOpenAiServer(
            "unused",
            toolCall = MockToolCall("read_file", readFileArgs(targetFile.absolutePath)),
        )
        try {
            withE2eAgent("outread", llmMock) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Read the file")))
                    assertEndTurn(events)
                    assertEquals(1, ops.permissionRequests.size, "an out-of-project read must ask permission")
                    assertEquals("read_file", ops.permissionRequests.single().title)
                    val rawInput = ops.permissionRequests.single().rawInput as JsonObject
                    assertEquals(targetFile.absolutePath, (rawInput["path"] as JsonPrimitive).content)
                    println("[ok] out-of-project read routed through session/request_permission")
                } finally {
                    connection.close()
                }
            }
        } finally {
            outsideDir.deleteRecursively()
        }
    }
}