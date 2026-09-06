package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import kotlinx.serialization.json.*
import net.dontdrinkandroot.acpagent.tools.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionAndFileStoreTest {

    private fun pathArgs(path: String): JsonObject = buildJsonObject { put("path", path) }

    @Test
    fun `in project read needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "x")
        assertEquals(false, permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(file.toString())))
    }

    @Test
    fun `out of project read needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempFile("acp-outside", ".txt")
        assertEquals(true, permissionNeeded(dir.toString(), ReadFileTool(), pathArgs(outside.toString())))
    }

    @Test
    fun `in project write needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), WriteFileTool(), pathArgs(dir.resolve("x.txt").toString()))
        )
    }

    @Test
    fun `out of project write needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(dir.toString(), WriteFileTool(), pathArgs(outside.resolve("x.txt").toString()))
        )
    }

    @Test
    fun `grep with out of project root needs permission and default root does not`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                GrepTool(),
                buildJsonObject { put("root", outside.toString()); put("pattern", "x") }
            )
        )
        assertEquals(
            false,
            permissionNeeded(dir.toString(), GrepTool(), buildJsonObject { put("pattern", "x") })
        )
    }

    @Test
    fun `non path non mutating tool never needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), UpdatePlanTool(), buildJsonObject { put("entries", JsonPrimitive("[]")) })
        )
    }

    private fun moveArgs(source: String, destination: String): JsonObject = buildJsonObject {
        put("source", source)
        put("destination", destination)
    }

    @Test
    fun `move within the project needs no permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val source = dir.resolve("a.txt")
        Files.writeString(source, "x")
        assertEquals(
            false,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(source.toString(), dir.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `move with an out of project destination needs permission even when the source is inside`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        val source = dir.resolve("a.txt")
        Files.writeString(source, "x")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(source.toString(), outside.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `move with an out of project source needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            true,
            permissionNeeded(
                dir.toString(),
                MoveFileTool(),
                moveArgs(outside.resolve("a.txt").toString(), dir.resolve("b.txt").toString())
            )
        )
    }

    @Test
    fun `delete within the project needs no permission and outside needs permission`() {
        val dir = Files.createTempDirectory("acp-perm")
        val outside = Files.createTempDirectory("acp-outside-tmp")
        assertEquals(
            false,
            permissionNeeded(dir.toString(), DeleteFileTool(), pathArgs(dir.resolve("a.txt").toString()))
        )
        assertEquals(
            true,
            permissionNeeded(dir.toString(), DeleteFileTool(), pathArgs(outside.resolve("a.txt").toString()))
        )
    }

    @Test
    fun `selectFileStore uses the client proxy only when enabled and both caps are present`() {
        val client = RecordingClient()
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = true,
            ) is ClientFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = false,
            ) is LocalFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true)),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
        assertTrue(
            selectFileStore(
                client,
                ClientCapabilities(),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
    }

    @Test
    fun `selectFileStore falls back to local without a client`() {
        assertTrue(
            selectFileStore(
                null,
                ClientCapabilities(fs = FileSystemCapability(readTextFile = true, writeTextFile = true)),
                fsProxyEnabled = true,
            ) is LocalFileStore
        )
    }
}

private class RecordingClient : ClientSessionOperations {
    val readCalls = mutableListOf<String>()
    val writeCalls = mutableListOf<String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        readCalls += path
        return ReadTextFileResponse("content of $path")
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        writeCalls += path
        return WriteTextFileResponse()
    }
}
