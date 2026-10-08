package net.dontdrinkandroot.acpagent.tools

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random

/**
 * Shared test support for the tool test files: temp dirs, [ToolContext]
 * doubles and fs-proxy doubles. Internal so it stays test-scoped.
 */

internal fun tmpDir(): String {
    val dir = "/tmp/acp-agent-test-${Random.nextBytes(6).toHex()}"
    SystemFileSystem.createDirectories(Path(dir))
    return dir
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun testContext(
    cwd: String,
    fileStore: FileStore = LocalFileStore(),
    fileExclusions: FileAccessExclusions = FileAccessExclusions.DEFAULT,
) = ToolContext(
    cwd = cwd,
    client = null,
    clientCapabilities = ClientCapabilities(),
    sessionId = SessionId("sess_test"),
    fileStore = fileStore,
    fileExclusions = fileExclusions,
)

/**
 * A client fs proxy double that would fail loudly if the file tools
 * accidentally issued a pre-read through it (the diff skip must avoid the
 * round-trip, not just the payload).
 */
internal class RecordingFsClient : ClientSessionOperations {
    val readCalls = mutableListOf<String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        readCalls += path
        return ReadTextFileResponse("proxy content")
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse =
        WriteTextFileResponse()
}
