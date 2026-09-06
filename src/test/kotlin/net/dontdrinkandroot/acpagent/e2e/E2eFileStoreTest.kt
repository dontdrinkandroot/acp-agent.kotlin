package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `e2e move_file runs without a prompt in project`() = runBlocking {
        var sourceFile: File? = null
        withE2eAgent("move", { projectDir ->
            val file = projectDir.resolve("old.txt")
            file.writeText("content")
            sourceFile = file
            MockOpenAiServer(
                "unused",
                toolCall = MockToolCall(
                    "move_file",
                    buildJsonObject {
                        put("source", file.absolutePath)
                        put("destination", file.resolveSibling("new.txt").absolutePath)
                    },
                ),
            )
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Move the file")))
                assertEndTurn(events)
                val file = requireNotNull(sourceFile)
                assertFalse(file.exists(), "the source must be moved away")
                assertTrue(file.resolveSibling("new.txt").exists(), "the destination must exist")
                assertTrue(ops.permissionRequests.isEmpty(), "in-project move must not ask permission")
                println("[ok] in-project move_file ran without a prompt")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `e2e move_file with out of project destination asks permission`() = runBlocking {
        val outsideDir = Files.createTempDirectory("acp-agent-e2e-moveout").toFile()
        try {
            var sourceFile: File? = null
            withE2eAgent("moveout", { projectDir ->
                val file = projectDir.resolve("old.txt")
                file.writeText("content")
                sourceFile = file
                MockOpenAiServer(
                    "unused",
                    toolCall = MockToolCall(
                        "move_file",
                        buildJsonObject {
                            put("source", file.absolutePath)
                            put("destination", outsideDir.resolve("out.txt").absolutePath)
                        },
                    ),
                )
            }) {
                val connection = connect()
                try {
                    connection.client.initialize(testClientInfo())
                    val ops = TestClientOperations()
                    val session = newSession(connection.client, projectDir, ops)
                    val events = collectPrompt(session, listOf(ContentBlock.Text("Move the file")))
                    assertEndTurn(events)
                    val file = requireNotNull(sourceFile)
                    assertEquals(1, ops.permissionRequests.size, "moving out of the project must ask permission")
                    val permissionTitle = ops.permissionRequests.single().title!!
                    assertTrue(
                        permissionTitle.startsWith("move_file(source:"),
                        "the permission prompt must not leave the user confirming blind: $permissionTitle",
                    )
                    val rawInput = ops.permissionRequests.single().rawInput as JsonObject
                    assertEquals(file.absolutePath, (rawInput["source"] as JsonPrimitive).content)
                    assertEquals(
                        outsideDir.resolve("out.txt").absolutePath,
                        (rawInput["destination"] as JsonPrimitive).content,
                    )
                    assertTrue(outsideDir.resolve("out.txt").exists(), "the allowed move must have happened")
                    assertFalse(file.exists(), "the source must be moved away")
                    println("[ok] out-of-project move_file destination routed through session/request_permission")
                } finally {
                    connection.close()
                }
            }
        } finally {
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `e2e delete_file runs without a prompt in project`() = runBlocking {
        var targetFile: File? = null
        withE2eAgent("delete", { projectDir ->
            val file = projectDir.resolve("obsolete.txt")
            file.writeText("obsolete")
            targetFile = file
            MockOpenAiServer("unused", toolCall = MockToolCall("delete_file", pathArgs(file.absolutePath)))
        }) {
            val connection = connect()
            try {
                connection.client.initialize(testClientInfo())
                val ops = TestClientOperations()
                val session = newSession(connection.client, projectDir, ops)
                val events = collectPrompt(session, listOf(ContentBlock.Text("Delete the file")))
                assertEndTurn(events)
                val file = requireNotNull(targetFile)
                assertFalse(file.exists(), "the file must be deleted")
                assertTrue(ops.permissionRequests.isEmpty(), "in-project delete must not ask permission")
                println("[ok] in-project delete_file ran without a prompt")
            } finally {
                connection.close()
            }
        }
    }
}