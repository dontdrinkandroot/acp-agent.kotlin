package net.dontdrinkandroot.acpagent.e2e

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Client operations whose permission request never resolves on its own; used to
 * pin the $/cancel_request behavior: the running prompt is cancelled while the
 * permission prompt is stuck, and the request is dismissed on the client side.
 */
internal class SuspendingPermissionOperations : ClientSessionOperations {
    val permissionRequestStarted = CompletableDeferred<Unit>()
    val permissionCancelled = CompletableDeferred<CancellationException?>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequestStarted.complete(Unit)
        return try {
            CompletableDeferred<Unit>().await()
            error("permission request must never resolve on its own")
        } catch (e: CancellationException) {
            permissionCancelled.complete(e)
            throw e
        }
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}

internal class TestClientOperations : ClientSessionOperations {
    val permissionRequests = mutableListOf<SessionUpdate.ToolCallUpdate>()
    val permissionOptions = mutableListOf<List<PermissionOption>>()
    val notifications = mutableListOf<SessionUpdate>()
    val fsReadCalls = mutableListOf<String>()
    val fsWriteCalls = mutableListOf<String>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        permissionRequests += toolCall
        permissionOptions += permissions
        return RequestPermissionResponse(
            RequestPermissionOutcome.Selected(PermissionOptionId("allow_once"))
        )
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        notifications += notification
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val file = File(path)
        val content = if (file.isFile) file.readText() else ""
        fsReadCalls += path
        return ReadTextFileResponse(content)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        fsWriteCalls += path
        return WriteTextFileResponse()
    }
}